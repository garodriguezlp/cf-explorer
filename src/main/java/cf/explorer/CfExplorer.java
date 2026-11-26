//JAVA 17+
//DEPS info.picocli:picocli:4.7.6
//FILES .cf-explorer.properties=../../../resources/.cf-explorer.properties

package cf.explorer;

import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.PropertiesDefaultProvider;
import picocli.CommandLine.Spec;

@Command(name = "cf-explorer", mixinStandardHelpOptions = true,
    defaultValueProvider = PropertiesDefaultProvider.class,
    description = "The next version of cf-explorer.")
public class CfExplorer implements Callable<Integer> {
  @Spec CommandSpec spec;

  @Option(names = "--message", required = true, descriptionKey = "the.message",
      description = "Message to display.")
  String message;

  @Option(names = "--count", defaultValue = "${CF_EXPLORER_COUNT:-1}",
      description = "Count to display.")
  int count;

  public static void main(String[] args) {
    System.exit(new CommandLine(new CfExplorer()).execute(args));
  }

  @Override
  public Integer call() {
    spec.commandLine().getOut().println(message);
    spec.commandLine().getOut().println(count);
    return 0;
  }
}
