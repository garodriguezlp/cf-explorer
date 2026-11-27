package cf.explorer.catalog;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
