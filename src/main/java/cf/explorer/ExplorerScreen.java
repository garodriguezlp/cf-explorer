package cf.explorer;

import cf.explorer.model.ShellState;
import cf.explorer.model.ShellCommands;
import cf.explorer.catalog.CloudFoundryCatalogClient;
import cf.explorer.ui.ApplicationCatalogPage;
import cf.explorer.ui.ShellView;
import dev.tamboui.toolkit.app.ToolkitApp;
import dev.tamboui.toolkit.element.Element;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;

final class ExplorerScreen extends ToolkitApp {
  private final CloudFoundryCatalogClient client;
  private final ApplicationCatalogPage catalog = new ApplicationCatalogPage();
  private final ShellState state = new ShellState(List.of(), ShellCommands.create(),
      Function.identity(),
      ForkJoinPool.commonPool(), action -> runner().runOnRenderThread(action));

  ExplorerScreen(String uaaUrl, String apiUrl, String username, String password) {
    client = new CloudFoundryCatalogClient(uaaUrl, apiUrl, username, password);
    state.setRootPage(catalog);
  }

  @Override
  protected void onStart() {
    catalog.startLoading(() -> {
      try {
        return client.load();
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw new CompletionException(ex);
      } catch (java.io.IOException ex) {
        throw new CompletionException(ex);
      }
    }, ForkJoinPool.commonPool(), action -> runner().runOnRenderThread(action),
        () -> !state.quitRequested());
  }

  @Override
  protected Element render() {
    return ShellView.render(state, this::quit);
  }
}
