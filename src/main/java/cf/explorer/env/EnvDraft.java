package cf.explorer.env;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Mutable draft of an application's user-provided environment variables, tracking staged additions,
 * updates, and deletions against the Cloud Foundry baseline.
 */
public final class EnvDraft {
  private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
  private static final Set<String> RESERVED_SYSTEM_KEYS = Set.of(
      "VCAP_APPLICATION", "VCAP_SERVICES", "MEMORY_LIMIT", "PORT");
  private static final Comparator<String> KEY_ORDER =
      String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder());

  /** Diff status of a variable relative to the baseline fetched from Cloud Foundry. */
  public enum ChangeKind { UNCHANGED, ADDED, UPDATED, DELETED }

  /** View of a single environment variable combining baseline and staged values. */
  public record Entry(String key, String baselineValue, String effectiveValue, ChangeKind kind) {
    public Entry {
      Objects.requireNonNull(key);
      Objects.requireNonNull(kind);
    }

    public boolean isChanged() {
      return kind != ChangeKind.UNCHANGED;
    }

    public boolean isDeleted() {
      return kind == ChangeKind.DELETED;
    }
  }

  /** Parsed {@code KEY} or {@code KEY=VALUE} input convention. */
  public record ParsedInput(String key, String value, boolean hasInlineValue) {
    public ParsedInput {
      Objects.requireNonNull(key);
    }
  }

  private record Mutation(boolean deleted, String value) {}

  private final Map<String, String> baseline;
  private final Map<String, Mutation> staged = new TreeMap<>(KEY_ORDER);

  public EnvDraft(Map<String, String> initialVars) {
    Objects.requireNonNull(initialVars);
    Map<String, String> sorted = new TreeMap<>(KEY_ORDER);
    for (Map.Entry<String, String> entry : initialVars.entrySet()) {
      sorted.put(Objects.requireNonNull(entry.getKey()),
          entry.getValue() == null ? "" : entry.getValue());
    }
    this.baseline = Collections.unmodifiableMap(sorted);
  }

  /** Splits {@code raw} on the first {@code =} when present, or treats the whole string as a key. */
  public static ParsedInput parseNewInput(String raw) {
    Objects.requireNonNull(raw);
    int eq = raw.indexOf('=');
    if (eq >= 0) {
      return new ParsedInput(raw.substring(0, eq).trim(), raw.substring(eq + 1), true);
    }
    return new ParsedInput(raw.trim(), null, false);
  }

  /**
   * Validates a Cloud Foundry environment variable name. Returns {@code null} when valid, or a
   * user-facing reason when invalid.
   */
  public static String validateKey(String key) {
    if (key == null || key.isBlank()) {
      return "Variable name must not be blank.";
    }
    if (!VALID_KEY.matcher(key).matches()) {
      return "Invalid key '" + key + "': use letters, digits, and underscores (cannot start with a digit).";
    }
    String upper = key.toUpperCase(Locale.ROOT);
    if (RESERVED_SYSTEM_KEYS.contains(upper) || upper.startsWith("CF_INSTANCE_")) {
      return "Cannot modify Cloud Foundry system variable '" + key + "'.";
    }
    return null;
  }

  public boolean existsInBaseline(String key) {
    return key != null && baseline.containsKey(key);
  }

  public Entry entry(String key) {
    if (key == null) {
      return null;
    }
    boolean inBase = baseline.containsKey(key);
    String baseVal = baseline.get(key);
    Mutation mut = staged.get(key);
    if (!inBase && mut == null) {
      return null;
    }
    if (mut == null) {
      return new Entry(key, baseVal, baseVal, ChangeKind.UNCHANGED);
    }
    if (mut.deleted()) {
      return inBase ? new Entry(key, baseVal, null, ChangeKind.DELETED) : null;
    }
    return new Entry(key, baseVal, mut.value(),
        inBase ? ChangeKind.UPDATED : ChangeKind.ADDED);
  }

  /** Returns all entries (baseline + staged new), ordered alphabetically by key. */
  public List<Entry> entries() {
    Map<String, Entry> merged = new TreeMap<>(KEY_ORDER);
    for (String key : baseline.keySet()) {
      Entry item = entry(key);
      if (item != null) {
        merged.put(key, item);
      }
    }
    for (String key : staged.keySet()) {
      if (!merged.containsKey(key)) {
        Entry item = entry(key);
        if (item != null) {
          merged.put(key, item);
        }
      }
    }
    return List.copyOf(merged.values());
  }

  /**
   * Filters entries using case-insensitive whitespace-separated tokens. Exact and substring key
   * matches rank ahead of fuzzy character-subsequence key matches and value substring matches.
   */
  public List<Entry> filter(String query) {
    List<Entry> all = entries();
    if (query == null || query.isBlank()) {
      return all;
    }
    String[] tokens = query.trim().toLowerCase(Locale.ROOT).split("\\s+");
    record Scored(Entry entry, int score) {}
    List<Scored> matches = new ArrayList<>();
    for (Entry candidate : all) {
      int totalScore = 0;
      boolean matchedAll = true;
      for (String token : tokens) {
        int tokenScore = scoreToken(candidate, token);
        if (tokenScore < 0) {
          matchedAll = false;
          break;
        }
        totalScore += tokenScore;
      }
      if (matchedAll) {
        matches.add(new Scored(candidate, totalScore));
      }
    }
    matches.sort(Comparator.comparingInt(Scored::score)
        .thenComparing(s -> s.entry().key(), KEY_ORDER));
    return matches.stream().map(Scored::entry).toList();
  }

  private static int scoreToken(Entry entry, String lowerToken) {
    String lowerKey = entry.key().toLowerCase(Locale.ROOT);
    if (lowerKey.equals(lowerToken)) {
      return 0;
    }
    if (lowerKey.startsWith(lowerToken)) {
      return 10;
    }
    int subIdx = lowerKey.indexOf(lowerToken);
    if (subIdx >= 0) {
      return 20 + Math.min(subIdx, 9);
    }
    int fuzzyPenalty = fuzzySubsequencePenalty(lowerKey, lowerToken);
    if (fuzzyPenalty >= 0) {
      return 40 + Math.min(fuzzyPenalty, 30);
    }
    String effective = entry.effectiveValue();
    if (effective != null && effective.toLowerCase(Locale.ROOT).contains(lowerToken)) {
      return 80;
    }
    String base = entry.baselineValue();
    if (base != null && base.toLowerCase(Locale.ROOT).contains(lowerToken)) {
      return 90;
    }
    return -1;
  }

  /**
   * Returns a non-negative gap penalty when {@code needle} is an in-order subsequence of
   * {@code haystack} (rewarding word-boundary hits after {@code _}), or {@code -1} when not matched.
   */
  private static int fuzzySubsequencePenalty(String haystack, String needle) {
    int[] positions = fuzzyPositions(haystack, needle);
    if (positions == null) {
      return -1;
    }
    int penalty = positions[0];
    for (int i = 1; i < positions.length; i++) {
      int gap = positions[i] - positions[i - 1] - 1;
      boolean boundary = positions[i] > 0 && haystack.charAt(positions[i] - 1) == '_';
      penalty += boundary ? Math.min(gap, 1) : gap * 2;
    }
    return penalty;
  }

  private static int[] fuzzyPositions(String lowerHaystack, String lowerNeedle) {
    if (lowerNeedle.isEmpty()) {
      return new int[0];
    }
    int[] pos = new int[lowerNeedle.length()];
    int h = 0;
    for (int n = 0; n < lowerNeedle.length(); n++) {
      char target = lowerNeedle.charAt(n);
      int found = -1;
      // Prefer boundary match (start of key or immediately after '_') when available
      for (int scan = h; scan < lowerHaystack.length(); scan++) {
        if (lowerHaystack.charAt(scan) == target && (scan == 0 || lowerHaystack.charAt(scan - 1) == '_')) {
          found = scan;
          break;
        }
      }
      if (found < 0) {
        found = lowerHaystack.indexOf(target, h);
      }
      if (found < 0) {
        return null;
      }
      pos[n] = found;
      h = found + 1;
    }
    return pos;
  }

  /**
   * Marks character indices in {@code key} matched by {@code query} (via substring or fuzzy
   * subsequence) so the UI can highlight matched characters.
   */
  public static boolean[] matchedKeyChars(String key, String query) {
    boolean[] highlighted = new boolean[key.length()];
    if (query == null || query.isBlank() || key.isEmpty()) {
      return highlighted;
    }
    String lowerKey = key.toLowerCase(Locale.ROOT);
    for (String token : query.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
      if (token.isEmpty()) {
        continue;
      }
      int idx = lowerKey.indexOf(token);
      if (idx >= 0) {
        while (idx >= 0) {
          for (int i = idx; i < Math.min(key.length(), idx + token.length()); i++) {
            highlighted[i] = true;
          }
          idx = lowerKey.indexOf(token, idx + Math.max(1, token.length()));
        }
      } else {
        int[] fuzzy = fuzzyPositions(lowerKey, token);
        if (fuzzy != null) {
          for (int pos : fuzzy) {
            if (pos >= 0 && pos < highlighted.length) {
              highlighted[pos] = true;
            }
          }
        }
      }
    }
    return highlighted;
  }

  /** Stages a new or updated value for {@code key}, collapsing back to unchanged if identical to baseline. */
  public void stageSet(String key, String value) {
    String validationError = validateKey(key);
    if (validationError != null) {
      throw new IllegalArgumentException(validationError);
    }
    Objects.requireNonNull(value);
    if (baseline.containsKey(key) && Objects.equals(baseline.get(key), value)) {
      staged.remove(key);
    } else {
      staged.put(key, new Mutation(false, value));
    }
  }

  /** Stages deletion of {@code key}. Deleting a newly staged key simply removes it from the draft. */
  public void stageDelete(String key) {
    Objects.requireNonNull(key);
    if (baseline.containsKey(key)) {
      staged.put(key, new Mutation(true, null));
    } else {
      staged.remove(key);
    }
  }

  /** Discards any staged change on {@code key}. Returns {@code true} if a staged change was removed. */
  public boolean undo(String key) {
    return key != null && staged.remove(key) != null;
  }

  public boolean hasChanges() {
    return !staged.isEmpty();
  }

  public int changeCount() {
    return staged.size();
  }

  public int addedCount() {
    int count = 0;
    for (Map.Entry<String, Mutation> e : staged.entrySet()) {
      if (!e.getValue().deleted() && !baseline.containsKey(e.getKey())) {
        count++;
      }
    }
    return count;
  }

  public int updatedCount() {
    int count = 0;
    for (Map.Entry<String, Mutation> e : staged.entrySet()) {
      if (!e.getValue().deleted() && baseline.containsKey(e.getKey())) {
        count++;
      }
    }
    return count;
  }

  public int deletedCount() {
    int count = 0;
    for (Mutation m : staged.values()) {
      if (m.deleted()) {
        count++;
      }
    }
    return count;
  }

  /** Human-readable breakdown such as {@code +1 new, ~2 updated, -1 deleted}. */
  public String summaryBreakdown() {
    List<String> parts = new ArrayList<>(3);
    int added = addedCount();
    int updated = updatedCount();
    int deleted = deletedCount();
    if (added > 0) {
      parts.add("+" + added + " new");
    }
    if (updated > 0) {
      parts.add("~" + updated + " updated");
    }
    if (deleted > 0) {
      parts.add("-" + deleted + " deleted");
    }
    return String.join(", ", parts);
  }

  /**
   * Builds the Cloud Foundry v3 {@code PATCH} delta map. Deleted variables map to {@code null};
   * added and updated variables map to their staged string values.
   */
  public Map<String, String> toPatchMap() {
    Map<String, String> patch = new LinkedHashMap<>();
    for (Map.Entry<String, Mutation> entry : staged.entrySet()) {
      Mutation m = entry.getValue();
      patch.put(entry.getKey(), m.deleted() ? null : m.value());
    }
    return Collections.unmodifiableMap(patch);
  }
}
