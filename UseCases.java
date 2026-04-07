package cf.explorer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Application use cases and the supporting types they own.
 *
 * <p>Each use case is a single-method object that orchestrates infra calls ({@link
 * CatalogProvider}, {@link FeignCfPlatformGateway}) and domain logic ({@link CatalogJoiner}, {@link
 * EnvFileWriter}). The {@link UseCases} factory constructs all three and wires shared config.
 */

/** Factory that constructs and exposes all application use-case instances. */
final class UseCases {

  private final LoadCatalogUseCase loadCatalog;
  private final ExportEnvUseCase exportEnv;
  private final OpenAppInBrowserUseCase openInBrowser;
  private final ExportKeystoreUseCase exportKeystore;

  UseCases(EnvConfig config) {
    this.loadCatalog = new LoadCatalogUseCase(config);
    this.exportEnv = new ExportEnvUseCase(config);
    this.openInBrowser = new OpenAppInBrowserUseCase(config);
    this.exportKeystore = new ExportKeystoreUseCase(config);
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
            config.fresh());
    this.joiner = new CatalogJoiner();
  }

  List<App> execute(CatalogLoadListener listener) {
    var snapshot = catalogProvider.loadCatalog(listener);
    return joiner.join(snapshot.organizations(), snapshot.spaces(), snapshot.apps());
  }
}

/** Fetches environment variables for a specific app and writes them to a {@code .env} file. */
final class ExportEnvUseCase {

  private final FeignCfPlatformGateway gateway;

  ExportEnvUseCase(EnvConfig config) {
    this.gateway =
        new FeignCfPlatformGateway(
            config.uaaUrl(), config.cfApiUrl(), config.cfUsername(), config.cfPassword());
  }

  EnvWriteResult execute(App app, EnvExportConfig config) throws IOException {
    var vars = gateway.fetchAppEnvVars(app.guid());
    return EnvFileWriter.write(app, vars, config);
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
record EnvWriteResult(Path path, List<String> excludedKeys, List<String> postProcessedKeys) {}

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

  static EnvWriteResult write(App app, Map<String, String> vars, EnvExportConfig config)
      throws IOException {
    var timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
    var safeName = app.name().replaceAll("[^A-Za-z0-9._-]", "-").replaceAll("-+", "-");
    var dir = CachePaths.defaultEnvsDir();
    Files.createDirectories(dir);
    var path = dir.resolve(safeName + "-" + timestamp + ".env");

    var entries = vars != null ? vars : Map.<String, String>of();
    var actualExcluded = new ArrayList<String>();
    var actualPostProcessed = new ArrayList<String>();

    var content =
        entries.entrySet().stream()
            .filter(
                e -> {
                  if (config.excludeKeys().contains(e.getKey())) {
                    actualExcluded.add(e.getKey());
                    return false;
                  }
                  return true;
                })
            .sorted(Map.Entry.comparingByKey())
            .map(
                e -> {
                  var processor = config.postProcessors().get(e.getKey());
                  if (processor != null) {
                    actualPostProcessed.add(e.getKey());
                    return e.getKey() + "=" + wrapInSingleQuotes(processor.process(e.getValue()));
                  }
                  return e.getKey() + "=" + escapeEnvValue(e.getValue());
                })
            .collect(Collectors.joining("\n"));
    Files.writeString(path, content.isEmpty() ? "" : content + "\n");
    actualExcluded.sort(null);
    actualPostProcessed.sort(null);
    return new EnvWriteResult(path, List.copyOf(actualExcluded), List.copyOf(actualPostProcessed));
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

  ExportKeystoreUseCase(EnvConfig config) {
    this.gateway =
        new FeignCfPlatformGateway(
            config.uaaUrl(), config.cfApiUrl(), config.cfUsername(), config.cfPassword());
    this.keystoreVar = config.keystoreVar();
    this.keystorePasswordVar = config.keystorePasswordVar();
  }

  KeystoreInspectResult execute(App app) throws Exception {
    var vars = gateway.fetchAppEnvVars(app.guid());

    var encodedKeystore = vars.get(keystoreVar);
    if (encodedKeystore == null) {
      throw new IllegalStateException(
          "Environment variable '" + keystoreVar + "' not found for app '" + app.name() + "'");
    }

    var encodedPassword = vars.get(keystorePasswordVar);
    if (encodedPassword == null) {
      throw new IllegalStateException(
          "Environment variable '" + keystorePasswordVar + "' not found for app '" + app.name() + "'");
    }

    final byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(encodedKeystore.strip());
    } catch (IllegalArgumentException ex) {
      throw new IllegalStateException(
          "Value of '" + keystoreVar + "' is not valid Base64: " + ex.getMessage(), ex);
    }

    final String clearPassword;
    try {
      clearPassword = new String(Base64.getDecoder().decode(encodedPassword.strip()));
    } catch (IllegalArgumentException ex) {
      throw new IllegalStateException(
          "Value of '" + keystorePasswordVar + "' is not valid Base64: " + ex.getMessage(), ex);
    }

    var jksPath = KeystoreFileWriter.write(app, bytes, clearPassword);

    try {
      var ks = KeyStore.getInstance("JKS");
      try (var is = new java.io.ByteArrayInputStream(bytes)) {
        ks.load(is, clearPassword.toCharArray());
      }
      var entries = new ArrayList<KeystoreEntry>();
      var aliases = Collections.list(ks.aliases());
      for (var alias : aliases) {
        var cert = ks.getCertificate(alias);
        if (cert instanceof X509Certificate x509) {
          var subjectDN = x509.getSubjectX500Principal().getName();
          var issuer = x509.getIssuerX500Principal().getName();
          var notBefore =
              x509.getNotBefore()
                  .toInstant()
                  .atZone(ZoneId.systemDefault())
                  .toLocalDateTime()
                  .format(DATE_FMT);
          var notAfter =
              x509.getNotAfter()
                  .toInstant()
                  .atZone(ZoneId.systemDefault())
                  .toLocalDateTime()
                  .format(DATE_FMT);
          entries.add(new KeystoreEntry(alias, subjectDN, issuer, notBefore, notAfter));
        }
      }
      entries.sort(java.util.Comparator.comparing(KeystoreEntry::alias));
      return KeystoreInspectResult.success(jksPath, List.copyOf(entries));
    } catch (Exception ex) {
      return KeystoreInspectResult.partial(jksPath, ex.getMessage());
    }
  }
}
