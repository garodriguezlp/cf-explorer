package cf.explorer.ui;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.list;
import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.richText;
import static dev.tamboui.toolkit.Toolkit.text;

import cf.explorer.catalog.ApplicationCatalog;
import cf.explorer.env.EnvDraft;
import cf.explorer.env.EnvDraft.ChangeKind;
import cf.explorer.env.EnvDraft.Entry;
import cf.explorer.model.Activity;
import cf.explorer.model.ShellPage;
import cf.explorer.model.ShellState;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.element.Size;
import dev.tamboui.toolkit.element.StyledElement;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Interactive Cloud Foundry application environment variable editor.
 *
 * <p>Supports live fuzzy/token filtering over variable keys and values, staging new variables
 * ({@code KEY=VALUE} or two-step key then value), in-place pre-filled value updates, staged
 * deletions, per-step inline confirmations, undo, and atomic batch apply.
 */
public final class EnvEditorPage implements ShellPage {
  private enum PromptMode { NONE, NEW_KEY, NEW_VALUE, SET_VALUE }

  private final ApplicationCatalog.App app;
  private final EnvDraft draft;
  private final ShellState defaultShell;
  private PromptMode promptMode = PromptMode.NONE;
  private ShellState promptShell;
  private String pendingKey = "";
  private String promptInput = "";
  private int promptCursor;
  private String filter = "";
  private int filterCursor;
  private String selectedKey;
  private Activity applyingActivity;

  public EnvEditorPage(ApplicationCatalog.App app, EnvDraft draft) {
    this(app, draft, null);
  }

  public EnvEditorPage(ApplicationCatalog.App app, EnvDraft draft, ShellState defaultShell) {
    this.app = Objects.requireNonNull(app);
    this.draft = Objects.requireNonNull(draft);
    this.defaultShell = defaultShell;
    reconcile();
  }

  public ApplicationCatalog.App app() {
    return app;
  }

  public EnvDraft draft() {
    return draft;
  }

  public boolean isApplying() {
    return applyingActivity != null;
  }

  public Activity startApplying(String label) {
    promptMode = PromptMode.NONE;
    promptShell = null;
    applyingActivity = Activity.start(label);
    return applyingActivity;
  }

  public void finishApplying() {
    applyingActivity = null;
  }

  @Override
  public Activity activity() {
    return applyingActivity;
  }

  @Override
  public Entry selected() {
    if (selectedKey == null) {
      return null;
    }
    return draft.entry(selectedKey);
  }

  @Override
  public String inputValue() {
    return promptMode == PromptMode.NONE ? filter : promptInput;
  }

  @Override
  public int inputCursor() {
    return promptMode == PromptMode.NONE ? filterCursor : promptCursor;
  }

  @Override
  public String inputTitle() {
    return switch (promptMode) {
      case NONE -> "Filter";
      case NEW_KEY -> "New Key (or KEY=VALUE)";
      case NEW_VALUE -> "Value for " + pendingKey;
      case SET_VALUE -> "Edit Value for " + pendingKey;
    };
  }

  @Override
  public boolean capturesSlash() {
    return promptMode != PromptMode.NONE;
  }

  @Override
  public boolean onEscape(ShellState state) {
    if (promptMode != PromptMode.NONE) {
      cancelPrompt();
      state.showStatus("Cancelled.");
      return true;
    }
    if (!filter.isEmpty()) {
      filter = "";
      filterCursor = 0;
      reconcile();
      return true;
    }
    return false;
  }

  @Override
  public String confirmDiscardPrompt() {
    if (!draft.hasChanges()) {
      return null;
    }
    int count = draft.changeCount();
    return "Discard " + count + " unapplied env "
        + (count == 1 ? "change" : "changes") + " for " + app.name() + "?";
  }

  /** Starts the {@code /new} prompt flow (`KEY=VALUE` or two-step key -> value). */
  public void beginNew(ShellState state) {
    Objects.requireNonNull(state);
    if (isApplying()) {
      return;
    }
    promptMode = PromptMode.NEW_KEY;
    promptShell = state;
    pendingKey = "";
    promptInput = "";
    promptCursor = 0;
    state.showStatus("Enter KEY or KEY=VALUE, then press Enter (Esc to cancel).");
  }

  /** Starts the {@code /set} prompt flow with the selected variable's value pre-filled. */
  public void beginSet(ShellState state) {
    Objects.requireNonNull(state);
    Entry current = selected();
    if (current == null || isApplying()) {
      return;
    }
    promptMode = PromptMode.SET_VALUE;
    promptShell = state;
    pendingKey = current.key();
    String initial = current.effectiveValue() != null
        ? current.effectiveValue()
        : (current.baselineValue() != null ? current.baselineValue() : "");
    promptInput = initial;
    promptCursor = initial.length();
    state.showStatus("Edit value for " + current.key() + ", then press Enter (Esc to cancel).");
  }

  /** Prompts inline confirmation to stage deletion of the selected variable. */
  public void beginDelete(ShellState state) {
    Objects.requireNonNull(state);
    Entry current = selected();
    if (current == null || current.isDeleted() || isApplying()) {
      return;
    }
    cancelPrompt();
    String key = current.key();
    state.requestConfirmation(
        "Stage deletion of " + key + " from " + app.name() + "?",
        () -> {
          draft.stageDelete(key);
          reconcile();
          state.showSuccess("Staged deletion of " + key + ".");
        });
  }

  /** Reverts any staged change on the currently selected variable. */
  public void undoSelected(ShellState state) {
    Objects.requireNonNull(state);
    Entry current = selected();
    if (current == null || !current.isChanged() || isApplying()) {
      return;
    }
    cancelPrompt();
    String key = current.key();
    if (draft.undo(key)) {
      reconcile();
      state.showStatus("Reverted staged change on " + key + ".");
    }
  }

  private void cancelPrompt() {
    promptMode = PromptMode.NONE;
    promptShell = null;
    pendingKey = "";
    promptInput = "";
    promptCursor = 0;
  }

  @Override
  public EventResult handleKey(KeyEvent event) {
    if (isApplying()) {
      return EventResult.HANDLED;
    }
    if (promptMode != PromptMode.NONE) {
      return handlePromptKey(event);
    }
    return handleFilterAndListKey(event);
  }

  private EventResult handlePromptKey(KeyEvent event) {
    if (event.isKey(KeyCode.ENTER)) {
      submitPrompt();
      return EventResult.HANDLED;
    }
    if (event.isKey(KeyCode.LEFT) && promptCursor > 0) {
      promptCursor = promptInput.offsetByCodePoints(promptCursor, -1);
      return EventResult.HANDLED;
    }
    if (event.isKey(KeyCode.RIGHT) && promptCursor < promptInput.length()) {
      promptCursor = promptInput.offsetByCodePoints(promptCursor, 1);
      return EventResult.HANDLED;
    }
    if (event.isKey(KeyCode.HOME)) {
      promptCursor = 0;
      return EventResult.HANDLED;
    }
    if (event.isKey(KeyCode.END)) {
      promptCursor = promptInput.length();
      return EventResult.HANDLED;
    }
    if (event.isKey(KeyCode.BACKSPACE) && promptCursor > 0) {
      int start = promptInput.offsetByCodePoints(promptCursor, -1);
      promptInput = promptInput.substring(0, start) + promptInput.substring(promptCursor);
      promptCursor = start;
      return EventResult.HANDLED;
    }
    if (event.isKey(KeyCode.DELETE) && promptCursor < promptInput.length()) {
      int end = promptInput.offsetByCodePoints(promptCursor, 1);
      promptInput = promptInput.substring(0, promptCursor) + promptInput.substring(end);
      return EventResult.HANDLED;
    }
    if (event.isTyping() && !event.hasNonShiftModifier()) {
      String ch = new String(Character.toChars(event.codePoint()));
      promptInput = promptInput.substring(0, promptCursor) + ch
          + promptInput.substring(promptCursor);
      promptCursor += ch.length();
      return EventResult.HANDLED;
    }
    return EventResult.HANDLED;
  }

  private void submitPrompt() {
    ShellState state = promptShell;
    if (state == null) {
      cancelPrompt();
      return;
    }
    if (promptMode == PromptMode.NEW_KEY) {
      EnvDraft.ParsedInput parsed = EnvDraft.parseNewInput(promptInput);
      String validationError = EnvDraft.validateKey(parsed.key());
      if (validationError != null) {
        state.showError(validationError);
        return;
      }
      Entry existing = draft.entry(parsed.key());
      if (existing != null && !existing.isDeleted()) {
        state.showError("Variable '" + parsed.key()
            + "' already exists. Select it and press Enter or /set to update.");
        return;
      }
      if (parsed.hasInlineValue()) {
        String key = parsed.key();
        String value = parsed.value();
        cancelPrompt();
        confirmStageNew(state, key, value);
      } else {
        pendingKey = parsed.key();
        promptMode = PromptMode.NEW_VALUE;
        promptInput = "";
        promptCursor = 0;
        state.showStatus("Enter value for " + pendingKey + ", then press Enter (Esc to cancel).");
      }
    } else if (promptMode == PromptMode.NEW_VALUE) {
      String key = pendingKey;
      String value = promptInput;
      cancelPrompt();
      confirmStageNew(state, key, value);
    } else if (promptMode == PromptMode.SET_VALUE) {
      String key = pendingKey;
      String value = promptInput;
      String validationError = EnvDraft.validateKey(key);
      if (validationError != null) {
        state.showError(validationError);
        return;
      }
      cancelPrompt();
      state.requestConfirmation(
          "Stage update to " + key + " = " + previewInline(value, 36) + " for " + app.name() + "?",
          () -> {
            draft.stageSet(key, value);
            selectedKey = key;
            reconcile();
            Entry updated = draft.entry(key);
            if (updated != null && updated.isChanged()) {
              state.showSuccess("Staged update to " + key + ".");
            } else {
              state.showStatus("Value of " + key + " matches baseline (unchanged).");
            }
          });
    }
  }

  private void confirmStageNew(ShellState state, String key, String value) {
    state.requestConfirmation(
        "Stage new variable " + key + " = " + previewInline(value, 36) + " for " + app.name() + "?",
        () -> {
          draft.stageSet(key, value);
          selectedKey = key;
          reconcile();
          state.showSuccess("Staged new variable " + key + ".");
        });
  }

  private EventResult handleFilterAndListKey(KeyEvent event) {
    if (event.isKey(KeyCode.ENTER) && defaultShell != null && selected() != null) {
      beginSet(defaultShell);
      return EventResult.HANDLED;
    }
    if (event.isKey(KeyCode.UP) || event.isKey(KeyCode.DOWN)) {
      List<Entry> visible = draft.filter(filter);
      if (!visible.isEmpty()) {
        int index = 0;
        for (int i = 0; i < visible.size(); i++) {
          if (visible.get(i).key().equals(selectedKey)) {
            index = i;
            break;
          }
        }
        index = Math.max(0, Math.min(visible.size() - 1,
            index + (event.isKey(KeyCode.UP) ? -1 : 1)));
        selectedKey = visible.get(index).key();
      }
      return EventResult.HANDLED;
    }
    if (event.isKey(KeyCode.LEFT) && filterCursor > 0) {
      filterCursor = filter.offsetByCodePoints(filterCursor, -1);
    } else if (event.isKey(KeyCode.RIGHT) && filterCursor < filter.length()) {
      filterCursor = filter.offsetByCodePoints(filterCursor, 1);
    } else if (event.isKey(KeyCode.HOME)) {
      filterCursor = 0;
    } else if (event.isKey(KeyCode.END)) {
      filterCursor = filter.length();
    } else if (event.isKey(KeyCode.BACKSPACE) && filterCursor > 0) {
      int start = filter.offsetByCodePoints(filterCursor, -1);
      filter = filter.substring(0, start) + filter.substring(filterCursor);
      filterCursor = start;
    } else if (event.isKey(KeyCode.DELETE) && filterCursor < filter.length()) {
      int end = filter.offsetByCodePoints(filterCursor, 1);
      filter = filter.substring(0, filterCursor) + filter.substring(end);
    } else if (event.isTyping() && !event.hasNonShiftModifier()) {
      String ch = new String(Character.toChars(event.codePoint()));
      filter = filter.substring(0, filterCursor) + ch + filter.substring(filterCursor);
      filterCursor += ch.length();
    } else {
      return EventResult.UNHANDLED;
    }
    reconcile();
    return EventResult.HANDLED;
  }

  private void reconcile() {
    List<Entry> visible = draft.filter(filter);
    if (visible.stream().noneMatch(e -> e.key().equals(selectedKey))) {
      selectedKey = visible.isEmpty() ? null : visible.get(0).key();
    }
  }

  @Override
  public Element render() {
    return new EditorLayout().fill();
  }

  private Element renderEditor(int width, int height) {
    List<Entry> all = draft.entries();
    List<Entry> visible = draft.filter(filter);
    String title = buildTitle(visible.size(), all.size());

    if (visible.isEmpty()) {
      Element empty = all.isEmpty()
          ? column(
              text(" No environment variables set on " + app.name() + "."),
              text(" Run /new (Ctrl+N) to stage a new variable, or press Esc to go back.").dim())
          : column(
              text(" No matching environment variables."),
              text(" Press Esc to clear the filter, or run /new (Ctrl+N) to add one.").dim());
      return panel(empty).title(title).rounded().borderColor(Color.CYAN).fill();
    }

    int selectedIdx = 0;
    for (int i = 0; i < visible.size(); i++) {
      if (visible.get(i).key().equals(selectedKey)) {
        selectedIdx = i;
        break;
      }
    }

    int keyWidth = Math.max(12, Math.min(36,
        visible.stream().mapToInt(e -> e.key().length()).max().orElse(12)));
    int valueMaxWidth = Math.max(12, width - keyWidth - 12);

    Element listPanel = list()
        .data(visible, entry -> richText(renderRow(entry, keyWidth, valueMaxWidth)))
        .title(title)
        .rounded()
        .borderColor(Color.CYAN)
        .highlightSymbol("> ")
        .highlightStyle(Style.create().bg(Color.indexed(236)).bold())
        .selected(selectedIdx)
        .autoScroll()
        .focusable(false)
        .fill();

    if (height < 14) {
      return listPanel;
    }

    Entry current = visible.get(selectedIdx);
    Element detailPanel = renderDetail(current, Math.max(20, width - 16));
    return column(listPanel, detailPanel).fill();
  }

  private String buildTitle(int visibleCount, int totalCount) {
    StringBuilder sb = new StringBuilder(" Env: ")
        .append(app.name())
        .append("  ")
        .append(visibleCount)
        .append('/')
        .append(totalCount);
    if (draft.hasChanges()) {
      sb.append("  [").append(draft.summaryBreakdown()).append(']');
    }
    if (applyingActivity != null) {
      sb.append("  [").append(LoadingView.glyph(applyingActivity)).append(" applying]");
    }
    sb.append(' ');
    return sb.toString();
  }

  private Text renderRow(Entry entry, int keyWidth, int valueMaxWidth) {
    List<Span> spans = new ArrayList<>();
    switch (entry.kind()) {
      case ADDED -> spans.add(Span.styled("+ ", Style.create().fg(Color.LIGHT_GREEN).bold()));
      case UPDATED -> spans.add(Span.styled("~ ", Style.create().fg(Color.LIGHT_YELLOW).bold()));
      case DELETED -> spans.add(Span.styled("- ", Style.create().fg(Color.LIGHT_RED).bold()));
      case UNCHANGED -> spans.add(Span.raw("  "));
    }

    String displayKey = truncate(entry.key(), keyWidth);
    spans.addAll(highlightKeySpans(displayKey, entry.key(), filter));
    int pad = Math.max(0, keyWidth - displayKey.length());
    spans.add(Span.raw(" ".repeat(pad) + " = "));

    if (entry.isDeleted()) {
      spans.add(Span.styled("<deleted>", Style.create().fg(Color.LIGHT_RED)));
    } else {
      String preview = previewInline(entry.effectiveValue(), valueMaxWidth);
      Color valColor = switch (entry.kind()) {
        case ADDED -> Color.LIGHT_GREEN;
        case UPDATED -> Color.LIGHT_YELLOW;
        default -> Color.WHITE;
      };
      spans.add(Span.styled(preview, Style.create().fg(valColor)));
    }
    return Text.from(Line.from(spans));
  }

  private static List<Span> highlightKeySpans(String displayKey, String fullKey, String query) {
    boolean[] matched = EnvDraft.matchedKeyChars(fullKey, query);
    List<Span> spans = new ArrayList<>();
    int limit = Math.min(displayKey.length(), matched.length);
    int i = 0;
    while (i < limit) {
      boolean flag = matched[i];
      int start = i;
      while (i < limit && matched[i] == flag) {
        i++;
      }
      String segment = displayKey.substring(start, i);
      spans.add(flag
          ? Span.styled(segment, Style.create().fg(Color.LIGHT_YELLOW).bold())
          : Span.raw(segment));
    }
    if (limit < displayKey.length()) {
      spans.add(Span.raw(displayKey.substring(limit)));
    }
    return spans;
  }

  private Element renderDetail(Entry entry, int maxValueWidth) {
    String stateLabel = switch (entry.kind()) {
      case UNCHANGED -> "UNCHANGED";
      case ADDED -> "STAGED NEW";
      case UPDATED -> "STAGED UPDATE";
      case DELETED -> "STAGED DELETE";
    };
    Color stateColor = switch (entry.kind()) {
      case UNCHANGED -> Color.GRAY;
      case ADDED -> Color.LIGHT_GREEN;
      case UPDATED -> Color.LIGHT_YELLOW;
      case DELETED -> Color.LIGHT_RED;
    };

    List<Element> lines = new ArrayList<>();
    lines.add(richText(Text.from(Line.from(
        Span.styled(" Key:      " + entry.key(), Style.create().fg(Color.BRIGHT_WHITE).bold()),
        Span.raw("  "),
        Span.styled("[" + stateLabel + "]", Style.create().fg(stateColor).bold())))));

    if (entry.kind() == ChangeKind.UPDATED) {
      lines.add(text(" Current:  " + previewInline(entry.baselineValue(), maxValueWidth))
          .fg(Color.GRAY));
      lines.add(text(" Staged:   " + previewInline(entry.effectiveValue(), maxValueWidth))
          .fg(Color.LIGHT_YELLOW));
    } else if (entry.kind() == ChangeKind.DELETED) {
      lines.add(text(" Current:  " + previewInline(entry.baselineValue(), maxValueWidth))
          .fg(Color.GRAY));
      lines.add(text(" Staged:   <will be removed on /apply>").fg(Color.LIGHT_RED));
    } else {
      Color color = entry.kind() == ChangeKind.ADDED ? Color.LIGHT_GREEN : Color.WHITE;
      lines.add(text(" Value:    " + previewInline(entry.effectiveValue(), maxValueWidth))
          .fg(color));
      String hints = " Enter or /set edit  |  /new Ctrl+N  |  /delete"
          + (entry.isChanged() ? "  |  /undo" : "")
          + (draft.hasChanges() ? "  |  /apply Ctrl+Y" : "");
      lines.add(text(hints).dim());
    }

    return panel(column(lines.toArray(Element[]::new)))
        .title(" Selected Variable ")
        .rounded()
        .borderColor(Color.CYAN)
        .length(lines.size() + 2);
  }

  private static String previewInline(String value, int maxWidth) {
    if (value == null || value.isEmpty()) {
      return "<empty>";
    }
    String escaped = value
        .replace("\r\n", "\\n")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
    return truncate(escaped, Math.max(4, maxWidth));
  }

  private static String truncate(String text, int maxWidth) {
    if (text.length() <= maxWidth) {
      return text;
    }
    return text.substring(0, Math.max(1, maxWidth - 1)) + "\u2026";
  }

  private final class EditorLayout extends StyledElement<EditorLayout> {
    @Override
    public Size preferredSize(int width, int height, RenderContext context) {
      return Size.UNKNOWN;
    }

    @Override
    protected void renderContent(Frame frame, Rect area, RenderContext context) {
      context.renderChild(renderEditor(area.width(), area.height()), frame, area);
    }
  }
}
