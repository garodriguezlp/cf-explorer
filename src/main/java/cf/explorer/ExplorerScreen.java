package cf.explorer;

import cf.explorer.browser.BrowserLauncher;
import cf.explorer.catalog.ApplicationCatalog;
import cf.explorer.catalog.CachedCatalogService;
import cf.explorer.catalog.CatalogCacheStore;
import cf.explorer.catalog.CatalogProgress;
import cf.explorer.catalog.CloudFoundryCatalogClient;
import cf.explorer.config.ExplorerConfig;
import cf.explorer.env.EnvEditorService;
import cf.explorer.export.EnvExportService;
import cf.explorer.io.ClipboardSupport;
import cf.explorer.keystore.KeystoreService;
import cf.explorer.lifecycle.AppLifecycleService;
import cf.explorer.model.ShellState;
import cf.explorer.ui.ApplicationCatalogPage;
import cf.explorer.ui.ShellView;
import dev.tamboui.toolkit.app.ToolkitApp;
import dev.tamboui.toolkit.element.Element;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;

/** Wires configuration, services, and commands into the TamboUI application. */
final class ExplorerScreen extends ToolkitApp {
  /** Shortest time a wait state stays up, so fast responses do not flicker. */
  static final Duration MINIMUM_WAIT = Duration.ofMillis(350);
  private final ExplorerConfig config;
  private final CachedCatalogService catalogService;
  private final ApplicationCatalogPage catalog = new ApplicationCatalogPage(MINIMUM_WAIT);
  private final ShellState state;

  ExplorerScreen(ExplorerConfig config) {
    this.config = Objects.requireNonNull(config);
    CloudFoundryCatalogClient client = new CloudFoundryCatalogClient(
        config.uaaUrl(), config.cfApiUrl(), config.cfUsername(), config.cfPassword(),
        config.skipSslValidation());
    this.catalogService = new CachedCatalogService(client, new CatalogCacheStore(config.cacheDir()));
    ClipboardSupport clipboard = ClipboardSupport.system();
    EnvExportService envExportService = new EnvExportService(
        client::fetchEnvironmentVariables, config.envsDir(), config.excludeKeys(),
        config.postProcessors(), config.copyToClipboard(), clipboard);
    KeystoreService keystoreService = new KeystoreService(
        client::fetchEnvironmentVariables, config.keystoreVar(), config.keystorePasswordVar(),
        config.jksDir(), config.copyToClipboard(), clipboard);
    AppLifecycleService lifecycleService = new AppLifecycleService(
        client, catalogService::updateCachedApp);
    EnvEditorService envEditorService = new EnvEditorService(
        client::fetchEnvironmentVariables, client::patchEnvironmentVariables);
    Executor worker = ForkJoinPool.commonPool();
    Consumer<Runnable> dispatch = action -> runner().runOnRenderThread(action);
    this.state = new ShellState(
        catalog,
        ExplorerCommands.create(
            catalog,
            this::refreshCatalog,
            envExportService,
            config.cfWebUrl(),
            BrowserLauncher.system(),
            keystoreService,
            lifecycleService,
            envEditorService,
            config.env(),
            worker,
            dispatch),
        worker,
        dispatch,
        MINIMUM_WAIT);
  }

  @Override
  protected void onStart() {
    catalog.startLoading(
        this::loadInitialCatalog,
        ForkJoinPool.commonPool(),
        action -> runner().runOnRenderThread(action),
        () -> !state.quitRequested());
  }

  @Override
  protected Element render() {
    return ShellView.render(state, this::quit);
  }

  private ApplicationCatalog loadInitialCatalog(CatalogProgress progress) {
    try {
      return catalogService.load(config.fresh(), progress);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new CompletionException(ex);
    } catch (IOException ex) {
      throw new CompletionException(ex);
    }
  }

  private ApplicationCatalog refreshCatalog(CatalogProgress progress) {
    try {
      return catalogService.refresh(progress);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new CompletionException(ex);
    } catch (IOException ex) {
      throw new CompletionException(ex);
    }
  }
}
