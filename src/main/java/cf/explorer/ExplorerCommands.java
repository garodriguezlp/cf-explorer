package cf.explorer;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.text;

import cf.explorer.browser.AppsManagerUrl;
import cf.explorer.browser.BrowserLauncher;
import cf.explorer.catalog.ApplicationCatalog;
import cf.explorer.env.EnvEditorService;
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
import cf.explorer.ui.EnvEditorPage;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** Registers all Cloud Foundry shell commands against the active typed selection and workspace. */
public final class ExplorerCommands {
  private static final Logger LOG = LoggerFactory.getLogger(ExplorerCommands.class);

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
    return create(catalogPage, refreshLoader, envExportService, cfWebUrl, browserLauncher,
        keystoreService, lifecycleService, null, envProfile, worker, dispatch);
  }

  /**
   * Registers all commands including lifecycle operations and the interactive {@code /env} editor
   * when {@code envEditorService} is provided.
   */
  public static List<ShellCommand> create(
      ApplicationCatalogPage catalogPage,
      ApplicationCatalogPage.ProgressLoader refreshLoader,
      EnvExportService envExportService,
      String cfWebUrl,
      BrowserLauncher browserLauncher,
      KeystoreService keystoreService,
      AppLifecycleService lifecycleService,
      EnvEditorService envEditorService,
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
    if (envEditorService != null) {
      commands.add(new ShellCommand(
          "env",
          'l',
          "Edit selected app environment variables",
          state -> state.selected(ApplicationCatalog.App.class) != null,
          state -> {
            ApplicationCatalog.App app = state.selected(ApplicationCatalog.App.class);
            if (app == null) {
              return;
            }
            state.openAsyncPage("Loading environment for " + app.name(), () -> {
              try (var opMdc = MDC.putCloseable("op", "env");
                   var appMdc = MDC.putCloseable("app", app.name())) {
                return new EnvEditorPage(app, envEditorService.loadDraft(app), state);
              } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new CompletionException(ex);
              } catch (IOException ex) {
                LOG.warn("Environment load failed for {} ({})", app.name(), app.guid(), ex);
                return safeErrorPage(" Environment Error ",
                    "Environment load failed: " + ErrorMessages.describe(ex));
              }
            });
          }));
    }
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
            try (var opMdc = MDC.putCloseable("op", "export");
                 var appMdc = MDC.putCloseable("app", app.name())) {
              return new EnvExportPage(envExportService.export(app));
            } catch (InterruptedException ex) {
              Thread.currentThread().interrupt();
              throw new CompletionException(ex);
            } catch (IOException ex) {
              LOG.warn("Environment export failed for {} ({})", app.name(), app.guid(), ex);
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
                LOG.info("Opened Apps Manager URL for {} ({}): {}", app.name(), app.guid(), url);
              } catch (IOException ex) {
                throw new CompletionException(ex);
              }
            }, worker).whenComplete((ignored, error) -> {
              if (error != null) {
                LOG.warn("Browser launch failed for {} ({})", app.name(), url, unwrap(error));
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
                LOG.warn("Render dispatch failed", ex);
              }
            });
          } catch (RejectedExecutionException ex) {
            LOG.warn("Browser launch rejected for {}", app.name(), ex);
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
            try (var opMdc = MDC.putCloseable("op", "keystore");
                 var appMdc = MDC.putCloseable("app", app.name())) {
              return new KeystoreViewPage(keystoreService.exportAndInspect(app));
            } catch (KeystoreService.SafeKeystoreException ex) {
              LOG.warn("Keystore validation failed for {} ({}): {}",
                  app.name(), app.guid(), ex.getMessage());
              return safeErrorPage(" Keystore Error ", ex.getMessage());
            } catch (InterruptedException ex) {
              Thread.currentThread().interrupt();
              throw new CompletionException(ex);
            } catch (IOException ex) {
              LOG.warn("Keystore export failed for {} ({})", app.name(), app.guid(), ex);
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

    if (envEditorService != null) {
      commands.add(new ShellCommand(
          "new",
          'n',
          "Stage a new environment variable",
          state -> {
            EnvEditorPage ed = activeEnvEditor(state);
            return ed != null && !ed.isApplying();
          },
          state -> {
            EnvEditorPage ed = activeEnvEditor(state);
            if (ed != null && !ed.isApplying()) {
              ed.beginNew(state);
            }
          }));
      commands.add(new ShellCommand(
          "set",
          "Update selected environment variable value",
          state -> {
            EnvEditorPage ed = activeEnvEditor(state);
            return ed != null && !ed.isApplying() && ed.selected() != null;
          },
          state -> {
            EnvEditorPage ed = activeEnvEditor(state);
            if (ed != null && !ed.isApplying() && ed.selected() != null) {
              ed.beginSet(state);
            }
          }));
      commands.add(new ShellCommand(
          "delete",
          "Stage deletion of selected environment variable",
          state -> {
            EnvEditorPage ed = activeEnvEditor(state);
            return ed != null && !ed.isApplying()
                && ed.selected() != null && !ed.selected().isDeleted();
          },
          state -> {
            EnvEditorPage ed = activeEnvEditor(state);
            if (ed != null && !ed.isApplying()) {
              ed.beginDelete(state);
            }
          }));
      commands.add(new ShellCommand(
          "undo",
          "Revert staged change on selected variable",
          state -> {
            EnvEditorPage ed = activeEnvEditor(state);
            return ed != null && !ed.isApplying()
                && ed.selected() != null && ed.selected().isChanged();
          },
          state -> {
            EnvEditorPage ed = activeEnvEditor(state);
            if (ed != null && !ed.isApplying()) {
              ed.undoSelected(state);
            }
          }));
      commands.add(new ShellCommand(
          "apply",
          'y',
          "Apply all staged environment changes to Cloud Foundry",
          state -> {
            EnvEditorPage ed = activeEnvEditor(state);
            return ed != null && !ed.isApplying() && ed.draft().hasChanges();
          },
          state -> {
            EnvEditorPage ed = activeEnvEditor(state);
            if (ed == null || ed.isApplying() || !ed.draft().hasChanges()) {
              return;
            }
            int count = ed.draft().changeCount();
            String noun = count == 1 ? "change" : "changes";
            String prompt = "Apply " + count + " env " + noun + " ("
                + ed.draft().summaryBreakdown() + ") to " + appTarget(ed.app(), envProfile) + "?";
            state.requestConfirmation(prompt, () -> runEnvApply(
                ed, envEditorService, catalogPage, lifecycleService, envProfile,
                state, worker, dispatch));
          }));
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
          String prompt = op.verb() + " " + appTarget(app, envProfile) + "?";
          state.requestConfirmation(prompt, () -> runLifecycleOperation(
              op, app, catalogPage, lifecycleService, state, worker, dispatch));
        });
  }

  private static String appTarget(ApplicationCatalog.App app, String envProfile) {
    String envSuffix = envProfile != null && !envProfile.isBlank()
        ? " [" + envProfile + "]" : "";
    return app.name() + " in " + app.orgName() + " \u203a " + app.spaceName() + envSuffix;
  }

  private static EnvEditorPage activeEnvEditor(ShellState state) {
    return state.activePage() instanceof EnvEditorPage editor ? editor : null;
  }

  private static void runEnvApply(
      EnvEditorPage editor,
      EnvEditorService envEditorService,
      ApplicationCatalogPage catalogPage,
      AppLifecycleService lifecycleService,
      String envProfile,
      ShellState state,
      Executor worker,
      Consumer<Runnable> dispatch) {
    if (editor.isApplying() || !editor.draft().hasChanges()) {
      return;
    }
    ApplicationCatalog.App app = editor.app();
    int count = editor.draft().changeCount();
    String noun = count == 1 ? "change" : "changes";
    String progressLabel = "Applying " + count + " env " + noun + " to " + app.name() + "...";
    Activity started = editor.startApplying(progressLabel);
    state.showProgress(progressLabel);
    try {
      CompletableFuture.runAsync(() -> {
        try (var opMdc = MDC.putCloseable("op", "env-apply");
             var appMdc = MDC.putCloseable("app", app.name())) {
          envEditorService.apply(app, editor.draft());
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          throw new CompletionException(ex);
        } catch (IOException ex) {
          throw new CompletionException(ex);
        }
      }, worker).whenComplete((ignored, error) -> {
        Throwable cause = error == null ? null : unwrap(error);
        if (cause != null) {
          LOG.warn("Environment update failed for {} ({})", app.name(), app.guid(), cause);
        }
        started.afterAtLeast(catalogPage.minimumWait(), () -> {
          try {
            dispatch.accept(() -> {
              if (state.quitRequested()) {
                return;
              }
              editor.finishApplying();
              if (cause == null) {
                if (state.activePage() == editor) {
                  state.backNow();
                }
                String summary = "Applied " + count + " env " + noun + " to " + app.name();
                if (lifecycleService != null && !catalogPage.isOperationInFlight(app.guid())) {
                  ApplicationCatalog.App targetApp = catalogPage.selected() != null
                      && catalogPage.selected().guid().equals(app.guid())
                      ? catalogPage.selected() : app;
                  state.requestConfirmation(
                      summary + ". Restart " + appTarget(targetApp, envProfile) + " now?",
                      () -> runLifecycleOperation(Operation.RESTART, targetApp, catalogPage,
                          lifecycleService, state, worker, dispatch),
                      () -> state.showSuccess(
                          summary + " (restart skipped; run /restart or /restage when ready)."));
                } else {
                  state.showSuccess(summary + ".");
                }
              } else {
                state.showError("Environment update failed for " + app.name() + ": "
                    + ErrorMessages.describe(cause));
              }
            });
          } catch (RuntimeException ex) {
            LOG.warn("Render dispatch failed", ex);
          }
        });
      });
    } catch (RejectedExecutionException ex) {
      LOG.warn("Environment update rejected for {} ({})", app.name(), app.guid(), ex);
      editor.finishApplying();
      state.showError("Environment update failed for " + app.name()
          + ": The background worker is unavailable.");
    }
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
        LOG.warn("Render dispatch failed", ex);
      }
    };
    try {
      CompletableFuture.supplyAsync(() -> {
        try (var opMdc = MDC.putCloseable("op", op.commandName());
             var appMdc = MDC.putCloseable("app", app.name())) {
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
          LOG.warn("{} failed for {} ({})", op.verb(), app.name(), app.guid(), cause);
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
            LOG.warn("Render dispatch failed", ex);
          }
        });
      });
    } catch (RejectedExecutionException ex) {
      LOG.warn("{} rejected for {} ({})", op.verb(), app.name(), app.guid(), ex);
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
