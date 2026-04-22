package cf.explorer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import feign.Client;
import feign.DefaultClient;
import feign.Feign;
import feign.Headers;
import feign.Param;
import feign.RequestLine;
import feign.form.FormEncoder;
import feign.jackson.JacksonDecoder;
import feign.jackson.JacksonEncoder;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntFunction;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

// --- Controller ---

@FunctionalInterface
interface UiDispatcher {
  void dispatch(Runnable action);
}

final class Controller {

  // ── State ──────────────────────────────────────────────────────────
  private AppState state = AppState.CatalogLoading.initial();

  private final LoadCatalogUseCase loadCatalogUseCase;
  private final ExportEnvUseCase exportEnvUseCase;
  private final OpenAppInBrowserUseCase openInBrowserUseCase;
  private final ExportKeystoreUseCase exportKeystoreUseCase;
  private final EnvExportConfig exportConfig;
  private final UiDispatcher dispatch;

  Controller(EnvConfig config, UiDispatcher dispatch) {
    var useCases = new UseCases(config);
    this.loadCatalogUseCase = useCases.loadCatalog();
    this.exportEnvUseCase = useCases.exportEnv();
    this.openInBrowserUseCase = useCases.openInBrowser();
    this.exportKeystoreUseCase = useCases.keystoreUseCase();
    this.exportConfig = new EnvExportConfig(config.excludeKeys(), config.postProcessors());
    this.dispatch = dispatch;
  }

  // ── Query ──────────────────────────────────────────────────────────
  AppState state() {
    return state;
  }

  // ── Sync commands (called on render thread from key handler) ───────
  void moveUp() {
    if (state instanceof AppState.Browsing b) state = b.moveUp();
  }

  void moveDown() {
    if (state instanceof AppState.Browsing b) state = b.moveDown();
  }

  void appendFilter(char ch) {
    if (state instanceof AppState.Browsing b) state = b.appendFilter(ch);
  }

  void backspaceFilter() {
    if (state instanceof AppState.Browsing b) state = b.backspaceFilter();
  }

  void clearFilter() {
    if (state instanceof AppState.Browsing b) state = b.clearFilter();
  }

  void returnToBrowsing() {
    if (state instanceof AppState.ExportDone d) state = d.back();
    else if (state instanceof AppState.ExportFailed f) state = f.back();
    else if (state instanceof AppState.KeystoreDone d) state = d.back();
    else if (state instanceof AppState.KeystoreFailed f) state = f.back();
  }

  // ── Async commands ─────────────────────────────────────────────────
  void start() {
    CompletableFuture.supplyAsync(() -> loadCatalogUseCase.execute(buildCatalogLoadListener()))
        .thenAccept(apps -> dispatch.dispatch(() -> onCatalogLoaded(apps)))
        .exceptionally(
            ex -> {
              dispatch.dispatch(() -> onCatalogLoadFailed(ex));
              return null;
            });
  }

  void freshReload() {
    if (!(state instanceof AppState.Browsing b)) return;
    state = AppState.CatalogLoading.initial();
    CompletableFuture.supplyAsync(() -> loadCatalogUseCase.executeFresh(buildCatalogLoadListener()))
        .thenAccept(apps -> dispatch.dispatch(() -> onCatalogLoaded(apps)))
        .exceptionally(
            ex -> {
              dispatch.dispatch(() -> onCatalogLoadFailed(ex));
              return null;
            });
  }

  void selectApp(App app) {
    transitionToExporting(app);
    CompletableFuture.supplyAsync(() -> doExport(app))
        .thenAccept(result -> dispatch.dispatch(() -> onExportDone(result)))
        .exceptionally(
            ex -> {
              dispatch.dispatch(() -> onExportFailed(ex));
              return null;
            });
  }

  void openInBrowser(App app) {
    CompletableFuture.runAsync(() -> openInBrowserUseCase.execute(app));
  }

  void exportKeystore(App app) {
    if (state instanceof AppState.Browsing b) state = b.toKeystoreExporting(app.name());
    CompletableFuture.supplyAsync(
            () -> {
              try {
                return exportKeystoreUseCase.execute(app);
              } catch (Exception ex) {
                throw new RuntimeException(ex);
              }
            })
        .thenAccept(result -> dispatch.dispatch(() -> onKeystoreDone(result)))
        .exceptionally(
            ex -> {
              dispatch.dispatch(() -> onKeystoreFailed(ex));
              return null;
            });
  }

  // ── Private helpers ────────────────────────────────────────────────
  private void transitionToExporting(App app) {
    if (state instanceof AppState.Browsing b) state = b.toExporting(app.name());
  }

  private EnvWriteResult doExport(App app) {
    try {
      return exportEnvUseCase.execute(app, exportConfig);
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }
  }

  private void onExportDone(EnvWriteResult result) {
    if (state instanceof AppState.EnvExporting e)
      state =
          e.toDone(
              result.path().toString(),
              result.excludedKeys(),
              result.postProcessedKeys(),
              result.clipboardCopied());
  }

  private void onExportFailed(Throwable ex) {
    if (state instanceof AppState.EnvExporting e) state = e.toFailed(unwrap(ex).getMessage());
  }

  private void onKeystoreDone(KeystoreInspectResult result) {
    if (state instanceof AppState.KeystoreExporting k) state = k.toDone(result);
  }

  private void onKeystoreFailed(Throwable ex) {
    if (state instanceof AppState.KeystoreExporting k) state = k.toFailed(unwrap(ex).getMessage());
  }

  private void onCatalogLoaded(List<App> apps) {
    if (state instanceof AppState.CatalogLoading l) state = l.toBrowsing(apps);
  }

  private void onCatalogLoadFailed(Throwable ex) {
    Throwable cause = unwrap(ex);
    state =
        state instanceof AppState.CatalogLoading l
            ? l.toLoadFailed(cause.getMessage())
            : new AppState.CatalogLoadFailed(
                new AppState.HeaderCounts(0, 0, 0), cause.getMessage());
  }

  private CatalogLoadListener buildCatalogLoadListener() {
    return new CatalogLoadListener() {
      @Override
      public void organizationsLoaded(int count) {
        dispatchLoadingUpdate(l -> l.withOrgsLoaded(count));
      }

      @Override
      public void spacesLoaded(int count) {
        dispatchLoadingUpdate(l -> l.withSpacesLoaded(count));
      }

      @Override
      public void appsLoaded(int count) {
        dispatchLoadingUpdate(l -> l.withAppsLoaded(count));
      }
    };
  }

  private void dispatchLoadingUpdate(UnaryOperator<AppState.CatalogLoading> update) {
    dispatch.dispatch(
        () -> {
          if (state instanceof AppState.CatalogLoading l) state = update.apply(l);
        });
  }

  private static Throwable unwrap(Throwable ex) {
    return ex.getCause() != null ? ex.getCause() : ex;
  }
}

// --- Use Cases ---

/** Factory that constructs and exposes all application use-case instances. */
final class UseCases {

  private final LoadCatalogUseCase loadCatalog;
  private final ExportEnvUseCase exportEnv;
  private final OpenAppInBrowserUseCase openInBrowser;
  private final ExportKeystoreUseCase exportKeystore;

  UseCases(EnvConfig config) {
    var sharedGateway =
        new FeignCfPlatformGateway(
            config.uaaUrl(), config.cfApiUrl(), config.cfUsername(), config.cfPassword());
    this.loadCatalog = new LoadCatalogUseCase(config);
    this.exportEnv =
        new ExportEnvUseCase(sharedGateway, config.profileDir(), config.copyToClipboard());
    this.openInBrowser = new OpenAppInBrowserUseCase(config);
    this.exportKeystore = new ExportKeystoreUseCase(sharedGateway, config);
  }

  LoadCatalogUseCase loadCatalog() {
    return loadCatalog;
  }

  ExportEnvUseCase exportEnv() {
    return exportEnv;
  }

  OpenAppInBrowserUseCase openInBrowser() {
    return openInBrowser;
  }

  ExportKeystoreUseCase keystoreUseCase() {
    return exportKeystore;
  }
}

/** Loads the application catalog from CF (via cache or live) and joins it into domain records. */
final class LoadCatalogUseCase {

  private final CatalogProvider catalogProvider;
  private final CatalogJoiner joiner;

  LoadCatalogUseCase(EnvConfig config) {
    this.catalogProvider =
        new CachedCatalogProvider(
            config.uaaUrl(),
            config.cfApiUrl(),
            config.cfUsername(),
            config.cfPassword(),
            config.fresh(),
            CachePaths.cacheDir(config.profileDir()));
    this.joiner = new CatalogJoiner();
  }

  List<App> execute(CatalogLoadListener listener) {
    var snapshot = catalogProvider.loadCatalog(listener);
    return joiner.join(snapshot.organizations(), snapshot.spaces(), snapshot.apps());
  }

  List<App> executeFresh(CatalogLoadListener listener) {
    var snapshot = catalogProvider.loadCatalogFresh(listener);
    return joiner.join(snapshot.organizations(), snapshot.spaces(), snapshot.apps());
  }
}

/** Fetches environment variables for a specific app and writes them to a {@code .env} file. */
final class ExportEnvUseCase {

  private final FeignCfPlatformGateway gateway;
  private final Path profileDir;
  private final boolean copyToClipboard;

  ExportEnvUseCase(FeignCfPlatformGateway gateway, Path profileDir, boolean copyToClipboard) {
    this.gateway = gateway;
    this.profileDir = profileDir;
    this.copyToClipboard = copyToClipboard;
  }

  EnvWriteResult execute(App app, EnvExportConfig config) throws IOException {
    var vars = gateway.fetchAppEnvVars(app.guid());
    var result = EnvFileWriter.write(app, vars, config, CachePaths.envsDir(profileDir));
    var copied = copyToClipboard && ClipboardWriter.copy(result.path().toAbsolutePath().toString());
    return new EnvWriteResult(
        result.path(), result.excludedKeys(), result.postProcessedKeys(), copied);
  }
}

/** Computes and opens the CF Apps Manager URL for a given app in the system browser. */
final class OpenAppInBrowserUseCase {

  private final String cfWebUrl;

  OpenAppInBrowserUseCase(EnvConfig config) {
    this.cfWebUrl = config.cfWebUrl();
  }

  void execute(App app) {
    var url =
        cfWebUrl.stripTrailing()
            + "/organizations/"
            + app.orgGuid()
            + "/spaces/"
            + app.spaceGuid()
            + "/applications/"
            + app.guid();
    BrowserLauncher.open(url);
  }
}

/** Named post-processors applied to specific env-var values before writing. */
enum Processor {
  /**
   * Strips boundary double-quotes, unescapes inner quotes, and collapses whitespace. Intended for
   * {@code SPRING_APPLICATION_JSON} and similar JSON-valued keys.
   */
  JSON {
    @Override
    public String process(String rawValue) {
      if (rawValue == null) return "";
      var v = rawValue;
      if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
        v = v.substring(1, v.length() - 1);
      }
      v = v.replace("\\\"", "\"");
      v = v.replaceAll("\\s+", " ").trim();
      return v;
    }
  };

  public abstract String process(String rawValue);
}

/** Bundles export-time options (exclusions and post-processors) for {@link EnvFileWriter}. */
record EnvExportConfig(List<String> excludeKeys, Map<String, Processor> postProcessors) {}

/**
 * Carries the output file path and per-key accounting from a successful {@link EnvFileWriter}
 * write.
 */
record EnvWriteResult(
    Path path,
    List<String> excludedKeys,
    List<String> postProcessedKeys,
    boolean clipboardCopied) {}

/**
 * Writes a sorted {@code .env} file from a map of CF app environment variables.
 *
 * <p>Keys listed in {@link EnvExportConfig#excludeKeys()} are omitted. Keys with a matching {@link
 * Processor} entry are post-processed before writing. All values are single-quote-wrapped to make
 * the file safe for shell sourcing.
 */
final class EnvFileWriter {

  private EnvFileWriter() {
    throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
  }

  static EnvWriteResult write(
      App app, Map<String, String> vars, EnvExportConfig config, Path envsDir) throws IOException {
    var timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
    var safeName = app.name().replaceAll("[^A-Za-z0-9._-]", "-").replaceAll("-+", "-");
    Files.createDirectories(envsDir);
    var path = envsDir.resolve(safeName + "-" + timestamp + ".env");

    var entries = vars != null ? vars : Map.<String, String>of();

    var actualExcluded =
        entries.keySet().stream().filter(k -> config.excludeKeys().contains(k)).sorted().toList();

    var entriesToWrite =
        entries.entrySet().stream()
            .filter(e -> !config.excludeKeys().contains(e.getKey()))
            .sorted(Map.Entry.comparingByKey())
            .toList();

    var actualPostProcessed =
        entriesToWrite.stream()
            .filter(e -> config.postProcessors().containsKey(e.getKey()))
            .map(Map.Entry::getKey)
            .sorted()
            .toList();

    var content =
        entriesToWrite.stream()
            .map(e -> formatEntry(e.getKey(), e.getValue(), config.postProcessors()))
            .collect(Collectors.joining("\n"));
    Files.writeString(path, content.isEmpty() ? "" : content + "\n");
    return new EnvWriteResult(
        path, List.copyOf(actualExcluded), List.copyOf(actualPostProcessed), false);
  }

  private static String formatEntry(
      String key, String value, Map<String, Processor> postProcessors) {
    var processor = postProcessors.get(key);
    if (processor != null) {
      return key + "=" + wrapInSingleQuotes(processor.process(value));
    }
    return key + "=" + escapeEnvValue(value);
  }

  /** Strips boundary double-quotes from a raw CF value, then wraps it in single quotes. */
  static String escapeEnvValue(String value) {
    if (value == null || value.isEmpty()) return "''";
    var v = value.replaceAll("^\"+|\"+$", "");
    return wrapInSingleQuotes(v);
  }

  /** Wraps a clean value in single quotes, escaping any embedded {@code '} characters. */
  static String wrapInSingleQuotes(String value) {
    if (value == null || value.isEmpty()) return "''";
    return "'" + value.replace("'", "'\\''") + "'";
  }
}

/**
 * Fetches the base64-encoded JKS keystore for a specific app, decodes it, writes it to disk, and
 * inspects its certificate entries.
 */
final class ExportKeystoreUseCase {

  private static final DateTimeFormatter DATE_FMT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private final FeignCfPlatformGateway gateway;
  private final String keystoreVar;
  private final String keystorePasswordVar;
  private final Path jksDir;
  private final boolean copyToClipboard;

  ExportKeystoreUseCase(FeignCfPlatformGateway gateway, EnvConfig config) {
    this.gateway = gateway;
    this.keystoreVar = config.keystoreVar();
    this.keystorePasswordVar = config.keystorePasswordVar();
    this.jksDir = CachePaths.jksDir(config.profileDir());
    this.copyToClipboard = config.copyToClipboard();
  }

  KeystoreInspectResult execute(App app) throws Exception {
    var vars = gateway.fetchAppEnvVars(app.guid());
    var keystoreBytes = decodeBase64EnvVar(vars, keystoreVar, app.name());
    var clearPassword = decodeBase64EnvVarAsString(vars, keystorePasswordVar, app.name());
    var jksPath = KeystoreFileWriter.write(app, keystoreBytes, clearPassword, jksDir);
    var copied = copyToClipboard && ClipboardWriter.copy(jksPath.toAbsolutePath().toString());
    try {
      var entries = inspectKeystore(keystoreBytes, clearPassword);
      return KeystoreInspectResult.success(jksPath, entries, copied);
    } catch (Exception ex) {
      return KeystoreInspectResult.partial(jksPath, ex.getMessage(), copied);
    }
  }

  private byte[] decodeBase64EnvVar(Map<String, String> vars, String varName, String appName) {
    var encoded = requireEnvVar(vars, varName, appName);
    try {
      return Base64.getDecoder().decode(encoded.strip());
    } catch (IllegalArgumentException ex) {
      throw new IllegalStateException(
          "Value of '" + varName + "' is not valid Base64: " + ex.getMessage(), ex);
    }
  }

  private String decodeBase64EnvVarAsString(
      Map<String, String> vars, String varName, String appName) {
    var encoded = requireEnvVar(vars, varName, appName);
    try {
      return new String(Base64.getDecoder().decode(encoded.strip()));
    } catch (IllegalArgumentException ex) {
      throw new IllegalStateException(
          "Value of '" + varName + "' is not valid Base64: " + ex.getMessage(), ex);
    }
  }

  private static String requireEnvVar(Map<String, String> vars, String varName, String appName) {
    var value = vars.get(varName);
    if (value == null) {
      throw new IllegalStateException(
          "Environment variable '" + varName + "' not found for app '" + appName + "'");
    }
    return value;
  }

  private List<KeystoreEntry> inspectKeystore(byte[] keystoreBytes, String clearPassword)
      throws Exception {
    var ks = KeyStore.getInstance("JKS");
    try (var is = new ByteArrayInputStream(keystoreBytes)) {
      ks.load(is, clearPassword.toCharArray());
    }
    var entries = new ArrayList<KeystoreEntry>();
    for (var alias : Collections.list(ks.aliases())) {
      var cert = ks.getCertificate(alias);
      if (cert instanceof X509Certificate x509) {
        entries.add(toKeystoreEntry(alias, x509));
      }
    }
    entries.sort(Comparator.comparing(KeystoreEntry::alias));
    return List.copyOf(entries);
  }

  private KeystoreEntry toKeystoreEntry(String alias, X509Certificate cert) {
    return new KeystoreEntry(
        alias,
        cert.getSubjectX500Principal().getName(),
        cert.getIssuerX500Principal().getName(),
        formatDate(cert.getNotBefore()),
        formatDate(cert.getNotAfter()));
  }

  private String formatDate(Date date) {
    return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime().format(DATE_FMT);
  }
}

// --- Infrastructure ---

/** Receives progress callbacks as each resource type is loaded from the CF platform. */
interface CatalogLoadListener {
  void organizationsLoaded(int count);

  void spacesLoaded(int count);

  void appsLoaded(int count);
}

/** Loads the CF application catalog, either from the live platform or a local cache. */
interface CatalogProvider {
  CatalogSnapshot loadCatalog(CatalogLoadListener listener);

  /**
   * Optional fresh-load variant that forces fetching from the live platform. Default
   * implementation delegates to {@link #loadCatalog(CatalogLoadListener)} so
   * providers that do not support a fresh fetch can reuse the regular load.
   */
  default CatalogSnapshot loadCatalogFresh(CatalogLoadListener listener) {
    return loadCatalog(listener);
  }
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
      String uaaUrl,
      String cfApiUrl,
      String username,
      String password,
      boolean refresh,
      Path cacheDir) {
    this.liveProvider = new LiveCatalogProvider(uaaUrl, cfApiUrl, username, password);
    this.cacheStore = new FileCatalogCacheStore(cacheDir);
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

  @Override
  public CatalogSnapshot loadCatalogFresh(CatalogLoadListener listener) {
    clearCacheSilently();
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

  private static Client trustAllSslClient() {
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
      return new DefaultClient(sslContext.getSocketFactory(), (hostname, session) -> true);
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

  static Path cacheDir(Path base) {
    return base.resolve("cache");
  }

  static Path envsDir(Path base) {
    return base.resolve("envs");
  }

  static Path jksDir(Path base) {
    return base.resolve("jks");
  }
}

/**
 * Writes a raw JKS byte array to disk with a timestamped filename under the default jks directory.
 */
final class KeystoreFileWriter {

  private KeystoreFileWriter() {
    throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
  }

  static Path write(App app, byte[] bytes, String clearPassword, Path jksDir) throws IOException {
    var timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
    var safeName = app.name().replaceAll("[^A-Za-z0-9._-]", "-").replaceAll("-+", "-");
    Files.createDirectories(jksDir);
    var path = jksDir.resolve(safeName + "-pass_" + clearPassword + "-" + timestamp + ".jks");
    Files.write(path, bytes);
    return path;
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

/**
 * Copies text to the system clipboard.
 *
 * <p>Fails silently — if the clipboard is unavailable (e.g. headless environments) the method
 * returns {@code false} without throwing.
 */
final class ClipboardWriter {

  private ClipboardWriter() {
    throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
  }

  static boolean copy(String text) {
    try {
      var selection = new StringSelection(text);
      Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
      return true;
    } catch (Exception e) {
      return false;
    }
  }
}

/** Opens a URL in the operating-system default browser. */
final class BrowserLauncher {

  private BrowserLauncher() {
    throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
  }

  static void open(String url) {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
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
