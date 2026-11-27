package cf.explorer.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Fully resolved runtime configuration for one cf-explorer session. */
public record ExplorerConfig(
    String env,
    String uaaUrl,
    String cfApiUrl,
    String cfUsername,
    String cfPassword,
    String cfWebUrl,
    boolean skipSslValidation,
    boolean fresh,
    List<String> excludeKeys,
    Map<String, EnvProcessor> postProcessors,
    String keystoreVar,
    String keystorePasswordVar,
    boolean copyToClipboard,
    Path profileDir) {

  public static final String DEFAULT_URL = "http://localhost:9090";
  public static final String DEFAULT_USERNAME = "admin";
  public static final String DEFAULT_PASSWORD = "admin";
  public static final String DEFAULT_EXCLUDE_KEY = "TRUSTSTORE";
  public static final String DEFAULT_KEYSTORE_VAR = "KEYSTORE";
  public static final String DEFAULT_KEYSTORE_PASSWORD_VAR = "KEYSTORE_PASSWORD";

  public ExplorerConfig {
    Objects.requireNonNull(uaaUrl);
    Objects.requireNonNull(cfApiUrl);
    Objects.requireNonNull(cfUsername);
    Objects.requireNonNull(cfPassword);
    Objects.requireNonNull(cfWebUrl);
    excludeKeys = List.copyOf(Objects.requireNonNull(excludeKeys));
    postProcessors = Map.copyOf(Objects.requireNonNull(postProcessors));
    Objects.requireNonNull(keystoreVar);
    Objects.requireNonNull(keystorePasswordVar);
    Objects.requireNonNull(profileDir);
  }

  public Path cacheDir() {
    return profileDir.resolve("cache");
  }

  public Path envsDir() {
    return profileDir.resolve("envs");
  }

  public Path jksDir() {
    return profileDir.resolve("jks");
  }

  public static boolean isValidEnv(String env) {
    return env == null || env.matches("[a-zA-Z0-9_-]+");
  }

  public static Path profileDir(String userHome, String env) {
    Path base = Path.of(userHome != null && !userHome.isBlank() ? userHome : ".", ".cf-explorer");
    return env != null ? base.resolve(env) : base;
  }

  public static String envSuffix(String env) {
    return env != null ? "_" + env.toUpperCase(Locale.ROOT) : "";
  }
}
