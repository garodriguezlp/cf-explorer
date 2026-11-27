package cf.explorer.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cf.explorer.catalog.ApplicationCatalog;
import cf.explorer.catalog.CloudFoundryCatalogClient;
import cf.explorer.catalog.CloudFoundryException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AppDeletionServiceTest {
  private static final ApplicationCatalog.App APP = new ApplicationCatalog.App(
      "app-1", "billing-api", "STARTED", "space-1", "production", "org-1", "finance",
      "2024-01-01", "2024-01-02");

  @Test
  void pollsDeletionJobUntilCompleteThenRemovesAppFromCache() throws Exception {
    Deque<String> states = new ArrayDeque<>(List.of("PROCESSING", "POLLING", "COMPLETE"));
    List<String> removed = new ArrayList<>();
    AtomicInteger sleeps = new AtomicInteger();
    AppDeletionService service = new AppDeletionService(
        api("job-1", states), removed::add, Duration.ofMillis(1), 5,
        d -> sleeps.incrementAndGet());

    service.delete(APP, () -> true);

    assertEquals(List.of("app-1"), removed);
    assertEquals(2, sleeps.get());
  }

  @Test
  void deletionWithoutJobCompletesImmediately() throws Exception {
    List<String> removed = new ArrayList<>();
    AppDeletionService service = new AppDeletionService(
        api(null, new ArrayDeque<>()), removed::add, Duration.ofMillis(1), 1, d -> {});

    service.delete(APP, () -> true);

    assertEquals(List.of("app-1"), removed);
  }

  @Test
  void failedJobTimeoutAndCancellationKeepCacheIntact() {
    List<String> removed = new ArrayList<>();

    AppDeletionService failing = new AppDeletionService(
        api("job-1", new ArrayDeque<>(List.of("FAILED"))), removed::add,
        Duration.ofMillis(1), 5, d -> {});
    CloudFoundryException failed = assertThrows(CloudFoundryException.class,
        () -> failing.delete(APP, () -> true));
    assertEquals("Cloud Foundry could not delete billing-api. "
        + "Check the app's events in Cloud Foundry.", failed.getMessage());

    Deque<String> forever = new ArrayDeque<>(List.of("PROCESSING", "PROCESSING", "PROCESSING",
        "PROCESSING"));
    AppDeletionService slow = new AppDeletionService(
        api("job-1", forever), removed::add, Duration.ofMillis(1), 2, d -> {});
    CloudFoundryException timedOut = assertThrows(CloudFoundryException.class,
        () -> slow.delete(APP, () -> true));
    assertTrue(timedOut.getMessage().contains("still deleting billing-api"),
        timedOut.getMessage());

    AtomicInteger requests = new AtomicInteger();
    AppDeletionService.Api counting = new AppDeletionService.Api() {
      @Override
      public String deleteApp(String appGuid) {
        requests.incrementAndGet();
        return null;
      }

      @Override
      public CloudFoundryCatalogClient.JobResult getJob(String jobGuid) {
        throw new AssertionError("unexpected poll");
      }
    };
    AppDeletionService inactive = new AppDeletionService(
        counting, removed::add, Duration.ofMillis(1), 1, d -> {});
    assertThrows(CancellationException.class, () -> inactive.delete(APP, () -> false));
    assertEquals(0, requests.get());

    assertTrue(removed.isEmpty());
  }

  private static AppDeletionService.Api api(String jobGuid, Deque<String> states) {
    return new AppDeletionService.Api() {
      @Override
      public String deleteApp(String appGuid) {
        assertEquals("app-1", appGuid);
        return jobGuid;
      }

      @Override
      public CloudFoundryCatalogClient.JobResult getJob(String guid) {
        assertEquals(jobGuid, guid);
        String state = states.poll();
        return new CloudFoundryCatalogClient.JobResult(guid, state, "FAILED".equals(state)
            ? "CF-AppDeleteFailed" : null);
      }
    };
  }
}
