package cf.explorer.model;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

public record ShellCommand(String name, String description, Predicate<ShellState> available,
    Consumer<ShellState> execute) {
  public ShellCommand {
    Objects.requireNonNull(name);
    Objects.requireNonNull(description);
    Objects.requireNonNull(available);
    Objects.requireNonNull(execute);
    if (!name.matches("[a-z][a-z0-9-]*")) {
      throw new IllegalArgumentException("Invalid command name: " + name);
    }
  }
}
