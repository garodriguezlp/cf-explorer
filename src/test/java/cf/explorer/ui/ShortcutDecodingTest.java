package cf.explorer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import cf.explorer.model.ShellCommand;
import cf.explorer.model.ShellState;
import cf.explorer.model.ShellCommands;
import dev.tamboui.terminal.Backend;
import dev.tamboui.toolkit.Toolkit;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.EventParser;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Feeds raw terminal bytes through TamboUI's {@link EventParser} to prove that every shortcut
 * letter reaches the shell as {@code Ctrl+<letter>}, and that the reserved letters do not.
 */
class ShortcutDecodingTest {
  @Test
  void everyRegisteredShortcutDecodesFromItsControlByte() throws Exception {
    List<ShellCommand> commands = Stream.concat(Stream.of(
        new ShellCommand("export", 'e', "Export", s -> true, s -> {}),
        new ShellCommand("keystore", 'k', "Keystore", s -> true, s -> {}),
        new ShellCommand("open", 'o', "Open", s -> true, s -> {}),
        new ShellCommand("start", 'u', "Start", s -> true, s -> {}),
        new ShellCommand("stop", 'p', "Stop", s -> true, s -> {}),
        new ShellCommand("restart", 't', "Restart", s -> true, s -> {}),
        new ShellCommand("restage", 'g', "Restage", s -> true, s -> {}),
        new ShellCommand("refresh", 'r', "Refresh", s -> true, s -> {})),
        ShellCommands.create().stream()).toList();
    ShellState state = new ShellState(() -> Toolkit.text("Root"), commands,
        Runnable::run, Runnable::run);
    for (ShellCommand command : state.shortcutCommands()) {
      KeyEvent event = decode(command.shortcut() - 'a' + 1);
      assertEquals(command.shortcut(), ShellView.shortcutKey(event, state), command.name());
    }
  }

  @Test
  void reservedLettersDecodeAsOtherKeys() throws Exception {
    assertEquals(KeyCode.TAB, decode('i' - 'a' + 1).code());
    assertEquals(KeyCode.ENTER, decode('j' - 'a' + 1).code());
    assertEquals(KeyCode.ENTER, decode('m' - 'a' + 1).code());
    assertEquals(KeyCode.BACKSPACE, decode('h' - 'a' + 1).code());
    assertEquals(KeyCode.ESCAPE, decode(27).code());
    assertEquals(true, decode('c' - 'a' + 1).isCtrlC());
  }

  private static KeyEvent decode(int controlByte) throws Exception {
    Queue<Integer> input = new ArrayDeque<>(List.of(controlByte));
    Backend backend = (Backend) Proxy.newProxyInstance(Backend.class.getClassLoader(),
        new Class<?>[] {Backend.class}, (proxy, method, args) -> {
          switch (method.getName()) {
            case "read":
              return input.isEmpty() ? -2 : input.remove();
            case "peek":
              return input.isEmpty() ? -2 : input.peek();
            default:
              Class<?> type = method.getReturnType();
              return type == boolean.class ? false : type == int.class ? 0 : null;
          }
        });
    Event event = EventParser.readEvent(backend, 10);
    return assertInstanceOf(KeyEvent.class, event);
  }
}
