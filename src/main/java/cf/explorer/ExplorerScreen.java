package cf.explorer;

import cf.explorer.model.ShellState;
import cf.explorer.ui.ShellView;
import dev.tamboui.toolkit.app.ToolkitApp;
import dev.tamboui.toolkit.element.Element;
import java.util.List;

final class ExplorerScreen extends ToolkitApp {
  private final ShellState state = new ShellState(List.of("Atlas", "Borealis", "Cirrus", "Delta"));
  @Override
  protected Element render() {
    return ShellView.render(state, this::quit);
  }
}
