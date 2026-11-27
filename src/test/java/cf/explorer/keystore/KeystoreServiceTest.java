package cf.explorer.keystore;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cf.explorer.catalog.ApplicationCatalog;
import cf.explorer.catalog.CloudFoundryCatalogClient;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KeystoreServiceTest {
  @TempDir Path temp;

  private static final ApplicationCatalog.App APP = new ApplicationCatalog.App(
      "app-guid-0001", "customer-api", "STARTED", "space-1", "prod", "org-1", "team",
      "2024-01-01", "2024-01-02");

  @Test
  void decodesSavesWithoutPasswordInFilenameAndInspectsWireMockKeystore() throws Exception {
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
      KeystoreService service = new KeystoreService(
          client::fetchEnvironmentVariables, "KEYSTORE", "KEYSTORE_PASSWORD",
          temp.resolve("jks"), true, text -> true);

      KeystoreService.InspectResult result =
          service.exportAndInspect(APP, LocalDateTime.of(2026, 4, 1, 10, 20, 30));

      assertTrue(result.inspected());
      assertTrue(Files.exists(result.jksPath()));
      assertEquals("customer-api-20260401-102030.jks", result.jksPath().getFileName().toString());
      assertFalse(result.jksPath().toString().contains("changeit"));
      assertTrue(result.clipboardCopied());
      assertFalse(result.entries().isEmpty());
      assertTrue(result.entries().stream().anyMatch(entry -> !entry.alias().isBlank()
          && !entry.subject().isBlank() && !entry.issuer().isBlank()));
    } finally {
      server.stop();
    }
  }

  @Test
  void reportsMissingVariablesInvalidBase64AndPartialSuccessOnBadPassword() throws Exception {
    KeystoreService missingVar = new KeystoreService(
        guid -> Map.of(), "KEYSTORE", "KEYSTORE_PASSWORD", temp.resolve("jks1"), true, t -> true);
    var missingEx = assertThrows(KeystoreService.SafeKeystoreException.class,
        () -> missingVar.exportAndInspect(APP));
    assertTrue(missingEx.getMessage().contains("KEYSTORE"));

    KeystoreService badBase64 = new KeystoreService(
        guid -> Map.of("KEYSTORE", "!!!not-base64!!!", "KEYSTORE_PASSWORD", "Y2hhbmdlaXQ="),
        "KEYSTORE", "KEYSTORE_PASSWORD", temp.resolve("jks2"), true, t -> true);
    var base64Ex = assertThrows(KeystoreService.SafeKeystoreException.class,
        () -> badBase64.exportAndInspect(APP));
    assertTrue(base64Ex.getMessage().contains("KEYSTORE"));
    assertFalse(base64Ex.getMessage().contains("!!!not-base64!!!"));

    String validBase64NotJks = Base64.getEncoder().encodeToString("not-a-jks".getBytes(StandardCharsets.UTF_8));
    String secretPass = Base64.getEncoder().encodeToString("super-secret-pw".getBytes(StandardCharsets.UTF_8));
    KeystoreService badJks = new KeystoreService(
        guid -> Map.of("KEYSTORE", validBase64NotJks, "KEYSTORE_PASSWORD", secretPass),
        "KEYSTORE", "KEYSTORE_PASSWORD", temp.resolve("jks3"), true, t -> false);

    KeystoreService.InspectResult partial = badJks.exportAndInspect(APP);
    assertTrue(Files.exists(partial.jksPath()));
    assertFalse(partial.jksPath().toString().contains("super-secret-pw"));
    assertTrue(partial.inspectionFailed());
    assertTrue(partial.entries().isEmpty());
    assertTrue(partial.clipboardRequested());
    assertFalse(partial.clipboardCopied());
  }
}
