package cf.explorer;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

// --- Domain Records ---

/**
 * Fully-joined Cloud Foundry application record, assembled from org, space, and app API responses.
 */
record App(
    String guid,
    String name,
    String state,
    String spaceGuid,
    String spaceName,
    String orgGuid,
    String orgName,
    String createdAt,
    String updatedAt) {}

/** A single certificate entry extracted from a JKS keystore. */
record KeystoreEntry(
    String alias, String subjectDN, String issuer, String notBefore, String notAfter) {}

/**
 * Result of inspecting a JKS keystore.
 *
 * <p>Always carries the path where raw bytes were written. When {@link #inspectionError()} is
 * {@code null} the cert entries are valid; when non-null the file was saved but the JKS could not
 * be parsed and {@link #entries()} will be empty.
 */
record KeystoreInspectResult(
    Path jksPath, List<KeystoreEntry> entries, String inspectionError, boolean clipboardCopied) {

  static KeystoreInspectResult success(
      Path jksPath, List<KeystoreEntry> entries, boolean clipboardCopied) {
    return new KeystoreInspectResult(jksPath, entries, null, clipboardCopied);
  }

  static KeystoreInspectResult partial(Path jksPath, String error, boolean clipboardCopied) {
    return new KeystoreInspectResult(jksPath, List.of(), error, clipboardCopied);
  }

  boolean inspected() {
    return inspectionError == null;
  }
}

// --- Domain Logic ---

/**
 * Joins raw CF organizations, spaces, and apps into {@link App} domain records.
 *
 * <p>All inputs are assumed to come from a single consistent {@link CatalogSnapshot}.
 */
final class CatalogJoiner {

  /**
   * Produces one {@link App} per raw app by resolving its space and org names. Apps whose space or
   * org cannot be resolved are annotated with {@code "unknown"}.
   */
  List<App> join(List<Organization> orgs, List<Space> spaces, List<AppResource> rawApps) {
    var orgNameByGuid = new HashMap<String, String>();
    for (var org : orgs) orgNameByGuid.put(org.guid(), org.name());

    var spaceByGuid = new HashMap<String, Space>();
    for (var space : spaces) spaceByGuid.put(space.guid(), space);

    return rawApps.stream()
        .map(
            app -> {
              var spaceGuid = app.relationships().space().data().guid();
              var space = spaceByGuid.get(spaceGuid);
              var spaceName = space != null ? space.name() : "unknown";
              var orgGuid = space != null ? space.relationships().organization().data().guid() : "";
              var orgName =
                  space != null ? orgNameByGuid.getOrDefault(orgGuid, "unknown") : "unknown";
              return new App(
                  app.guid(),
                  app.name(),
                  app.state(),
                  spaceGuid,
                  spaceName,
                  orgGuid,
                  orgName,
                  app.createdAt(),
                  app.updatedAt());
            })
        .toList();
  }
}

/**
 * Filters a list of {@link App} records using a whitespace-delimited query string.
 *
 * <p>Each token must match at least one of an app's filterable fields (name, state, space, org,
 * created/updated date). All tokens must match the same record (AND semantics across tokens, OR
 * semantics across fields).
 */
final class TokenFilter {

  private TokenFilter() {
    throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
  }

  static List<App> apply(List<App> apps, String query) {
    if (query == null || query.isBlank()) {
      return apps;
    }
    var tokens = tokenize(query);
    return apps.stream().filter(app -> allTokensMatch(app, tokens)).toList();
  }

  static List<String> tokenize(String query) {
    if (query == null || query.isBlank()) return List.of();
    return Arrays.stream(query.trim().split("\\s+")).map(t -> t.toLowerCase(Locale.ROOT)).toList();
  }

  private static boolean allTokensMatch(App app, List<String> tokens) {
    return tokens.stream().allMatch(token -> matchesAnyField(app, token));
  }

  private static boolean matchesAnyField(App app, String token) {
    return containsIgnoreCase(app.name(), token)
        || containsIgnoreCase(app.state(), token)
        || containsIgnoreCase(app.spaceName(), token)
        || containsIgnoreCase(app.orgName(), token)
        || containsIgnoreCase(dateOnly(app.updatedAt()), token)
        || containsIgnoreCase(dateOnly(app.createdAt()), token);
  }

  private static String dateOnly(String iso) {
    return iso != null && iso.length() >= 10 ? iso.substring(0, 10) : "";
  }

  private static boolean containsIgnoreCase(String field, String token) {
    return field != null && field.toLowerCase(Locale.ROOT).contains(token);
  }
}

// --- App State ---

/** Immutable snapshot of org/space/app totals displayed in the title bar on every screen. */
sealed interface AppState
    permits AppState.CatalogLoading,
        AppState.CatalogLoadFailed,
        AppState.Browsing,
        AppState.EnvExporting,
        AppState.ExportDone,
        AppState.ExportFailed,
        AppState.KeystoreExporting,
        AppState.KeystoreDone,
        AppState.KeystoreFailed {

  /** Immutable snapshot of org/space/app totals displayed in the title bar on every screen. */
  record HeaderCounts(int orgs, int spaces, int apps) {}

  /**
   * The application is fetching orgs, spaces, and apps from the CF API. Tracks per-resource load
   * completion and the running app count for the progress indicator.
   */
  record CatalogLoading(
      boolean orgsLoaded,
      boolean spacesLoaded,
      boolean appsLoaded,
      int orgsCount,
      int spacesCount,
      int loadingAppsCount)
      implements AppState {
    /** Returns the starting state with nothing loaded yet. */
    static CatalogLoading initial() {
      return new CatalogLoading(false, false, false, 0, 0, 0);
    }

    int appsCount() {
      return loadingAppsCount;
    }

    /**
     * Returns a value in {@code [0.0, 1.0]} reflecting how many of the three resource types (orgs,
     * spaces, apps) have finished loading.
     */
    double progress() {
      int done = (orgsLoaded ? 1 : 0) + (spacesLoaded ? 1 : 0) + (appsLoaded ? 1 : 0);
      return done / 3.0;
    }

    CatalogLoading withOrgsLoaded(int count) {
      return new CatalogLoading(
          true, spacesLoaded, appsLoaded, count, spacesCount, loadingAppsCount);
    }

    CatalogLoading withSpacesLoaded(int count) {
      return new CatalogLoading(orgsLoaded, true, appsLoaded, orgsCount, count, loadingAppsCount);
    }

    CatalogLoading withAppsLoaded(int count) {
      return new CatalogLoading(orgsLoaded, spacesLoaded, true, orgsCount, spacesCount, count);
    }

    /** Transitions to {@link Browsing} once all resources have loaded successfully. */
    AppState.Browsing toBrowsing(List<App> apps) {
      return AppState.Browsing.of(
          apps, new AppState.HeaderCounts(orgsCount, spacesCount, apps.size()));
    }

    /** Transitions to {@link CatalogLoadFailed} when a CF API call fails during loading. */
    AppState.CatalogLoadFailed toLoadFailed(String errorMessage) {
      return new AppState.CatalogLoadFailed(
          new AppState.HeaderCounts(orgsCount, spacesCount, appsCount()), errorMessage);
    }
  }

  /**
   * A CF API call failed during the initial catalog load. The user can only quit from this state.
   */
  record CatalogLoadFailed(HeaderCounts header, String errorMessage) implements AppState {}

  /**
   * The catalog is fully loaded and the user is browsing the app list. Supports live token-based
   * filtering and cursor navigation.
   */
  record Browsing(
      HeaderCounts header,
      List<App> allApps,
      List<App> filteredApps,
      int selectedIndex,
      String filterQuery,
      List<String> filterTokens)
      implements AppState {
    static Browsing of(List<App> apps, HeaderCounts header) {
      return new Browsing(header, apps, apps, 0, "", List.of());
    }

    Browsing moveUp() {
      return withIndex(Math.max(0, selectedIndex - 1));
    }

    Browsing moveDown() {
      return withIndex(Math.min(filteredApps.size() - 1, selectedIndex + 1));
    }

    Browsing appendFilter(char c) {
      return withFilter(filterQuery + c);
    }

    Browsing backspaceFilter() {
      if (filterQuery.isEmpty()) return this;
      return withFilter(filterQuery.substring(0, filterQuery.length() - 1));
    }

    Browsing clearFilter() {
      return new Browsing(
          header, allApps, allApps, clamp(selectedIndex, allApps.size()), "", List.of());
    }

    /** Returns the currently highlighted app, or {@code null} when the filtered list is empty. */
    App selectedApp() {
      return filteredApps.isEmpty() ? null : filteredApps.get(selectedIndex);
    }

    boolean hasFilter() {
      return !filterQuery.isEmpty();
    }

    private Browsing withIndex(int i) {
      return new Browsing(header, allApps, filteredApps, i, filterQuery, filterTokens);
    }

    private Browsing withFilter(String q) {
      var tokens = TokenFilter.tokenize(q);
      var filtered = TokenFilter.apply(allApps, q);
      return new Browsing(
          header, allApps, filtered, clamp(selectedIndex, filtered.size()), q, tokens);
    }

    private static int clamp(int i, int size) {
      return size == 0 ? 0 : Math.max(0, Math.min(i, size - 1));
    }

    /** Transitions to {@link EnvExporting} while the env-var export runs for the given app. */
    AppState.EnvExporting toExporting(String appName) {
      return new AppState.EnvExporting(this, appName);
    }

    /**
     * Transitions to {@link KeystoreExporting} while the keystore inspection runs for the given
     * app.
     */
    AppState.KeystoreExporting toKeystoreExporting(String appName) {
      return new AppState.KeystoreExporting(this, appName);
    }
  }

  /**
   * An env-var export is in progress for {@code appName}. Retains the preceding {@link Browsing}
   * state so that back-navigation restores it exactly.
   */
  record EnvExporting(Browsing previous, String appName) implements AppState {
    HeaderCounts header() {
      return previous.header();
    }

    /** Transitions to {@link ExportDone} after a successful export. */
    AppState.ExportDone toDone(
        String filePath,
        List<String> excludedKeys,
        List<String> postProcessedKeys,
        boolean clipboardCopied) {
      return new AppState.ExportDone(
          previous, appName, filePath, excludedKeys, postProcessedKeys, clipboardCopied);
    }

    /** Transitions to {@link ExportFailed} when the export operation throws. */
    AppState.ExportFailed toFailed(String errorMessage) {
      return new AppState.ExportFailed(previous, errorMessage);
    }
  }

  /**
   * The env-var export completed successfully. Carries the output file path and the lists of keys
   * that were excluded or post-processed.
   */
  record ExportDone(
      Browsing previous,
      String appName,
      String filePath,
      List<String> excludedKeys,
      List<String> postProcessedKeys,
      boolean clipboardCopied)
      implements AppState {
    HeaderCounts header() {
      return previous.header();
    }

    Browsing back() {
      return previous;
    }
  }

  /**
   * The env-var export failed. Retains the preceding {@link Browsing} state so the user can ESC
   * back to the app list.
   */
  record ExportFailed(Browsing previous, String errorMessage) implements AppState {
    HeaderCounts header() {
      return previous.header();
    }

    Browsing back() {
      return previous;
    }
  }

  /**
   * A keystore inspection is in progress for {@code appName}. Retains the preceding {@link
   * Browsing} state so that back-navigation restores it exactly.
   */
  record KeystoreExporting(Browsing previous, String appName) implements AppState {
    HeaderCounts header() {
      return previous.header();
    }

    AppState.KeystoreDone toDone(KeystoreInspectResult result) {
      return new AppState.KeystoreDone(previous, appName, result, result.clipboardCopied());
    }

    AppState.KeystoreFailed toFailed(String errorMessage) {
      return new AppState.KeystoreFailed(previous, errorMessage);
    }
  }

  /**
   * The keystore inspection completed successfully. Carries the file path and certificate entries.
   */
  record KeystoreDone(
      Browsing previous, String appName, KeystoreInspectResult result, boolean clipboardCopied)
      implements AppState {
    HeaderCounts header() {
      return previous.header();
    }

    Browsing back() {
      return previous;
    }
  }

  /**
   * The keystore inspection failed. Retains the preceding {@link Browsing} state so the user can
   * ESC back to the app list.
   */
  record KeystoreFailed(Browsing previous, String errorMessage) implements AppState {
    HeaderCounts header() {
      return previous.header();
    }

    Browsing back() {
      return previous;
    }
  }
}
