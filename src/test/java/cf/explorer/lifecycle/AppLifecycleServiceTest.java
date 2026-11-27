package cf.explorer.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cf.explorer.catalog.ApplicationCatalog;
import cf.explorer.catalog.CatalogCacheStore;
import cf.explorer.catalog.CloudFoundryCatalogClient;
import cf.explorer.catalog.CloudFoundryException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppLifecycleServiceTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  private static final ApplicationCatalog.App APP = new ApplicationCatalog.App(
      "a1", "billing-api", "STARTED", "s1", "production", "o1", "finance",
      "2024-01-01T00:00:00Z", "2024-01-02T00:00:00Z");

  @TempDir Path temp;

  @Test
  void startStopAndRestartUpdateAppAndPatchDiskCachePreservingRelationships() throws Exception {
    Path cacheDir = temp.resolve("cache");
    CatalogCacheStore store = new CatalogCacheStore(cacheDir);
    store.write(new CloudFoundryCatalogClient.CatalogSnapshot(
        List.of(JSON.readTree("{\"guid\":\"o1\",\"name\":\"finance\"}")),
        List.of(JSON.readTree("{\"guid\":\"s1\",\"name\":\"production\","
            + "\"relationships\":{\"organization\":{\"data\":{\"guid\":\"o1\"}}}}")),
        List.of(JSON.readTree("{\"guid\":\"a1\",\"name\":\"billing-api\","
            + "\"state\":\"STARTED\",\"created_at\":\"2024-01-01T00:00:00Z\","
            + "\"updated_at\":\"2024-01-02T00:00:00Z\","
            + "\"relationships\":{\"space\":{\"data\":{\"guid\":\"s1\"}}}}"))));

    StubApi api = new StubApi();
    AppLifecycleService service = new AppLifecycleService(
        api,
        node -> {
          try {
            store.updateApp(node);
          } catch (IOException ex) {
            throw new IllegalStateException(ex);
          }
        },
        Duration.ofMillis(1),
        5,
        d -> {});

    ApplicationCatalog.App stopped = service.stop(APP);
    assertEquals("STOPPED", stopped.state());
    assertEquals("2026-05-01T10:00:00Z", stopped.updatedAt());

    ApplicationCatalog cachedAfterStop = store.readIfPresent().orElseThrow();
    assertEquals("STOPPED", cachedAfterStop.apps().get(0).state());
    assertEquals("finance", cachedAfterStop.apps().get(0).orgName());
    assertEquals("production", cachedAfterStop.apps().get(0).spaceName());

    ApplicationCatalog.App started = service.start(stopped);
    assertEquals("STARTED", started.state());
    assertEquals("STARTED", store.readIfPresent().orElseThrow().apps().get(0).state());

    ApplicationCatalog.App restarted = service.restart(started);
    assertEquals("STARTED", restarted.state());
    assertEquals("2026-05-01T10:02:00Z", restarted.updatedAt());
  }

  @Test
  void restageStagesPollsAssignsDropletRestartsAndHandlesFailuresAndCancellation() throws Exception {
    StubApi api = new StubApi();
    AtomicInteger sleeps = new AtomicInteger();
    List<String> steps = new ArrayList<>();
    AppLifecycleService service = new AppLifecycleService(
        api, node -> {}, Duration.ofMillis(1), 5, d -> sleeps.incrementAndGet());

    ApplicationCatalog.App restaged = service.restage(APP, steps::add, () -> true);
    assertEquals(List.of("STAGING 1/4", "STAGING 2/4", "STAGING 3/4", "STAGING 4/4"), steps);
    assertEquals(2, sleeps.get());
    assertEquals("droplet-99", api.assignedDropletGuid);
    assertEquals("STARTED", restaged.state());

    // Build failure surfaces safe CloudFoundryException without raw error details
    api.finalBuildState = "FAILED";
    CloudFoundryException failed = assertThrows(CloudFoundryException.class,
        () -> service.restage(APP, s -> {}, () -> true));
    assertTrue(failed.getMessage().contains("Staging failed for billing-api"));

    // Timeout after maxPolls
    api.alwaysStaging = true;
    CloudFoundryException timedOut = assertThrows(CloudFoundryException.class,
        () -> service.restage(APP, s -> {}, () -> true));
    assertTrue(timedOut.getMessage().contains("Staging timed out"));

    // Cancellation during poll halts before droplet assignment or restart
    api.alwaysStaging = false;
    api.finalBuildState = "STAGED";
    api.assignedDropletGuid = null;
    AtomicBoolean active = new AtomicBoolean(true);
    AppLifecycleService cancelling = new AppLifecycleService(
        api, node -> {}, Duration.ofMillis(1), 5, d -> active.set(false));
    assertThrows(CancellationException.class,
        () -> cancelling.restage(APP, s -> {}, active::get));
    assertEquals(null, api.assignedDropletGuid);
  }

  private static final class StubApi implements AppLifecycleService.Api {
    private int pollCount;
    private String finalBuildState = "STAGED";
    private boolean alwaysStaging;
    private String assignedDropletGuid;

    @Override
    public CloudFoundryCatalogClient.AppStateResult startApp(String appGuid) throws IOException {
      return result(appGuid, "STARTED", "2026-05-01T10:01:00Z");
    }

    @Override
    public CloudFoundryCatalogClient.AppStateResult stopApp(String appGuid) throws IOException {
      return result(appGuid, "STOPPED", "2026-05-01T10:00:00Z");
    }

    @Override
    public CloudFoundryCatalogClient.AppStateResult restartApp(String appGuid) throws IOException {
      return result(appGuid, "STARTED", "2026-05-01T10:02:00Z");
    }

    @Override
    public String findLatestReadyPackageGuid(String appGuid) {
      return "pkg-1";
    }

    @Override
    public CloudFoundryCatalogClient.BuildResult createBuild(String packageGuid) {
      pollCount = 0;
      return new CloudFoundryCatalogClient.BuildResult("build-1", "STAGING", null);
    }

    @Override
    public CloudFoundryCatalogClient.BuildResult getBuild(String buildGuid) {
      pollCount++;
      if (alwaysStaging || pollCount < 2) {
        return new CloudFoundryCatalogClient.BuildResult(buildGuid, "STAGING", null);
      }
      return new CloudFoundryCatalogClient.BuildResult(
          buildGuid, finalBuildState, "STAGED".equals(finalBuildState) ? "droplet-99" : null);
    }

    @Override
    public void setCurrentDroplet(String appGuid, String dropletGuid) {
      this.assignedDropletGuid = dropletGuid;
    }

    private static CloudFoundryCatalogClient.AppStateResult result(String guid, String state,
        String updatedAt) throws IOException {
      JsonNode raw = JSON.readTree("{\"guid\":\"" + guid + "\",\"state\":\"" + state
          + "\",\"updated_at\":\"" + updatedAt + "\"}");
      return new CloudFoundryCatalogClient.AppStateResult(guid, state, updatedAt, raw);
    }
  }
}
