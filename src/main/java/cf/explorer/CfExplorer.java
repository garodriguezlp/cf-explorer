//JAVA 17+

//COMPILE_OPTIONS -encoding UTF-8
//RUNTIME_OPTIONS -Dfile.encoding=UTF-8

//DEPS org.jline:jline:3.25.1
//DEPS dev.tamboui:tamboui-toolkit:0.7.0
//DEPS dev.tamboui:tamboui-jline3-backend:0.7.0
//DEPS info.picocli:picocli:4.7.6
//DEPS com.fasterxml.jackson.core:jackson-databind:2.19.1

//SOURCES ExplorerScreen.java
//SOURCES catalog/ApplicationCatalog.java
//SOURCES catalog/CloudFoundryCatalogClient.java
//SOURCES example/ExampleFeatures.java
//SOURCES model/ShellCommand.java
//SOURCES model/ShellCommands.java
//SOURCES model/ShellPage.java
//SOURCES model/ShellState.java
//SOURCES ui/ShellView.java
//SOURCES ui/ApplicationCatalogPage.java

//FILES .cf-explorer.properties=../../../resources/.cf-explorer.properties

package cf.explorer;

import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.PropertiesDefaultProvider;

@Command(name = "cf-explorer", mixinStandardHelpOptions = true,
    defaultValueProvider = PropertiesDefaultProvider.class,
    description = "The next version of cf-explorer.")
public class CfExplorer implements Callable<Integer> {
  @Option(names = "--cf-api-url", descriptionKey = "cf.api.url",
      defaultValue = "${CF_API_URL:-http://localhost:9090}",
      description = "Cloud Foundry API URL. Env: CF_API_URL.")
  String cfApiUrl;

  @Option(names = "--uaa-url", defaultValue = "${CF_UAA_URL:-http://localhost:9090}",
      description = "UAA URL. Env: CF_UAA_URL.")
  String uaaUrl;

  @Option(names = "--cf-username", defaultValue = "${CF_USERNAME:-admin}",
      description = "Cloud Foundry username. Env: CF_USERNAME.")
  String username;

  @Option(names = "--cf-password", defaultValue = "${CF_PASSWORD:-admin}",
      description = "Cloud Foundry password. Env: CF_PASSWORD.")
  String password;

  public static void main(String[] args) {
    System.exit(new CommandLine(new CfExplorer()).execute(args));
  }

  @Override
  public Integer call() throws Exception {
    showScreen();
    return 0;
  }

  void showScreen() throws Exception {
    new ExplorerScreen(uaaUrl, cfApiUrl, username, password).run();
  }
}
