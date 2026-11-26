package cf.explorer.ui;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.list;
import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.text;

import cf.explorer.model.ShellState;
import cf.explorer.model.ShellCommand;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.element.Size;
import dev.tamboui.toolkit.element.StyledElement;
import dev.tamboui.toolkit.elements.ListElement;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

public final class ShellView {
  private ShellView() {}

  public static Element render(ShellState state, Runnable quit) {
    return new ShellLayout(state).id("main").focusable()
        .onKeyEvent(event -> handleKey(event, state, quit));
  }

  private static Element layout(ShellState state, int height) {
    boolean compact = height < 16;
    List<Element> layout = new ArrayList<>();
    layout.add(text(" CF EXPLORER  /  EXAMPLE WORKSPACE").fg(Color.LIGHT_CYAN).bold());
    if (state.page() == null) {
      List<String> items = state.visibleItems();
      if (items.isEmpty()) {
        layout.add(panel(text(state.filter().isEmpty() ? " No items." : " No matching items.").dim())
            .title(" Examples  0 shown ").rounded().borderColor(Color.CYAN).fill());
      } else {
        List<String> rows = items.stream().map(state::itemLabel).toList();
        layout.add(list(rows).title(" Examples  " + items.size() + " shown ")
            .rounded().borderColor(Color.CYAN).highlightSymbol("> ")
            .highlightStyle(Style.create().fg(Color.LIGHT_CYAN).bg(Color.indexed(236)).bold())
            .selected(items.indexOf(state.selected())).autoScroll().focusable(false).fill());
      }
    } else {
      layout.add(state.page().render());
    }

    if (state.command() != null) {
      List<ShellCommand> suggestions = state.suggestions();
      if (suggestions.isEmpty()) {
        Element empty = text(" No suggestions.").dim();
        layout.add(compact ? empty : panel(empty).title(" Commands ")
            .rounded().borderColor(Color.LIGHT_CYAN).length(3));
      } else {
        List<String> rows = suggestions.stream()
            .map(suggestion -> "/" + suggestion.name() + "  -  " + suggestion.description()).toList();
        ListElement<?> commands = list(rows).highlightSymbol("> ")
            .highlightStyle(Style.create().fg(Color.LIGHT_CYAN).bold())
            .selected(state.suggestionIndex()).autoScroll().focusable(false);
        if (compact) {
          layout.add(commands.length(Math.min(2, suggestions.size())));
        } else {
          layout.add(commands.title(" Commands ").rounded().borderColor(Color.LIGHT_CYAN)
              .length(suggestions.size() + 2));
        }
      }
    }

    String input = state.command() != null
        ? "/" + editing(state.command(), state.cursor())
        : state.page() == null ? editing(state.filter(), state.cursor()) : "Type / for commands";
    layout.add(panel(text(" " + input).fg(Color.BRIGHT_WHITE))
        .title(state.command() != null ? " Command " : state.page() == null ? " Filter " : " Input ")
        .rounded().borderColor(Color.LIGHT_CYAN).length(3));
    if (!state.status().isEmpty()) {
      layout.add(text(" " + state.status()).fg(Color.LIGHT_YELLOW));
    }
    if (compact) {
      layout.add(text(" Esc back/cancel  |  Ctrl-C quit").fg(Color.GRAY));
    } else {
      layout.add(text(" / commands  |  Up/Down select  |  Tab complete  |  Enter run").fg(Color.GRAY));
      layout.add(text(" Esc cancel/back  |  Ctrl-C quit").fg(Color.GRAY));
    }
    return column(layout.toArray(Element[]::new));
  }

  private static final class ShellLayout extends StyledElement<ShellLayout> {
    private final ShellState state;
    private ShellLayout(ShellState state) {
      this.state = state;
    }

    @Override
    public Size preferredSize(int width, int height, RenderContext context) {
      return Size.UNKNOWN;
    }

    @Override
    protected void renderContent(Frame frame, Rect area, RenderContext context) {
      context.renderChild(layout(state, area.height()), frame, area);
    }
  }

  private static String editing(String value, int cursor) {
    return cursor < 0 ? value : value.substring(0, cursor) + "|" + value.substring(cursor);
  }

  static EventResult handleKey(KeyEvent event, ShellState state, Runnable quit) {
    if (!event.isPressOrRepeat()) {
      return EventResult.UNHANDLED;
    }
    if (event.isCtrlC()) {
      state.requestQuit();
      quit.run();
    } else if (event.isKey(KeyCode.ESCAPE)) {
      state.cancel();
    } else if (state.command() == null && state.page() != null
        && !(event.isTyping() && !event.hasNonShiftModifier() && event.codePoint() == '/')
        && state.page().handleKey(event) == EventResult.HANDLED) {
      return EventResult.HANDLED;
    } else if (event.isKey(KeyCode.UP)) {
      state.moveSelection(-1);
    } else if (event.isKey(KeyCode.DOWN)) {
      state.moveSelection(1);
    } else if (event.isKey(KeyCode.LEFT)) {
      state.moveCursor(-1);
    } else if (event.isKey(KeyCode.RIGHT)) {
      state.moveCursor(1);
    } else if (event.isKey(KeyCode.HOME)) {
      state.cursorToStart();
    } else if (event.isKey(KeyCode.END)) {
      state.cursorToEnd();
    } else if (event.isKey(KeyCode.BACKSPACE)) {
      state.backspace();
    } else if (event.isKey(KeyCode.DELETE)) {
      state.delete();
    } else if (event.isKey(KeyCode.TAB)) {
      state.complete();
    } else if (event.isKey(KeyCode.ENTER)) {
      state.confirm();
      if (state.quitRequested()) {
        quit.run();
      }
    } else if (event.isTyping() && !event.hasNonShiftModifier()) {
      state.type(event.codePoint());
    } else {
      return EventResult.UNHANDLED;
    }
    return EventResult.HANDLED;
  }
}
