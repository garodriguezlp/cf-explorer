//SOURCES components/WelcomeBody.java

package cf.explorer.ui;

import static dev.tamboui.toolkit.Toolkit.panel;

import cf.explorer.model.WelcomeContent;
import cf.explorer.ui.components.WelcomeBody;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.EventResult;

public final class WelcomeView {
  private WelcomeView() {}

  public static Element render(WelcomeContent content, Runnable quit) {
    return panel(WelcomeBody.render(content))
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
