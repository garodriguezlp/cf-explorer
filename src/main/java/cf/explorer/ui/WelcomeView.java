package cf.explorer.ui;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.text;

import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.EventResult;

public final class WelcomeView {
  private WelcomeView() {}

  public static Element render(String message, int count, Runnable quit) {
    return panel(column(text(message), text("Count: " + count), text("Press q to quit.")))
        .title("cf-explorer")
        .rounded()
        .id("main")
        .focusable()
        .onKeyEvent(event -> {
          if (event.isChar('q') || event.isCtrlC()) {
            quit.run();
            return EventResult.HANDLED;
          }
          return EventResult.UNHANDLED;
        });
  }
}
