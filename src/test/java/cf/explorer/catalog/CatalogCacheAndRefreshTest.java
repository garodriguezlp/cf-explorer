package cf.explorer.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cf.explorer.ui.ApplicationCatalogPage;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CatalogCacheAndRefreshTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path temp;

  @Test
  void isolatesProfileCachesAndHandlesHitsMissesFreshAndCorruptFiles() throws Exception {
    Path devCache = temp.resolve("dev").resolve("cache");
    Path prodCache = temp.resolve("prod").resolve("cache");

    AtomicInteger devCalls = new AtomicInteger();
    CachedCatalogService devService = new CachedCatalogService(() -> {
      devCalls.incrementAndGet();
      return snapshot("dev-app-" + devCalls.get());
    }, new CatalogCacheStore(devCache));

    AtomicInteger prodCalls = new AtomicInteger();
    CachedCatalogService prodService = new CachedCatalogService(() -> {
      prodCalls.incrementAndGet();
      return snapshot("prod-app-" + prodCalls.get());
    }, new CatalogCacheStore(prodCache));

    // Cache miss loads live and writes files compatible with main
    ApplicationCatalog first = devService.load(false);
    assertEquals(1, devCalls.get());
    assertEquals("dev-app-1", first.apps().get(0).name());
    assertTrue(Files.exists(devCache.resolve("organizations.json")));
    assertTrue(Files.exists(devCache.resolve("spaces.json")));
    assertTrue(Files.exists(devCache.resolve("apps.json")));

    // Cache hit avoids live call
    ApplicationCatalog cached = devService.load(false);
    assertEquals(1, devCalls.get());
    assertEquals("dev-app-1", cached.apps().get(0).name());
    List<String> reported = new ArrayList<>();
    devService.load(false, new CatalogProgress() {
      @Override public void completed(Resource resource, int count) {
        reported.add(resource + " " + count);
      }
    });
    assertEquals(List.of("ORGANIZATIONS 1", "SPACES 1", "APPS 1"), reported);
    assertEquals(1, devCalls.get());

    // Separate profile is isolated
    ApplicationCatalog prod = prodService.load(false);
    assertEquals(1, prodCalls.get());
    assertEquals("prod-app-1", prod.apps().get(0).name());

    // --fresh bypasses cache and updates it
    ApplicationCatalog fresh = devService.load(true);
    assertEquals(2, devCalls.get());
    assertEquals("dev-app-2", fresh.apps().get(0).name());
    assertEquals("dev-app-2", devService.load(false).apps().get(0).name());
    assertEquals(2, devCalls.get());

    // Corrupt cache falls back to live
    Files.writeString(devCache.resolve("apps.json"), "{not-valid-json");
    ApplicationCatalog recovered = devService.load(false);
    assertEquals(3, devCalls.get());
    assertEquals("dev-app-3", recovered.apps().get(0).name());

    // Failed refresh preserves existing on-disk cache
    CachedCatalogService failingRefresh = new CachedCatalogService(() -> {
      throw new IOException("network down");
    }, new CatalogCacheStore(devCache));
    assertThrows(IOException.class, failingRefresh::refresh);
    assertEquals("dev-app-3", devService.load(false).apps().get(0).name());
  }

  @Test
  void refreshPreservesFilterAndSelectionKeepsPreviousOnFailureAndDiscardsStaleLoads() {
    var app1 = new ApplicationCatalog.App("guid-1", "alpha", "STARTED", "s1", "dev", "o1", "org",
        "2024-01-01", "2024-01-02");
    var app2 = new ApplicationCatalog.App("guid-2", "alpha-two", "STARTED", "s1", "dev", "o1",
        "org", "2024-01-01", "2024-01-02");
    var app2Renamed = new ApplicationCatalog.App("guid-2", "alpha-two-renamed", "STOPPED", "s1",
        "dev", "o1", "org", "2024-01-01", "2024-01-03");
    var app3 = new ApplicationCatalog.App("guid-3", "alpha-three", "STARTED", "s1", "dev", "o1",
        "org", "2024-01-01", "2024-01-04");

    Queue<Runnable> worker = new ArrayDeque<>();
    Queue<Runnable> dispatch = new ArrayDeque<>();
    AtomicReference<String> status = new AtomicReference<>("");

    ApplicationCatalogPage page = new ApplicationCatalogPage();
    page.loaded(new ApplicationCatalog(1, 1, List.of(app1, app2)));
    for (char c : "alpha".toCharArray()) {
      page.handleKey(KeyEvent.ofChar(c));
    }
    page.handleKey(KeyEvent.ofKey(KeyCode.DOWN));
    assertEquals("guid-2", page.selected().guid());

    // Failed refresh keeps previous catalog, filter, and selection
    page.startRefresh(() -> { throw new IllegalStateException("secret failure"); },
        worker::add, dispatch::add, () -> true, status::set);
    assertEquals("Refreshing catalog...", status.get());
    worker.remove().run();
    dispatch.remove().run();
    assertEquals("guid-2", page.selected().guid());
    assertEquals("alpha", page.inputValue());
    assertEquals("Catalog refresh failed: Unexpected error (IllegalStateException). "
        + "Keeping previous catalog.", status.get());
    assertFalse(status.get().contains("secret"));

    // Overlapping refreshes: older completion must not overwrite newer result
    page.startRefresh(() -> new ApplicationCatalog(1, 1, List.of(app1)),
        worker::add, dispatch::add, () -> true, status::set);
    page.startRefresh(() -> new ApplicationCatalog(1, 1, List.of(app1, app2Renamed)),
        worker::add, dispatch::add, () -> true, status::set);
    Runnable firstWork = worker.remove();
    Runnable secondWork = worker.remove();
    secondWork.run();
    firstWork.run();
    dispatch.remove().run(); // newer finishes first
    assertEquals("guid-2", page.selected().guid());
    assertEquals("alpha-two-renamed", page.selected().name());
    dispatch.remove().run(); // stale completion ignored
    assertEquals("guid-2", page.selected().guid());
    assertEquals("alpha-two-renamed", page.selected().name());

    // Disappearing selection falls back to first visible app
    page.startRefresh(() -> new ApplicationCatalog(1, 1, List.of(app3)),
        worker::add, dispatch::add, () -> true, status::set);
    worker.remove().run();
    dispatch.remove().run();
    assertEquals("guid-3", page.selected().guid());
    assertEquals("Catalog refreshed.", status.get());
  }

  @Test
  void removesDeletedAppFromCacheOnlyWhenPresent() throws Exception {
    CatalogCacheStore store = new CatalogCacheStore(temp.resolve("cache"));
    CachedCatalogService service = new CachedCatalogService(() -> snapshot("doomed"), store);
    service.removeCachedApp("a1"); // no cache yet: nothing to do
    service.load(false);
    service.removeCachedApp("missing");
    assertEquals(1, store.readIfPresent().orElseThrow().apps().size());
    service.removeCachedApp("a1");
    assertTrue(store.readIfPresent().orElseThrow().apps().isEmpty());
  }

  private static CloudFoundryCatalogClient.CatalogSnapshot snapshot(String appName)
      throws IOException {
    return new CloudFoundryCatalogClient.CatalogSnapshot(
        List.of(JSON.readTree("{\"guid\":\"o1\",\"name\":\"org-one\"}")),
        List.of(JSON.readTree("{\"guid\":\"s1\",\"name\":\"space-one\","
            + "\"relationships\":{\"organization\":{\"data\":{\"guid\":\"o1\"}}}}")),
        List.of(JSON.readTree("{\"guid\":\"a1\",\"name\":\"" + appName + "\","
            + "\"state\":\"STARTED\",\"created_at\":\"2024-01-01T00:00:00Z\","
            + "\"updated_at\":\"2024-01-02T00:00:00Z\","
            + "\"relationships\":{\"space\":{\"data\":{\"guid\":\"s1\"}}}}")));
  }
}
