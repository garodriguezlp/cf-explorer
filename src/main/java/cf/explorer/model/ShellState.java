package cf.explorer.model;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Interaction state for the connection-free shell. */
public final class ShellState {
  public record Command(String name, String description) {}

  private static final List<Command> COMMANDS = List.of(
      new Command("inspect", "Open the selected item"),
      new Command("mark", "Toggle the selected item's mark"),
      new Command("back", "Return to the list"),
      new Command("quit", "Exit cf-explorer"));

  private final List<String> items;
  private final Set<String> marked = new HashSet<>();
  private String filter = "";
  private String selected;
  private String detail;
  private String command;
  private int cursor;
  private int filterCursor;
  private int suggestionIndex;
  private String status = "";
  private boolean quit;

  public ShellState(List<String> items) {
    this.items = List.copyOf(items);
    selected = items.isEmpty() ? null : items.get(0);
  }

  public List<String> visibleItems() {
    String needle = filter.toLowerCase(Locale.ROOT);
    return items.stream().filter(item -> item.toLowerCase(Locale.ROOT).contains(needle)).toList();
  }

  public String filter() { return filter; }
  public String selected() { return selected; }
  public String detail() { return detail; }
  public String command() { return command; }
  public int cursor() { return cursor; }
  public int suggestionIndex() { return suggestionIndex; }
  public String status() { return status; }
  public boolean quitRequested() { return quit; }
  public boolean isMarked(String item) { return marked.contains(item); }

  public List<Command> suggestions() {
    if (command == null) {
      return List.of();
    }
    String prefix = command.toLowerCase(Locale.ROOT);
    return COMMANDS.stream().filter(this::available)
        .filter(candidate -> candidate.name().startsWith(prefix)).toList();
  }

  public void type(int codePoint) {
    if (command == null && codePoint == '/') {
      filterCursor = cursor;
      command = "";
      cursor = 0;
    } else if (command != null) {
      command = insert(command, codePoint);
    } else if (detail == null) {
      filter = insert(filter, codePoint);
      reconcileSelection();
    } else {
      return;
    }
    suggestionIndex = 0;
    status = "";
  }

  private String insert(String value, int codePoint) {
    String inserted = new String(Character.toChars(codePoint));
    String result = value.substring(0, cursor) + inserted + value.substring(cursor);
    cursor += inserted.length();
    return result;
  }

  public void backspace() {
    String value = editingValue();
    if (value == null || cursor == 0) {
      return;
    }
    int start = value.offsetByCodePoints(cursor, -1);
    replaceEdited(value.substring(0, start) + value.substring(cursor), start);
  }

  public void delete() {
    String value = editingValue();
    if (value == null || cursor == value.length()) {
      return;
    }
    int end = value.offsetByCodePoints(cursor, 1);
    replaceEdited(value.substring(0, cursor) + value.substring(end), cursor);
  }

  public void moveCursor(int direction) {
    String value = editingValue();
    if (value != null && ((direction < 0 && cursor > 0)
        || (direction > 0 && cursor < value.length()))) {
      cursor = value.offsetByCodePoints(cursor, direction < 0 ? -1 : 1);
    }
  }

  public void cursorToStart() { cursor = 0; }
  public void cursorToEnd() {
    String value = editingValue();
    if (value != null) {
      cursor = value.length();
    }
  }

  private String editingValue() {
    return command != null ? command : detail == null ? filter : null;
  }

  private void replaceEdited(String value, int newCursor) {
    if (command != null) {
      command = value;
      suggestionIndex = 0;
    } else {
      filter = value;
      reconcileSelection();
    }
    cursor = newCursor;
    status = "";
  }

  private void reconcileSelection() {
    List<String> visible = visibleItems();
    if (!visible.contains(selected)) {
      selected = visible.isEmpty() ? null : visible.get(0);
    }
  }

  public void moveSelection(int direction) {
    if (command != null) {
      int size = suggestions().size();
      if (size > 0) {
        suggestionIndex = Math.max(0, Math.min(size - 1, suggestionIndex + direction));
      }
    } else if (detail == null) {
      List<String> visible = visibleItems();
      if (!visible.isEmpty()) {
        int index = Math.max(0, Math.min(visible.size() - 1, visible.indexOf(selected) + direction));
        selected = visible.get(index);
      }
    }
  }

  public void complete() {
    List<Command> suggestions = suggestions();
    if (!suggestions.isEmpty()) {
      command = suggestions.get(suggestionIndex).name();
      cursor = command.length();
      suggestionIndex = 0;
      status = "";
    } else if (command != null) {
      status = "No suggestions.";
    }
  }

  public void confirm() {
    if (command == null) {
      return;
    }
    String name = command.toLowerCase(Locale.ROOT);
    Command exact = COMMANDS.stream().filter(candidate -> candidate.name().equals(name))
        .findFirst().orElse(null);
    List<Command> suggestions = suggestions();
    Command chosen = exact != null ? exact
        : suggestions.isEmpty() ? null : suggestions.get(suggestionIndex);
    if (chosen == null) {
      status = "Unknown command: /" + command;
    } else if (!available(chosen)) {
      status = "Command unavailable: /" + chosen.name();
    } else {
      execute(chosen);
      command = null;
      cursor = detail == null ? filterCursor : 0;
    }
  }

  private boolean available(Command candidate) {
    return switch (candidate.name()) {
      case "inspect", "mark" -> detail == null && selected != null;
      case "back" -> detail != null;
      case "quit" -> true;
      default -> false;
    };
  }

  private void execute(Command chosen) {
    status = "";
    switch (chosen.name()) {
      case "inspect" -> detail = selected;
      case "mark" -> {
        if (!marked.add(selected)) {
          marked.remove(selected);
          status = "Unmarked " + selected + ".";
        } else {
          status = "Marked " + selected + ".";
        }
      }
      case "back" -> detail = null;
      case "quit" -> quit = true;
      default -> throw new IllegalStateException("Unknown command: " + chosen.name());
    }
  }

  public void cancel() {
    status = "";
    if (command != null) {
      command = null;
      cursor = detail == null ? filterCursor : 0;
    } else if (detail != null) {
      detail = null;
      cursor = filterCursor;
    } else if (!filter.isEmpty()) {
      filter = "";
      cursor = 0;
      reconcileSelection();
    }
  }
}
