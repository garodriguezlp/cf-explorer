package cf.explorer;

import cf.explorer.model.WelcomeContent;
import cf.explorer.ui.WelcomeView;
import dev.tamboui.toolkit.app.ToolkitApp;
import dev.tamboui.toolkit.element.Element;

final class ExplorerScreen extends ToolkitApp {
  private final WelcomeContent content;

  ExplorerScreen(String message, int count) {
    this.content = new WelcomeContent(message, count);
  }

  @Override
  protected Element render() {
    return WelcomeView.render(content, this::quit);
  }
}
