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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CloudFoundryCatalogClient {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final long DEFAULT_TOKEN_TTL_SECONDS = 300L;
  private static final long TOKEN_REFRESH_BUFFER_SECONDS = 30L;

  private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofSeconds(15)).build();
  private final String uaaUrl;
  private final String apiUrl;
  private final String username;
  private final String password;
  private final Object tokenLock = new Object();
  private volatile String cachedToken;
  private volatile long tokenExpiresAtMillis;

  public record CatalogSnapshot(List<JsonNode> organizations, List<JsonNode> spaces,
      List<JsonNode> apps) {
    public CatalogSnapshot {
      organizations = List.copyOf(organizations);
      spaces = List.copyOf(spaces);
      apps = List.copyOf(apps);
    }

    public ApplicationCatalog toCatalog() throws IOException {
      return join(organizations, spaces, apps);
    }
  }

  public CloudFoundryCatalogClient(String uaaUrl, String apiUrl, String username, String password) {
    this.uaaUrl = Objects.requireNonNull(uaaUrl);
    this.apiUrl = Objects.requireNonNull(apiUrl);
    this.username = Objects.requireNonNull(username);
    this.password = Objects.requireNonNull(password);
  }

  public ApplicationCatalog load() throws IOException, InterruptedException {
    return loadSnapshot().toCatalog();
  }

  public CatalogSnapshot loadSnapshot() throws IOException, InterruptedException {
    String token = accessToken(false);
    List<JsonNode> orgs = fetch("/v3/organizations", token);
    List<JsonNode> spaces = fetch("/v3/spaces", token);
    List<JsonNode> apps = fetch("/v3/apps", token);
    return new CatalogSnapshot(orgs, spaces, apps);
  }

  public Map<String, String> fetchEnvironmentVariables(String appGuid)
      throws IOException, InterruptedException {
    Objects.requireNonNull(appGuid);
    if (appGuid.isBlank()) {
      throw new IllegalArgumentException("App GUID must not be blank");
    }
    String encodedGuid = URLEncoder.encode(appGuid, StandardCharsets.UTF_8);
    JsonNode response = sendAuthorizedGet("/v3/apps/" + encodedGuid + "/environment_variables");
    JsonNode varNode = response.get("var");
    if (varNode == null || varNode.isNull()) {
      return Map.of();
    }
    if (!varNode.isObject()) {
      throw new IOException("Invalid environment variables response");
    }
    Map<String, String> result = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> entry : varNode.properties()) {
      JsonNode value = entry.getValue();
      result.put(entry.getKey(), value == null || value.isNull() ? "" : value.asText());
    }
    return Map.copyOf(result);
  }

  public static ApplicationCatalog join(List<JsonNode> orgs, List<JsonNode> spaces,
      List<JsonNode> apps) throws IOException {
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

  private String accessToken(boolean forceRefresh) throws IOException, InterruptedException {
    if (!forceRefresh && cachedToken != null && System.currentTimeMillis() < tokenExpiresAtMillis) {
      return cachedToken;
    }
    synchronized (tokenLock) {
      if (!forceRefresh && cachedToken != null
          && System.currentTimeMillis() < tokenExpiresAtMillis) {
        return cachedToken;
      }
      String form = "grant_type=password&client_id=cf&client_secret=&username="
          + encode(username) + "&password=" + encode(password);
      HttpRequest tokenRequest = HttpRequest.newBuilder(uri(uaaUrl, "/oauth/token"))
          .timeout(Duration.ofSeconds(30))
          .header("Content-Type", "application/x-www-form-urlencoded")
          .POST(HttpRequest.BodyPublishers.ofString(form)).build();
      JsonNode response = send(tokenRequest);
      String token = required(response, "access_token");
      long expiresIn = response.path("expires_in").isIntegralNumber()
          ? response.path("expires_in").asLong() : DEFAULT_TOKEN_TTL_SECONDS;
      long effectiveSeconds = Math.max(0L, expiresIn - TOKEN_REFRESH_BUFFER_SECONDS);
      cachedToken = token;
      tokenExpiresAtMillis = System.currentTimeMillis() + effectiveSeconds * 1000L;
      return token;
    }
  }

  private JsonNode sendAuthorizedGet(String path) throws IOException, InterruptedException {
    String token = accessToken(false);
    HttpResponse<String> response = http.send(authorizedGet(path, token),
        HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() == 401) {
      String refreshed = accessToken(true);
      response = http.send(authorizedGet(path, refreshed), HttpResponse.BodyHandlers.ofString());
    }
    return parseJsonResponse(response);
  }

  private List<JsonNode> fetch(String path, String initialToken)
      throws IOException, InterruptedException {
    List<JsonNode> all = new ArrayList<>();
    String token = initialToken;
    int page = 1;
    while (true) {
      String pagePath = path + "?per_page=100&page=" + page;
      HttpResponse<String> raw = http.send(authorizedGet(pagePath, token),
          HttpResponse.BodyHandlers.ofString());
      if (raw.statusCode() == 401) {
        token = accessToken(true);
        raw = http.send(authorizedGet(pagePath, token), HttpResponse.BodyHandlers.ofString());
      }
      JsonNode response = parseJsonResponse(raw);
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

  private HttpRequest authorizedGet(String path, String token) {
    return HttpRequest.newBuilder(uri(apiUrl, path))
        .timeout(Duration.ofSeconds(30))
        .header("Authorization", "Bearer " + token).GET().build();
  }

  private JsonNode send(HttpRequest request) throws IOException, InterruptedException {
    return parseJsonResponse(http.send(request, HttpResponse.BodyHandlers.ofString()));
  }

  private static JsonNode parseJsonResponse(HttpResponse<String> response) throws IOException {
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
