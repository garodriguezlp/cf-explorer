package cf.explorer.env;

import cf.explorer.catalog.ApplicationCatalog;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Loads an application's environment variables into an {@link EnvDraft} and applies staged deltas. */
public final class EnvEditorService {
  private static final Logger LOG = LoggerFactory.getLogger(EnvEditorService.class);
  @FunctionalInterface
  public interface EnvVarFetcher {
    Map<String, String> fetchEnvironmentVariables(String appGuid)
        throws IOException, InterruptedException;
  }

  @FunctionalInterface
  public interface EnvVarPatcher {
    Map<String, String> patchEnvironmentVariables(String appGuid, Map<String, String> changes)
        throws IOException, InterruptedException;
  }

  private final EnvVarFetcher fetcher;
  private final EnvVarPatcher patcher;

  public EnvEditorService(EnvVarFetcher fetcher, EnvVarPatcher patcher) {
    this.fetcher = Objects.requireNonNull(fetcher);
    this.patcher = Objects.requireNonNull(patcher);
  }

  public EnvDraft loadDraft(ApplicationCatalog.App app) throws IOException, InterruptedException {
    Objects.requireNonNull(app);
    Map<String, String> vars = fetcher.fetchEnvironmentVariables(app.guid());
    LOG.info("Loaded {} environment variables for app {} ({})", vars.size(), app.name(), app.guid());
    return new EnvDraft(vars);
  }

  public Map<String, String> apply(ApplicationCatalog.App app, EnvDraft draft)
      throws IOException, InterruptedException {
    Objects.requireNonNull(app);
    Objects.requireNonNull(draft);
    Map<String, String> patch = draft.toPatchMap();
    if (patch.isEmpty()) {
      return Map.of();
    }
    LOG.info("Applying {} environment changes ({}) to app {} ({})",
        patch.size(), draft.summaryBreakdown(), app.name(), app.guid());
    return patcher.patchEnvironmentVariables(app.guid(), patch);
  }
}
