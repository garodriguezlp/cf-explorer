package cf.explorer.ui.components;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.text;

import cf.explorer.model.WelcomeContent;
import dev.tamboui.toolkit.element.Element;

public final class WelcomeBody {
  private WelcomeBody() {}

  public static Element render(WelcomeContent content) {
    return column(text(content.message()), text("Count: " + content.count()), text("Press q to quit."));
  }
}
