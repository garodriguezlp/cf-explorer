//JAVA 17+

//COMPILE_OPTIONS -encoding UTF-8
//RUNTIME_OPTIONS -Dfile.encoding=UTF-8

//DEPS org.jline:jline:3.25.1
//DEPS dev.tamboui:tamboui-toolkit:0.7.0
//DEPS dev.tamboui:tamboui-jline3-backend:0.7.0
//DEPS info.picocli:picocli:4.7.6

//FILES .cf-explorer.properties=../../../resources/.cf-explorer.properties

package cf.explorer;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.text;

import dev.tamboui.toolkit.app.ToolkitApp;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.EventResult;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.PropertiesDefaultProvider;

@Command(name = "cf-explorer", mixinStandardHelpOptions = true,
    defaultValueProvider = PropertiesDefaultProvider.class,
    description = "The next version of cf-explorer.")
public class CfExplorer implements Callable<Integer> {
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
  public Integer call() throws Exception {
    showScreen(message, count);
    return 0;
  }

  void showScreen(String message, int count) throws Exception {
    new Screen(message, count).run();
  }

  private static final class Screen extends ToolkitApp {
    private final String message;
    private final int count;

    Screen(String message, int count) {
      this.message = message;
      this.count = count;
    }

    @Override
    protected Element render() {
      return panel(column(text(message), text("Count: " + count), text("Press q to quit.")))
          .title("cf-explorer")
          .rounded()
          .id("main")
          .focusable()
          .onKeyEvent(event -> {
            if (event.isChar('q') || event.isCtrlC()) {
              quit();
              return EventResult.HANDLED;
            }
            return EventResult.UNHANDLED;
          });
    }
  }
}
