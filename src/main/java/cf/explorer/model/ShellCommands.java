package cf.explorer.model;

import java.util.List;

/** Built-in navigation commands shared by every shell. */
public final class ShellCommands {
  private ShellCommands() {}

  public static List<ShellCommand> create() {
    return List.of(
        new ShellCommand("back", 'b', "Return to the previous page", s -> s.page() != null,
            ShellState::back),
        new ShellCommand("quit", 'x', "Exit cf-explorer", s -> true, ShellState::requestQuit));
  }
}
