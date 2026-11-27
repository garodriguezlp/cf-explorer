package cf.explorer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cf.explorer.model.ShellCommand;
import cf.explorer.model.ShellState;
import cf.explorer.model.ShellCommands;
import cf.explorer.model.ShellPage;
import cf.explorer.model.ExampleWorkspace;
import cf.explorer.catalog.ApplicationCatalog;
import cf.explorer.catalog.CatalogProgress;
import cf.explorer.env.EnvDraft;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.Toolkit;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ShellViewTest {
  @Test
  void catalogRootFiltersAndRestoresTypedSelectionAcrossNavigation() {
    var first = new ApplicationCatalog.App("one", "same", "STARTED", "s1", "production",
        "o1", "team", "2024-01-01", "2024-02-01");
    var second = new ApplicationCatalog.App("two", "same", "STOPPED", "s2", "staging",
        "o1", "team", "2023-01-01", "2024-03-01");
    ApplicationCatalogPage catalog = new ApplicationCatalogPage();
    ShellState state = new ShellState(catalog, ShellCommands.create(), Runnable::run, Runnable::run);
    assertTrue(render(state).contains("Loading Cloud Foundry catalog"));
    catalog.loaded(new ApplicationCatalog(1, 2, List.of(first, second)));
    assertTrue(render(state).contains("Apps  2/2"));
    assertTrue(render(state).contains("STARTED   c: 2024-01-01 \u203a u: 2024-02-01"));
    ShellView.handleKey(KeyEvent.ofKey(KeyCode.DOWN), state, () -> {});
    assertEquals(second, state.selected(ApplicationCatalog.App.class));
    for (char c : "staging".toCharArray()) {
      ShellView.handleKey(KeyEvent.ofChar(c), state, () -> {});
    }
    assertEquals(second, state.selected(ApplicationCatalog.App.class));
    assertTrue(render(state).contains("Apps  1/2"));
    Buffer filtered = renderBuffer(state, 100, 24);
    boolean highlightedMatch = false;
    for (int y = 0; y < filtered.height(); y++) {
      for (int x = 0; x < filtered.width(); x++) {
        if (filtered.get(x, y).symbol().equals("s")
            && filtered.get(x, y).style().fg().filter(Color.LIGHT_YELLOW::equals).isPresent()) {
          highlightedMatch = true;
        }
      }
    }
    assertTrue(highlightedMatch, "The selected row's filter match should be colored");
    ShellView.handleKey(KeyEvent.ofChar('/'), state, () -> {});
    assertTrue(render(state).contains(" Command "));
    assertFalse(render(state).contains(" Filter "));
    ShellView.handleKey(KeyEvent.ofKey(KeyCode.ESCAPE), state, () -> {});
    assertTrue(render(state).contains("staging|"));
    state.openPage(() -> Toolkit.text("Detail"));
    assertNull(state.selected(ApplicationCatalog.App.class));
    state.back();
    assertEquals(second, state.selected(ApplicationCatalog.App.class));
    assertTrue(render(state).contains("staging|"));
    ShellView.handleKey(KeyEvent.ofKey(KeyCode.ESCAPE), state, () -> {});
    assertTrue(render(state).contains("Apps  2/2"));
    assertEquals(second, state.selected(ApplicationCatalog.App.class));
    catalog.failed("Could not connect to the Cloud Foundry endpoint.");
    String failure = render(state);
    assertTrue(failure.contains("Catalog loading failed."), failure);
    assertTrue(failure.contains("Could not connect to the Cloud Foundry endpoint."), failure);
    assertTrue(failure.contains("/refresh"), failure);
  }

  @Test
  void catalogLoadingAppliesResultsOnlyOnRenderThreadAndDiscardsStaleResults() {
    Queue<Runnable> worker = new ArrayDeque<>();
    Queue<Runnable> dispatch = new ArrayDeque<>();
    ApplicationCatalogPage page = new ApplicationCatalogPage();
    ShellState state = new ShellState(page, ShellCommands.create(), worker::add, dispatch::add);
    var app = new ApplicationCatalog.App("one", "app", "STARTED", "s", "space", "o", "org",
        "2024-01-01", "2024-01-02");
    ApplicationCatalog data = new ApplicationCatalog(1, 1, List.of(app));
    page.startLoading(() -> data, worker::add, dispatch::add, () -> !state.quitRequested());
    worker.remove().run();
    assertTrue(render(state).contains("Loading Cloud Foundry"));
    dispatch.remove().run();
    assertEquals(app, state.selected(ApplicationCatalog.App.class));

    page.startLoading(() -> data, worker::add, dispatch::add, () -> !state.quitRequested());
    worker.remove().run();
    page.startLoading(() -> { throw new IllegalStateException("private content"); },
        worker::add, dispatch::add, () -> !state.quitRequested());
    dispatch.remove().run();
    assertNull(state.currentSelection());
    worker.remove().run();
    dispatch.remove().run();
    assertTrue(render(state).contains("Catalog loading failed."));
    assertFalse(render(state).contains("private content"));

    page.startLoading(() -> data, worker::add, dispatch::add, () -> !state.quitRequested());
    worker.remove().run();
    state.requestQuit();
    dispatch.remove().run();
    assertNull(state.currentSelection());
  }

  @Test
  void catalogLoadingShowsPerResourceProgressAndEscCancels() {
    Queue<Runnable> worker = new ArrayDeque<>();
    Queue<Runnable> dispatch = new ArrayDeque<>();
    ApplicationCatalogPage page = new ApplicationCatalogPage();
    ShellState state = new ShellState(page, ShellCommands.create(), worker::add, dispatch::add);
    var app = new ApplicationCatalog.App("one", "app", "STARTED", "s", "space", "o", "org",
        "2024-01-01", "2024-01-02");
    page.startLoading((CatalogProgress progress) -> {
      progress.authenticated();
      progress.completed(CatalogProgress.Resource.ORGANIZATIONS, 4);
      progress.fetched(CatalogProgress.Resource.SPACES, 100, 400);
      return new ApplicationCatalog(1, 1, List.of(app));
    }, worker::add, dispatch::add, () -> !state.quitRequested());
    String waiting = render(state);
    assertTrue(waiting.contains("Loading Cloud Foundry catalog..."), waiting);
    assertTrue(waiting.contains("Sign in        contacting UAA..."), waiting);
    assertTrue(waiting.contains("Apps           waiting"), waiting);
    assertTrue(waiting.contains("0%"), waiting);
    assertTrue(waiting.contains("Press Esc to cancel."), waiting);

    worker.remove().run();
    for (int i = 0; i < 3; i++) {
      dispatch.remove().run();
    }
    String progress = render(state);
    assertTrue(progress.contains("\u2713 Organizations  4 found"), progress);
    assertTrue(progress.contains("Spaces         100 of 400"), progress);
    assertTrue(progress.contains("Apps           fetching..."), progress);
    assertTrue(progress.contains("56%"), progress);

    ShellView.handleKey(KeyEvent.ofKey(KeyCode.ESCAPE), state, () -> {});
    dispatch.remove().run();
    String cancelled = render(state);
    assertTrue(cancelled.contains("Catalog loading cancelled."), cancelled);
    assertTrue(cancelled.contains("Run /refresh (Ctrl+R) to retry."), cancelled);
    assertNull(state.currentSelection());
    assertNull(page.activity());
  }

  @Test
  void escCancelsARefreshButClearsTheFilterFirst() {
    Queue<Runnable> worker = new ArrayDeque<>();
    Queue<Runnable> dispatch = new ArrayDeque<>();
    ApplicationCatalogPage page = new ApplicationCatalogPage();
    ShellState state = new ShellState(page, ShellCommands.create(), worker::add, dispatch::add);
    var app = new ApplicationCatalog.App("one", "app", "STARTED", "s", "space", "o", "org",
        "2024-01-01", "2024-01-02");
    var other = new ApplicationCatalog.App("two", "other", "STARTED", "s", "space", "o", "org",
        "2024-01-01", "2024-01-02");
    page.loaded(new ApplicationCatalog(1, 1, List.of(app)));
    page.startRefresh(() -> new ApplicationCatalog(1, 1, List.of(app, other)), worker::add,
        dispatch::add, () -> true, state::showStatus);
    String refreshing = render(state);
    assertTrue(refreshing.contains("refreshing 0%]"), refreshing);
    assertEquals("Refreshing catalog...", state.statusActivity().label());

    keys(state, "ap");
    ShellView.handleKey(KeyEvent.ofKey(KeyCode.ESCAPE), state, () -> {});
    assertEquals("", page.inputValue());
    assertTrue(page.activity() != null, "the first Esc clears the filter");
    ShellView.handleKey(KeyEvent.ofKey(KeyCode.ESCAPE), state, () -> {});
    assertNull(page.activity());
    assertEquals("Refresh cancelled. Keeping the current catalog.", state.status());
    worker.remove().run();
    dispatch.remove().run();
    assertTrue(render(state).contains("Apps  1/1"));
  }

  @Test
  void catalogEmptyStatesExplainWhatToDoNext() {
    ApplicationCatalogPage page = new ApplicationCatalogPage();
    ShellState state = new ShellState(page, ShellCommands.create(), Runnable::run, Runnable::run);
    page.loaded(new ApplicationCatalog(1, 1, List.of()));
    String empty = render(state);
    assertTrue(empty.contains("No apps."), empty);
    assertTrue(empty.contains("Run /refresh (Ctrl+R) to reload."), empty);
    page.loaded(new ApplicationCatalog(1, 1, List.of(new ApplicationCatalog.App("one", "app",
        "STARTED", "s", "space", "o", "org", "2024-01-01", "2024-01-02"))));
    keys(state, "zzz");
    String noMatch = render(state);
    assertTrue(noMatch.contains("No matching apps."), noMatch);
    assertTrue(noMatch.contains("Press Esc to clear the filter."), noMatch);
  }

  @Test
  void catalogShowsMatchesInLongNamesAndIdentifiers() {
    var app = new ApplicationCatalog.App("unique-guid-987",
        "very-long-application-name-with-many-parts-and-a-needle-near-the-end",
        "STARTED", "space-id", "production", "org-id", "organization",
        "2024-01-01", "2024-01-02");
    ApplicationCatalogPage page = new ApplicationCatalogPage();
    page.loaded(new ApplicationCatalog(1, 1, List.of(app)));
    ShellState state = new ShellState(page, ShellCommands.create(), Runnable::run, Runnable::run);
    for (char c : "needle".toCharArray()) {
      ShellView.handleKey(KeyEvent.ofChar(c), state, () -> {});
    }
    String screen = render(state, 100, 24);
    assertTrue(screen.contains("needle"), screen);
    assertTrue(screen.contains("\u2026"), screen);
    ShellView.handleKey(KeyEvent.ofKey(KeyCode.ESCAPE), state, () -> {});
    for (char c : "unique-guid".toCharArray()) {
      ShellView.handleKey(KeyEvent.ofChar(c), state, () -> {});
    }
    screen = render(state, 100, 24);
    assertTrue(screen.contains("id: unique-guid-987"), screen);
    assertEquals(app, state.selected(ApplicationCatalog.App.class));
  }

  @Test
  void rendersListSuggestionsErrorsAndDetail() {
    ShellState state = state(List.of("Atlas", "Alpine", "Delta"));
    String initial = render(state);
    assertTrue(initial.contains("CF EXPLORER"), initial);
    assertTrue(initial.contains("Examples"), initial);
    assertTrue(initial.contains(" Filter "), initial);
    assertTrue(initial.contains("> Atlas"), initial);
    keys(state, "A/in");
    String suggestions = render(state);
    assertTrue(suggestions.contains("/in|"), suggestions);
    assertTrue(suggestions.contains("/inspect  Open the selected item"), suggestions);
    keys(state, KeyCode.ENTER);
    String detail = render(state);
    assertTrue(detail.contains(" Detail "), detail);
    assertTrue(detail.contains(" Atlas"), detail);
    keys(state, KeyCode.ESCAPE);
    keys(state, "/nonsense");
    keys(state, KeyCode.ENTER);
    String error = render(state);
    assertTrue(error.contains("No suggestions."), error);
    assertTrue(error.contains("Unknown command: /nonsense"), error);
  }

  @Test
  void rendersEmptyAndNoMatchStates() {
    assertTrue(render(state(List.of())).contains("No items."));
    ShellState state = state(List.of("Atlas"));
    keys(state, "missing");
    assertTrue(render(state).contains("No matching items."));
  }

  @Test
  void keepsSelectionAndCommandInputVisibleInCompactTerminals() {
    ShellState state = state(List.of("Atlas", "Alpine", "Delta", "Echo", "Foxtrot"));
    for (int i = 0; i < 4; i++) {
      keys(state, KeyCode.DOWN);
    }
    keys(state, "/");
    for (int i = 0; i < 4; i++) {
      keys(state, KeyCode.DOWN);
    }
    String screen = render(state, 40, 12);
    assertTrue(screen.contains("> Foxtrot"), screen);
    assertTrue(screen.contains("> /quit"), screen);
    assertTrue(screen.contains(" /|"), screen);
    assertTrue(screen.contains("Ctrl+C quit"), screen);
  }

  @Test
  void selectedItemHasAVisibleAccent() {
    Buffer buffer = renderBuffer(state(List.of("Atlas")), 60, 16);
    boolean highlighted = false;
    for (int y = 0; y < buffer.height(); y++) {
      for (int x = 0; x < buffer.width(); x++) {
        if (buffer.get(x, y).symbol().equals(">")
            && buffer.get(x, y).style().fg().filter(Color.LIGHT_CYAN::equals).isPresent()
            && buffer.get(x, y).style().bg().filter(Color.indexed(236)::equals).isPresent()) {
          highlighted = true;
        }
      }
    }
    assertTrue(highlighted);
  }

  @Test
  void envEditorHighlightsKeyAndValueMatchesInEscapedPreviews() {
    EnvDraft draft = new EnvDraft(Map.of());
    draft.stageSet("SETTING", "x\r\na\tb-c");
    var app = new ApplicationCatalog.App("one", "app", "STARTED", "s", "space", "o", "org",
        "2024-01-01", "2024-01-02");
    ShellState state = new ShellState(new EnvEditorPage(app, draft), ShellCommands.create(),
        Runnable::run, Runnable::run);
    keys(state, "setting AbC");

    Buffer buffer = renderBuffer(state, 100, 24);
    assertRowColors(buffer, "SETTING", Color.LIGHT_YELLOW, Color.LIGHT_YELLOW,
        Color.LIGHT_YELLOW, Color.LIGHT_YELLOW, Color.LIGHT_YELLOW, Color.LIGHT_YELLOW,
        Color.LIGHT_YELLOW);
    assertRowColors(buffer, "x\\na\\tb-c", Color.LIGHT_GREEN, Color.LIGHT_GREEN,
        Color.LIGHT_GREEN, Color.LIGHT_YELLOW, Color.LIGHT_GREEN, Color.LIGHT_GREEN,
        Color.LIGHT_YELLOW, Color.LIGHT_GREEN, Color.LIGHT_YELLOW);
  }

  @Test
  void envEditorHighlightsSubstringWithoutColoringTruncationOrEmptyPlaceholder() {
    EnvDraft draft = new EnvDraft(Map.of("KEY", "hit-012345678901234567890-tail", "EMPTY", ""));
    var app = new ApplicationCatalog.App("one", "app", "STARTED", "s", "space", "o", "org",
        "2024-01-01", "2024-01-02");
    ShellState state = state(List.of());
    EnvEditorPage editor = new EnvEditorPage(app, draft);
    state.openPage(editor);
    keys(state, "hit tail");
    Buffer buffer = renderBuffer(state, 40, 12);
    assertRowColors(buffer, "hit-", Color.LIGHT_YELLOW, Color.LIGHT_YELLOW,
        Color.LIGHT_YELLOW, Color.WHITE);
    assertRowColors(buffer, "\u2026", Color.WHITE);

    keys(state, KeyCode.ESCAPE);
    assertEquals("", editor.inputValue());
    keys(state, "empty");
    assertRowColors(renderBuffer(state, 100, 24), "<empty>", Color.WHITE, Color.WHITE,
        Color.WHITE, Color.WHITE, Color.WHITE, Color.WHITE, Color.WHITE);
  }

  private static void assertRowColors(Buffer buffer, String text, Color... colors) {
    assertEquals(text.length(), colors.length);
    for (int y = 0; y < buffer.height(); y++) {
      StringBuilder row = new StringBuilder();
      for (int x = 0; x < buffer.width(); x++) {
        row.append(buffer.get(x, y).symbol());
      }
      int start = row.indexOf(text);
      if (start >= 0 && row.indexOf(" = ") >= 0) {
        for (int i = 0; i < text.length(); i++) {
          assertEquals(colors[i], buffer.get(start + i, y).style().fg().orElse(null),
              "Unexpected color at " + i + " in " + text);
        }
        return;
      }
    }
    throw new AssertionError("Text not found in an env row: " + text);
  }

  @Test
  void routesTypingNavigationCompletionAndBack() {
    ExampleWorkspace examples = new ExampleWorkspace(List.of("Atlas", "Alpine", "Delta"));
    ShellState state = examples.state();
    keys(state, "Al");
    keys(state, KeyCode.DOWN);
    keys(state, "/in");
    keys(state, KeyCode.TAB);
    keys(state, KeyCode.ENTER);
    assertTrue(render(state).contains(" Alpine"));
    keys(state, KeyCode.ESCAPE);
    assertNull(state.page());
    assertEquals("Al", examples.root().filter());
    assertEquals("Alpine", state.selected(String.class));
    assertTrue(render(state).contains("Al|"));
    keys(state, KeyCode.ESCAPE);
    assertEquals("", examples.root().filter());
  }

  @Test
  void qIsFilterInputAndQuitCommandExits() {
    ExampleWorkspace examples = new ExampleWorkspace(List.of("Atlas"));
    ShellState state = examples.state();
    AtomicBoolean quit = new AtomicBoolean();
    assertEquals(EventResult.HANDLED,
        ShellView.handleKey(KeyEvent.ofChar('q'), state, () -> quit.set(true)));
    assertEquals("q", examples.root().filter());
    for (char c : "/quit".toCharArray()) {
      ShellView.handleKey(KeyEvent.ofChar(c), state, () -> quit.set(true));
    }
    ShellView.handleKey(KeyEvent.ofKey(KeyCode.ENTER), state, () -> quit.set(true));
    assertTrue(quit.get());
  }

  @Test
  void localSummaryReflectsFilteredAndMarkedItems() {
    ExampleWorkspace examples = new ExampleWorkspace(List.of("Alpine", "Alps", "Delta"));
    ShellState state = examples.state();
    keys(state, "Al/mark");
    keys(state, KeyCode.ENTER);
    keys(state, "/summary");
    keys(state, KeyCode.ENTER);
    String screen = render(state);
    assertTrue(screen.contains("Visible items: 2"), screen);
    assertTrue(screen.contains("Marked visible items: 1"), screen);
    keys(state, KeyCode.ESCAPE);
    assertEquals("Al", examples.root().filter());
    assertEquals("Alpine", state.selected(String.class));
  }

  @Test
  void marksAreLabelsNotSelections() {
    ShellState state = state(List.of("Atlas", "Borealis"));
    keys(state, "/mark");
    keys(state, KeyCode.ENTER);
    keys(state, KeyCode.DOWN);
    keys(state, "/mark");
    keys(state, KeyCode.ENTER);
    String screen = render(state);
    assertTrue(screen.contains("Atlas (marked)"), screen);
    assertTrue(screen.contains("> Borealis (marked)"), screen);
    assertEquals("Borealis", state.selected(String.class));
    keys(state, "/resources");
    keys(state, KeyCode.ENTER);
    assertTrue(render(state).contains("Resources for Borealis"));
  }

  @Test
  void resourcesAndInspectUseCurrentSelectionAndRestoreBothViews() {
    ExampleWorkspace examples = new ExampleWorkspace(List.of("Atlas", "Borealis", "Delta"));
    ShellState state = examples.state();
    keys(state, "a");
    keys(state, KeyCode.DOWN);
    assertEquals("Borealis", state.currentSelection());
    keys(state, "/resources");
    keys(state, KeyCode.ENTER);
    String resources = render(state);
    assertTrue(resources.contains("Resources for Borealis"), resources);
    assertTrue(resources.contains("> API"), resources);
    assertEquals("API", state.currentSelection());
    keys(state, KeyCode.DOWN);
    assertTrue(render(state).contains("> Worker"));
    assertEquals("Worker", state.currentSelection());
    keys(state, "/inspect");
    keys(state, KeyCode.ENTER);
    String detail = render(state);
    assertTrue(detail.contains("Resource detail"), detail);
    assertTrue(detail.contains(" Worker"), detail);
    assertTrue(detail.contains("Example resource for Borealis."), detail);
    assertNull(state.currentSelection());
    keys(state, KeyCode.ESCAPE);
    assertTrue(render(state).contains("> Worker"));
    assertEquals("Worker", state.currentSelection());
    keys(state, "/back");
    keys(state, KeyCode.ENTER);
    assertNull(state.page());
    assertEquals("Borealis", state.currentSelection());
    assertEquals("a", examples.root().filter());
    assertEquals(1, examples.root().inputCursor());
  }

  @Test
  void pageHandlesKeysWithoutInterceptingShellCommandsOrEscape() {
    ShellState state = state(List.of("Atlas"));
    AtomicBoolean handled = new AtomicBoolean();
    state.openPage(new ShellPage() {
      @Override public Element render() { return Toolkit.text("Custom"); }
      @Override public EventResult handleKey(KeyEvent event) {
        handled.set(true);
        return EventResult.HANDLED;
      }
    });
    ShellView.handleKey(KeyEvent.ofKey(KeyCode.DOWN), state, () -> {});
    assertTrue(handled.get());
    handled.set(false);
    ShellView.handleKey(KeyEvent.ofChar('/'), state, () -> {});
    assertEquals("", state.command());
    assertFalse(handled.get());
    ShellView.handleKey(KeyEvent.ofKey(KeyCode.ESCAPE), state, () -> {});
    ShellView.handleKey(KeyEvent.ofKey(KeyCode.ESCAPE), state, () -> {});
    assertNull(state.page());
  }

  @Test
  void rendersLoadingAndSafeFailure() {
    Queue<Runnable> worker = new ArrayDeque<>();
    Queue<Runnable> render = new ArrayDeque<>();
    ShellState state = new ShellState(() -> Toolkit.text("Root"), List.of(), worker::add, render::add);
    state.openAsyncPage("Building report", () -> {
      throw new IllegalStateException("private content");
    });
    assertTrue(render(state).contains("Building report..."));
    worker.remove().run();
    render.remove().run();
    String screen = render(state);
    assertTrue(screen.contains("Building report failed."), screen);
    assertFalse(screen.contains("private content"), screen);
  }

  @Test
  void loadingPageAnimatesAndEscCancelsAndDiscardsTheLateResult() {
    Queue<Runnable> worker = new ArrayDeque<>();
    Queue<Runnable> render = new ArrayDeque<>();
    ShellState state = new ShellState(() -> Toolkit.text("Root"), ShellCommands.create(),
        worker::add, render::add);
    state.openAsyncPage("Building report", () -> () -> Toolkit.text("Report"));
    assertEquals("Building report", state.loading().label());
    String screen = render(state);
    assertTrue(screen.contains(" Working "), screen);
    assertTrue(screen.contains("Press Esc to cancel."), screen);
    ShellView.handleKey(KeyEvent.ofKey(KeyCode.ESCAPE), state, () -> {});
    assertNull(state.page());
    assertNull(state.loading());
    assertEquals("Cancelled: Building report.", state.status());
    worker.remove().run();
    render.remove().run();
    assertNull(state.page());
    assertTrue(render(state).contains("Root"));
  }

  @Test
  void minimumWaitKeepsTheSpinnerUpForFastResults() throws InterruptedException {
    BlockingQueue<Runnable> render = new LinkedBlockingQueue<>();
    ShellState state = new ShellState(() -> Toolkit.text("Root"), List.of(), Runnable::run,
        render::add, Duration.ofMillis(200));
    state.openAsyncPage("Work", () -> () -> Toolkit.text("Done"));
    assertTrue(render.isEmpty(), "a fast result waits for the minimum display time");
    Runnable apply = render.poll(5, TimeUnit.SECONDS);
    assertTrue(state.loading().elapsed().toMillis() >= 200);
    apply.run();
    assertNull(state.loading());
    assertTrue(render(state).contains("Done"));
  }

  @Test
  void statusLineSpinsWhileProgressIsShown() {
    ShellState state = state(List.of("Atlas"));
    state.showProgress("Opening Atlas...");
    assertEquals("Opening Atlas...", state.statusActivity().label());
    String screen = render(state);
    assertTrue(screen.contains(" Opening Atlas..."), screen);
    char spinner = screen.charAt(screen.indexOf("Opening Atlas...") - 2);
    assertTrue(spinner >= '\u2800' && spinner <= '\u28ff', screen);
    state.showStatus("Opened Atlas.");
    assertNull(state.statusActivity());
  }

  @Test
  void ctrlCDiscardsPendingCompletion() {
    Queue<Runnable> worker = new ArrayDeque<>();
    Queue<Runnable> render = new ArrayDeque<>();
    ShellState state = new ShellState(() -> Toolkit.text("Root"), List.of(), worker::add, render::add);
    state.openAsyncPage("Work", () -> () -> Toolkit.text("Done"));
    ShellPage loading = state.page();
    worker.remove().run();
    AtomicBoolean quit = new AtomicBoolean();
    ShellView.handleKey(KeyEvent.ofChar('c', KeyModifiers.CTRL), state, () -> quit.set(true));
    render.remove().run();
    assertTrue(quit.get());
    assertTrue(state.quitRequested());
    assertEquals(loading, state.page());
  }

  @Test
  void ctrlShortcutsRunFromFilterAndCommandViewWithoutChangingInput() {
    ExampleWorkspace examples = new ExampleWorkspace(List.of("Atlas"));
    ShellState state = examples.state();
    AtomicBoolean quit = new AtomicBoolean();
    keys(state, "xb");
    assertEquals("xb", examples.root().filter());
    assertEquals(EventResult.HANDLED, ShellView.handleKey(
        KeyEvent.ofChar('b', KeyModifiers.CTRL), state, () -> quit.set(true)));
    assertEquals("xb", examples.root().filter());
    assertEquals("Command unavailable: /back", state.status());

    keys(state, "/in");
    ShellView.handleKey(KeyEvent.ofChar('x', KeyModifiers.CTRL), state, () -> quit.set(true));
    assertNull(state.command());
    assertTrue(quit.get());
    assertTrue(state.quitRequested());
  }

  @Test
  void textProducingChordsAndUnboundCtrlKeysAreNotShortcuts() {
    ShellState state = state(List.of("Atlas"));
    KeyEvent altGr = KeyEvent.builder().code(KeyCode.CHAR).character('x')
        .modifiers(KeyModifiers.of(true, true, false)).text("\u00bb").build();
    assertEquals(ShellCommand.NO_SHORTCUT, ShellView.shortcutKey(altGr, state));
    assertEquals(ShellCommand.NO_SHORTCUT,
        ShellView.shortcutKey(KeyEvent.ofChar('x', KeyModifiers.ALT), state));
    assertEquals(ShellCommand.NO_SHORTCUT,
        ShellView.shortcutKey(KeyEvent.ofChar('y', KeyModifiers.CTRL), state));
    assertEquals(ShellCommand.NO_SHORTCUT, ShellView.shortcutKey(KeyEvent.ofChar('x'), state));
    assertEquals('x', ShellView.shortcutKey(KeyEvent.ofChar('x', KeyModifiers.CTRL), state));
    assertEquals('x', ShellView.shortcutKey(
        KeyEvent.ofChar('X', KeyModifiers.of(true, false, true)), state));
  }

  @Test
  void rendersGlobalDebugWarningBannerAcrossPagesAndCompactTerminals() {
    ShellState normal = state(List.of("Atlas"));
    assertFalse(render(normal, 100, 24).contains("DEBUG MODE"));

    ShellState debugState = new ShellState(
        () -> Toolkit.text("CatalogRoot"),
        ShellCommands.create(),
        Runnable::run,
        Runnable::run,
        Duration.ZERO,
        "/tmp/profile/cf-explorer.log");

    String full = render(debugState, 110, 24);
    assertTrue(full.contains("[DEBUG MODE]"), full);
    assertTrue(full.contains("sensitive data may be logged to /tmp/profile/cf-explorer.log"), full);
    assertTrue(full.contains("CatalogRoot"), full);

    debugState.openPage(() -> Toolkit.text("EnvEditorSubpage"));
    String subpage = render(debugState, 110, 24);
    assertTrue(subpage.contains("[DEBUG MODE]"), subpage);
    assertTrue(subpage.contains("EnvEditorSubpage"), subpage);

    String compact = render(debugState, 90, 12);
    assertTrue(compact.contains("DEBUG MODE: sensitive data may be logged"), compact);
    assertTrue(compact.contains("EnvEditorSubpage"), compact);
  }

  private static String render(ShellState state) {
    return render(state, 100, 24);
  }

  private static ShellState state(List<String> items) {
    return new ExampleWorkspace(items).state();
  }

  private static void keys(ShellState state, String input) {
    for (char c : input.toCharArray()) {
      ShellView.handleKey(KeyEvent.ofChar(c), state, () -> {});
    }
  }

  private static void keys(ShellState state, KeyCode key) {
    ShellView.handleKey(KeyEvent.ofKey(key), state, () -> {});
  }

  private static String render(ShellState state, int width, int height) {
    Buffer buffer = renderBuffer(state, width, height);
    StringBuilder result = new StringBuilder();
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        result.append(buffer.get(x, y).symbol());
      }
      result.append('\n');
    }
    return result.toString();
  }

  private static Buffer renderBuffer(ShellState state, int width, int height) {
    Rect area = new Rect(0, 0, width, height);
    Buffer buffer = Buffer.empty(area);
    ShellView.render(state, () -> {})
        .render(Frame.forTesting(buffer), area, DefaultRenderContext.createEmpty());
    return buffer;
  }
}
