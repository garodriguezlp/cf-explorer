package cf.explorer.lifecycle;

import cf.explorer.catalog.ApplicationCatalog;
import cf.explorer.catalog.CloudFoundryCatalogClient;
import cf.explorer.catalog.CloudFoundryException;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deletes Cloud Foundry applications via v3 {@code DELETE /v3/apps/:guid}, waits for the
 * asynchronous deletion job to finish, and removes the app from the local catalog cache.
 */
public final class AppDeletionService {
  private static final Logger LOG = LoggerFactory.getLogger(AppDeletionService.class);
  private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(2);
  private static final int DEFAULT_MAX_POLLS = 150;

  /** Low-level Cloud Foundry v3 deletion operations. */
  public interface Api {
    /** Requests deletion and returns the asynchronous job GUID, or {@code null} if none. */
    String deleteApp(String appGuid) throws IOException, InterruptedException;

    CloudFoundryCatalogClient.JobResult getJob(String jobGuid)
        throws IOException, InterruptedException;

    static Api fromClient(CloudFoundryCatalogClient client) {
      Objects.requireNonNull(client);
      return new Api() {
        @Override
        public String deleteApp(String appGuid) throws IOException, InterruptedException {
          return client.deleteApp(appGuid);
        }

        @Override
        public CloudFoundryCatalogClient.JobResult getJob(String jobGuid)
            throws IOException, InterruptedException {
          return client.getJob(jobGuid);
        }
      };
    }
  }

  private final Api api;
  private final Consumer<String> cacheRemover;
  private final Duration pollInterval;
  private final int maxPolls;
  private final AppLifecycleService.Sleeper sleeper;

  public AppDeletionService(CloudFoundryCatalogClient client, Consumer<String> cacheRemover) {
    this(Api.fromClient(client), cacheRemover, DEFAULT_POLL_INTERVAL, DEFAULT_MAX_POLLS,
        duration -> Thread.sleep(Math.max(1L, duration.toMillis())));
  }

  public AppDeletionService(Api api, Consumer<String> cacheRemover, Duration pollInterval,
      int maxPolls, AppLifecycleService.Sleeper sleeper) {
    this.api = Objects.requireNonNull(api);
    this.cacheRemover = Objects.requireNonNull(cacheRemover);
    this.pollInterval = Objects.requireNonNull(pollInterval);
    if (maxPolls < 1) {
      throw new IllegalArgumentException("maxPolls must be >= 1");
    }
    this.maxPolls = maxPolls;
    this.sleeper = Objects.requireNonNull(sleeper);
  }

  /**
   * Deletes {@code app} and blocks until Cloud Foundry reports the deletion job complete.
   * {@code active} stops polling (with {@link CancellationException}) when the session ends.
   */
  public void delete(ApplicationCatalog.App app, BooleanSupplier active)
      throws IOException, InterruptedException {
    Objects.requireNonNull(app);
    Objects.requireNonNull(active);
    checkActive(active);
    LOG.info("Delete initiated for app {} ({}) in {} / {}",
        app.name(), app.guid(), app.orgName(), app.spaceName());
    String jobGuid = api.deleteApp(app.guid());
    if (jobGuid != null) {
      CloudFoundryCatalogClient.JobResult job = api.getJob(jobGuid);
      int polls = 0;
      while (!isTerminal(job.state())) {
        if (polls++ >= maxPolls) {
          throw new CloudFoundryException("Cloud Foundry is still deleting " + app.name()
              + ". Run /refresh later to check.");
        }
        checkActive(active);
        sleeper.sleep(pollInterval);
        checkActive(active);
        job = api.getJob(jobGuid);
      }
      if ("FAILED".equalsIgnoreCase(job.state())) {
        LOG.warn("Delete job {} failed for app {} ({}): {}",
            jobGuid, app.name(), app.guid(), job.errorTitle());
        throw new CloudFoundryException("Cloud Foundry could not delete " + app.name()
            + ". Check the app's events in Cloud Foundry.");
      }
      LOG.info("Delete job {} completed after {} polls", jobGuid, polls);
    }
    cacheRemover.accept(app.guid());
    LOG.info("Delete completed for app {} ({})", app.name(), app.guid());
  }

  private static boolean isTerminal(String state) {
    return "COMPLETE".equalsIgnoreCase(state) || "FAILED".equalsIgnoreCase(state);
  }

  private static void checkActive(BooleanSupplier active) {
    if (!active.getAsBoolean()) {
      throw new CancellationException("Operation cancelled");
    }
  }
}
