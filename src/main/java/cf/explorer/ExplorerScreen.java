package cf.explorer;

import cf.explorer.model.ShellState;
import cf.explorer.model.ShellCommands;
import cf.explorer.example.ExampleFeatures;
import cf.explorer.ui.ShellView;
import dev.tamboui.toolkit.app.ToolkitApp;
import dev.tamboui.toolkit.element.Element;
import java.util.List;
import java.util.stream.Stream;
import java.util.concurrent.ForkJoinPool;

final class ExplorerScreen extends ToolkitApp {
  private final ExampleFeatures examples = new ExampleFeatures();
  private final ShellState state = new ShellState(List.of("Atlas", "Borealis", "Cirrus", "Delta"),
      Stream.concat(examples.commands().stream(), ShellCommands.create().stream()).toList(),
      examples::itemLabel,
      ForkJoinPool.commonPool(), action -> runner().runOnRenderThread(action));
  @Override
  protected Element render() {
    return ShellView.render(state, this::quit);
  }
}
