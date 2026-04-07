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
      names = "--uaa-url",
      required = true,
      description = "UAA base URL.",
      defaultValue = "${CF_UAA_URL:-http://localhost:9090}")
  private String uaaUrl;

  @Option(
      names = "--cf-api-url",
      required = true,
      description = "CF API base URL.",
      defaultValue = "${CF_API_URL:-http://localhost:9090}")
  private String cfApiUrl;

  @Option(
      names = "--cf-username",
      required = true,
      description = "CF username.",
      defaultValue = "${CF_USERNAME:-admin}")
  private String cfUsername;

  @Option(
      names = "--cf-password",
      required = true,
      description = "CF password.",
      defaultValue = "${CF_PASSWORD:-admin}")
  private String cfPassword;

  @Option(
      names = "--cf-web-url",
      required = true,
      description =
          "CF Apps Manager base URL. Used by Ctrl+O to open the selected app in the browser.",
      defaultValue = "${CF_WEB_URL:-http://localhost:9090}")
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

  public static void main(String[] args) {
    int exitCode = new CommandLine(new CfExplorer()).execute(args);
    System.exit(exitCode);
  }

  @Override
  public Integer call() throws Exception {
    var config =
        new EnvConfig(
            uaaUrl,
            cfApiUrl,
            cfUsername,
            cfPassword,
            cfWebUrl,
            fresh,
            List.copyOf(excludeKeys),
            Map.copyOf(postProcessors),
            keystoreVar,
            keystorePasswordVar);
    new Launcher(config).run();
    return 0;
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
    String keystorePasswordVar) {}

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
