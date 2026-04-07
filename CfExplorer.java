///usr/bin/env jbang "$0" "$@" ; exit $?

//JAVA 17

//COMPILE_OPTIONS -encoding UTF-8
//RUNTIME_OPTIONS -Dfile.encoding=UTF-8

//DEPS dev.tamboui:tamboui-toolkit:LATEST
//DEPS dev.tamboui:tamboui-jline3-backend:LATEST
//DEPS info.picocli:picocli:4.7.6
//DEPS io.github.openfeign:feign-core:13.11
//DEPS io.github.openfeign:feign-form:13.11
//DEPS io.github.openfeign:feign-jackson:13.11
//DEPS tools.jackson.core:jackson-databind:3.1.1

//SOURCES Domain.java
//SOURCES Model.java
//SOURCES View.java
//SOURCES Infra.java
//SOURCES UseCases.java
//SOURCES Controller.java
//SOURCES KeyHandler.java

package cf.explorer;

import dev.tamboui.toolkit.app.ToolkitApp;
import dev.tamboui.toolkit.element.Element;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.PropertiesDefaultProvider;

@Command(
    name = "cf-explorer",
    mixinStandardHelpOptions = true,
    description = "Interactive Cloud Foundry environment browser",
    defaultValueProvider = PropertiesDefaultProvider.class)
public class CfExplorer implements Callable<Integer> {

  @Option(
      names = "--env",
      description =
          "Profile name (letters, digits, hyphens, underscores). When set, connection params"
              + " are read from CF_*_<ENV> env vars and local dirs are scoped to"
              + " ~/.cf-explorer/<env>/.")
  private String env;

  @Option(
      names = "--uaa-url",
      description = "UAA base URL. Env: CF_UAA_URL[_<ENV>]. Default: http://localhost:9090")
  private String uaaUrl;

  @Option(
      names = "--cf-api-url",
      description = "CF API base URL. Env: CF_API_URL[_<ENV>]. Default: http://localhost:9090")
  private String cfApiUrl;

  @Option(
      names = "--cf-username",
      description = "CF username. Env: CF_USERNAME[_<ENV>]. Default: admin")
  private String cfUsername;

  @Option(
      names = "--cf-password",
      description = "CF password. Env: CF_PASSWORD[_<ENV>]. Default: admin")
  private String cfPassword;

  @Option(
      names = "--cf-web-url",
      description =
          "CF Apps Manager base URL. Used by Ctrl+O to open the selected app in the browser."
              + " Env: CF_WEB_URL[_<ENV>]. Default: http://localhost:9090")
  private String cfWebUrl;

  @Option(
      names = "--fresh",
      description = "Ignore local catalog cache and fetch all orgs/spaces/apps from CF API.")
  private boolean fresh;

  @Option(
      names = "--exclude-key",
      description = "Keys to exclude (exact, case-sensitive). Can be repeated.",
      defaultValue = "TRUSTSTORE")
  private List<String> excludeKeys;

  @Option(
      names = "--post-process",
      description = "Apply a named post-processor to specific keys. Format: KEY=PROCESSOR.",
      defaultValue = "SPRING_APPLICATION_JSON=JSON")
  private Map<String, Processor> postProcessors;

  @Option(
      names = "--keystore-var",
      description =
          "Name of the CF env var that holds the base64-encoded JKS keystore.",
      defaultValue = "${KEYSTORE_VAR:-KEYSTORE}")
  private String keystoreVar;

  @Option(
      names = "--keystore-password-var",
      description =
          "Name of the CF env var that holds the base64-encoded keystore password.",
      defaultValue = "${KEYSTORE_PASSWORD_VAR:-KEYSTORE_PASSWORD}")
  private String keystorePasswordVar;

  @Option(
      names = "--copy-to-clipboard",
      negatable = true,
      description =
          "Copy the exported file path to the system clipboard after a successful export."
              + " Use --no-copy-to-clipboard to disable. Default: true",
      defaultValue = "true")
  private boolean copyToClipboard;

  public static void main(String[] args) {
    int exitCode = new CommandLine(new CfExplorer()).execute(args);
    System.exit(exitCode);
  }

  @Override
  public Integer call() throws Exception {
    if (!validateEnv()) return 1;
    new Launcher(buildConfig()).run();
    return 0;
  }

  private boolean validateEnv() {
    if (env != null && !env.matches("[a-zA-Z0-9_-]+")) {
      System.err.println(
          "Error: --env value must contain only letters, digits, hyphens, or underscores.");
      return false;
    }
    return true;
  }

  private EnvConfig buildConfig() {
    var suffix = envSuffix();
    return new EnvConfig(
        resolve(uaaUrl, "CF_UAA_URL", suffix, "http://localhost:9090"),
        resolve(cfApiUrl, "CF_API_URL", suffix, "http://localhost:9090"),
        resolve(cfUsername, "CF_USERNAME", suffix, "admin"),
        resolve(cfPassword, "CF_PASSWORD", suffix, "admin"),
        resolve(cfWebUrl, "CF_WEB_URL", suffix, "http://localhost:9090"),
        fresh,
        List.copyOf(excludeKeys),
        Map.copyOf(postProcessors),
        keystoreVar,
        keystorePasswordVar,
        copyToClipboard,
        profileDir());
  }

  private String envSuffix() {
    return env != null ? "_" + env.toUpperCase() : "";
  }

  private Path profileDir() {
    var home = System.getProperty("user.home", ".");
    return env != null
        ? Path.of(home, ".cf-explorer", env)
        : Path.of(home, ".cf-explorer");
  }

  private static String resolve(
      String cliVal, String baseVar, String envSuffix, String fallback) {
    if (cliVal != null && !cliVal.isBlank()) return cliVal;
    if (!envSuffix.isEmpty()) {
      var profileVal = System.getenv(baseVar + envSuffix);
      if (profileVal != null && !profileVal.isBlank()) return profileVal;
    }
    var baseVal = System.getenv(baseVar);
    if (baseVal != null && !baseVal.isBlank()) return baseVal;
    return fallback;
  }
}

record EnvConfig(
    String uaaUrl,
    String cfApiUrl,
    String cfUsername,
    String cfPassword,
    String cfWebUrl,
    boolean fresh,
    List<String> excludeKeys,
    Map<String, Processor> postProcessors,
    String keystoreVar,
    String keystorePasswordVar,
    boolean copyToClipboard,
    Path profileDir) {}

final class Launcher extends ToolkitApp {

  private final Controller controller;
  private final View view;
  private final KeyHandler keyHandler;

  Launcher(EnvConfig config) {
    UiDispatcher dispatch = action -> runner().runOnRenderThread(action);
    this.controller = new Controller(config, dispatch);
    this.keyHandler = new KeyHandler(controller);
    this.view = new View(controller, keyHandler);
  }

  @Override
  protected void onStart() {
    controller.start();
  }

  @Override
  protected Element render() {
    return view.render();
  }
}
