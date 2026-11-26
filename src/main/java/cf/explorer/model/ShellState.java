package cf.explorer.model;

import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.text;

import dev.tamboui.style.Color;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;

/** Interaction state for the connection-free shell. */
public final class ShellState {
  private static final Logger LOG = Logger.getLogger(ShellState.class.getName());
  private final List<String> items;
  private final List<ShellCommand> commands;
  private final Function<String, String> itemLabel;
  private final Executor worker;
  private final Consumer<Runnable> dispatch;
  private final Deque<ShellPage> previousPages = new ArrayDeque<>();
  private String filter = "";
  private String selected;
  private ShellPage page;
  private ShellPage loadingPage;
  private String command;
  private int cursor;
  private int filterCursor;
  private int suggestionIndex;
  private String status = "";
  private boolean quit;

  public ShellState(List<String> items, List<ShellCommand> commands,
      Function<String, String> itemLabel, Executor worker, Consumer<Runnable> dispatch) {
    this.items = List.copyOf(items);
    this.commands = List.copyOf(commands);
    this.itemLabel = Objects.requireNonNull(itemLabel);
    this.worker = Objects.requireNonNull(worker);
    this.dispatch = Objects.requireNonNull(dispatch);
    if (commands.stream().map(ShellCommand::name).distinct().count() != commands.size()) {
      throw new IllegalArgumentException("Duplicate command name");
    }
    selected = items.isEmpty() ? null : items.get(0);
  }

  public List<String> visibleItems() {
    String needle = filter.toLowerCase(Locale.ROOT);
    return items.stream().filter(item -> item.toLowerCase(Locale.ROOT).contains(needle)).toList();
  }

  public String filter() { return filter; }
  public String selected() { return selected; }
  public ShellPage page() { return page; }
  public String currentSelection() { return page == null ? selected : page.selected(); }
  public String command() { return command; }
  public int cursor() { return cursor; }
  public int suggestionIndex() {
    return Math.max(0, Math.min(suggestionIndex, suggestions().size() - 1));
  }
  public String status() { return status; }
  public boolean quitRequested() { return quit; }
  public String itemLabel(String item) { return itemLabel.apply(item); }

  public List<ShellCommand> suggestions() {
    if (command == null) {
      return List.of();
    }
    String prefix = command.toLowerCase(Locale.ROOT);
    return commands.stream().filter(candidate -> candidate.available().test(this))
        .filter(candidate -> candidate.name().startsWith(prefix)).toList();
  }

  public void type(int codePoint) {
    if (command == null && codePoint == '/') {
      if (page == null) {
        filterCursor = cursor;
      }
      command = "";
      cursor = 0;
    } else if (command != null) {
      command = insert(command, codePoint);
    } else if (page == null) {
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
    return command != null ? command : page == null ? filter : null;
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
        suggestionIndex = Math.max(0, Math.min(size - 1, suggestionIndex() + direction));
      }
    } else if (page == null) {
      List<String> visible = visibleItems();
      if (!visible.isEmpty()) {
        int index = Math.max(0, Math.min(visible.size() - 1, visible.indexOf(selected) + direction));
        selected = visible.get(index);
      }
    }
  }

  public void complete() {
    List<ShellCommand> suggestions = suggestions();
    if (!suggestions.isEmpty()) {
      command = suggestions.get(Math.min(suggestionIndex, suggestions.size() - 1)).name();
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
    ShellCommand exact = commands.stream().filter(candidate -> candidate.name().equals(name))
        .findFirst().orElse(null);
    List<ShellCommand> suggestions = suggestions();
    ShellCommand chosen = exact != null ? exact
        : suggestions.isEmpty() ? null
        : suggestions.get(Math.min(suggestionIndex, suggestions.size() - 1));
    if (chosen == null) {
      status = "Unknown command: /" + command;
    } else if (!chosen.available().test(this)) {
      status = "Command unavailable: /" + chosen.name();
    } else {
      status = "";
      try {
        chosen.execute().accept(this);
        command = null;
        cursor = page == null ? filterCursor : 0;
      } catch (RuntimeException ex) {
        logFailure("Command /" + chosen.name(), ex);
        status = "Command failed: /" + chosen.name();
      }
    }
  }

  public void showStatus(String message) { status = Objects.requireNonNull(message); }

  public void openPage(ShellPage next) {
    Objects.requireNonNull(next);
    if (page == null && command == null) {
      filterCursor = cursor;
    }
    if (page != null && page != loadingPage) {
      previousPages.push(page);
    }
    loadingPage = null;
    page = next;
  }

  public void back() {
    loadingPage = null;
    page = previousPages.poll();
    cursor = page == null ? filterCursor : 0;
  }

  public void requestQuit() { quit = true; }

  public void openAsyncPage(String label, Supplier<? extends ShellPage> work) {
    Objects.requireNonNull(label);
    Objects.requireNonNull(work);
    ShellPage loading = () -> panel(text(" " + label + "..."))
        .title(" Loading ").rounded().borderColor(Color.CYAN).fill();
    openPage(loading);
    loadingPage = loading;
    try {
      CompletableFuture.supplyAsync(() -> Objects.requireNonNull(work.get()), worker)
          .whenComplete((result, error) -> {
            if (error != null) {
              logFailure(label, error);
            }
            try {
              dispatch.accept(() -> {
                if (page == loading && !quit) {
                  loadingPage = null;
                  page = error == null ? result : () -> panel(text(" " + label + " failed."))
                      .title(" Error ").rounded().borderColor(Color.CYAN).fill();
                }
              });
            } catch (RuntimeException ex) {
              logFailure("Render dispatch", ex);
            }
          });
    } catch (RejectedExecutionException ex) {
      logFailure(label, ex);
      loadingPage = null;
      page = () -> panel(text(" " + label + " failed."))
          .title(" Error ").rounded().borderColor(Color.CYAN).fill();
    }
  }

  private static void logFailure(String operation, Throwable error) {
    Throwable cause = error instanceof CompletionException && error.getCause() != null
        ? error.getCause() : error;
    LOG.warning(operation + " failed (" + cause.getClass().getName() + ")");
  }

  public void cancel() {
    status = "";
    if (command != null) {
      command = null;
      cursor = page == null ? filterCursor : 0;
    } else if (page != null) {
      back();
    } else if (!filter.isEmpty()) {
      filter = "";
      cursor = 0;
      reconcileSelection();
    }
  }
}
