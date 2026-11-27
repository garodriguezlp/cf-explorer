//JAVA 17+

//COMPILE_OPTIONS -encoding UTF-8
//RUNTIME_OPTIONS -Dfile.encoding=UTF-8

//DEPS org.jline:jline:3.25.1
//DEPS dev.tamboui:tamboui-toolkit:0.7.0
//DEPS dev.tamboui:tamboui-jline3-backend:0.7.0
//DEPS info.picocli:picocli:4.7.6
//DEPS com.fasterxml.jackson.core:jackson-databind:2.19.1

//SOURCES ExplorerCommands.java
//SOURCES ExplorerScreen.java
//SOURCES browser/AppsManagerUrl.java
//SOURCES browser/BrowserLauncher.java
//SOURCES catalog/ApplicationCatalog.java
//SOURCES catalog/CachedCatalogService.java
//SOURCES catalog/CatalogCacheStore.java
//SOURCES catalog/CloudFoundryCatalogClient.java
//SOURCES config/EnvProcessor.java
//SOURCES config/ExplorerConfig.java
//SOURCES example/ExampleFeatures.java
//SOURCES export/EnvExportService.java
//SOURCES export/EnvFileWriter.java
//SOURCES io/ClipboardSupport.java
//SOURCES keystore/KeystoreEntry.java
//SOURCES keystore/KeystoreService.java
//SOURCES model/ShellCommand.java
//SOURCES model/ShellCommands.java
//SOURCES model/ShellPage.java
//SOURCES model/ShellState.java
//SOURCES ui/ApplicationCatalogPage.java
//SOURCES ui/EnvExportPage.java
//SOURCES ui/KeystoreViewPage.java
//SOURCES ui/ShellView.java

//FILES .cf-explorer.properties=../../../resources/.cf-explorer.properties

package cf.explorer;

import cf.explorer.config.EnvProcessor;
import cf.explorer.config.ExplorerConfig;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Function;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParseResult;
import picocli.CommandLine.PropertiesDefaultProvider;
import picocli.CommandLine.Spec;

@Command(name = "cf-explorer", mixinStandardHelpOptions = true,
    defaultValueProvider = PropertiesDefaultProvider.class,
    description = "Interactive Cloud Foundry environment browser.")
public class CfExplorer implements Callable<Integer> {
  @Spec
  CommandSpec spec;

  @Option(names = "--env",
      description = "Profile name (letters, digits, hyphens, underscores). "
          + "Reads CF_*_<ENV> variables first and scopes local files to ~/.cf-explorer/<env>/.")
  String env;

  @Option(names = "--cf-api-url", descriptionKey = "cf.api.url",
      defaultValue = "${CF_API_URL:-http://localhost:9090}",
      description = "Cloud Foundry API URL. Env: CF_API_URL[_<ENV>].")
  String cfApiUrl;

  @Option(names = "--uaa-url", defaultValue = "${CF_UAA_URL:-http://localhost:9090}",
      description = "UAA URL. Env: CF_UAA_URL[_<ENV>].")
  String uaaUrl;

  @Option(names = "--cf-username", defaultValue = "${CF_USERNAME:-admin}",
      description = "Cloud Foundry username. Env: CF_USERNAME[_<ENV>].")
  String username;

  @Option(names = "--cf-password", defaultValue = "${CF_PASSWORD:-admin}",
      description = "Cloud Foundry password. Env: CF_PASSWORD[_<ENV>].")
  String password;

  @Option(names = "--cf-web-url", defaultValue = "${CF_WEB_URL:-http://localhost:9090}",
      description = "CF Apps Manager base URL. Env: CF_WEB_URL[_<ENV>].")
  String cfWebUrl;

  @Option(names = "--fresh",
      description = "Ignore local catalog cache and fetch fresh data from Cloud Foundry.")
  boolean fresh;

  @Option(names = "--exclude-key",
      description = "Keys to exclude from .env export (exact, case-sensitive). Repeatable.",
      defaultValue = ExplorerConfig.DEFAULT_EXCLUDE_KEY)
  List<String> excludeKeys;

  @Option(names = "--post-process",
      description = "Apply a named post-processor to specific keys. Format: KEY=PROCESSOR.",
      defaultValue = "SPRING_APPLICATION_JSON=JSON")
  Map<String, EnvProcessor> postProcessors;

  @Option(names = "--keystore-var",
      description = "CF env var holding the base64-encoded JKS keystore.",
      defaultValue = "${KEYSTORE_VAR:-KEYSTORE}")
  String keystoreVar;

  @Option(names = "--keystore-password-var",
      description = "CF env var holding the base64-encoded keystore password.",
      defaultValue = "${KEYSTORE_PASSWORD_VAR:-KEYSTORE_PASSWORD}")
  String keystorePasswordVar;

  @Option(names = "--copy-to-clipboard",
      negatable = true,
      description = "Copy exported file paths to the system clipboard. Default: true.",
      defaultValue = "true",
      fallbackValue = "true")
  boolean copyToClipboard;

  public static void main(String[] args) {
    System.exit(new CommandLine(new CfExplorer()).execute(args));
  }

  @Override
  public Integer call() throws Exception {
    if (!ExplorerConfig.isValidEnv(env)) {
      String message =
          "Error: --env value must contain only letters, digits, hyphens, or underscores.";
      if (spec != null && spec.commandLine() != null) {
        spec.commandLine().getErr().println(message);
      } else {
        System.err.println(message);
      }
      return 1;
    }
    showScreen(buildConfig());
    return 0;
  }

  ExplorerConfig buildConfig() {
    return buildConfig(System::getProperty, System::getenv);
  }

  ExplorerConfig buildConfig(Function<String, String> systemProperties,
      Function<String, String> environment) {
    String suffix = ExplorerConfig.envSuffix(env);
    ParseResult parseResult = spec != null && spec.commandLine() != null
        ? spec.commandLine().getParseResult() : null;
    return new ExplorerConfig(
        env,
        resolveOption(parseResult, "--uaa-url", uaaUrl, "CF_UAA_URL", suffix,
            ExplorerConfig.DEFAULT_URL, systemProperties, environment),
        resolveOption(parseResult, "--cf-api-url", cfApiUrl, "CF_API_URL", suffix,
            ExplorerConfig.DEFAULT_URL, systemProperties, environment),
        resolveOption(parseResult, "--cf-username", username, "CF_USERNAME", suffix,
            ExplorerConfig.DEFAULT_USERNAME, systemProperties, environment),
        resolveOption(parseResult, "--cf-password", password, "CF_PASSWORD", suffix,
            ExplorerConfig.DEFAULT_PASSWORD, systemProperties, environment),
        resolveOption(parseResult, "--cf-web-url", cfWebUrl, "CF_WEB_URL", suffix,
            ExplorerConfig.DEFAULT_URL, systemProperties, environment),
        fresh,
        excludeKeys != null ? excludeKeys : List.of(ExplorerConfig.DEFAULT_EXCLUDE_KEY),
        postProcessors != null ? postProcessors : Map.of("SPRING_APPLICATION_JSON", EnvProcessor.JSON),
        keystoreVar != null ? keystoreVar : ExplorerConfig.DEFAULT_KEYSTORE_VAR,
        keystorePasswordVar != null ? keystorePasswordVar
            : ExplorerConfig.DEFAULT_KEYSTORE_PASSWORD_VAR,
        copyToClipboard,
        ExplorerConfig.profileDir(systemProperties.apply("user.home"), env));
  }

  private static String resolveOption(ParseResult parseResult, String optionName,
      String parsedValue, String baseVar, String envSuffix, String fallback,
      Function<String, String> systemProperties, Function<String, String> environment) {
    if (parseResult != null && parseResult.hasMatchedOption(optionName)) {
      return parsedValue;
    }
    if (!envSuffix.isEmpty()) {
      String profileKey = baseVar + envSuffix;
      String sysVal = systemProperties.apply(profileKey);
      if (sysVal != null && !sysVal.isBlank()) {
        return sysVal;
      }
      String envVal = environment.apply(profileKey);
      if (envVal != null && !envVal.isBlank()) {
        return envVal;
      }
    }
    return parsedValue != null && !parsedValue.isBlank() ? parsedValue : fallback;
  }

  void showScreen(ExplorerConfig config) throws Exception {
    new ExplorerScreen(config).run();
  }
}
