package cf.explorer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cf.explorer.model.ShellState;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ShellViewTest {
  @Test
  void rendersListSuggestionsErrorsAndDetail() {
    ShellState state = new ShellState(List.of("Atlas", "Alpine", "Delta"));
    String initial = render(state);
    assertTrue(initial.lines().skip(1).findFirst().orElseThrow().contains("Examples"), initial);
    assertFalse(initial.contains("Count:"), initial);
    assertFalse(initial.contains("next version"), initial);
    assertTrue(initial.contains(" Filter "), initial);
    assertTrue(initial.contains("> [ ] Atlas"), initial);
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
    assertTrue(render(new ShellState(List.of())).contains("No items."));
    ShellState state = new ShellState(List.of("Atlas"));
    for (char c : "missing".toCharArray()) {
      state.type(c);
    }
    assertTrue(render(state).contains("No matching items."));
  }

  @Test
  void keepsSelectionAndCommandInputVisibleInCompactTerminals() {
    ShellState state = new ShellState(List.of("Atlas", "Alpine", "Delta", "Echo", "Foxtrot"));
    for (int i = 0; i < 4; i++) {
      state.moveSelection(1);
    }
    state.type('/');
    state.moveSelection(2);
    String screen = render(state, 40, 12);
    assertTrue(screen.contains("> [ ] Foxtrot"), screen);
    assertTrue(screen.contains("> /quit"), screen);
    assertTrue(screen.contains(" /|"), screen);
    assertTrue(screen.contains("Ctrl-C quit"), screen);
  }

  @Test
  void selectedItemHasAVisibleAccent() {
    Buffer buffer = renderBuffer(new ShellState(List.of("Atlas")), 60, 16);
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
    ShellState state = new ShellState(List.of("Atlas", "Alpine", "Delta"));
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
    assertEquals("Alpine", state.detail());
    ShellView.handleKey(KeyEvent.ofKey(KeyCode.ESCAPE), state, quit);
    assertEquals("Al", state.filter());
    assertEquals("Alpine", state.selected());
  }

  @Test
  void qIsFilterInputAndQuitCommandExits() {
    ShellState state = new ShellState(List.of("Atlas"));
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

  private static String render(ShellState state) {
    return render(state, 100, 24);
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
