package cf.explorer.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CloudFoundryCatalogClient {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofSeconds(15)).build();
  private final String uaaUrl;
  private final String apiUrl;
  private final String username;
  private final String password;

  public CloudFoundryCatalogClient(String uaaUrl, String apiUrl, String username, String password) {
    this.uaaUrl = Objects.requireNonNull(uaaUrl);
    this.apiUrl = Objects.requireNonNull(apiUrl);
    this.username = Objects.requireNonNull(username);
    this.password = Objects.requireNonNull(password);
  }

  public ApplicationCatalog load() throws IOException, InterruptedException {
    String form = "grant_type=password&client_id=cf&client_secret=&username="
        + encode(username) + "&password=" + encode(password);
    HttpRequest tokenRequest = HttpRequest.newBuilder(uri(uaaUrl, "/oauth/token"))
        .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(form)).build();
    String token = required(send(tokenRequest), "access_token");
    List<JsonNode> orgs = fetch("/v3/organizations", token);
    List<JsonNode> spaces = fetch("/v3/spaces", token);
    List<JsonNode> apps = fetch("/v3/apps", token);
    Map<String, String> orgNames = new HashMap<>();
    for (JsonNode org : orgs) {
      orgNames.put(required(org, "guid"), required(org, "name"));
    }
    Map<String, JsonNode> spacesById = new HashMap<>();
    for (JsonNode space : spaces) {
      spacesById.put(required(space, "guid"), space);
    }
    List<ApplicationCatalog.App> joined = new ArrayList<>();
    for (JsonNode app : apps) {
      String spaceId = relationship(app, "space");
      JsonNode space = spacesById.get(spaceId);
      String orgId = space == null ? "" : relationship(space, "organization");
      joined.add(new ApplicationCatalog.App(required(app, "guid"), required(app, "name"),
          required(app, "state"), spaceId, space == null ? "unknown" : required(space, "name"),
          orgId, orgNames.getOrDefault(orgId, "unknown"),
          optional(app, "created_at"), optional(app, "updated_at")));
    }
    return new ApplicationCatalog(orgs.size(), spaces.size(), joined);
  }

  private List<JsonNode> fetch(String path, String token) throws IOException, InterruptedException {
    List<JsonNode> all = new ArrayList<>();
    int page = 1;
    while (true) {
      HttpRequest request = HttpRequest.newBuilder(uri(apiUrl, path + "?per_page=100&page=" + page))
          .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + token).GET().build();
      JsonNode response = send(request);
      JsonNode resources = response.get("resources");
      JsonNode pagination = response.get("pagination");
      if (resources == null || !resources.isArray() || pagination == null
          || !pagination.isObject() || !pagination.has("next")) {
        throw new IOException("Invalid catalog response");
      }
      resources.forEach(all::add);
      if (pagination.get("next").isNull()) {
        return all;
      }
      page++;
    }
  }

  private JsonNode send(HttpRequest request) throws IOException, InterruptedException {
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IOException("HTTP request failed (" + response.statusCode() + ")");
    }
    JsonNode json = JSON.readTree(response.body());
    if (json == null || !json.isObject()) {
      throw new IOException("Invalid API response");
    }
    return json;
  }

  private static String relationship(JsonNode item, String name) throws IOException {
    JsonNode value = item.path("relationships").path(name).path("data");
    return required(value, "guid");
  }

  private static String required(JsonNode node, String field) throws IOException {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      throw new IOException("Invalid API response field: " + field);
    }
    return value.asText();
  }

  private static String optional(JsonNode node, String field) {
    return node.path(field).isTextual() ? node.path(field).asText() : null;
  }

  private static URI uri(String base, String path) {
    return URI.create(base.replaceAll("/+$", "") + path);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
