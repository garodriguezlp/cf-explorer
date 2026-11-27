package cf.explorer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cf.explorer.catalog.ApplicationCatalog;
import cf.explorer.export.EnvExportService;
import cf.explorer.keystore.KeystoreService;
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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
    assertTrue(footer.contains("Shortcuts:  Ctrl+E export  Ctrl+O open  Ctrl+K keystore"
        + "  Ctrl+R refresh  Ctrl+X quit"), footer);

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
