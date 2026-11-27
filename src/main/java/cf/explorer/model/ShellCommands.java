package cf.explorer.model;

import java.util.List;

public final class ShellCommands {
  private ShellCommands() {}

  public static List<ShellCommand> create() {
    return List.of(
        new ShellCommand("back", "Return to the list", s -> s.page() != null, ShellState::back),
        new ShellCommand("quit", "Exit cf-explorer", s -> true, ShellState::requestQuit));
  }
}
