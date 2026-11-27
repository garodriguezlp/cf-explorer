package cf.explorer.model;

import static cf.explorer.model.ExampleWorkspace.press;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tamboui.toolkit.Toolkit;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ShellStateTest {
  private static final ShellPage ROOT = () -> Toolkit.text("Root");

  @Test
  void typingOutsideCommandModeIsIgnoredByTheShell() {
    ShellState state = new ShellState(ROOT, ShellCommands.create(), Runnable::run, Runnable::run);
    state.type('a');
    assertNull(state.command());
    state.type('/');
    assertEquals("", state.command());
    assertSame(ROOT, state.activePage());
  }

  @Test
  void editsCommandAtCursorAndDeletesWholeUnicodeCharacters() {
    ShellState state = new ShellState(ROOT, ShellCommands.create(), Runnable::run, Runnable::run);
    "/A\uD83D\uDE00B".codePoints().forEach(state::type);
    state.moveCursor(-1);
    state.backspace();
    assertEquals("AB", state.command());
    state.delete();
    assertEquals("A", state.command());
    state.cursorToStart();
    "x".codePoints().forEach(state::type);
    assertEquals("xA", state.command());
    state.cursorToEnd();
    state.backspace();
    assertEquals("x", state.command());
  }

  @Test
  void completesAndExecutesCommandsAndRestoresRootContext() {
    ExampleWorkspace examples = new ExampleWorkspace(List.of("Atlas", "Delta", "Alpine"));
    ShellState state = examples.state();
    press(state, "Al");
    state.rootPage().handleKey(KeyEvent.ofKey(KeyCode.DOWN));
    assertEquals("Alpine", state.selected(String.class));
    press(state, "/");
    assertEquals(List.of("inspect", "mark", "resources", "summary", "quit"),
        state.suggestions().stream().map(ShellCommand::name).toList());
    state.moveSelection(1);
    state.complete();
    assertEquals("mark", state.command());
    state.confirm();
    assertNull(state.command());
    assertEquals("Alpine (marked)", examples.root().label("Alpine"));
    assertEquals("Marked Alpine.", state.status());
    press(state, "/insp");
    state.confirm();
    assertTrue(state.page() != null);
    press(state, "/");
    assertEquals(List.of("back", "quit"),
        state.suggestions().stream().map(ShellCommand::name).toList());
    press(state, "back");
    state.confirm();
    assertNull(state.page());
    assertEquals("Al", examples.root().filter());
    assertEquals("Alpine", state.selected(String.class));
  }

  @Test
  void handlesUnknownUnavailableAndEmptySuggestions() {
    ShellState state = new ExampleWorkspace(List.of("Atlas")).state();
    press(state, "missing/inspect");
    assertTrue(state.suggestions().isEmpty());
    state.complete();
    assertEquals("No suggestions.", state.status());
    state.confirm();
    assertEquals("Command unavailable: /inspect", state.status());
    state.cancel();
    press(state, "/nonsense");
    state.confirm();
    assertEquals("Unknown command: /nonsense", state.status());
    state.cancel();
    press(state, "/back");
    state.confirm();
    assertEquals("Command unavailable: /back", state.status());
  }

  @Test
  void escapeCancelsCommandThenReturnsFromPage() {
    ShellState state = new ExampleWorkspace(List.of("Atlas")).state();
    press(state, "/inspect");
    state.confirm();
    press(state, "/");
    state.cancel();
    assertNull(state.command());
    assertTrue(state.page() != null);
    state.cancel();
    assertNull(state.page());
    press(state, "/quit");
    state.confirm();
    assertTrue(state.quitRequested());
  }

  @Test
  void registeredCommandsDispatchAndFailuresAreReportedSafely() {
    AtomicBoolean ran = new AtomicBoolean();
    ShellCommand action = new ShellCommand("local", "Local action", s -> true,
        s -> ran.set(true));
    ShellCommand failure = new ShellCommand("fail", "Fail", s -> true,
        s -> { throw new IllegalStateException("sensitive details"); });
    ShellState state = new ShellState(ROOT, List.of(action, failure),
        Runnable::run, Runnable::run);
    press(state, "/local");
    state.confirm();
    assertTrue(ran.get());
    press(state, "/fail");
    state.confirm();
    assertEquals("Command failed: /fail", state.status());
    assertThrows(IllegalArgumentException.class, () -> new ShellState(ROOT,
        List.of(action, action), Runnable::run, Runnable::run));
  }

  @Test
  void commandNamesAreSingleWords() {
    assertThrows(IllegalArgumentException.class,
        () -> new ShellCommand("export-env", "Two words", s -> true, s -> {}));
    assertThrows(IllegalArgumentException.class,
        () -> new ShellCommand("export env", "Two words", s -> true, s -> {}));
    assertEquals("export", new ShellCommand("export", "One word", s -> true, s -> {}).name());
  }

  @Test
  void asyncCompletionRunsOnlyWhenDispatched() {
    Queue<Runnable> worker = new ArrayDeque<>();
    Queue<Runnable> render = new ArrayDeque<>();
    ShellState state = new ShellState(ROOT, List.of(), worker::add, render::add);
    ShellPage result = () -> Toolkit.text("Done");
    state.openAsyncPage("Work", () -> result);
    ShellPage loading = state.page();
    worker.remove().run();
    assertSame(loading, state.page());
    render.remove().run();
    assertSame(result, state.page());
    state.cancel();
    assertNull(state.page());
    assertSame(ROOT, state.activePage());
  }

  @Test
  void asyncFailuresAndStaleResultsDoNotReplaceNewViews() {
    Queue<Runnable> worker = new ArrayDeque<>();
    Queue<Runnable> render = new ArrayDeque<>();
    ShellState state = new ShellState(ROOT, List.of(), worker::add, render::add);
    state.openAsyncPage("Work", () -> { throw new IllegalStateException("secret"); });
    ShellPage loading = state.page();
    worker.remove().run();
    render.remove().run();
    assertTrue(state.page() != loading);
    state.cancel();
    state.openAsyncPage("Work", () -> () -> Toolkit.text("Old result"));
    worker.remove().run();
    state.cancel();
    ShellPage current = () -> Toolkit.text("Current");
    state.openPage(current);
    render.remove().run();
    assertSame(current, state.page());
    state.cancel();
    assertNull(state.page());
  }

  @Test
  void nestedViewsRestoreThePreviousPage() {
    ShellState state = new ShellState(ROOT, List.of(), Runnable::run, Runnable::run);
    ShellPage first = () -> Toolkit.text("First");
    ShellPage second = () -> Toolkit.text("Second");
    state.openPage(first);
    state.openPage(second);
    state.back();
    assertSame(first, state.page());
    state.back();
    assertNull(state.page());
  }

  @Test
  void suggestionSelectionRemainsValidWhenAsyncCompletionChangesAvailability() {
    Queue<Runnable> worker = new ArrayDeque<>();
    Queue<Runnable> render = new ArrayDeque<>();
    ShellPage[] loading = new ShellPage[1];
    ShellCommand temporary = new ShellCommand("temporary", "During loading",
        s -> s.page() == loading[0], s -> {});
    ShellState state = new ShellState(ROOT,
        Stream.concat(ShellCommands.create().stream(), Stream.of(temporary)).toList(),
        worker::add, render::add);
    state.openAsyncPage("Work", () -> () -> Toolkit.text("Done"));
    loading[0] = state.page();
    press(state, "/");
    state.moveSelection(2);
    worker.remove().run();
    render.remove().run();
    assertEquals(1, state.suggestionIndex());
    state.confirm();
    assertTrue(state.quitRequested());
  }
}
