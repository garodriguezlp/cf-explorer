package cf.explorer;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;

@FunctionalInterface
interface UiDispatcher {
  void dispatch(Runnable action);
}

final class Controller {

  // ── State ──────────────────────────────────────────────────────────
  private AppState state = AppState.CatalogLoading.initial();

  private final LoadCatalogUseCase loadCatalogUseCase;
  private final ExportEnvUseCase exportEnvUseCase;
  private final OpenAppInBrowserUseCase openInBrowserUseCase;
  private final ExportKeystoreUseCase exportKeystoreUseCase;
  private final EnvExportConfig exportConfig;
  private final UiDispatcher dispatch;

  Controller(EnvConfig config, UiDispatcher dispatch) {
    var useCases = new UseCases(config);
    this.loadCatalogUseCase = useCases.loadCatalog();
    this.exportEnvUseCase = useCases.exportEnv();
    this.openInBrowserUseCase = useCases.openInBrowser();
    this.exportKeystoreUseCase = useCases.keystoreUseCase();
    this.exportConfig = new EnvExportConfig(config.excludeKeys(), config.postProcessors());
    this.dispatch = dispatch;
  }

  // ── Query ──────────────────────────────────────────────────────────
  AppState state() {
    return state;
  }

  // ── Sync commands (called on render thread from key handler) ───────
  void moveUp() {
    if (state instanceof AppState.Browsing b) state = b.moveUp();
  }

  void moveDown() {
    if (state instanceof AppState.Browsing b) state = b.moveDown();
  }

  void appendFilter(char ch) {
    if (state instanceof AppState.Browsing b) state = b.appendFilter(ch);
  }

  void backspaceFilter() {
    if (state instanceof AppState.Browsing b) state = b.backspaceFilter();
  }

  void clearFilter() {
    if (state instanceof AppState.Browsing b) state = b.clearFilter();
  }

  void returnToBrowsing() {
    if (state instanceof AppState.ExportDone d) state = d.back();
    else if (state instanceof AppState.ExportFailed f) state = f.back();
    else if (state instanceof AppState.KeystoreDone d) state = d.back();
    else if (state instanceof AppState.KeystoreFailed f) state = f.back();
  }

  // ── Async commands ─────────────────────────────────────────────────
  void start() {
    CompletableFuture.supplyAsync(() -> loadCatalogUseCase.execute(buildCatalogLoadListener()))
        .thenAccept(apps -> dispatch.dispatch(() -> onCatalogLoaded(apps)))
        .exceptionally(
            ex -> {
              dispatch.dispatch(() -> onCatalogLoadFailed(ex));
              return null;
            });
  }

  void selectApp(App app) {
    transitionToExporting(app);
    CompletableFuture.supplyAsync(() -> doExport(app))
        .thenAccept(result -> dispatch.dispatch(() -> onExportDone(result)))
        .exceptionally(
            ex -> {
              dispatch.dispatch(() -> onExportFailed(ex));
              return null;
            });
  }

  void openInBrowser(App app) {
    CompletableFuture.runAsync(() -> openInBrowserUseCase.execute(app));
  }

  void exportKeystore(App app) {
    if (state instanceof AppState.Browsing b) state = b.toKeystoreExporting(app.name());
    CompletableFuture.supplyAsync(
            () -> {
              try {
                return exportKeystoreUseCase.execute(app);
              } catch (Exception ex) {
                throw new RuntimeException(ex);
              }
            })
        .thenAccept(result -> dispatch.dispatch(() -> onKeystoreDone(result)))
        .exceptionally(
            ex -> {
              dispatch.dispatch(() -> onKeystoreFailed(ex));
              return null;
            });
  }

  // ── Private helpers ────────────────────────────────────────────────
  private void transitionToExporting(App app) {
    if (state instanceof AppState.Browsing b) state = b.toExporting(app.name());
  }

  private EnvWriteResult doExport(App app) {
    try {
      return exportEnvUseCase.execute(app, exportConfig);
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }
  }

  private void onExportDone(EnvWriteResult result) {
    if (state instanceof AppState.EnvExporting e)
      state = e.toDone(
          result.path().toString(), result.excludedKeys(), result.postProcessedKeys(),
          result.clipboardCopied());
  }

  private void onExportFailed(Throwable ex) {
    if (state instanceof AppState.EnvExporting e) state = e.toFailed(unwrap(ex).getMessage());
  }

  private void onKeystoreDone(KeystoreInspectResult result) {
    if (state instanceof AppState.KeystoreExporting k) state = k.toDone(result);
  }

  private void onKeystoreFailed(Throwable ex) {
    if (state instanceof AppState.KeystoreExporting k) state = k.toFailed(unwrap(ex).getMessage());
  }

  private void onCatalogLoaded(List<App> apps) {
    if (state instanceof AppState.CatalogLoading l) state = l.toBrowsing(apps);
  }

  private void onCatalogLoadFailed(Throwable ex) {
    Throwable cause = unwrap(ex);
    state =
        state instanceof AppState.CatalogLoading l
            ? l.toLoadFailed(cause.getMessage())
            : new AppState.CatalogLoadFailed(
                new AppState.HeaderCounts(0, 0, 0), cause.getMessage());
  }

  private CatalogLoadListener buildCatalogLoadListener() {
    return new CatalogLoadListener() {
      @Override
      public void organizationsLoaded(int count) {
        dispatchLoadingUpdate(l -> l.withOrgsLoaded(count));
      }

      @Override
      public void spacesLoaded(int count) {
        dispatchLoadingUpdate(l -> l.withSpacesLoaded(count));
      }

      @Override
      public void appsLoaded(int count) {
        dispatchLoadingUpdate(l -> l.withAppsLoaded(count));
      }
    };
  }

  private void dispatchLoadingUpdate(UnaryOperator<AppState.CatalogLoading> update) {
    dispatch.dispatch(
        () -> {
          if (state instanceof AppState.CatalogLoading l) state = update.apply(l);
        });
  }

  private static Throwable unwrap(Throwable ex) {
    return ex.getCause() != null ? ex.getCause() : ex;
  }
}
