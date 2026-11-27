package cf.explorer.lifecycle;

import cf.explorer.catalog.ApplicationCatalog;
import cf.explorer.catalog.CloudFoundryCatalogClient;
import cf.explorer.catalog.CloudFoundryException;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates Cloud Foundry v3 application lifecycle operations ({@code start}, {@code stop},
 * {@code restart}, and multi-step {@code restage}) and synchronizes the local catalog cache.
 */
public final class AppLifecycleService {
  private static final Logger LOG = LoggerFactory.getLogger(AppLifecycleService.class);
  private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(2);
  private static final int DEFAULT_MAX_POLLS = 150;

  /** Cloud Foundry lifecycle actions exposed as shell commands. */
  public enum Operation {
    START("start", 'u', "Start", "Starting", "Started", "STARTING"),
    STOP("stop", 'p', "Stop", "Stopping", "Stopped", "STOPPING"),
    RESTART("restart", 't', "Restart", "Restarting", "Restarted", "RESTARTING"),
    RESTAGE("restage", 'g', "Restage", "Restaging", "Restaged", "STAGING");

    private final String commandName;
    private final char shortcut;
    private final String verb;
    private final String presentParticiple;
    private final String pastParticiple;
    private final String badgeLabel;

    Operation(String commandName, char shortcut, String verb, String presentParticiple,
        String pastParticiple, String badgeLabel) {
      this.commandName = commandName;
      this.shortcut = shortcut;
      this.verb = verb;
      this.presentParticiple = presentParticiple;
      this.pastParticiple = pastParticiple;
      this.badgeLabel = badgeLabel;
    }

    public String commandName() { return commandName; }
    public char shortcut() { return shortcut; }
    public String verb() { return verb; }
    public String presentParticiple() { return presentParticiple; }
    public String pastParticiple() { return pastParticiple; }
    public String badgeLabel() { return badgeLabel; }
  }

  /** Low-level Cloud Foundry v3 lifecycle operations. */
  public interface Api {
    CloudFoundryCatalogClient.AppStateResult startApp(String appGuid)
        throws IOException, InterruptedException;

    CloudFoundryCatalogClient.AppStateResult stopApp(String appGuid)
        throws IOException, InterruptedException;

    CloudFoundryCatalogClient.AppStateResult restartApp(String appGuid)
        throws IOException, InterruptedException;

    String findLatestReadyPackageGuid(String appGuid) throws IOException, InterruptedException;

    CloudFoundryCatalogClient.BuildResult createBuild(String packageGuid)
        throws IOException, InterruptedException;

    CloudFoundryCatalogClient.BuildResult getBuild(String buildGuid)
        throws IOException, InterruptedException;

    void setCurrentDroplet(String appGuid, String dropletGuid)
        throws IOException, InterruptedException;

    static Api fromClient(CloudFoundryCatalogClient client) {
      Objects.requireNonNull(client);
      return new Api() {
        @Override
        public CloudFoundryCatalogClient.AppStateResult startApp(String appGuid)
            throws IOException, InterruptedException {
          return client.startApp(appGuid);
        }

        @Override
        public CloudFoundryCatalogClient.AppStateResult stopApp(String appGuid)
            throws IOException, InterruptedException {
          return client.stopApp(appGuid);
        }

        @Override
        public CloudFoundryCatalogClient.AppStateResult restartApp(String appGuid)
            throws IOException, InterruptedException {
          return client.restartApp(appGuid);
        }

        @Override
        public String findLatestReadyPackageGuid(String appGuid)
            throws IOException, InterruptedException {
          return client.findLatestReadyPackageGuid(appGuid);
        }

        @Override
        public CloudFoundryCatalogClient.BuildResult createBuild(String packageGuid)
            throws IOException, InterruptedException {
          return client.createBuild(packageGuid);
        }

        @Override
        public CloudFoundryCatalogClient.BuildResult getBuild(String buildGuid)
            throws IOException, InterruptedException {
          return client.getBuild(buildGuid);
        }

        @Override
        public void setCurrentDroplet(String appGuid, String dropletGuid)
            throws IOException, InterruptedException {
          client.setCurrentDroplet(appGuid, dropletGuid);
        }
      };
    }
  }

  /** Injectable sleep strategy for build polling. */
  @FunctionalInterface
  public interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
  }

  private final Api api;
  private final Consumer<JsonNode> cacheUpdater;
  private final Duration pollInterval;
  private final int maxPolls;
  private final Sleeper sleeper;

  public AppLifecycleService(CloudFoundryCatalogClient client, Consumer<JsonNode> cacheUpdater) {
    this(Api.fromClient(client), cacheUpdater, DEFAULT_POLL_INTERVAL, DEFAULT_MAX_POLLS,
        duration -> Thread.sleep(Math.max(1L, duration.toMillis())));
  }

  public AppLifecycleService(Api api, Consumer<JsonNode> cacheUpdater, Duration pollInterval,
      int maxPolls, Sleeper sleeper) {
    this.api = Objects.requireNonNull(api);
    this.cacheUpdater = Objects.requireNonNull(cacheUpdater);
    this.pollInterval = Objects.requireNonNull(pollInterval);
    if (maxPolls < 1) {
      throw new IllegalArgumentException("maxPolls must be >= 1");
    }
    this.maxPolls = maxPolls;
    this.sleeper = Objects.requireNonNull(sleeper);
  }

  public ApplicationCatalog.App execute(Operation operation, ApplicationCatalog.App app,
      Consumer<String> stepListener, BooleanSupplier active)
      throws IOException, InterruptedException {
    Objects.requireNonNull(operation);
    Objects.requireNonNull(app);
    Objects.requireNonNull(stepListener);
    Objects.requireNonNull(active);
    LOG.info("{} initiated for app {} ({})", operation.verb(), app.name(), app.guid());
    ApplicationCatalog.App updated = switch (operation) {
      case START -> applyResult(app, api.startApp(app.guid()));
      case STOP -> applyResult(app, api.stopApp(app.guid()));
      case RESTART -> applyResult(app, api.restartApp(app.guid()));
      case RESTAGE -> restage(app, stepListener, active);
    };
    LOG.info("{} completed for app {} ({}) -> {}", operation.verb(), app.name(), app.guid(),
        updated.state());
    return updated;
  }

  public ApplicationCatalog.App start(ApplicationCatalog.App app)
      throws IOException, InterruptedException {
    return execute(Operation.START, app, step -> {}, () -> true);
  }

  public ApplicationCatalog.App stop(ApplicationCatalog.App app)
      throws IOException, InterruptedException {
    return execute(Operation.STOP, app, step -> {}, () -> true);
  }

  public ApplicationCatalog.App restart(ApplicationCatalog.App app)
      throws IOException, InterruptedException {
    return execute(Operation.RESTART, app, step -> {}, () -> true);
  }

  public ApplicationCatalog.App restage(ApplicationCatalog.App app, Consumer<String> stepListener,
      BooleanSupplier active) throws IOException, InterruptedException {
    Objects.requireNonNull(app);
    Objects.requireNonNull(stepListener);
    Objects.requireNonNull(active);
    checkActive(active);
    stepListener.accept("STAGING 1/4");
    String packageGuid = api.findLatestReadyPackageGuid(app.guid());

    checkActive(active);
    stepListener.accept("STAGING 2/4");
    CloudFoundryCatalogClient.BuildResult build = api.createBuild(packageGuid);
    LOG.info("Staging build {} created from package {} for app {}",
        build.guid(), packageGuid, app.name());
    int polls = 0;
    while ("STAGING".equalsIgnoreCase(build.state())) {
      if (polls++ >= maxPolls) {
        throw new CloudFoundryException("Staging timed out while waiting for build to finish.");
      }
      checkActive(active);
      sleeper.sleep(pollInterval);
      checkActive(active);
      build = api.getBuild(build.guid());
    }
    LOG.info("Build {} finished with state={} after {} polls (droplet={})",
        build.guid(), build.state(), polls, build.dropletGuid());
    if (!"STAGED".equalsIgnoreCase(build.state())) {
      throw new CloudFoundryException(
          "Staging failed for " + app.name() + ". Check build logs in Cloud Foundry.");
    }
    if (build.dropletGuid() == null || build.dropletGuid().isBlank()) {
      throw new CloudFoundryException("Staged build did not produce a droplet.");
    }

    checkActive(active);
    stepListener.accept("STAGING 3/4");
    api.setCurrentDroplet(app.guid(), build.dropletGuid());

    checkActive(active);
    stepListener.accept("STAGING 4/4");
    return applyResult(app, api.restartApp(app.guid()));
  }

  private ApplicationCatalog.App applyResult(ApplicationCatalog.App app,
      CloudFoundryCatalogClient.AppStateResult result) {
    if (result.rawApp() != null) {
      cacheUpdater.accept(result.rawApp());
    }
    return app.withState(result.state(), result.updatedAt());
  }

  private static void checkActive(BooleanSupplier active) {
    if (!active.getAsBoolean()) {
      throw new CancellationException("Operation cancelled");
    }
  }
}
