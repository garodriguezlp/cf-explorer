package cf.explorer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import feign.Feign;
import feign.Headers;
import feign.Param;
import feign.RequestLine;
import feign.form.FormEncoder;
import feign.jackson.JacksonDecoder;
import feign.jackson.JacksonEncoder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntFunction;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Infrastructure layer for the cf-explorer application.
 *
 * <p>Contains all network I/O (Feign-based CF v3 API and UAA clients), local disk caching, and
 * browser launching. Nothing in this file is domain logic — every class here either communicates
 * with an external system or manages a local resource.
 */

/** Receives progress callbacks as each resource type is loaded from the CF platform. */
interface CatalogLoadListener {
  void organizationsLoaded(int count);

  void spacesLoaded(int count);

  void appsLoaded(int count);
}

/** Loads the CF application catalog, either from the live platform or a local cache. */
interface CatalogProvider {
  CatalogSnapshot loadCatalog(CatalogLoadListener listener);
}

/** Raw snapshot of orgs, spaces, and apps as returned by the CF API or the local cache. */
record CatalogSnapshot(
    List<Organization> organizations, List<Space> spaces, List<AppResource> apps) {}

/**
 * {@link CatalogProvider} that transparently serves catalog data from a local disk cache, fetching
 * from the live platform only when the cache is absent or a refresh is requested.
 */
final class CachedCatalogProvider implements CatalogProvider {

  private final CatalogProvider liveProvider;
  private final FileCatalogCacheStore cacheStore;
  private final boolean refresh;

  CachedCatalogProvider(
      String uaaUrl, String cfApiUrl, String username, String password, boolean refresh) {
    this.liveProvider = new LiveCatalogProvider(uaaUrl, cfApiUrl, username, password);
    this.cacheStore = new FileCatalogCacheStore(CachePaths.defaultCacheDir());
    this.refresh = refresh;
  }

  @Override
  public CatalogSnapshot loadCatalog(CatalogLoadListener listener) {
    if (refresh) {
      clearCacheSilently();
      return fetchAndCacheLive(listener);
    }
    var cached = tryReadCache();
    if (cached != null) {
      listener.organizationsLoaded(cached.organizations().size());
      listener.spacesLoaded(cached.spaces().size());
      listener.appsLoaded(cached.apps().size());
      return cached;
    }
    return fetchAndCacheLive(listener);
  }

  private CatalogSnapshot fetchAndCacheLive(CatalogLoadListener listener) {
    var liveSnapshot = liveProvider.loadCatalog(listener);
    try {
      cacheStore.write(liveSnapshot);
    } catch (IOException ignored) {
      // Cache write failures should not block normal app startup.
    }
    return liveSnapshot;
  }

  private CatalogSnapshot tryReadCache() {
    try {
      return cacheStore.readIfPresent();
    } catch (IOException ignored) {
      return null;
    }
  }

  private void clearCacheSilently() {
    try {
      cacheStore.clear();
    } catch (IOException ignored) {
    }
  }
}

/** {@link CatalogProvider} that fetches the catalog directly from the CF platform. */
final class LiveCatalogProvider implements CatalogProvider {

  private final FeignCfPlatformGateway gateway;

  LiveCatalogProvider(String uaaUrl, String cfApiUrl, String username, String password) {
    this.gateway = new FeignCfPlatformGateway(uaaUrl, cfApiUrl, username, password);
  }

  @Override
  public CatalogSnapshot loadCatalog(CatalogLoadListener listener) {
    return gateway.fetchRawCatalog(listener);
  }
}

/**
 * Feign-based gateway for the CF v3 API and UAA token endpoint.
 *
 * <p>Fetches orgs, spaces, and apps in parallel using {@link CompletableFuture}. The trust-all SSL
 * configuration is intentional for dev/test environments only and must never be used in production.
 */
final class FeignCfPlatformGateway {

  private final ApiClient cfApiClient;
  private final PagedFetcher pagedFetcher = new PagedFetcher();

  FeignCfPlatformGateway(String uaaUrl, String cfApiUrl, String username, String password) {
    var mapper = Jackson.mapper();
    var uaaClient =
        Feign.builder()
            .client(trustAllSslClient())
            .encoder(new FormEncoder(new JacksonEncoder(mapper)))
            .decoder(new JacksonDecoder(mapper))
            .target(UaaClient.class, uaaUrl);
    var tokenProvider = new BearerTokenProvider(uaaClient, username, password);
    this.cfApiClient =
        Feign.builder()
            .client(trustAllSslClient())
            .requestInterceptor(
                template -> template.header("Authorization", "Bearer " + tokenProvider.getToken()))
            .decoder(new JacksonDecoder(mapper))
            .target(ApiClient.class, cfApiUrl);
  }

  CatalogSnapshot fetchRawCatalog(CatalogLoadListener listener) {
    var orgsFuture =
        CompletableFuture.supplyAsync(
            () -> pagedFetcher.fetchAll(p -> cfApiClient.getOrganizations(100, p)));
    var spacesFuture =
        CompletableFuture.supplyAsync(
            () -> pagedFetcher.fetchAll(p -> cfApiClient.getSpaces(100, p)));
    var appsFuture =
        CompletableFuture.supplyAsync(
            () -> pagedFetcher.fetchAll(p -> cfApiClient.getApps(100, p)));

    orgsFuture.thenAccept(orgs -> listener.organizationsLoaded(orgs.size()));
    spacesFuture.thenAccept(spaces -> listener.spacesLoaded(spaces.size()));
    appsFuture.thenAccept(apps -> listener.appsLoaded(apps.size()));

    CompletableFuture.allOf(orgsFuture, spacesFuture, appsFuture).join();
    return new CatalogSnapshot(orgsFuture.join(), spacesFuture.join(), appsFuture.join());
  }

  Map<String, String> fetchAppEnvVars(String appGuid) {
    var response = cfApiClient.getAppEnvironmentVariables(appGuid);
    return response.var() != null ? response.var() : Map.of();
  }

  private static feign.Client trustAllSslClient() {
    try {
      var trustAll =
          new TrustManager[] {
            new X509TrustManager() {
              public void checkClientTrusted(X509Certificate[] c, String a) {}

              public void checkServerTrusted(X509Certificate[] c, String a) {}

              public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
              }
            }
          };
      var sslContext = SSLContext.getInstance("TLS");
      sslContext.init(null, trustAll, new SecureRandom());
      return new feign.DefaultClient(sslContext.getSocketFactory(), (hostname, session) -> true);
    } catch (Exception e) {
      throw new RuntimeException("Failed to create trust-all SSL client", e);
    }
  }
}

/**
 * Lazily fetches and caches a UAA bearer token, refreshing it 30 seconds before expiry. Thread-safe
 * via double-checked locking on {@link #refresh()}.
 */
class BearerTokenProvider {

  private final UaaClient uaaClient;
  private final String username;
  private final String password;

  private volatile String cachedToken;
  private volatile long expiresAt;

  BearerTokenProvider(UaaClient uaaClient, String username, String password) {
    this.uaaClient = uaaClient;
    this.username = username;
    this.password = password;
  }

  String getToken() {
    if (cachedToken == null || System.currentTimeMillis() >= expiresAt) {
      refresh();
    }
    return cachedToken;
  }

  private synchronized void refresh() {
    if (cachedToken != null && System.currentTimeMillis() < expiresAt) return;
    var resp = uaaClient.token("password", "cf", "", username, password);
    cachedToken = resp.accessToken();
    expiresAt = System.currentTimeMillis() + Math.max(0L, resp.expiresIn() - 30L) * 1000L;
  }
}

/** Iterates paginated CF API responses, collecting all pages into a single list. */
final class PagedFetcher {

  <T> List<T> fetchAll(IntFunction<? extends PagedResponse<T>> pageCall) {
    List<T> all = new ArrayList<>();
    int page = 1;
    while (true) {
      var response = pageCall.apply(page++);
      if (response == null) break;
      all.addAll(response.resources() != null ? response.resources() : List.of());
      var pg = response.pagination();
      if (pg == null || pg.next() == null) break;
    }
    return all;
  }
}

/**
 * Reads and writes a {@link CatalogSnapshot} to three JSON files on disk. Writes are performed
 * atomically via a temp-file rename.
 */
final class FileCatalogCacheStore {

  private static final ObjectMapper MAPPER = Jackson.mapper();

  private final Path cacheDir;

  FileCatalogCacheStore(Path cacheDir) {
    this.cacheDir = cacheDir;
  }

  CatalogSnapshot readIfPresent() throws IOException {
    var orgsPath = organizationsPath();
    var spacesPath = spacesPath();
    var appsPath = appsPath();
    if (!Files.exists(orgsPath) || !Files.exists(spacesPath) || !Files.exists(appsPath)) {
      return null;
    }
    var organizations =
        MAPPER.readValue(orgsPath.toFile(), new TypeReference<List<Organization>>() {});
    var spaces = MAPPER.readValue(spacesPath.toFile(), new TypeReference<List<Space>>() {});
    var apps = MAPPER.readValue(appsPath.toFile(), new TypeReference<List<AppResource>>() {});
    return new CatalogSnapshot(organizations, spaces, apps);
  }

  void write(CatalogSnapshot snapshot) throws IOException {
    Files.createDirectories(cacheDir);
    writeAtomically(organizationsPath(), snapshot.organizations());
    writeAtomically(spacesPath(), snapshot.spaces());
    writeAtomically(appsPath(), snapshot.apps());
  }

  void clear() throws IOException {
    Files.deleteIfExists(organizationsPath());
    Files.deleteIfExists(spacesPath());
    Files.deleteIfExists(appsPath());
  }

  private void writeAtomically(Path target, Object value) throws IOException {
    var temp = target.resolveSibling(target.getFileName().toString() + ".tmp");
    MAPPER.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), value);
    try {
      Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (UnsupportedOperationException ex) {
      Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private Path organizationsPath() {
    return cacheDir.resolve("organizations.json");
  }

  private Path spacesPath() {
    return cacheDir.resolve("spaces.json");
  }

  private Path appsPath() {
    return cacheDir.resolve("apps.json");
  }
}

/** Canonical paths for the on-disk cache and env-file output directories. */
final class CachePaths {
  private CachePaths() {
    throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
  }

  static Path defaultCacheDir() {
    return Path.of(System.getProperty("user.home", "."), ".cf-explorer", "cache");
  }

  static Path defaultEnvsDir() {
    return Path.of(System.getProperty("user.home", "."), ".cf-explorer", "envs");
  }
}

/**
 * Shared Jackson {@link ObjectMapper} configuration used by both the Feign transport and the
 * on-disk cache serializer.
 */
final class Jackson {

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  private Jackson() {
    throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
  }

  static ObjectMapper mapper() {
    return MAPPER;
  }
}

/** Opens a URL in the operating-system default browser. */
final class BrowserLauncher {

  private BrowserLauncher() {
    throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
  }

  static void open(String url) {
    String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
    try {
      ProcessBuilder pb;
      if (os.contains("win")) {
        pb = new ProcessBuilder("cmd", "/c", "start", "", url);
      } else if (os.contains("mac")) {
        pb = new ProcessBuilder("open", url);
      } else {
        pb = new ProcessBuilder("xdg-open", url);
      }
      pb.start();
    } catch (IOException e) {
      // Non-fatal — browser not available; user can copy the URL manually.
    }
  }
}

/** Feign declarative client for the UAA token endpoint. */
interface UaaClient {
  @RequestLine("POST /oauth/token")
  @Headers("Content-Type: application/x-www-form-urlencoded")
  TokenResponse token(
      @Param("grant_type") String grantType,
      @Param("client_id") String clientId,
      @Param("client_secret") String clientSecret,
      @Param("username") String username,
      @Param("password") String password);
}

/** Feign declarative client for the CF v3 REST API. */
interface ApiClient {
  @RequestLine("GET /v3/organizations?per_page={perPage}&page={page}")
  OrganizationsResponse getOrganizations(@Param("perPage") int perPage, @Param("page") int page);

  @RequestLine("GET /v3/spaces?per_page={perPage}&page={page}")
  SpacesResponse getSpaces(@Param("perPage") int perPage, @Param("page") int page);

  @RequestLine("GET /v3/apps?per_page={perPage}&page={page}")
  AppsResponse getApps(@Param("perPage") int perPage, @Param("page") int page);

  @RequestLine("GET /v3/apps/{guid}/environment_variables")
  AppEnvResponse getAppEnvironmentVariables(@Param("guid") String guid);
}

interface PagedResponse<T> {
  Pagination pagination();

  List<T> resources();
}

record TokenResponse(String accessToken, long expiresIn) {}

record Pagination(int totalResults, int totalPages, Pagination.Link next) {
  record Link(String href) {}
}

record Organization(String guid, String name) {}

record Space(String guid, String name, Space.Relationships relationships) {
  record Relationships(OrgLink organization) {}

  record OrgLink(Ref data) {}

  record Ref(String guid) {}
}

record AppResource(
    String guid,
    String name,
    String state,
    String createdAt,
    String updatedAt,
    AppResource.Relationships relationships) {
  record Relationships(SpaceLink space) {}

  record SpaceLink(Ref data) {}

  record Ref(String guid) {}
}

record AppEnvResponse(Map<String, String> var) {}

record OrganizationsResponse(Pagination pagination, List<Organization> resources)
    implements PagedResponse<Organization> {}

record SpacesResponse(Pagination pagination, List<Space> resources)
    implements PagedResponse<Space> {}

record AppsResponse(Pagination pagination, List<AppResource> resources)
    implements PagedResponse<AppResource> {}
