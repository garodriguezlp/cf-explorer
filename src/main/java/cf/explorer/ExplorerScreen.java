package cf.explorer;

import cf.explorer.ui.WelcomeView;
import dev.tamboui.toolkit.app.ToolkitApp;
import dev.tamboui.toolkit.element.Element;

final class ExplorerScreen extends ToolkitApp {
  private final String message;
  private final int count;

  ExplorerScreen(String message, int count) {
    this.message = message;
    this.count = count;
  }

  @Override
  protected Element render() {
    return WelcomeView.render(message, count, this::quit);
  }
}
