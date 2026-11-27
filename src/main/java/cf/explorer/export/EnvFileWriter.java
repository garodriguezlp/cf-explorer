package cf.explorer.export;

import cf.explorer.catalog.ApplicationCatalog;
import cf.explorer.config.EnvProcessor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Formats and writes Cloud Foundry application environment variables to a shell-sourceable
 * {@code .env} file.
 */
public final class EnvFileWriter {
  private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

  private EnvFileWriter() {}

  public record WriteResult(
      Path path,
      int writtenCount,
      List<String> excludedKeys,
      List<String> postProcessedKeys) {
    public WriteResult {
      Objects.requireNonNull(path);
      excludedKeys = List.copyOf(Objects.requireNonNull(excludedKeys));
      postProcessedKeys = List.copyOf(Objects.requireNonNull(postProcessedKeys));
    }
  }

  public static WriteResult write(ApplicationCatalog.App app, Map<String, String> vars,
      List<String> excludeKeys, Map<String, EnvProcessor> postProcessors, Path envsDir)
      throws IOException {
    return write(app, vars, excludeKeys, postProcessors, envsDir, LocalDateTime.now());
  }

  public static WriteResult write(ApplicationCatalog.App app, Map<String, String> vars,
      List<String> excludeKeys, Map<String, EnvProcessor> postProcessors, Path envsDir,
      LocalDateTime timestamp) throws IOException {
    Objects.requireNonNull(app);
    Objects.requireNonNull(excludeKeys);
    Objects.requireNonNull(postProcessors);
    Objects.requireNonNull(envsDir);
    Objects.requireNonNull(timestamp);

    String safeName = sanitizeFilename(app.name());
    Files.createDirectories(envsDir);
    Path path = envsDir.resolve(safeName + "-" + TIMESTAMP.format(timestamp) + ".env");

    Map<String, String> entries = vars != null ? vars : Map.of();
    List<String> actualExcluded = entries.keySet().stream()
        .filter(excludeKeys::contains).sorted().toList();
    List<Map.Entry<String, String>> entriesToWrite = entries.entrySet().stream()
        .filter(entry -> !excludeKeys.contains(entry.getKey()))
        .sorted(Map.Entry.comparingByKey()).toList();
    List<String> actualPostProcessed = entriesToWrite.stream()
        .filter(entry -> postProcessors.containsKey(entry.getKey()))
        .map(Map.Entry::getKey).sorted().toList();

    String content = entriesToWrite.stream()
        .map(entry -> formatEntry(entry.getKey(), entry.getValue(), postProcessors))
        .collect(Collectors.joining("\n"));
    Files.writeString(path, content.isEmpty() ? "" : content + "\n", StandardCharsets.UTF_8);
    return new WriteResult(path, entriesToWrite.size(), actualExcluded, actualPostProcessed);
  }

  public static String sanitizeFilename(String name) {
    String sanitized = name.replaceAll("[^A-Za-z0-9._-]", "-").replaceAll("-+", "-");
    return sanitized.isBlank() ? "app" : sanitized;
  }

  static String formatEntry(String key, String value, Map<String, EnvProcessor> postProcessors) {
    EnvProcessor processor = postProcessors.get(key);
    if (processor != null) {
      return key + "=" + wrapInSingleQuotes(processor.process(value));
    }
    return key + "=" + escapeEnvValue(value);
  }

  public static String escapeEnvValue(String value) {
    if (value == null || value.isEmpty()) {
      return "''";
    }
    String stripped = value.replaceAll("^\"+|\"+$", "");
    return wrapInSingleQuotes(stripped);
  }

  public static String wrapInSingleQuotes(String value) {
    if (value == null || value.isEmpty()) {
      return "''";
    }
    return "'" + value.replace("'", "'\\''") + "'";
  }
}
