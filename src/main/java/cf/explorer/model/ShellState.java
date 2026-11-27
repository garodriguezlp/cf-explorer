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
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Interaction state for the command-driven shell: the root page, the stack of opened pages, the
 * slash-command input, and the status line.
 *
 * <p>The root page owns its own list, filter, and selection. The shell only owns command entry
 * and navigation. All methods must be called on the render thread.
 */
public final class ShellState {
  private static final Logger LOG = Logger.getLogger(ShellState.class.getName());
  private final ShellPage rootPage;
  private final List<ShellCommand> commands;
  private final Executor worker;
  private final Consumer<Runnable> dispatch;
  private final Deque<ShellPage> previousPages = new ArrayDeque<>();
  private ShellPage page;
  private ShellPage loadingPage;
  private String command;
  private int cursor;
  private int suggestionIndex;
  private String status = "";
  private boolean quit;

  public ShellState(ShellPage rootPage, List<ShellCommand> commands, Executor worker,
      Consumer<Runnable> dispatch) {
    this.rootPage = Objects.requireNonNull(rootPage);
    this.commands = List.copyOf(commands);
    this.worker = Objects.requireNonNull(worker);
    this.dispatch = Objects.requireNonNull(dispatch);
    if (commands.stream().map(ShellCommand::name).distinct().count() != commands.size()) {
      throw new IllegalArgumentException("Duplicate command name");
    }
  }

  /** The opened page, or {@code null} when the root page is active. */
  public ShellPage page() { return page; }
  public ShellPage rootPage() { return rootPage; }
  public ShellPage activePage() { return page != null ? page : rootPage; }
  public Object currentSelection() { return activePage().selected(); }
  public <T> T selected(Class<T> type) {
    Object selection = currentSelection();
    return type.isInstance(selection) ? type.cast(selection) : null;
  }
  /** The command being typed (without the leading slash), or {@code null} outside command mode. */
  public String command() { return command; }
  public int cursor() { return cursor; }
  public int suggestionIndex() {
    return Math.max(0, Math.min(suggestionIndex, suggestions().size() - 1));
  }
  public String status() { return status; }
  public boolean quitRequested() { return quit; }

  public List<ShellCommand> suggestions() {
    if (command == null) {
      return List.of();
    }
    String prefix = command.toLowerCase(Locale.ROOT);
    return commands.stream().filter(candidate -> candidate.available().test(this))
        .filter(candidate -> candidate.name().startsWith(prefix)).toList();
  }

  /** Starts command mode on {@code /}, or edits the command being typed. Other input is ignored. */
  public void type(int codePoint) {
    if (command == null && codePoint == '/') {
      command = "";
      cursor = 0;
    } else if (command != null) {
      String inserted = new String(Character.toChars(codePoint));
      command = command.substring(0, cursor) + inserted + command.substring(cursor);
      cursor += inserted.length();
    } else {
      return;
    }
    suggestionIndex = 0;
    status = "";
  }

  public void backspace() {
    if (command == null || cursor == 0) {
      return;
    }
    int start = command.offsetByCodePoints(cursor, -1);
    replaceCommand(command.substring(0, start) + command.substring(cursor), start);
  }

  public void delete() {
    if (command == null || cursor == command.length()) {
      return;
    }
    int end = command.offsetByCodePoints(cursor, 1);
    replaceCommand(command.substring(0, cursor) + command.substring(end), cursor);
  }

  public void moveCursor(int direction) {
    if (command != null && ((direction < 0 && cursor > 0)
        || (direction > 0 && cursor < command.length()))) {
      cursor = command.offsetByCodePoints(cursor, direction < 0 ? -1 : 1);
    }
  }

  public void cursorToStart() { cursor = 0; }

  public void cursorToEnd() {
    if (command != null) {
      cursor = command.length();
    }
  }

  private void replaceCommand(String value, int newCursor) {
    command = value;
    suggestionIndex = 0;
    cursor = newCursor;
    status = "";
  }

  /** Moves the highlighted suggestion while in command mode. */
  public void moveSelection(int direction) {
    int size = suggestions().size();
    if (command != null && size > 0) {
      suggestionIndex = Math.max(0, Math.min(size - 1, suggestionIndex() + direction));
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
      command = null;
      cursor = 0;
      try {
        chosen.execute().accept(this);
      } catch (RuntimeException ex) {
        logFailure("Command /" + chosen.name(), ex);
        status = "Command failed: /" + chosen.name();
      }
    }
  }

  public void showStatus(String message) { status = Objects.requireNonNull(message); }

  public void openPage(ShellPage next) {
    Objects.requireNonNull(next);
    if (page != null && page != loadingPage) {
      previousPages.push(page);
    }
    loadingPage = null;
    page = next;
  }

  public void back() {
    loadingPage = null;
    page = previousPages.poll();
  }

  public void requestQuit() { quit = true; }

  /**
   * Opens a loading page, runs {@code work} on the worker, and replaces the loading page with the
   * result on the render thread. The result is discarded if the user navigated away or quit.
   */
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
                  page = error == null ? result : failurePage(label);
                }
              });
            } catch (RuntimeException ex) {
              logFailure("Render dispatch", ex);
            }
          });
    } catch (RejectedExecutionException ex) {
      logFailure(label, ex);
      loadingPage = null;
      page = failurePage(label);
    }
  }

  private static ShellPage failurePage(String label) {
    return () -> panel(text(" " + label + " failed."))
        .title(" Error ").rounded().borderColor(Color.RED).fill();
  }

  private static void logFailure(String operation, Throwable error) {
    Throwable cause = error instanceof CompletionException && error.getCause() != null
        ? error.getCause() : error;
    LOG.warning(operation + " failed (" + cause.getClass().getName() + ")");
  }

  /** Cancels command entry, or returns from the opened page. */
  public void cancel() {
    status = "";
    if (command != null) {
      command = null;
      cursor = 0;
    } else if (page != null) {
      back();
    }
  }
}
