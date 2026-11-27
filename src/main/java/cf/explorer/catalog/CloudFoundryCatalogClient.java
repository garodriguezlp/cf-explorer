package cf.explorer.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal Cloud Foundry v3 and UAA client built on {@link HttpClient}.
 *
 * <p>Authenticates with the password grant, caches the bearer token until shortly before it
 * expires, retries once on HTTP 401, and loads organizations, spaces, and apps in parallel.
 * Failures are reported as {@link CloudFoundryException} with messages that never include
 * credentials or response bodies.
 */
public final class CloudFoundryCatalogClient {
  private static final Logger LOG = LoggerFactory.getLogger(CloudFoundryCatalogClient.class);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final long DEFAULT_TOKEN_TTL_SECONDS = 300L;
  private static final long TOKEN_REFRESH_BUFFER_SECONDS = 30L;

  private static final String DISABLE_HOSTNAME_VERIFICATION =
      "jdk.internal.httpclient.disableHostnameVerification";

  private final HttpExchangeLogger http;
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

  /** Updated application state returned by a Cloud Foundry v3 lifecycle action. */
  public record AppStateResult(String guid, String state, String updatedAt, JsonNode rawApp) {
    public AppStateResult {
      Objects.requireNonNull(guid);
      Objects.requireNonNull(state);
    }
  }

  /** Cloud Foundry v3 build status returned while staging a package. */
  public record BuildResult(String guid, String state, String dropletGuid) {
    public BuildResult {
      Objects.requireNonNull(guid);
      Objects.requireNonNull(state);
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
    this.http = new HttpExchangeLogger(builder.build());
  }

  public ApplicationCatalog load() throws IOException, InterruptedException {
    return loadSnapshot().toCatalog();
  }

  public CatalogSnapshot loadSnapshot() throws IOException, InterruptedException {
    return loadSnapshot(CatalogProgress.NONE);
  }

  /** Loads organizations, spaces, and apps in parallel, reporting each page to {@code progress}. */
  public CatalogSnapshot loadSnapshot(CatalogProgress progress)
      throws IOException, InterruptedException {
    Objects.requireNonNull(progress);
    long startNanos = System.nanoTime();
    String token = accessToken(false);
    progress.authenticated();
    ExecutorService pool = Executors.newFixedThreadPool(3, runnable -> {
      Thread thread = new Thread(runnable, "cf-catalog-fetch");
      thread.setDaemon(true);
      return thread;
    });
    try {
      CompletableFuture<List<JsonNode>> orgs = fetchAsync("/v3/organizations",
          CatalogProgress.Resource.ORGANIZATIONS, token, pool, progress);
      CompletableFuture<List<JsonNode>> spaces = fetchAsync("/v3/spaces",
          CatalogProgress.Resource.SPACES, token, pool, progress);
      CompletableFuture<List<JsonNode>> apps = fetchAsync("/v3/apps",
          CatalogProgress.Resource.APPS, token, pool, progress);
      CatalogSnapshot snapshot = new CatalogSnapshot(await(orgs), await(spaces), await(apps));
      long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
      LOG.info("Fetched catalog snapshot from Cloud Foundry: orgs={} spaces={} apps={} ({} ms)",
          snapshot.organizations().size(), snapshot.spaces().size(), snapshot.apps().size(),
          elapsedMs);
      return snapshot;
    } finally {
      pool.shutdownNow();
    }
  }

  private CompletableFuture<List<JsonNode>> fetchAsync(String path,
      CatalogProgress.Resource resource, String token, ExecutorService pool,
      CatalogProgress progress) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        List<JsonNode> all = fetch(path, token, (fetched, total) ->
            progress.fetched(resource, fetched, total));
        progress.completed(resource, all.size());
        return all;
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
    String encodedGuid = encodeRequiredGuid(appGuid, "App GUID");
    JsonNode response = sendAuthorizedJson(
        "GET", "/v3/apps/" + encodedGuid + "/environment_variables", null,
        "Cloud Foundry API request");
    return parseEnvVarMap(response);
  }

  /**
   * Atomically adds, updates, and deletes application environment variables via Cloud Foundry v3.
   * Entries mapped to {@code null} delete that variable; non-null entries set or update it.
   */
  public Map<String, String> patchEnvironmentVariables(String appGuid, Map<String, String> changes)
      throws IOException, InterruptedException {
    String encodedGuid = encodeRequiredGuid(appGuid, "App GUID");
    Objects.requireNonNull(changes);
    ObjectNode root = JSON.createObjectNode();
    ObjectNode varNode = root.putObject("var");
    for (Map.Entry<String, String> entry : changes.entrySet()) {
      String key = entry.getKey();
      if (key == null || key.isBlank()) {
        throw new IllegalArgumentException("Environment variable key must not be blank");
      }
      if (entry.getValue() == null) {
        varNode.putNull(key);
      } else {
        varNode.put(key, entry.getValue());
      }
    }
    String body = JSON.writeValueAsString(root);
    JsonNode response = sendAuthorizedJson(
        "PATCH", "/v3/apps/" + encodedGuid + "/environment_variables", body,
        "Update environment variables");
    return parseEnvVarMap(response);
  }

  private static Map<String, String> parseEnvVarMap(JsonNode response) throws IOException {
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

  public AppStateResult startApp(String appGuid) throws IOException, InterruptedException {
    return performAppAction(appGuid, "start", "Start app");
  }

  public AppStateResult stopApp(String appGuid) throws IOException, InterruptedException {
    return performAppAction(appGuid, "stop", "Stop app");
  }

  public AppStateResult restartApp(String appGuid) throws IOException, InterruptedException {
    return performAppAction(appGuid, "restart", "Restart app");
  }

  private AppStateResult performAppAction(String appGuid, String action, String operation)
      throws IOException, InterruptedException {
    String encodedGuid = encodeRequiredGuid(appGuid, "App GUID");
    JsonNode response = sendAuthorizedJson(
        "POST", "/v3/apps/" + encodedGuid + "/actions/" + action, "", operation);
    return new AppStateResult(
        required(response, "guid"),
        required(response, "state"),
        optional(response, "updated_at"),
        response);
  }

  public String findLatestReadyPackageGuid(String appGuid)
      throws IOException, InterruptedException {
    String encodedGuid = encodeRequiredGuid(appGuid, "App GUID");
    JsonNode response = sendAuthorizedJson(
        "GET",
        "/v3/apps/" + encodedGuid + "/packages?states=READY&order_by=-created_at&per_page=1",
        null,
        "Fetch app packages");
    JsonNode resources = response.get("resources");
    if (resources == null || !resources.isArray()) {
      throw new CloudFoundryException("Invalid packages response");
    }
    if (resources.isEmpty()) {
      throw new CloudFoundryException("No READY package found to restage.");
    }
    return required(resources.get(0), "guid");
  }

  public BuildResult createBuild(String packageGuid) throws IOException, InterruptedException {
    Objects.requireNonNull(packageGuid);
    if (packageGuid.isBlank()) {
      throw new IllegalArgumentException("Package GUID must not be blank");
    }
    String body = JSON.writeValueAsString(Map.of("package", Map.of("guid", packageGuid)));
    JsonNode response = sendAuthorizedJson("POST", "/v3/builds", body, "Create build");
    return parseBuild(response);
  }

  public BuildResult getBuild(String buildGuid) throws IOException, InterruptedException {
    String encodedGuid = encodeRequiredGuid(buildGuid, "Build GUID");
    JsonNode response = sendAuthorizedJson("GET", "/v3/builds/" + encodedGuid, null, "Poll build");
    return parseBuild(response);
  }

  public void setCurrentDroplet(String appGuid, String dropletGuid)
      throws IOException, InterruptedException {
    String encodedApp = encodeRequiredGuid(appGuid, "App GUID");
    Objects.requireNonNull(dropletGuid);
    if (dropletGuid.isBlank()) {
      throw new IllegalArgumentException("Droplet GUID must not be blank");
    }
    String body = JSON.writeValueAsString(Map.of("data", Map.of("guid", dropletGuid)));
    sendAuthorizedJson(
        "PATCH", "/v3/apps/" + encodedApp + "/relationships/current_droplet", body,
        "Assign current droplet");
  }

  private static BuildResult parseBuild(JsonNode response) throws IOException {
    String guid = required(response, "guid");
    String state = required(response, "state");
    JsonNode droplet = response.path("droplet").path("guid");
    String dropletGuid = droplet.isTextual() && !droplet.asText().isBlank()
        ? droplet.asText() : null;
    return new BuildResult(guid, state, dropletGuid);
  }

  private static String encodeRequiredGuid(String guid, String label) {
    Objects.requireNonNull(guid);
    if (guid.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return URLEncoder.encode(guid, StandardCharsets.UTF_8);
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
          http.send(tokenRequest, form, "UAA token request"), "UAA token request");
      String token = required(response, "access_token");
      long expiresIn = response.path("expires_in").isIntegralNumber()
          ? response.path("expires_in").asLong() : DEFAULT_TOKEN_TTL_SECONDS;
      long effectiveSeconds = Math.max(0L, expiresIn - TOKEN_REFRESH_BUFFER_SECONDS);
      cachedToken = token;
      tokenExpiresAtMillis = System.currentTimeMillis() + effectiveSeconds * 1000L;
      LOG.info("Authenticated with UAA (token expires_in={}s)", expiresIn);
      return token;
    }
  }

  private JsonNode sendAuthorizedJson(String method, String path, String jsonBody,
      String operation) throws IOException, InterruptedException {
    String token = accessToken(false);
    HttpResponse<String> response = http.send(
        authorizedRequest(method, path, jsonBody, token), jsonBody, operation);
    if (response.statusCode() == 401) {
      LOG.info("Received HTTP 401 on {} {}; refreshing UAA token and retrying", method, path);
      String refreshed = accessToken(true);
      response = http.send(
          authorizedRequest(method, path, jsonBody, refreshed), jsonBody, operation);
    }
    return parseJsonResponse(response, operation);
  }

  private interface PageListener {
    void fetched(int fetched, int total);
  }

  private List<JsonNode> fetch(String path, String initialToken, PageListener listener)
      throws IOException, InterruptedException {
    List<JsonNode> all = new ArrayList<>();
    String token = initialToken;
    int page = 1;
    while (true) {
      String pagePath = path + "?per_page=100&page=" + page;
      HttpResponse<String> raw = http.send(
          authorizedRequest("GET", pagePath, null, token), null, "Catalog fetch " + path);
      if (raw.statusCode() == 401) {
        LOG.info("Received HTTP 401 on GET {}; refreshing UAA token and retrying", pagePath);
        token = accessToken(true);
        raw = http.send(
            authorizedRequest("GET", pagePath, null, token), null, "Catalog fetch " + path);
      }
      JsonNode response = parseJsonResponse(raw, "Cloud Foundry API request");
      JsonNode resources = response.get("resources");
      JsonNode pagination = response.get("pagination");
      if (resources == null || !resources.isArray() || pagination == null
          || !pagination.isObject() || !pagination.has("next")) {
        throw new CloudFoundryException("Invalid catalog response from " + path);
      }
      resources.forEach(all::add);
      JsonNode total = pagination.get("total_results");
      listener.fetched(all.size(), total != null && total.canConvertToInt() ? total.asInt() : -1);
      if (pagination.get("next").isNull()) {
        return all;
      }
      page++;
    }
  }

  private HttpRequest authorizedRequest(String method, String path, String jsonBody, String token) {
    HttpRequest.Builder builder = HttpRequest.newBuilder(uri(apiUrl, path))
        .timeout(Duration.ofSeconds(30))
        .header("Authorization", "Bearer " + token);
    if ("GET".equals(method)) {
      builder.GET();
    } else if (jsonBody == null || jsonBody.isEmpty()) {
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      builder.header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
    }
    return builder.build();
  }

  private static JsonNode parseJsonResponse(HttpResponse<String> response, String operation)
      throws IOException {
    int status = response.statusCode();
    if (status == 401 || status == 403) {
      throw new CloudFoundryException(operation + " was rejected (HTTP " + status
          + "). Check the username, password, and URLs.");
    }
    if (status == 404) {
      throw new CloudFoundryException(operation + " failed (HTTP 404): resource not found.");
    }
    if (status == 422) {
      throw new CloudFoundryException(operation
          + " was rejected by Cloud Foundry (HTTP 422). The app may not have a staged droplet.");
    }
    if (status < 200 || status >= 300) {
      throw new CloudFoundryException(operation + " failed (HTTP " + status + ").");
    }
    JsonNode json;
    try {
      json = JSON.readTree(response.body());
    } catch (IOException ex) {
      throw new CloudFoundryException(operation + " returned a non-JSON response.", ex);
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
