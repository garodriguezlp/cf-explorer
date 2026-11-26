package cf.explorer.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cf.explorer.example.ExampleFeatures;
import dev.tamboui.toolkit.Toolkit;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ShellStateTest {
  @Test
  void filteringAndSelectionRemainStableWhenPossible() {
    ShellState state = state(List.of("Atlas", "Borealis", "Delta"));
    state.moveSelection(1);
    type(state, "a");
    assertEquals("Borealis", state.selected());
    assertEquals(List.of("Atlas", "Borealis", "Delta"), state.visibleItems());
    type(state, "TL");
    assertEquals(List.of("Atlas"), state.visibleItems());
    assertEquals("Atlas", state.selected());
    type(state, "x");
    assertTrue(state.visibleItems().isEmpty());
    assertNull(state.selected());
    state.backspace();
    assertEquals("Atlas", state.selected());
    state.cancel();
    assertEquals("", state.filter());
    assertEquals("Atlas", state.selected());
    assertTrue(state(List.of()).visibleItems().isEmpty());
  }

  @Test
  void editsAtCursorAndCancelsCommandsWithoutLosingContext() {
    ShellState state = state(List.of("Alpine", "Delta"));
    type(state, "At");
    state.moveCursor(-1);
    type(state, "l");
    assertEquals("Alt", state.filter());
    state.delete();
    assertEquals("Al", state.filter());
    type(state, "/in");
    state.cancel();
    assertEquals("Al", state.filter());
    assertEquals("Alpine", state.selected());
    assertEquals(2, state.cursor());
    state.cursorToStart();
    state.delete();
    assertEquals("l", state.filter());
    state.cursorToEnd();
    state.backspace();
    assertEquals("", state.filter());
  }

  @Test
  void preservesFilterCursorAcrossCommandsAndDeletesWholeUnicodeCharacters() {
    ShellState state = state(List.of("A😀B"));
    type(state, "A😀B");
    state.moveCursor(-1);
    type(state, "/");
    state.cancel();
    state.backspace();
    assertEquals("AB", state.filter());
    state.delete();
    assertEquals("A", state.filter());
  }

  @Test
  void completesAndExecutesCommandsAndRestoresListContext() {
    ShellState state = state(List.of("Atlas", "Delta", "Alpine"));
    type(state, "Al");
    state.moveSelection(1);
    type(state, "/");
    assertEquals(List.of("inspect", "mark", "resources", "summary", "quit"),
        state.suggestions().stream().map(ShellCommand::name).toList());
    state.moveSelection(1);
    state.complete();
    assertEquals("mark", state.command());
    state.confirm();
    assertEquals("Alpine (marked)", state.itemLabel("Alpine"));
    assertEquals("Marked Alpine.", state.status());
    type(state, "/insp");
    state.confirm();
    assertTrue(state.page() != null);
    assertEquals(List.of("back", "quit"),
        startCommandAndGetSuggestions(state));
    type(state, "back");
    state.confirm();
    assertNull(state.page());
    assertEquals("Al", state.filter());
    assertEquals("Alpine", state.selected());
    type(state, "/mark");
    state.confirm();
    assertEquals("Alpine", state.itemLabel("Alpine"));
  }

  @Test
  void handlesUnknownUnavailableAndEmptySuggestions() {
    ShellState state = state(List.of("Atlas"));
    type(state, "missing/inspect");
    assertTrue(state.suggestions().isEmpty());
    state.complete();
    assertEquals("No suggestions.", state.status());
    state.confirm();
    assertEquals("Command unavailable: /inspect", state.status());
    state.cancel();
    type(state, "/nonsense");
    state.confirm();
    assertEquals("Unknown command: /nonsense", state.status());
    state.cancel();
    type(state, "/back");
    state.confirm();
    assertEquals("Command unavailable: /back", state.status());
    state.cancel();
    type(state, "missing/resources");
    state.confirm();
    assertEquals("Command unavailable: /resources", state.status());
  }

  @Test
  void escapeReturnsFromDetailsAndQuitWorksWithoutSelection() {
    ShellState state = state(List.of("Atlas"));
    type(state, "/inspect");
    state.confirm();
    type(state, "/");
    state.cancel();
    assertTrue(state.page() != null);
    state.cancel();
    assertNull(state.page());
    type(state, "missing/quit");
    state.confirm();
    assertTrue(state.quitRequested());
  }

  @Test
  void registeredCommandsDispatchAndFailuresAreReported() {
    AtomicBoolean ran = new AtomicBoolean();
    ShellCommand action = new ShellCommand("local", "Local action",
        s -> s.selected() != null, s -> ran.set(true));
    ShellCommand failure = new ShellCommand("fail", "Fail", s -> true,
        s -> { throw new IllegalStateException("sensitive details"); });
    ShellState state = new ShellState(List.of("Atlas"), List.of(action, failure),
        Function.identity(), Runnable::run, Runnable::run);
    type(state, "/local");
    state.confirm();
    assertTrue(ran.get());
    type(state, "/fail");
    state.confirm();
    assertEquals("Command failed: /fail", state.status());
    assertThrows(IllegalArgumentException.class, () -> new ShellState(List.of(),
        List.of(action, action), Function.identity(), Runnable::run, Runnable::run));
  }

  @Test
  void asyncCompletionRunsOnlyWhenDispatchedAndRestoresContext() {
    Queue<Runnable> worker = new ArrayDeque<>();
    Queue<Runnable> render = new ArrayDeque<>();
    ShellState state = new ShellState(List.of("Atlas"), List.of(), Function.identity(),
        worker::add, render::add);
    type(state, "At");
    ShellPage result = () -> Toolkit.text("Done");
    state.openAsyncPage("Work", () -> result);
    ShellPage loading = state.page();
    worker.remove().run();
    assertSame(loading, state.page());
    render.remove().run();
    assertSame(result, state.page());
    state.cancel();
    assertNull(state.page());
    assertEquals("At", state.filter());
    assertEquals("Atlas", state.selected());
  }

  @Test
  void asyncFailuresAndStaleResultsDoNotReplaceNewViews() {
    Queue<Runnable> worker = new ArrayDeque<>();
    Queue<Runnable> render = new ArrayDeque<>();
    ShellState state = new ShellState(List.of("Atlas"), List.of(), Function.identity(),
        worker::add, render::add);
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
  void nestedViewsRestoreThePreviousPageAndListContext() {
    ShellState state = state(List.of("Atlas"));
    type(state, "At");
    state.moveCursor(-1);
    ShellPage first = () -> Toolkit.text("First");
    ShellPage second = () -> Toolkit.text("Second");
    state.openPage(first);
    state.openPage(second);
    state.back();
    assertSame(first, state.page());
    state.back();
    assertNull(state.page());
    assertEquals("At", state.filter());
    assertEquals(1, state.cursor());
  }

  @Test
  void backCommandFromPagePreservesFilterCursor() {
    ShellState state = state(List.of("Atlas"));
    type(state, "At");
    state.moveCursor(-1);
    type(state, "/inspect");
    state.confirm();
    type(state, "/back");
    state.confirm();
    assertEquals(1, state.cursor());
    type(state, "l");
    assertEquals("Alt", state.filter());
  }

  @Test
  void suggestionSelectionRemainsValidWhenAsyncCompletionChangesAvailability() {
    Queue<Runnable> worker = new ArrayDeque<>();
    Queue<Runnable> render = new ArrayDeque<>();
    ShellPage[] loading = new ShellPage[1];
    ShellCommand temporary = new ShellCommand("temporary", "During loading",
        s -> s.page() == loading[0], s -> {});
    ShellState state = new ShellState(List.of("Atlas"),
        Stream.concat(ShellCommands.create().stream(), Stream.of(temporary)).toList(),
        Function.identity(), worker::add, render::add);
    state.openAsyncPage("Work", () -> () -> Toolkit.text("Done"));
    loading[0] = state.page();
    type(state, "/");
    state.moveSelection(2);
    worker.remove().run();
    render.remove().run();
    assertEquals(1, state.suggestionIndex());
    state.confirm();
    assertTrue(state.quitRequested());
  }

  private static List<String> startCommandAndGetSuggestions(ShellState state) {
    type(state, "/");
    return state.suggestions().stream().map(ShellCommand::name).toList();
  }

  private static ShellState state(List<String> items) {
    ExampleFeatures examples = new ExampleFeatures();
    return new ShellState(items,
        Stream.concat(examples.commands().stream(), ShellCommands.create().stream()).toList(),
        examples::itemLabel, Runnable::run, Runnable::run);
  }

  private static void type(ShellState state, String input) {
    input.codePoints().forEach(state::type);
  }
}
