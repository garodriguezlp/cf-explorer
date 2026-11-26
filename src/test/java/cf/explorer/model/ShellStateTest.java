package cf.explorer.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ShellStateTest {
  @Test
  void filteringAndSelectionRemainStableWhenPossible() {
    ShellState state = new ShellState(List.of("Atlas", "Borealis", "Delta"));
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
    assertTrue(new ShellState(List.of()).visibleItems().isEmpty());
  }

  @Test
  void editsAtCursorAndCancelsCommandsWithoutLosingContext() {
    ShellState state = new ShellState(List.of("Alpine", "Delta"));
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
    ShellState state = new ShellState(List.of("A😀B"));
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
    ShellState state = new ShellState(List.of("Atlas", "Delta", "Alpine"));
    type(state, "Al");
    state.moveSelection(1);
    type(state, "/");
    assertEquals(List.of("inspect", "mark", "quit"),
        state.suggestions().stream().map(ShellState.Command::name).toList());
    state.moveSelection(1);
    state.complete();
    assertEquals("mark", state.command());
    state.confirm();
    assertTrue(state.isMarked("Alpine"));
    assertEquals("Marked Alpine.", state.status());
    type(state, "/insp");
    state.confirm();
    assertEquals("Alpine", state.detail());
    assertEquals(List.of("back", "quit"),
        startCommandAndGetSuggestions(state));
    type(state, "back");
    state.confirm();
    assertNull(state.detail());
    assertEquals("Al", state.filter());
    assertEquals("Alpine", state.selected());
    type(state, "/mark");
    state.confirm();
    assertFalse(state.isMarked("Alpine"));
  }

  @Test
  void handlesUnknownUnavailableAndEmptySuggestions() {
    ShellState state = new ShellState(List.of("Atlas"));
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
  }

  @Test
  void escapeReturnsFromDetailsAndQuitWorksWithoutSelection() {
    ShellState state = new ShellState(List.of("Atlas"));
    type(state, "/inspect");
    state.confirm();
    type(state, "/");
    state.cancel();
    assertEquals("Atlas", state.detail());
    state.cancel();
    assertNull(state.detail());
    type(state, "missing/quit");
    state.confirm();
    assertTrue(state.quitRequested());
  }

  private static List<String> startCommandAndGetSuggestions(ShellState state) {
    type(state, "/");
    return state.suggestions().stream().map(ShellState.Command::name).toList();
  }

  private static void type(ShellState state, String input) {
    input.codePoints().forEach(state::type);
  }
}
