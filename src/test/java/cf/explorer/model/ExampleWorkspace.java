package cf.explorer.model;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.list;
import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.text;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Connection-free test fixture: a filterable root list plus a few commands that exercise nested
 * pages, async pages, and per-page selections without a Cloud Foundry backend.
 */
public final class ExampleWorkspace {
  private final Set<String> marked = new HashSet<>();
  private final ItemsPage root;

  public ExampleWorkspace(List<String> items) {
    this.root = new ItemsPage(List.copyOf(items));
  }

  public ItemsPage root() {
    return root;
  }

  /** A synchronous shell with the example commands followed by the built-in ones. */
  public ShellState state() {
    return new ShellState(root, allCommands(), Runnable::run, Runnable::run);
  }

  public List<ShellCommand> allCommands() {
    return Stream.concat(commands().stream(), ShellCommands.create().stream()).toList();
  }

  public List<ShellCommand> commands() {
    return List.of(
        new ShellCommand("inspect", "Open the selected item",
            s -> (s.page() instanceof ResourcesPage || s.page() == null)
                && s.currentSelection() != null,
            this::inspect),
        new ShellCommand("mark", "Toggle the selected item's mark",
            s -> s.page() == null && s.currentSelection() != null, this::toggleMark),
        new ShellCommand("resources", "Browse resources for the selected item",
            s -> s.page() == null && s.currentSelection() != null,
            s -> s.openPage(new ResourcesPage(s.selected(String.class)))),
        new ShellCommand("summary", "Summarize the visible items",
            s -> s.page() == null, s -> {
              List<String> snapshot = root.visibleItems();
              long count = snapshot.stream().filter(marked::contains).count();
              s.openAsyncPage("Building summary", () -> new SummaryPage(snapshot.size(), count));
            }));
  }

  /** Sends text the way the view does: page keys outside command mode, shell input otherwise. */
  public static void press(ShellState state, String input) {
    input.codePoints().forEach(c -> {
      if (state.command() != null || c == '/'
          || state.activePage().handleKey(KeyEvent.ofChar((char) c)) != EventResult.HANDLED) {
        state.type(c);
      }
    });
  }

  private void inspect(ShellState state) {
    if (state.page() instanceof ResourcesPage resources) {
      state.openPage(new ResourceDetailPage(resources.parent, state.selected(String.class)));
    } else {
      String item = state.selected(String.class);
      state.openPage(new DetailPage(item, marked.contains(item)));
    }
  }

  private void toggleMark(ShellState state) {
    String item = state.selected(String.class);
    if (marked.add(item)) {
      state.showStatus("Marked " + item + ".");
    } else {
      marked.remove(item);
      state.showStatus("Unmarked " + item + ".");
    }
  }

  /** Root list with a case-insensitive filter and a selection that survives filtering. */
  public final class ItemsPage implements ShellPage {
    private final List<String> items;
    private String filter = "";
    private int cursor;
    private String remembered;

    private ItemsPage(List<String> items) {
      this.items = items;
      this.remembered = items.isEmpty() ? null : items.get(0);
    }

    public String filter() {
      return filter;
    }

    public List<String> visibleItems() {
      String needle = filter.toLowerCase(Locale.ROOT);
      return items.stream().filter(i -> i.toLowerCase(Locale.ROOT).contains(needle)).toList();
    }

    public String label(String item) {
      return item + (marked.contains(item) ? " (marked)" : "");
    }

    @Override
    public String selected() {
      return visibleItems().contains(remembered) ? remembered : null;
    }

    @Override
    public String inputValue() {
      return filter;
    }

    @Override
    public int inputCursor() {
      return cursor;
    }

    @Override
    public Element render() {
      List<String> visible = visibleItems();
      if (visible.isEmpty()) {
        return panel(text(items.isEmpty() ? " No items." : " No matching items."))
            .title(" Examples ").rounded().borderColor(Color.CYAN).fill();
      }
      return list(visible.stream().map(this::label).toList())
          .title(" Examples  " + visible.size() + " shown ")
          .rounded().borderColor(Color.CYAN).highlightSymbol("> ")
          .highlightStyle(Style.create().fg(Color.LIGHT_CYAN).bg(Color.indexed(236)).bold())
          .selected(Math.max(0, visible.indexOf(remembered))).autoScroll().focusable(false)
          .fill();
    }

    @Override
    public EventResult handleKey(KeyEvent event) {
      if (event.isKey(KeyCode.ESCAPE)) {
        filter = "";
        cursor = 0;
      } else if (event.isKey(KeyCode.UP) || event.isKey(KeyCode.DOWN)) {
        List<String> visible = visibleItems();
        if (!visible.isEmpty()) {
          int index = Math.max(0, visible.indexOf(remembered));
          index += event.isKey(KeyCode.UP) ? -1 : 1;
          remembered = visible.get(Math.max(0, Math.min(visible.size() - 1, index)));
        }
      } else if (event.isKey(KeyCode.LEFT) && cursor > 0) {
        cursor = filter.offsetByCodePoints(cursor, -1);
      } else if (event.isKey(KeyCode.RIGHT) && cursor < filter.length()) {
        cursor = filter.offsetByCodePoints(cursor, 1);
      } else if (event.isKey(KeyCode.HOME)) {
        cursor = 0;
      } else if (event.isKey(KeyCode.END)) {
        cursor = filter.length();
      } else if (event.isKey(KeyCode.BACKSPACE) && cursor > 0) {
        int start = filter.offsetByCodePoints(cursor, -1);
        filter = filter.substring(0, start) + filter.substring(cursor);
        cursor = start;
      } else if (event.isKey(KeyCode.DELETE) && cursor < filter.length()) {
        int end = filter.offsetByCodePoints(cursor, 1);
        filter = filter.substring(0, cursor) + filter.substring(end);
      } else if (event.isTyping() && !event.hasNonShiftModifier()) {
        String value = new String(Character.toChars(event.codePoint()));
        filter = filter.substring(0, cursor) + value + filter.substring(cursor);
        cursor += value.length();
      } else {
        return EventResult.UNHANDLED;
      }
      List<String> visible = visibleItems();
      if (!visible.isEmpty() && !visible.contains(remembered)) {
        remembered = visible.get(0);
      }
      return EventResult.HANDLED;
    }
  }

  private record DetailPage(String item, boolean marked) implements ShellPage {
    @Override
    public Element render() {
      return panel(column(
          text(" " + item).fg(Color.LIGHT_CYAN).bold(),
          text(""),
          text(marked ? " Marked" : " Not marked").fg(Color.GRAY)))
          .title(" Detail ").rounded().borderColor(Color.CYAN).fill();
    }
  }

  private static final class ResourcesPage implements ShellPage {
    private final String parent;
    private final List<String> resources = List.of("API", "Worker");
    private int selected;

    private ResourcesPage(String parent) {
      this.parent = parent;
    }

    @Override
    public String selected() {
      return resources.get(selected);
    }

    @Override
    public Element render() {
      return list(resources).title(" Resources for " + parent + " ")
          .rounded().borderColor(Color.CYAN).highlightSymbol("> ")
          .highlightStyle(Style.create().fg(Color.LIGHT_CYAN).bg(Color.indexed(236)).bold())
          .selected(selected).autoScroll().focusable(false).fill();
    }

    @Override
    public EventResult handleKey(KeyEvent event) {
      if (event.isKey(KeyCode.UP)) {
        selected = Math.max(0, selected - 1);
      } else if (event.isKey(KeyCode.DOWN)) {
        selected = Math.min(resources.size() - 1, selected + 1);
      } else {
        return EventResult.UNHANDLED;
      }
      return EventResult.HANDLED;
    }
  }

  private record ResourceDetailPage(String parent, String resource) implements ShellPage {
    @Override
    public Element render() {
      return panel(column(
          text(" " + resource).fg(Color.LIGHT_CYAN).bold(),
          text(""),
          text(" Example resource for " + parent + ".").fg(Color.GRAY)))
          .title(" Resource detail ").rounded().borderColor(Color.CYAN).fill();
    }
  }

  private record SummaryPage(int visible, long marked) implements ShellPage {
    @Override
    public Element render() {
      return panel(column(text(" Visible items: " + visible),
          text(" Marked visible items: " + marked)))
          .title(" Summary ").rounded().borderColor(Color.CYAN).fill();
    }
  }
}
