package cf.explorer;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.text;

import cf.explorer.browser.AppsManagerUrl;
import cf.explorer.browser.BrowserLauncher;
import cf.explorer.catalog.ApplicationCatalog;
import cf.explorer.export.EnvExportService;
import cf.explorer.keystore.KeystoreService;
import cf.explorer.lifecycle.AppLifecycleService;
import cf.explorer.lifecycle.AppLifecycleService.Operation;
import cf.explorer.model.Activity;
import cf.explorer.model.ShellCommand;
import cf.explorer.model.ShellCommands;
import cf.explorer.model.ShellPage;
import cf.explorer.model.ShellState;
import cf.explorer.ui.ApplicationCatalogPage;
import cf.explorer.ui.EnvExportPage;
import cf.explorer.ui.ErrorMessages;
import cf.explorer.ui.KeystoreViewPage;
import cf.explorer.ui.LoadingView;
import dev.tamboui.style.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Logger;

/** Registers all Cloud Foundry shell commands against the active typed selection and workspace. */
public final class ExplorerCommands {
  private static final Logger LOG = Logger.getLogger(ExplorerCommands.class.getName());

  private ExplorerCommands() {}

  public static List<ShellCommand> create(
      ApplicationCatalogPage catalogPage,
      Supplier<ApplicationCatalog> refreshLoader,
      EnvExportService envExportService,
      String cfWebUrl,
      BrowserLauncher browserLauncher,
      KeystoreService keystoreService,
      Executor worker,
      Consumer<Runnable> dispatch) {
    Objects.requireNonNull(refreshLoader);
    return create(catalogPage, progress -> refreshLoader.get(), envExportService, cfWebUrl,
        browserLauncher, keystoreService, null, null, worker, dispatch);
  }

  /** Registers the commands; {@code refreshLoader} reports progress to the catalog page. */
  public static List<ShellCommand> create(
      ApplicationCatalogPage catalogPage,
      ApplicationCatalogPage.ProgressLoader refreshLoader,
      EnvExportService envExportService,
      String cfWebUrl,
      BrowserLauncher browserLauncher,
      KeystoreService keystoreService,
      Executor worker,
      Consumer<Runnable> dispatch) {
    return create(catalogPage, refreshLoader, envExportService, cfWebUrl, browserLauncher,
        keystoreService, null, null, worker, dispatch);
  }

  /**
   * Registers all commands including application lifecycle operations ({@code /start},
   * {@code /stop}, {@code /restart}, {@code /restage}) when {@code lifecycleService} is provided.
   */
  public static List<ShellCommand> create(
      ApplicationCatalogPage catalogPage,
      ApplicationCatalogPage.ProgressLoader refreshLoader,
      EnvExportService envExportService,
      String cfWebUrl,
      BrowserLauncher browserLauncher,
      KeystoreService keystoreService,
      AppLifecycleService lifecycleService,
      String envProfile,
      Executor worker,
      Consumer<Runnable> dispatch) {
    Objects.requireNonNull(catalogPage);
    Objects.requireNonNull(refreshLoader);
    Objects.requireNonNull(envExportService);
    Objects.requireNonNull(cfWebUrl);
    Objects.requireNonNull(browserLauncher);
    Objects.requireNonNull(keystoreService);
    Objects.requireNonNull(worker);
    Objects.requireNonNull(dispatch);

    List<ShellCommand> commands = new ArrayList<>();
    commands.add(new ShellCommand(
        "export",
        'e',
        "Export selected app environment to a .env file",
        state -> state.selected(ApplicationCatalog.App.class) != null,
        state -> {
          ApplicationCatalog.App app = state.selected(ApplicationCatalog.App.class);
          if (app == null) {
            return;
          }
          state.openAsyncPage("Exporting environment for " + app.name(), () -> {
            try {
              return new EnvExportPage(envExportService.export(app));
            } catch (InterruptedException ex) {
              Thread.currentThread().interrupt();
              throw new CompletionException(ex);
            } catch (IOException ex) {
              LOG.warning("Environment export failed (" + ex.getClass().getName() + ")");
              return safeErrorPage(" Export Error ", "Export failed: " + ErrorMessages.describe(ex));
            }
          });
        }));

    commands.add(new ShellCommand(
        "open",
        'o',
        "Open selected app in Apps Manager",
        state -> AppsManagerUrl.canBuild(state.selected(ApplicationCatalog.App.class)),
        state -> {
          ApplicationCatalog.App app = state.selected(ApplicationCatalog.App.class);
          if (!AppsManagerUrl.canBuild(app)) {
            return;
          }
          String url = AppsManagerUrl.build(cfWebUrl, app);
          state.showProgress("Opening " + app.name() + " in Apps Manager...");
          try {
            CompletableFuture.runAsync(() -> {
              try {
                browserLauncher.open(url);
              } catch (IOException ex) {
                throw new CompletionException(ex);
              }
            }, worker).whenComplete((ignored, error) -> {
              if (error != null) {
                LOG.warning("Browser launch failed (" + unwrap(error).getClass().getName() + ")");
              }
              try {
                dispatch.accept(() -> {
                  if (!state.quitRequested()) {
                    state.showStatus(error == null
                        ? "Opened " + app.name() + " in Apps Manager."
                        : "Could not open browser. Apps Manager URL: " + url);
                  }
                });
              } catch (RuntimeException ex) {
                LOG.warning("Render dispatch failed (" + ex.getClass().getName() + ")");
              }
            });
          } catch (RejectedExecutionException ex) {
            LOG.warning("Browser launch failed (" + ex.getClass().getName() + ")");
            state.showStatus("Could not open browser. Apps Manager URL: " + url);
          }
        }));

    commands.add(new ShellCommand(
        "keystore",
        'k',
        "Export and inspect selected app JKS keystore",
        state -> state.selected(ApplicationCatalog.App.class) != null,
        state -> {
          ApplicationCatalog.App app = state.selected(ApplicationCatalog.App.class);
          if (app == null) {
            return;
          }
          state.openAsyncPage("Inspecting keystore for " + app.name(), () -> {
            try {
              return new KeystoreViewPage(keystoreService.exportAndInspect(app));
            } catch (KeystoreService.SafeKeystoreException ex) {
              return safeErrorPage(" Keystore Error ", ex.getMessage());
            } catch (InterruptedException ex) {
              Thread.currentThread().interrupt();
              throw new CompletionException(ex);
            } catch (IOException ex) {
              LOG.warning("Keystore export failed (" + ex.getClass().getName() + ")");
              return safeErrorPage(" Keystore Error ",
                  "Keystore export failed: " + ErrorMessages.describe(ex));
            }
          });
        }));

    if (lifecycleService != null) {
      commands.add(lifecycleCommand(Operation.START, "Start selected app",
          app -> !"STARTED".equalsIgnoreCase(app.state()),
          catalogPage, lifecycleService, envProfile, worker, dispatch));
      commands.add(lifecycleCommand(Operation.STOP, "Stop selected app",
          app -> !"STOPPED".equalsIgnoreCase(app.state()),
          catalogPage, lifecycleService, envProfile, worker, dispatch));
      commands.add(lifecycleCommand(Operation.RESTART, "Restart selected app",
          app -> true, catalogPage, lifecycleService, envProfile, worker, dispatch));
      commands.add(lifecycleCommand(Operation.RESTAGE,
          "Restage selected app from its latest ready package",
          app -> true, catalogPage, lifecycleService, envProfile, worker, dispatch));
    }

    commands.add(new ShellCommand(
        "refresh",
        'r',
        "Reload catalog from Cloud Foundry",
        state -> state.page() == null,
        state -> catalogPage.startRefresh(
            refreshLoader, worker, dispatch, () -> !state.quitRequested(), state::showStatus)));

    commands.addAll(ShellCommands.create());
    return List.copyOf(commands);
  }

  private static ShellCommand lifecycleCommand(
      Operation op,
      String description,
      Predicate<ApplicationCatalog.App> statePredicate,
      ApplicationCatalogPage catalogPage,
      AppLifecycleService lifecycleService,
      String envProfile,
      Executor worker,
      Consumer<Runnable> dispatch) {
    Predicate<ShellState> available = state -> {
      ApplicationCatalog.App app = state.selected(ApplicationCatalog.App.class);
      return app != null && !catalogPage.isOperationInFlight(app.guid())
          && statePredicate.test(app);
    };
    return new ShellCommand(
        op.commandName(),
        op.shortcut(),
        description,
        available,
        state -> {
          ApplicationCatalog.App app = state.selected(ApplicationCatalog.App.class);
          if (app == null || catalogPage.isOperationInFlight(app.guid())
              || !statePredicate.test(app)) {
            return;
          }
          String envSuffix = envProfile != null && !envProfile.isBlank()
              ? " [" + envProfile + "]" : "";
          String prompt = op.verb() + " " + app.name() + " in " + app.orgName()
              + " \u203a " + app.spaceName() + envSuffix + "?";
          state.requestConfirmation(prompt, () -> runLifecycleOperation(
              op, app, catalogPage, lifecycleService, state, worker, dispatch));
        });
  }

  private static void runLifecycleOperation(
      Operation op,
      ApplicationCatalog.App app,
      ApplicationCatalogPage catalogPage,
      AppLifecycleService lifecycleService,
      ShellState state,
      Executor worker,
      Consumer<Runnable> dispatch) {
    if (catalogPage.isOperationInFlight(app.guid())) {
      return;
    }
    Activity started = catalogPage.startOperation(app, op.badgeLabel());
    state.showProgress(op.presentParticiple() + " " + app.name() + " in background...");
    Consumer<String> stepListener = stepBadge -> {
      try {
        dispatch.accept(() -> {
          if (!state.quitRequested()) {
            catalogPage.updateOperationBadge(app.guid(), stepBadge);
          }
        });
      } catch (RuntimeException ex) {
        LOG.warning("Render dispatch failed (" + ex.getClass().getName() + ")");
      }
    };
    try {
      CompletableFuture.supplyAsync(() -> {
        try {
          return Objects.requireNonNull(
              lifecycleService.execute(op, app, stepListener, () -> !state.quitRequested()));
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          throw new CompletionException(ex);
        } catch (IOException ex) {
          throw new CompletionException(ex);
        }
      }, worker).whenComplete((updated, error) -> {
        Throwable cause = error == null ? null : unwrap(error);
        if (cause != null && !(cause instanceof CancellationException)) {
          LOG.warning(op.verb() + " failed (" + cause.getClass().getName() + ")");
        }
        started.afterAtLeast(catalogPage.minimumWait(), () -> {
          try {
            dispatch.accept(() -> {
              if (state.quitRequested()) {
                return;
              }
              if (cause == null) {
                catalogPage.finishOperation(app.guid(), updated);
                long seconds = started.elapsed().toSeconds();
                String took = seconds >= 1
                    ? " (" + LoadingView.shortDuration(started.elapsed()) + ")" : "";
                state.showSuccess(op.pastParticiple() + " " + app.name()
                    + " \u2192 " + updated.state() + took);
              } else {
                catalogPage.finishOperation(app.guid(), null);
                if (!(cause instanceof CancellationException)) {
                  state.showError(op.verb() + " failed for " + app.name() + ": "
                      + ErrorMessages.describe(cause));
                }
              }
            });
          } catch (RuntimeException ex) {
            LOG.warning("Render dispatch failed (" + ex.getClass().getName() + ")");
          }
        });
      });
    } catch (RejectedExecutionException ex) {
      LOG.warning(op.verb() + " failed (" + ex.getClass().getName() + ")");
      catalogPage.finishOperation(app.guid(), null);
      state.showError(op.verb() + " failed for " + app.name()
          + ": The background worker is unavailable.");
    }
  }

  private static ShellPage safeErrorPage(String title, String message) {
    return () -> panel(column(
        text(" " + message).fg(Color.LIGHT_YELLOW),
        text(""),
        text(" Press Esc or /back to return to the app catalog.").dim()))
        .title(title).rounded().borderColor(Color.RED).fill();
  }

  private static Throwable unwrap(Throwable error) {
    return error instanceof CompletionException && error.getCause() != null
        ? error.getCause() : error;
  }
}
