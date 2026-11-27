package cf.explorer.catalog;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

import cf.explorer.io.LogSetup;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CloudFoundryCatalogClientTest {
  @TempDir Path temp;

  @Test
  void loadsUnmodifiedMockServerMappings() throws Exception {
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
      ApplicationCatalog catalog = client(server, "admin").load();
      assertEquals(4, catalog.organizations());
      assertEquals(7, catalog.spaces());
      assertEquals(16, catalog.apps().size());
      var app = catalog.apps().get(0);
      assertEquals("app-guid-0001", app.guid());
      assertEquals("platform-engineering", app.orgName());
      assertEquals("production", app.spaceName());
      assertEquals("app-guid-0001", catalog.filter("CUSTOMER production 2024-03").get(0).guid());
      assertTrue(catalog.filter("customer missing").isEmpty());
      List<String> events = new CopyOnWriteArrayList<>();
      client(server, "admin").loadSnapshot(new CatalogProgress() {
        @Override public void authenticated() { events.add("auth"); }
        @Override public void fetched(Resource resource, int fetched, int total) {
          events.add(resource + " " + fetched + "/" + total);
        }
        @Override public void completed(Resource resource, int count) {
          events.add(resource + " done " + count);
        }
      });
      assertEquals("auth", events.get(0));
      assertTrue(events.containsAll(List.of("ORGANIZATIONS 4/4", "ORGANIZATIONS done 4",
          "SPACES 7/7", "SPACES done 7", "APPS 16/16", "APPS done 16")), events.toString());
      IOException rejected = assertThrows(IOException.class,
          () -> client(server, "wrong").load());
      assertTrue(rejected.getMessage().startsWith("UAA token request was rejected (HTTP 401)"),
          rejected.getMessage());
      assertFalse(rejected.getMessage().contains("wrong"));
    } finally {
      server.stop();
    }
  }

  @Test
  void requestsEveryPageAndReportsInvalidResponses() throws Exception {
    WireMockServer server = new WireMockServer(wireMockConfig().dynamicPort());
    server.start();
    try {
      server.stubFor(post(urlPathEqualTo("/oauth/token"))
          .withRequestBody(containing("username=user%2Btest"))
          .withRequestBody(containing("password=p%26ss"))
          .willReturn(okJson("{\"access_token\":\"test-token\"}")));
      server.stubFor(get(urlPathEqualTo("/v3/organizations"))
          .withHeader("Authorization", equalTo("Bearer test-token"))
          .willReturn(okJson(page("null", "{\"guid\":\"o\",\"name\":\"org\"}"))));
      server.stubFor(get(urlPathEqualTo("/v3/spaces"))
          .willReturn(okJson(page("null", "{\"guid\":\"s\",\"name\":\"space\","
              + "\"relationships\":{\"organization\":{\"data\":{\"guid\":\"o\"}}}}"))));
      server.stubFor(get(urlPathEqualTo("/v3/apps")).withQueryParam("page", equalTo("1"))
          .willReturn(okJson(page("{\"href\":\"next\"}", app("a", "first")))));
      server.stubFor(get(urlPathEqualTo("/v3/apps")).withQueryParam("page", equalTo("2"))
          .willReturn(okJson(page("null", app("b", "second")))));
      ApplicationCatalog catalog = new CloudFoundryCatalogClient(server.baseUrl(), server.baseUrl(),
          "user+test", "p&ss").load();
      assertEquals(2, catalog.apps().size());
      assertEquals("b", catalog.apps().get(1).guid());
      server.verify(1, getRequestedFor(urlPathEqualTo("/v3/apps"))
          .withQueryParam("page", equalTo("2")));

      server.resetAll();
      server.stubFor(post(urlPathEqualTo("/oauth/token"))
          .willReturn(okJson("{\"access_token\":\"test-token\"}")));
      server.stubFor(get(urlPathEqualTo("/v3/organizations")).willReturn(okJson("{}")));
      assertThrows(IOException.class, () -> client(server, "admin").load());
    } finally {
      server.stop();
    }
  }

  @Test
  void executesLifecycleActionsRestageEndpoints401RetryAndSafeErrors() throws Exception {
    WireMockServer server = new WireMockServer(wireMockConfig().dynamicPort());
    server.start();
    try {
      server.stubFor(post(urlPathEqualTo("/oauth/token"))
          .inScenario("auth")
          .whenScenarioStateIs("Started")
          .willSetStateTo("REFRESHED")
          .willReturn(okJson("{\"access_token\":\"expired-token\"}")));
      server.stubFor(post(urlPathEqualTo("/oauth/token"))
          .inScenario("auth")
          .whenScenarioStateIs("REFRESHED")
          .willReturn(okJson("{\"access_token\":\"fresh-token\"}")));

      // First POST gets 401 with expired-token, retries with fresh-token and succeeds
      server.stubFor(post(urlPathEqualTo("/v3/apps/app-1/actions/stop"))
          .withHeader("Authorization", equalTo("Bearer expired-token"))
          .willReturn(aResponse().withStatus(401).withBody("{\"detail\":\"sensitive-token\"}")));
      server.stubFor(post(urlPathEqualTo("/v3/apps/app-1/actions/stop"))
          .withHeader("Authorization", equalTo("Bearer fresh-token"))
          .willReturn(okJson(
              "{\"guid\":\"app-1\",\"state\":\"STOPPED\",\"updated_at\":\"2026-06-01T00:00:00Z\"}")));

      server.stubFor(post(urlPathEqualTo("/v3/apps/app-1/actions/start"))
          .withHeader("Authorization", equalTo("Bearer fresh-token"))
          .willReturn(okJson(
              "{\"guid\":\"app-1\",\"state\":\"STARTED\",\"updated_at\":\"2026-06-01T00:01:00Z\"}")));
      server.stubFor(post(urlPathEqualTo("/v3/apps/app-1/actions/restart"))
          .withHeader("Authorization", equalTo("Bearer fresh-token"))
          .willReturn(okJson(
              "{\"guid\":\"app-1\",\"state\":\"STARTED\",\"updated_at\":\"2026-06-01T00:02:00Z\"}")));

      server.stubFor(get(urlPathEqualTo("/v3/apps/app-1/packages"))
          .withQueryParam("states", equalTo("READY"))
          .willReturn(okJson("{\"resources\":[{\"guid\":\"pkg-42\",\"state\":\"READY\"}]}")));
      server.stubFor(post(urlPathEqualTo("/v3/builds"))
          .withRequestBody(equalToJson("{\"package\":{\"guid\":\"pkg-42\"}}"))
          .willReturn(aResponse().withStatus(201)
              .withHeader("Content-Type", "application/json")
              .withBody("{\"guid\":\"bld-7\",\"state\":\"STAGING\"}")));
      server.stubFor(get(urlPathEqualTo("/v3/builds/bld-7"))
          .willReturn(okJson(
              "{\"guid\":\"bld-7\",\"state\":\"STAGED\",\"droplet\":{\"guid\":\"drp-9\"}}")));
      server.stubFor(patch(urlPathEqualTo("/v3/apps/app-1/relationships/current_droplet"))
          .withRequestBody(equalToJson("{\"data\":{\"guid\":\"drp-9\"}}"))
          .willReturn(okJson("{\"data\":{\"guid\":\"drp-9\"}}")));

      CloudFoundryCatalogClient client = client(server, "admin");
      var stopped = client.stopApp("app-1");
      assertEquals("STOPPED", stopped.state());
      assertEquals("2026-06-01T00:00:00Z", stopped.updatedAt());

      assertEquals("STARTED", client.startApp("app-1").state());
      assertEquals("STARTED", client.restartApp("app-1").state());
      assertEquals("pkg-42", client.findLatestReadyPackageGuid("app-1"));
      var created = client.createBuild("pkg-42");
      assertEquals("STAGING", created.state());
      assertNull(created.dropletGuid());
      var staged = client.getBuild("bld-7");
      assertEquals("STAGED", staged.state());
      assertEquals("drp-9", staged.dropletGuid());
      client.setCurrentDroplet("app-1", "drp-9");

      // 422 and 404 map to safe CloudFoundryException without leaking raw response body
      server.stubFor(post(urlPathEqualTo("/v3/apps/unstaged/actions/start"))
          .willReturn(aResponse().withStatus(422)
              .withBody("{\"errors\":[{\"detail\":\"do-not-leak-raw-body\"}]}")));
      CloudFoundryException unprocessable = assertThrows(CloudFoundryException.class,
          () -> client.startApp("unstaged"));
      assertTrue(unprocessable.getMessage().contains("HTTP 422"), unprocessable.getMessage());
      assertFalse(unprocessable.getMessage().contains("do-not-leak-raw-body"));

      server.stubFor(post(urlPathEqualTo("/v3/apps/missing/actions/stop"))
          .willReturn(aResponse().withStatus(404).withBody("secret-404-body")));
      CloudFoundryException missing = assertThrows(CloudFoundryException.class,
          () -> client.stopApp("missing"));
      assertTrue(missing.getMessage().contains("HTTP 404"), missing.getMessage());
      assertFalse(missing.getMessage().contains("secret-404-body"));

      server.stubFor(get(urlPathEqualTo("/v3/apps/no-pkg/packages"))
          .willReturn(okJson("{\"resources\":[]}")));
      CloudFoundryException noPkg = assertThrows(CloudFoundryException.class,
          () -> client.findLatestReadyPackageGuid("no-pkg"));
      assertEquals("No READY package found to restage.", noPkg.getMessage());

      // PATCH environment_variables sends string upserts + explicit JSON null deletions
      server.stubFor(patch(urlPathEqualTo("/v3/apps/app-1/environment_variables"))
          .withHeader("Authorization", equalTo("Bearer fresh-token"))
          .withRequestBody(equalToJson(
              "{\"var\":{\"NEW_KEY\":\"https://api.internal/v2\",\"OLD_KEY\":null}}"))
          .willReturn(okJson(
              "{\"var\":{\"NEW_KEY\":\"https://api.internal/v2\",\"KEEP\":\"1\"}}")));
      Map<String, String> patchPayload = new LinkedHashMap<>();
      patchPayload.put("NEW_KEY", "https://api.internal/v2");
      patchPayload.put("OLD_KEY", null);
      Map<String, String> patched = client.patchEnvironmentVariables("app-1", patchPayload);
      assertEquals(Map.of("NEW_KEY", "https://api.internal/v2", "KEEP", "1"), patched);

      server.stubFor(patch(urlPathEqualTo("/v3/apps/forbidden/environment_variables"))
          .willReturn(aResponse().withStatus(403).withBody("{\"secret\":\"never-echo\"}")));
      CloudFoundryException forbidden = assertThrows(CloudFoundryException.class,
          () -> client.patchEnvironmentVariables("forbidden", Map.of("K", "V")));
      assertTrue(forbidden.getMessage().contains("HTTP 403"), forbidden.getMessage());
      assertFalse(forbidden.getMessage().contains("never-echo"));
    } finally {
      server.stop();
    }
  }

  @Test
  void deletesAppsPollsJobsAndReportsSafeErrors() throws Exception {
    WireMockServer server = new WireMockServer(wireMockConfig().dynamicPort());
    server.start();
    try {
      server.stubFor(post(urlPathEqualTo("/oauth/token"))
          .willReturn(okJson("{\"access_token\":\"token\"}")));
      server.stubFor(delete(urlPathEqualTo("/v3/apps/app-1"))
          .withHeader("Authorization", equalTo("Bearer token"))
          .willReturn(aResponse().withStatus(202)
              .withHeader("Location", server.baseUrl() + "/v3/jobs/job-9")));
      server.stubFor(get(urlPathEqualTo("/v3/jobs/job-9"))
          .willReturn(okJson("{\"guid\":\"job-9\",\"state\":\"FAILED\",\"errors\":"
              + "[{\"title\":\"CF-AppDeleteFailed\",\"detail\":\"do-not-leak\"}]}")));
      server.stubFor(delete(urlPathEqualTo("/v3/apps/no-job"))
          .willReturn(aResponse().withStatus(202)));
      server.stubFor(delete(urlPathEqualTo("/v3/apps/forbidden"))
          .willReturn(aResponse().withStatus(403).withBody("{\"secret\":\"never-echo\"}")));
      server.stubFor(delete(urlPathEqualTo("/v3/apps/locked"))
          .willReturn(aResponse().withStatus(422).withBody("{\"secret\":\"never-echo\"}")));

      CloudFoundryCatalogClient client = client(server, "admin");
      assertEquals("job-9", client.deleteApp("app-1"));
      var job = client.getJob("job-9");
      assertEquals("FAILED", job.state());
      assertEquals("CF-AppDeleteFailed", job.errorTitle());
      assertNull(client.deleteApp("no-job"));

      CloudFoundryException forbidden = assertThrows(CloudFoundryException.class,
          () -> client.deleteApp("forbidden"));
      assertTrue(forbidden.getMessage().contains("HTTP 403"), forbidden.getMessage());
      assertFalse(forbidden.getMessage().contains("never-echo"));
      CloudFoundryException locked = assertThrows(CloudFoundryException.class,
          () -> client.deleteApp("locked"));
      assertEquals("Delete app was rejected by Cloud Foundry (HTTP 422).", locked.getMessage());

      assertEquals("abc", CloudFoundryCatalogClient.jobGuid("https://api.x/v3/jobs/abc?x=1"));
      assertNull(CloudFoundryCatalogClient.jobGuid("https://api.x/v3/apps/abc"));
    } finally {
      server.stop();
    }
  }

  @Test
  void httpInterceptorOmitsPayloadsByDefaultAndLogsFullTrafficInDebugMode() throws Exception {
    WireMockServer server = new WireMockServer(wireMockConfig().dynamicPort());
    server.start();
    try {
      server.stubFor(post(urlPathEqualTo("/oauth/token"))
          .willReturn(okJson("{\"access_token\":\"secret-bearer-token\",\"expires_in\":600}")));
      server.stubFor(get(urlPathEqualTo("/v3/apps/app-1/environment_variables"))
          .willReturn(okJson("{\"var\":{\"DB_PASS\":\"top-secret-db-pw\"}}")));
      server.stubFor(post(urlPathEqualTo("/v3/apps/app-1/actions/start"))
          .willReturn(aResponse().withStatus(422)
              .withHeader("X-Vcap-Request-Id", "req-vcap-999")
              .withBody("{\"errors\":[{\"detail\":\"raw-422-secret-body\"}]}")));

      Path normalLogs = temp.resolve("normal-logs");
      LogSetup.toFile(normalLogs, false);
      try {
        CloudFoundryCatalogClient normalClient = new CloudFoundryCatalogClient(
            server.baseUrl(), server.baseUrl(), "admin", "ultra-secret-uaa-pw");
        assertEquals(Map.of("DB_PASS", "top-secret-db-pw"),
            normalClient.fetchEnvironmentVariables("app-1"));
        assertThrows(CloudFoundryException.class, () -> normalClient.startApp("app-1"));
      } finally {
        LogSetup.close();
      }

      String defaultLog = Files.readString(normalLogs.resolve(LogSetup.LOG_FILE));
      assertTrue(defaultLog.contains("Authenticated with UAA"), defaultLog);
      assertTrue(defaultLog.contains("vcap-request-id=req-vcap-999"), defaultLog);
      assertFalse(defaultLog.contains("ultra-secret-uaa-pw"), defaultLog);
      assertFalse(defaultLog.contains("secret-bearer-token"), defaultLog);
      assertFalse(defaultLog.contains("top-secret-db-pw"), defaultLog);
      assertFalse(defaultLog.contains("raw-422-secret-body"), defaultLog);

      Path debugLogs = temp.resolve("debug-logs");
      LogSetup.toFile(debugLogs, true);
      try {
        CloudFoundryCatalogClient debugClient = new CloudFoundryCatalogClient(
            server.baseUrl(), server.baseUrl(), "admin", "ultra-secret-uaa-pw");
        assertEquals(Map.of("DB_PASS", "top-secret-db-pw"),
            debugClient.fetchEnvironmentVariables("app-1"));
        assertThrows(CloudFoundryException.class, () -> debugClient.startApp("app-1"));
      } finally {
        LogSetup.close();
      }

      String debugLog = Files.readString(debugLogs.resolve(LogSetup.LOG_FILE));
      assertTrue(debugLog.contains("--> #1 POST " + server.baseUrl() + "/oauth/token"), debugLog);
      assertTrue(debugLog.contains("<-- #1 200 ("), debugLog);
      assertTrue(debugLog.contains("secret-bearer-token"), debugLog);
      assertTrue(debugLog.contains("top-secret-db-pw"), debugLog);
      assertTrue(debugLog.contains("raw-422-secret-body"), debugLog);
    } finally {
      LogSetup.close();
      server.stop();
    }
  }

  private static CloudFoundryCatalogClient client(WireMockServer server, String username) {
    return new CloudFoundryCatalogClient(server.baseUrl(), server.baseUrl(), username, "admin");
  }

  private static String page(String next, String resource) {
    return "{\"pagination\":{\"next\":" + next + "},\"resources\":[" + resource + "]}";
  }

  private static String app(String id, String name) {
    return "{\"guid\":\"" + id + "\",\"name\":\"" + name
        + "\",\"state\":\"STARTED\",\"relationships\":{\"space\":{\"data\":{\"guid\":\"s\"}}}}";
  }
}
