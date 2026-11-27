package cf.explorer.catalog;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
    } finally {
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
