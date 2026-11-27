package cf.explorer.env;

import cf.explorer.catalog.ApplicationCatalog;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/** Loads an application's environment variables into an {@link EnvDraft} and applies staged deltas. */
public final class EnvEditorService {
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
    return new EnvDraft(fetcher.fetchEnvironmentVariables(app.guid()));
  }

  public Map<String, String> apply(ApplicationCatalog.App app, EnvDraft draft)
      throws IOException, InterruptedException {
    Objects.requireNonNull(app);
    Objects.requireNonNull(draft);
    Map<String, String> patch = draft.toPatchMap();
    if (patch.isEmpty()) {
      return Map.of();
    }
    return patcher.patchEnvironmentVariables(app.guid(), patch);
  }
}
