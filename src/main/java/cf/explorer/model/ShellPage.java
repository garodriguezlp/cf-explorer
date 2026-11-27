package cf.explorer.model;

import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyEvent;

/** A shell page owns its presentation; navigation and input remain in the shell. */
public interface ShellPage {
  Element render();

  default Object selected() {
    return null;
  }

  default String inputValue() {
    return null;
  }

  default int inputCursor() {
    return 0;
  }

  default EventResult handleKey(KeyEvent event) {
    return EventResult.UNHANDLED;
  }
}
