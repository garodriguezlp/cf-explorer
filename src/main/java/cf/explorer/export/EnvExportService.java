package cf.explorer.export;

import cf.explorer.catalog.ApplicationCatalog;
import cf.explorer.config.EnvProcessor;
import cf.explorer.io.ClipboardSupport;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Fetches an app's environment variables, writes a {@code .env} file, and optionally copies the path. */
public final class EnvExportService {
  @FunctionalInterface
  public interface EnvVarFetcher {
    Map<String, String> fetchEnvironmentVariables(String appGuid)
        throws IOException, InterruptedException;
  }

  public record ExportResult(
      String appName,
      Path path,
      int writtenCount,
      List<String> excludedKeys,
      List<String> postProcessedKeys,
      boolean clipboardRequested,
      boolean clipboardCopied) {
    public ExportResult {
      Objects.requireNonNull(appName);
      Objects.requireNonNull(path);
      excludedKeys = List.copyOf(Objects.requireNonNull(excludedKeys));
      postProcessedKeys = List.copyOf(Objects.requireNonNull(postProcessedKeys));
    }
  }

  private final EnvVarFetcher fetcher;
  private final Path envsDir;
  private final List<String> excludeKeys;
  private final Map<String, EnvProcessor> postProcessors;
  private final boolean copyToClipboard;
  private final ClipboardSupport clipboard;

  public EnvExportService(EnvVarFetcher fetcher, Path envsDir, List<String> excludeKeys,
      Map<String, EnvProcessor> postProcessors, boolean copyToClipboard,
      ClipboardSupport clipboard) {
    this.fetcher = Objects.requireNonNull(fetcher);
    this.envsDir = Objects.requireNonNull(envsDir);
    this.excludeKeys = List.copyOf(Objects.requireNonNull(excludeKeys));
    this.postProcessors = Map.copyOf(Objects.requireNonNull(postProcessors));
    this.copyToClipboard = copyToClipboard;
    this.clipboard = Objects.requireNonNull(clipboard);
  }

  public ExportResult export(ApplicationCatalog.App app) throws IOException, InterruptedException {
    Objects.requireNonNull(app);
    Map<String, String> vars = fetcher.fetchEnvironmentVariables(app.guid());
    EnvFileWriter.WriteResult written =
        EnvFileWriter.write(app, vars, excludeKeys, postProcessors, envsDir);
    boolean copied = copyToClipboard && clipboard.copy(written.path().toAbsolutePath().toString());
    return new ExportResult(app.name(), written.path(), written.writtenCount(),
        written.excludedKeys(), written.postProcessedKeys(), copyToClipboard, copied);
  }
}
