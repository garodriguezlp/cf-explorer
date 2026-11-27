package cf.explorer.export;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cf.explorer.catalog.ApplicationCatalog;
import cf.explorer.catalog.CloudFoundryCatalogClient;
import cf.explorer.config.EnvProcessor;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnvExportTest {
  @TempDir Path temp;

  @Test
  void writesSortedQuotedExcludedAndPostProcessedEnvFile() throws Exception {
    var app = new ApplicationCatalog.App("g1", "my app/v1", "STARTED", "s1", "dev", "o1", "org",
        "2024-01-01", "2024-01-02");
    Map<String, String> vars = new LinkedHashMap<>();
    vars.put("Z_KEY", "plain");
    vars.put("A_EMPTY", "");
    vars.put("GREETING", "it's alive!");
    vars.put("QUOTED", "\"already quoted\"");
    vars.put("TRUSTSTORE", "should-be-excluded");
    vars.put("truststore", "case-sensitive-kept");
    vars.put("SPRING_APPLICATION_JSON",
        "\"{\\\"host\\\":\\\"db.internal\\\",   \\\"port\\\":5432}\"");

    Path envsDir = temp.resolve("prod").resolve("envs");
    EnvFileWriter.WriteResult result = EnvFileWriter.write(
        app, vars, List.of("TRUSTSTORE"), Map.of("SPRING_APPLICATION_JSON", EnvProcessor.JSON),
        envsDir, LocalDateTime.of(2026, 3, 15, 9, 8, 7));

    assertEquals(envsDir.resolve("my-app-v1-20260315-090807.env"), result.path());
    assertEquals(6, result.writtenCount());
    assertEquals(List.of("TRUSTSTORE"), result.excludedKeys());
    assertEquals(List.of("SPRING_APPLICATION_JSON"), result.postProcessedKeys());

    String content = Files.readString(result.path());
    assertEquals(
        "A_EMPTY=''\n"
            + "GREETING='it'\\''s alive!'\n"
            + "QUOTED='already quoted'\n"
            + "SPRING_APPLICATION_JSON='{\"host\":\"db.internal\", \"port\":5432}'\n"
            + "Z_KEY='plain'\n"
            + "truststore='case-sensitive-kept'\n",
        content);
  }

  @Test
  void reportsClipboardOutcomesSeparatelyFromFileWriteAndHandlesWireMockEnvVars() throws Exception {
    Path mappings = Files.createDirectory(temp.resolve("mappings"));
    try (var files = Files.list(Path.of("wiremock", "mappings"))) {
      for (Path file : files.toList()) {
        Files.copy(file, mappings.resolve(file.getFileName()));
      }
    }
    WireMockServer server = new WireMockServer(wireMockConfig().dynamicPort()
        .usingFilesUnderDirectory(temp.toString()));
    server.start();
    try {
      CloudFoundryCatalogClient client = new CloudFoundryCatalogClient(
          server.baseUrl(), server.baseUrl(), "admin", "admin");
      ApplicationCatalog catalog = client.load();
      ApplicationCatalog.App app = catalog.apps().get(0);

      AtomicReference<String> copiedText = new AtomicReference<>();
      EnvExportService okClipboard = new EnvExportService(
          client::fetchEnvironmentVariables, temp.resolve("envs"),
          List.of("TRUSTSTORE"), Map.of("SPRING_APPLICATION_JSON", EnvProcessor.JSON),
          true, text -> { copiedText.set(text); return true; });
      EnvExportService.ExportResult ok = okClipboard.export(app);
      assertTrue(Files.exists(ok.path()));
      assertTrue(ok.clipboardRequested());
      assertTrue(ok.clipboardCopied());
      assertEquals(ok.path().toAbsolutePath().toString(), copiedText.get());
      assertEquals(List.of("TRUSTSTORE"), ok.excludedKeys());
      assertEquals(List.of("SPRING_APPLICATION_JSON"), ok.postProcessedKeys());
      String written = Files.readString(ok.path());
      assertFalse(written.contains("TRUSTSTORE="));
      assertTrue(written.contains("GREETING='it'\\''s alive!'"));

      EnvExportService failedClipboard = new EnvExportService(
          client::fetchEnvironmentVariables, temp.resolve("envs2"),
          List.of("TRUSTSTORE"), Map.of("SPRING_APPLICATION_JSON", EnvProcessor.JSON),
          true, text -> false);
      EnvExportService.ExportResult partial = failedClipboard.export(app);
      assertTrue(Files.exists(partial.path()));
      assertTrue(partial.clipboardRequested());
      assertFalse(partial.clipboardCopied());

      EnvExportService failingApi = new EnvExportService(
          guid -> { throw new IOException("API unavailable"); }, temp.resolve("envs3"),
          List.of(), Map.of(), true, text -> true);
      assertThrows(IOException.class, () -> failingApi.export(app));
    } finally {
      server.stop();
    }
  }
}
