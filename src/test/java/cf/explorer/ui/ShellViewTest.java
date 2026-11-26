package cf.explorer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cf.explorer.model.ShellState;
import cf.explorer.model.ShellCommands;
import cf.explorer.model.ShellPage;
import cf.explorer.example.ExampleFeatures;
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
import java.util.List;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ShellViewTest {
  @Test
  void rendersListSuggestionsErrorsAndDetail() {
    ShellState state = state(List.of("Atlas", "Alpine", "Delta"));
    String initial = render(state);
    assertTrue(initial.lines().skip(1).findFirst().orElseThrow().contains("Examples"), initial);
    assertFalse(initial.contains("Count:"), initial);
    assertFalse(initial.contains("next version"), initial);
    assertTrue(initial.contains(" Filter "), initial);
    assertTrue(initial.contains("> Atlas"), initial);
    assertFalse(initial.contains("[ ]"), initial);
    for (char c : "A/in".toCharArray()) {
      state.type(c);
    }
    String suggestions = render(state);
    assertTrue(suggestions.contains("/in|"), suggestions);
    assertTrue(suggestions.contains("/inspect  -  Open the selected item"), suggestions);
    state.confirm();
    String detail = render(state);
    assertTrue(detail.contains(" Detail "), detail);
    assertTrue(detail.contains(" Atlas"), detail);
    state.cancel();
    for (char c : "/nonsense".toCharArray()) {
      state.type(c);
    }
    state.confirm();
    String error = render(state);
    assertTrue(error.contains("No suggestions."), error);
    assertTrue(error.contains("Unknown command: /nonsense"), error);
  }

  @Test
  void rendersEmptyAndNoMatchStates() {
    assertTrue(render(state(List.of())).contains("No items."));
    ShellState state = state(List.of("Atlas"));
    for (char c : "missing".toCharArray()) {
      state.type(c);
    }
    assertTrue(render(state).contains("No matching items."));
  }

  @Test
  void keepsSelectionAndCommandInputVisibleInCompactTerminals() {
    ShellState state = state(List.of("Atlas", "Alpine", "Delta", "Echo", "Foxtrot"));
    for (int i = 0; i < 4; i++) {
      state.moveSelection(1);
    }
    state.type('/');
    state.moveSelection(4);
    String screen = render(state, 40, 12);
    assertTrue(screen.contains("> Foxtrot"), screen);
    assertTrue(screen.contains("> /quit"), screen);
    assertTrue(screen.contains(" /|"), screen);
    assertTrue(screen.contains("Ctrl-C quit"), screen);
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
  void routesTypingNavigationCompletionAndBack() {
    ShellState state = state(List.of("Atlas", "Alpine", "Delta"));
    Runnable quit = () -> {};
    for (char c : "Al".toCharArray()) {
      ShellView.handleKey(KeyEvent.ofChar(c), state, quit);
    }
    ShellView.handleKey(KeyEvent.ofKey(KeyCode.DOWN), state, quit);
    for (char c : "/in".toCharArray()) {
      ShellView.handleKey(KeyEvent.ofChar(c), state, quit);
    }
    ShellView.handleKey(KeyEvent.ofKey(KeyCode.TAB), state, quit);
    ShellView.handleKey(KeyEvent.ofKey(KeyCode.ENTER), state, quit);
    assertTrue(render(state).contains(" Alpine"));
    ShellView.handleKey(KeyEvent.ofKey(KeyCode.ESCAPE), state, quit);
    assertEquals("Al", state.filter());
    assertEquals("Alpine", state.selected());
  }

  @Test
  void qIsFilterInputAndQuitCommandExits() {
    ShellState state = state(List.of("Atlas"));
    AtomicBoolean quit = new AtomicBoolean();
    assertEquals(EventResult.HANDLED,
        ShellView.handleKey(KeyEvent.ofChar('q'), state, () -> quit.set(true)));
    assertEquals("q", state.filter());
    for (char c : "/quit".toCharArray()) {
      ShellView.handleKey(KeyEvent.ofChar(c), state, () -> quit.set(true));
    }
    ShellView.handleKey(KeyEvent.ofKey(KeyCode.ENTER), state, () -> quit.set(true));
    assertTrue(quit.get());
  }

  @Test
  void localSummaryReflectsFilteredAndMarkedItems() {
    ShellState state = state(List.of("Alpine", "Alps", "Delta"));
    for (char c : "Al/mark".toCharArray()) {
      state.type(c);
    }
    state.confirm();
    for (char c : "/summary".toCharArray()) {
      state.type(c);
    }
    state.confirm();
    String screen = render(state);
    assertTrue(screen.contains("Visible items: 2"), screen);
    assertTrue(screen.contains("Marked visible items: 1"), screen);
    state.cancel();
    assertEquals("Al", state.filter());
    assertEquals("Alpine", state.selected());
  }

  @Test
  void marksAreLabelsNotSelections() {
    ShellState state = state(List.of("Atlas", "Borealis"));
    for (char c : "/mark".toCharArray()) {
      state.type(c);
    }
    state.confirm();
    state.moveSelection(1);
    for (char c : "/mark".toCharArray()) {
      state.type(c);
    }
    state.confirm();
    String screen = render(state);
    assertTrue(screen.contains("Atlas (marked)"), screen);
    assertTrue(screen.contains("> Borealis (marked)"), screen);
    assertFalse(screen.contains("[ ]"), screen);
    assertFalse(screen.contains("[*]"), screen);
    assertEquals("Borealis", state.selected());
    for (char c : "/resources".toCharArray()) {
      state.type(c);
    }
    state.confirm();
    assertTrue(render(state).contains("Resources for Borealis"));
  }

  @Test
  void resourcesAndInspectUseCurrentSelectionAndRestoreBothViews() {
    ShellState state = state(List.of("Atlas", "Borealis", "Delta"));
    Runnable quit = () -> {};
    ShellView.handleKey(KeyEvent.ofChar('a'), state, quit);
    ShellView.handleKey(KeyEvent.ofKey(KeyCode.DOWN), state, quit);
    assertEquals("Borealis", state.currentSelection());
    for (char c : "/resources".toCharArray()) {
      ShellView.handleKey(KeyEvent.ofChar(c), state, quit);
    }
    ShellView.handleKey(KeyEvent.ofKey(KeyCode.ENTER), state, quit);
    String resources = render(state);
    assertTrue(resources.contains("Resources for Borealis"), resources);
    assertTrue(resources.contains("> API"), resources);
    assertEquals("API", state.currentSelection());
    ShellView.handleKey(KeyEvent.ofKey(KeyCode.DOWN), state, quit);
    assertTrue(render(state).contains("> Worker"));
    assertEquals("Worker", state.currentSelection());
    for (char c : "/inspect".toCharArray()) {
      ShellView.handleKey(KeyEvent.ofChar(c), state, quit);
    }
    ShellView.handleKey(KeyEvent.ofKey(KeyCode.ENTER), state, quit);
    String detail = render(state);
    assertTrue(detail.contains("Resource detail"), detail);
    assertTrue(detail.contains(" Worker"), detail);
    assertTrue(detail.contains("Example resource for Borealis."), detail);
    assertNull(state.currentSelection());
    ShellView.handleKey(KeyEvent.ofKey(KeyCode.ESCAPE), state, quit);
    assertTrue(render(state).contains("> Worker"));
    assertEquals("Worker", state.currentSelection());
    for (char c : "/back".toCharArray()) {
      ShellView.handleKey(KeyEvent.ofChar(c), state, quit);
    }
    ShellView.handleKey(KeyEvent.ofKey(KeyCode.ENTER), state, quit);
    assertNull(state.page());
    assertEquals("Borealis", state.selected());
    assertEquals("Borealis", state.currentSelection());
    assertEquals("a", state.filter());
    assertEquals(1, state.cursor());
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
    ShellState state = new ShellState(List.of(), List.of(), Function.identity(),
        worker::add, render::add);
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
  void ctrlCDiscardsPendingCompletion() {
    Queue<Runnable> worker = new ArrayDeque<>();
    Queue<Runnable> render = new ArrayDeque<>();
    ShellState state = new ShellState(List.of(), List.of(), Function.identity(),
        worker::add, render::add);
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

  private static String render(ShellState state) {
    return render(state, 100, 24);
  }

  private static ShellState state(List<String> items) {
    ExampleFeatures examples = new ExampleFeatures();
    return new ShellState(items,
        Stream.concat(examples.commands().stream(), ShellCommands.create().stream()).toList(),
        examples::itemLabel, Runnable::run, Runnable::run);
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
