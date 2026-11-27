package cf.explorer.model;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A shell command. Names are a single lowercase word so they are easy to type after {@code /}.
 *
 * <p>A command may also declare a global {@code Ctrl+<letter>} shortcut that runs it from any
 * page without opening the command view. Letters the terminal reserves or decodes as other keys
 * are rejected; see {@link #RESERVED_SHORTCUTS}.
 */
public record ShellCommand(String name, char shortcut, String description,
    Predicate<ShellState> available, Consumer<ShellState> execute) {
  /** Marks a command without a shortcut. */
  public static final char NO_SHORTCUT = '\0';

  /**
   * Letters that cannot be shortcuts: Ctrl+C and Ctrl+V (OS copy/paste), Ctrl+A and Ctrl+F (OS
   * select-all and find), Ctrl+Z (undo/suspend), Ctrl+S and Ctrl+Q (save/quit and flow control),
   * Ctrl+D (end of input), Ctrl+W (close/delete-word), and Ctrl+I, Ctrl+J, Ctrl+M, Ctrl+H, which
   * terminals send as Tab, Enter, Enter, and Backspace.
   */
  public static final String RESERVED_SHORTCUTS = "acdfhijmqsvwz";

  public ShellCommand {
    Objects.requireNonNull(name);
    Objects.requireNonNull(description);
    Objects.requireNonNull(available);
    Objects.requireNonNull(execute);
    if (!name.matches("[a-z][a-z0-9]*")) {
      throw new IllegalArgumentException("Invalid command name: " + name);
    }
    if (shortcut != NO_SHORTCUT
        && (shortcut < 'a' || shortcut > 'z' || RESERVED_SHORTCUTS.indexOf(shortcut) >= 0)) {
      throw new IllegalArgumentException("Invalid shortcut for /" + name + ": Ctrl+" + shortcut);
    }
  }

  /** Creates a command without a shortcut. */
  public ShellCommand(String name, String description, Predicate<ShellState> available,
      Consumer<ShellState> execute) {
    this(name, NO_SHORTCUT, description, available, execute);
  }

  public boolean hasShortcut() {
    return shortcut != NO_SHORTCUT;
  }

  /** The shortcut as shown to users, for example {@code Ctrl+R}, or an empty string. */
  public String shortcutLabel() {
    return hasShortcut() ? "Ctrl+" + String.valueOf(shortcut).toUpperCase(Locale.ROOT) : "";
  }
}
