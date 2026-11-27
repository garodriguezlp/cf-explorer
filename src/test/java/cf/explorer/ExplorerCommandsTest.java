package cf.explorer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cf.explorer.catalog.ApplicationCatalog;
import cf.explorer.catalog.CloudFoundryCatalogClient;
import cf.explorer.catalog.CloudFoundryException;
import cf.explorer.export.EnvExportService;
import cf.explorer.keystore.KeystoreService;
import cf.explorer.lifecycle.AppLifecycleService;
import cf.explorer.model.ShellCommand;
import cf.explorer.model.ShellState;
import cf.explorer.ui.ApplicationCatalogPage;
import cf.explorer.ui.ShellView;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExplorerCommandsTest {
  @TempDir Path temp;

  private static final ApplicationCatalog.App APP = new ApplicationCatalog.App(
      "app-1", "billing-api", "STARTED", "space-1", "production", "org-1", "finance",
      "2024-01-01", "2024-01-02");

  @Test
  void commandsExposeAvailabilityAndExecuteExportOpenKeystoreAndRefreshOffRenderThread() {
    Queue<Runnable> worker = new ArrayDeque<>();
    Queue<Runnable> dispatch = new ArrayDeque<>();
    AtomicReference<String> openedUrl = new AtomicReference<>();

    ApplicationCatalogPage catalogPage = new ApplicationCatalogPage();
    EnvExportService envService = new EnvExportService(
        guid -> Map.of("SECRET", "do-not-display", "APP_ENV", "prod"),
        temp.resolve("envs"), List.of(), Map.of(), true, text -> false);
    KeystoreService ksService = new KeystoreService(
        guid -> Map.of(
            "KEYSTORE", Base64.getEncoder().encodeToString("bad-jks".getBytes(StandardCharsets.UTF_8)),
            "KEYSTORE_PASSWORD", Base64.getEncoder().encodeToString("s3cr3t".getBytes(StandardCharsets.UTF_8))),
        "KEYSTORE", "KEYSTORE_PASSWORD", temp.resolve("jks"), false, text -> true);

    List<ShellCommand> commands = ExplorerCommands.create(
        catalogPage,
        () -> new ApplicationCatalog(1, 1, List.of(APP)),
        envService,
        "https://apps.example.com",
        openedUrl::set,
        ksService,
        worker::add,
        dispatch::add);

    ShellState state = new ShellState(catalogPage, commands, worker::add, dispatch::add);

    // Before catalog loads: only refresh and quit are available
    type(state, "/");
    assertEquals(List.of("refresh", "quit"),
        state.suggestions().stream().map(ShellCommand::name).toList());
    state.cancel();

    // Load catalog
    catalogPage.loaded(new ApplicationCatalog(1, 1, List.of(APP)));
    type(state, "/");
    assertEquals(List.of("export", "open", "keystore", "refresh", "quit"),
        state.suggestions().stream().map(ShellCommand::name).toList());
    String commandView = render(state);
    assertTrue(commandView.contains("/export    Ctrl+E  Export selected app environment"),
        commandView);
    assertTrue(commandView.contains("/open      Ctrl+O  Open selected app"), commandView);
    assertTrue(commandView.contains("/keystore  Ctrl+K  Export and inspect"), commandView);
    assertTrue(commandView.contains("/refresh   Ctrl+R  Reload catalog"), commandView);
    assertTrue(commandView.contains("/quit      Ctrl+X  Exit cf-explorer"), commandView);
    state.cancel();
    String footer = render(state);
    assertFalse(footer.contains("Ctrl+E"), "shortcuts are listed only in the command view");

    // /open runs off render thread and reports success
    type(state, "/open");
    state.confirm();
    assertEquals("Opening billing-api in Apps Manager...", state.status());
    assertTrue(state.statusActivity() != null, "the status line spins while the browser opens");
    worker.remove().run();
    dispatch.remove().run();
    assertNull(state.statusActivity());
    assertEquals("https://apps.example.com/organizations/org-1/spaces/space-1/applications/app-1",
        openedUrl.get());
    assertEquals("Opened billing-api in Apps Manager.", state.status());

    // /export runs off render thread and renders safe summary without leaking values
    type(state, "/export");
    state.confirm();
    assertTrue(render(state).contains("Exporting environment for billing-api..."));
    worker.remove().run();
    dispatch.remove().run();
    String exportScreen = render(state);
    assertTrue(exportScreen.contains("Environment Exported"), exportScreen);
    assertTrue(exportScreen.contains("billing-api"), exportScreen);
    assertTrue(exportScreen.contains("copying path to clipboard failed"), exportScreen);
    assertFalse(exportScreen.contains("do-not-display"), exportScreen);
    state.cancel();

    // /keystore runs off render thread and shows partial-success safely without leaking password
    type(state, "/keystore");
    state.confirm();
    assertTrue(render(state).contains("Inspecting keystore for billing-api..."));
    worker.remove().run();
    dispatch.remove().run();
    String ksScreen = render(state);
    assertTrue(ksScreen.contains("Keystore Saved (Inspection Failed)"), ksScreen);
    assertFalse(ksScreen.contains("s3cr3t"), ksScreen);
    state.cancel();

    // /refresh reloads catalog
    type(state, "/refresh");
    state.confirm();
    worker.remove().run();
    dispatch.remove().run();
    assertEquals("Catalog refreshed.", state.status());

    // Ctrl+R refreshes from the catalog without opening the command view
    assertTrue(state.runShortcut('r'));
    assertEquals("Refreshing catalog...", state.status());
    worker.remove().run();
    dispatch.remove().run();
    assertEquals("Catalog refreshed.", state.status());

    // Ctrl+E opens the export page, where Ctrl+R is unavailable and Ctrl+B returns
    assertTrue(state.runShortcut('e'));
    assertTrue(render(state).contains("Exporting environment for billing-api"));
    worker.remove().run();
    dispatch.remove().run();
    assertTrue(render(state).contains("Environment Exported"));
    assertTrue(state.runShortcut('r'));
    assertEquals("Command unavailable: /refresh", state.status());
    assertTrue(state.runShortcut('b'));
    assertEquals(APP, state.selected(ApplicationCatalog.App.class));
  }

  @Test
  void browserLauncherFailureReportsSafeFallbackUrl() {
    Queue<Runnable> worker = new ArrayDeque<>();
    Queue<Runnable> dispatch = new ArrayDeque<>();
    ApplicationCatalogPage catalogPage = new ApplicationCatalogPage();
    catalogPage.loaded(new ApplicationCatalog(1, 1, List.of(APP)));

    List<ShellCommand> commands = ExplorerCommands.create(
        catalogPage,
        () -> new ApplicationCatalog(1, 1, List.of(APP)),
        new EnvExportService(g -> Map.of(), temp.resolve("envs"), List.of(), Map.of(), false, t -> true),
        "https://apps.example.com",
        url -> { throw new IOException("no browser"); },
        new KeystoreService(g -> Map.of(), "K", "P", temp.resolve("jks"), false, t -> true),
        worker::add,
        dispatch::add);

    ShellState state = new ShellState(catalogPage, commands, worker::add, dispatch::add);

    type(state, "/open");
    state.confirm();
    worker.remove().run();
    dispatch.remove().run();
    assertEquals(
        "Could not open browser. Apps Manager URL: "
            + "https://apps.example.com/organizations/org-1/spaces/space-1/applications/app-1",
        state.status());
  }

  @Test
  void keystoreMissingVariableShowsSafeErrorWithoutLeakingSecrets() {
    ApplicationCatalogPage catalogPage = new ApplicationCatalogPage();
    catalogPage.loaded(new ApplicationCatalog(1, 1, List.of(APP)));

    List<ShellCommand> commands = ExplorerCommands.create(
        catalogPage,
        () -> new ApplicationCatalog(1, 1, List.of(APP)),
        new EnvExportService(g -> Map.of(), temp.resolve("envs"), List.of(), Map.of(), false, t -> true),
        "https://apps.example.com",
        url -> {},
        new KeystoreService(g -> Map.of(), "CUSTOM_KS", "CUSTOM_PW", temp.resolve("jks"), false, t -> true),
        Runnable::run,
        Runnable::run);

    ShellState state = new ShellState(catalogPage, commands, Runnable::run, Runnable::run);

    type(state, "/keystore");
    state.confirm();
    String screen = render(state);
    assertTrue(screen.contains("Keystore Error"), screen);
    assertTrue(screen.contains("CUSTOM_KS"), screen);
    state.cancel();
    assertEquals(APP, state.selected(ApplicationCatalog.App.class));
  }

  @Test
  void lifecycleCommandsConfirmInlineAnimateRowsConcurrentlyAndNotifyOnCompletion() {
    var startedApp = new ApplicationCatalog.App(
        "app-1", "billing-api", "STARTED", "space-1", "production", "org-1", "finance",
        "2024-01-01", "2024-01-02");
    var stoppedApp = new ApplicationCatalog.App(
        "app-2", "auth-worker", "STOPPED", "space-1", "production", "org-1", "finance",
        "2024-01-01", "2024-01-02");

    Queue<Runnable> worker = new ArrayDeque<>();
    Queue<Runnable> dispatch = new ArrayDeque<>();
    AtomicReference<Boolean> failNextStop = new AtomicReference<>(false);

    AppLifecycleService.Api api = new AppLifecycleService.Api() {
      @Override
      public CloudFoundryCatalogClient.AppStateResult startApp(String appGuid) {
        return new CloudFoundryCatalogClient.AppStateResult(
            appGuid, "STARTED", "2026-06-01T12:00:00Z", null);
      }

      @Override
      public CloudFoundryCatalogClient.AppStateResult stopApp(String appGuid) throws IOException {
        if (failNextStop.get()) {
          throw new CloudFoundryException("Stop app was rejected (HTTP 403).");
        }
        return new CloudFoundryCatalogClient.AppStateResult(
            appGuid, "STOPPED", "2026-06-01T12:01:00Z", null);
      }

      @Override
      public CloudFoundryCatalogClient.AppStateResult restartApp(String appGuid) {
        return new CloudFoundryCatalogClient.AppStateResult(
            appGuid, "STARTED", "2026-06-01T12:02:00Z", null);
      }

      @Override
      public String findLatestReadyPackageGuid(String appGuid) {
        return "pkg-1";
      }

      @Override
      public CloudFoundryCatalogClient.BuildResult createBuild(String packageGuid) {
        return new CloudFoundryCatalogClient.BuildResult("bld-1", "STAGING", null);
      }

      @Override
      public CloudFoundryCatalogClient.BuildResult getBuild(String buildGuid) {
        return new CloudFoundryCatalogClient.BuildResult(buildGuid, "STAGED", "drp-1");
      }

      @Override
      public void setCurrentDroplet(String appGuid, String dropletGuid) {}
    };

    ApplicationCatalogPage catalogPage = new ApplicationCatalogPage();
    catalogPage.loaded(new ApplicationCatalog(1, 1, List.of(startedApp, stoppedApp)));
    AppLifecycleService lifecycle = new AppLifecycleService(
        api, node -> {}, Duration.ofMillis(1), 5, d -> {});

    List<ShellCommand> commands = ExplorerCommands.create(
        catalogPage,
        progress -> new ApplicationCatalog(1, 1, List.of(startedApp, stoppedApp)),
        new EnvExportService(g -> Map.of(), temp.resolve("envs"), List.of(), Map.of(), false, t -> true),
        "https://apps.example.com",
        url -> {},
        new KeystoreService(g -> Map.of(), "K", "P", temp.resolve("jks"), false, t -> true),
        lifecycle,
        "prod",
        worker::add,
        dispatch::add);

    ShellState state = new ShellState(catalogPage, commands, worker::add, dispatch::add);

    // For STARTED app: stop, restart, restage available; start is not
    type(state, "/");
    assertEquals(
        List.of("export", "open", "keystore", "stop", "restart", "restage", "refresh", "quit"),
        state.suggestions().stream().map(ShellCommand::name).toList());
    state.cancel();

    // Filter stays intact when Ctrl+P triggers inline confirmation, and 'n' cancels cleanly
    for (char c : "finance".toCharArray()) {
      ShellView.handleKey(KeyEvent.ofChar(c), state, () -> {});
    }
    ShellView.handleKey(KeyEvent.ofChar('p', KeyModifiers.CTRL), state, () -> {});
    String confirmScreen = render(state);
    assertTrue(confirmScreen.contains(" Confirm "), confirmScreen);
    assertTrue(
        confirmScreen.contains("Stop billing-api in finance \u203a production [prod]?"),
        confirmScreen);
    assertTrue(confirmScreen.contains("[Enter/y] confirm  [Esc/n] cancel"), confirmScreen);
    ShellView.handleKey(KeyEvent.ofChar('n'), state, () -> {});
    assertNull(state.confirmation());
    assertEquals("Cancelled.", state.status());
    assertEquals("finance", catalogPage.inputValue());
    assertTrue(worker.isEmpty());

    // Confirming Ctrl+P starts background stop; user stays in catalog with live row & header badge
    ShellView.handleKey(KeyEvent.ofChar('p', KeyModifiers.CTRL), state, () -> {});
    ShellView.handleKey(KeyEvent.ofChar('y'), state, () -> {});
    assertNull(state.confirmation());
    assertEquals(1, worker.size());
    String inFlightSingle = render(state);
    assertTrue(inFlightSingle.contains("STOPPING"), inFlightSingle);
    assertTrue(inFlightSingle.contains("billing-api: stopping]"), inFlightSingle);

    // Duplicate mutations on billing-api are locked while in flight, but user can move to
    // auth-worker and concurrently start it with Ctrl+U -> Enter!
    assertTrue(state.runShortcut('t'));
    assertEquals("Command unavailable: /restart", state.status());
    ShellView.handleKey(KeyEvent.ofKey(KeyCode.DOWN), state, () -> {});
    assertEquals("auth-worker", state.selected(ApplicationCatalog.App.class).name());
    ShellView.handleKey(KeyEvent.ofChar('u', KeyModifiers.CTRL), state, () -> {});
    ShellView.handleKey(KeyEvent.ofKey(KeyCode.ENTER), state, () -> {});
    assertEquals(2, worker.size());
    String inFlightMulti = render(state);
    assertTrue(inFlightMulti.contains("STARTING"), inFlightMulti);
    assertTrue(inFlightMulti.contains("2 ops running]"), inFlightMulti);

    // First background job completes -> billing-api flips to STOPPED and shows green ✓ notice
    worker.remove().run();
    dispatch.remove().run();
    assertEquals("\u2713 Stopped billing-api \u2192 STOPPED", state.status());
    assertEquals(ShellState.StatusKind.SUCCESS, state.statusKind());

    // Second background job completes -> auth-worker flips to STARTED
    worker.remove().run();
    dispatch.remove().run();
    assertEquals("\u2713 Started auth-worker \u2192 STARTED", state.status());

    // Move back up to billing-api (now STOPPED): /start is now available, /stop is not
    ShellView.handleKey(KeyEvent.ofKey(KeyCode.UP), state, () -> {});
    assertEquals("STOPPED", state.selected(ApplicationCatalog.App.class).state());
    type(state, "/");
    assertTrue(state.suggestions().stream().anyMatch(c -> c.name().equals("start")));
    assertFalse(state.suggestions().stream().anyMatch(c -> c.name().equals("stop")));
    state.cancel();

    // /restage (Ctrl+G) updates step badges on the render thread and restarts into STARTED
    ShellView.handleKey(KeyEvent.ofChar('g', KeyModifiers.CTRL), state, () -> {});
    ShellView.handleKey(KeyEvent.ofKey(KeyCode.ENTER), state, () -> {});
    worker.remove().run();
    // Drain step updates + final completion on render thread
    dispatch.remove().run();
    assertTrue(render(state).contains("STAGING 1/4"));
    while (!dispatch.isEmpty()) {
      dispatch.remove().run();
    }
    assertEquals("\u2713 Restaged billing-api \u2192 STARTED", state.status());
    assertEquals("STARTED", state.selected(ApplicationCatalog.App.class).state());

    // Safe failure reporting sets ERROR status without altering app state
    failNextStop.set(true);
    ShellView.handleKey(KeyEvent.ofChar('p', KeyModifiers.CTRL), state, () -> {});
    ShellView.handleKey(KeyEvent.ofKey(KeyCode.ENTER), state, () -> {});
    worker.remove().run();
    dispatch.remove().run();
    assertEquals(ShellState.StatusKind.ERROR, state.statusKind());
    assertTrue(state.status().startsWith("\u2717 Stop failed for billing-api: Stop app was rejected"));
    assertEquals("STARTED", state.selected(ApplicationCatalog.App.class).state());
  }

  private static void type(ShellState state, String input) {
    input.codePoints().forEach(state::type);
  }

  private static String render(ShellState state) {
    Rect area = new Rect(0, 0, 120, 28);
    Buffer buffer = Buffer.empty(area);
    ShellView.render(state, () -> {})
        .render(Frame.forTesting(buffer), area, DefaultRenderContext.createEmpty());
    StringBuilder result = new StringBuilder();
    for (int y = 0; y < area.height(); y++) {
      for (int x = 0; x < area.width(); x++) {
        result.append(buffer.get(x, y).symbol());
      }
      result.append('\n');
    }
    return result.toString();
  }
}
