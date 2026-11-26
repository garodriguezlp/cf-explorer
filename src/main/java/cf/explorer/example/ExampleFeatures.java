package cf.explorer.example;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.list;
import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.text;

import cf.explorer.model.ShellCommand;
import cf.explorer.model.ShellPage;
import cf.explorer.model.ShellState;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ExampleFeatures {
  private final Set<String> marked = new HashSet<>();

  public String itemLabel(String item) {
    return item + (marked.contains(item) ? " (marked)" : "");
  }

  public List<ShellCommand> commands() {
    return List.of(
        new ShellCommand("inspect", "Open the selected item",
            s -> (s.page() instanceof ResourcesPage || s.page() == null)
                && s.currentSelection() != null,
            this::inspect),
        new ShellCommand("mark", "Toggle the selected item's mark",
            s -> s.page() == null && s.selected() != null, this::toggleMark),
        new ShellCommand("resources", "Browse resources for the selected item",
            s -> s.page() == null && s.selected() != null,
            s -> s.openPage(new ResourcesPage(s.selected()))),
        new ShellCommand("summary", "Summarize the visible items",
            s -> s.page() == null, s -> {
              List<String> snapshot = s.visibleItems();
              long count = snapshot.stream().filter(marked::contains).count();
              s.openAsyncPage("Building summary", () -> new SummaryPage(snapshot.size(), count));
            }));
  }

  private void inspect(ShellState state) {
    if (state.page() instanceof ResourcesPage resources) {
      state.openPage(new ResourceDetailPage(resources.parent, state.selected(String.class)));
    } else {
      state.openPage(new DetailPage(state.selected(), marked.contains(state.selected())));
    }
  }

  private void toggleMark(ShellState state) {
    String item = state.selected();
    if (marked.add(item)) {
      state.showStatus("Marked " + item + ".");
    } else {
      marked.remove(item);
      state.showStatus("Unmarked " + item + ".");
    }
  }

  private record DetailPage(String item, boolean marked) implements ShellPage {
    @Override
    public Element render() {
      return panel(column(
          text(" " + item).fg(Color.LIGHT_CYAN).bold(),
          text(""),
          text(" Example item in the connection-free shell.").fg(Color.GRAY),
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
