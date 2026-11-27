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
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Minimal Cloud Foundry v3 and UAA client built on {@link HttpClient}.
 *
 * <p>Authenticates with the password grant, caches the bearer token until shortly before it
 * expires, retries once on HTTP 401, and loads organizations, spaces, and apps in parallel.
 * Failures are reported as {@link CloudFoundryException} with messages that never include
 * credentials or response bodies.
 */
public final class CloudFoundryCatalogClient {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final long DEFAULT_TOKEN_TTL_SECONDS = 300L;
  private static final long TOKEN_REFRESH_BUFFER_SECONDS = 30L;

  private static final String DISABLE_HOSTNAME_VERIFICATION =
      "jdk.internal.httpclient.disableHostnameVerification";

  private final HttpClient http;
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
    this(uaaUrl, apiUrl, username, password, false);
  }

  /**
   * Creates a client. When {@code skipSslValidation} is {@code true}, TLS certificates and host
   * names are not verified, which is only appropriate for foundations with self-signed
   * certificates.
   */
  public CloudFoundryCatalogClient(String uaaUrl, String apiUrl, String username, String password,
      boolean skipSslValidation) {
    this.uaaUrl = Objects.requireNonNull(uaaUrl);
    this.apiUrl = Objects.requireNonNull(apiUrl);
    this.username = Objects.requireNonNull(username);
    this.password = Objects.requireNonNull(password);
    HttpClient.Builder builder = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(15));
    if (skipSslValidation) {
      // The JDK client reads this flag once, when its first instance is created.
      System.setProperty(DISABLE_HOSTNAME_VERIFICATION, "true");
      builder.sslContext(trustAllContext());
    }
    this.http = builder.build();
  }

  public ApplicationCatalog load() throws IOException, InterruptedException {
    return loadSnapshot().toCatalog();
  }

  public CatalogSnapshot loadSnapshot() throws IOException, InterruptedException {
    String token = accessToken(false);
    ExecutorService pool = Executors.newFixedThreadPool(3, runnable -> {
      Thread thread = new Thread(runnable, "cf-catalog-fetch");
      thread.setDaemon(true);
      return thread;
    });
    try {
      CompletableFuture<List<JsonNode>> orgs = fetchAsync("/v3/organizations", token, pool);
      CompletableFuture<List<JsonNode>> spaces = fetchAsync("/v3/spaces", token, pool);
      CompletableFuture<List<JsonNode>> apps = fetchAsync("/v3/apps", token, pool);
      return new CatalogSnapshot(await(orgs), await(spaces), await(apps));
    } finally {
      pool.shutdownNow();
    }
  }

  private CompletableFuture<List<JsonNode>> fetchAsync(String path, String token,
      ExecutorService pool) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        return fetch(path, token);
      } catch (IOException | InterruptedException ex) {
        throw new CompletionException(ex);
      }
    }, pool);
  }

  private static List<JsonNode> await(CompletableFuture<List<JsonNode>> future)
      throws IOException, InterruptedException {
    try {
      return future.join();
    } catch (CompletionException ex) {
      Throwable cause = ex.getCause();
      if (cause instanceof IOException io) {
        throw io;
      }
      if (cause instanceof InterruptedException interrupted) {
        throw interrupted;
      }
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw ex;
    }
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
      throw new CloudFoundryException("Invalid environment variables response");
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
      JsonNode response = parseJsonResponse(
          http.send(tokenRequest, HttpResponse.BodyHandlers.ofString()), "UAA token request");
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
    return parseJsonResponse(response, "Cloud Foundry API request");
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
      JsonNode response = parseJsonResponse(raw, "Cloud Foundry API request");
      JsonNode resources = response.get("resources");
      JsonNode pagination = response.get("pagination");
      if (resources == null || !resources.isArray() || pagination == null
          || !pagination.isObject() || !pagination.has("next")) {
        throw new CloudFoundryException("Invalid catalog response from " + path);
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

  private static JsonNode parseJsonResponse(HttpResponse<String> response, String operation)
      throws IOException {
    int status = response.statusCode();
    if (status == 401 || status == 403) {
      throw new CloudFoundryException(operation + " was rejected (HTTP " + status
          + "). Check the username, password, and URLs.");
    }
    if (status < 200 || status >= 300) {
      throw new CloudFoundryException(operation + " failed (HTTP " + status + ").");
    }
    JsonNode json;
    try {
      json = JSON.readTree(response.body());
    } catch (IOException ex) {
      throw new CloudFoundryException(operation + " returned a non-JSON response.");
    }
    if (json == null || !json.isObject()) {
      throw new CloudFoundryException(operation + " returned an unexpected response.");
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
      throw new CloudFoundryException("Invalid API response field: " + field);
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

  private static SSLContext trustAllContext() {
    TrustManager trustAll = new X509TrustManager() {
      @Override
      public void checkClientTrusted(X509Certificate[] chain, String authType) {}

      @Override
      public void checkServerTrusted(X509Certificate[] chain, String authType) {}

      @Override
      public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
      }
    };
    try {
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, new TrustManager[] {trustAll}, new SecureRandom());
      return context;
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException("Could not initialize TLS context", ex);
    }
  }
}
