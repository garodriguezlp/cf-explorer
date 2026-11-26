package cf.explorer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class CfExplorerTest {
  @TempDir Path home;

  @Test
  void startsAndLoadsItsResource() throws Exception {
    Result result = executeWithHome();
    assertEquals(0, result.exitCode());
    assertEquals("cf-explorer: next version (phase 1)\n1", result.output());
  }

  @Test
  void loadsDefaultsFromUserHomeAndCommandLineOverridesThem() throws Exception {
    Files.writeString(home.resolve(".cf-explorer.properties"),
        "message=Fallback message\nthe.message=Configured message\n");

    Result configured = executeWithHome();
    assertEquals(0, configured.exitCode());
    assertEquals("Configured message\n1", configured.output());
    Result overridden = executeWithHome("--message", "Command-line message");
    assertEquals(0, overridden.exitCode());
    assertEquals("Command-line message\n1", overridden.output());
  }

  @Test
  void rejectsMissingRequiredMessage() throws Exception {
    Files.writeString(home.resolve(".cf-explorer.properties"), "unrelated=value\n");

    Result result = executeWithHome();
    assertEquals(2, result.exitCode());
    assertTrue(result.error().contains("--message"));
    assertEquals("", result.output());
    Result explicit = executeWithHome("--message", "Command-line message");
    assertEquals(0, explicit.exitCode());
    assertEquals("Command-line message\n1", explicit.output());
  }

  @Test
  void usesOptionNameWhenDescriptionKeyIsAbsent() throws Exception {
    Files.writeString(home.resolve(".cf-explorer.properties"), "message=Fallback message\n");

    Result result = executeWithHome();
    assertEquals(0, result.exitCode());
    assertEquals("Fallback message\n1", result.output());
  }

  @Test
  void printsZeroCount() throws Exception {
    Files.writeString(home.resolve(".cf-explorer.properties"), "the.message=Hello\ncount=0\n");

    Result result = executeWithHome();
    assertEquals(0, result.exitCode());
    assertEquals("Hello\n0", result.output());
  }

  @Test
  void countDefaultsAndOverridesArePassedToTheScreen() throws Exception {
    Files.writeString(home.resolve(".cf-explorer.properties"),
        "the.message=Hello\ncount=${CF_EXPLORER_COUNT:-1}\n");

    assertEquals("Hello\n1", executeWithHome().output());
    assertEquals("Hello\n3", executeWithHome("--count", "3").output());
    Files.writeString(home.resolve(".cf-explorer.properties"), "the.message=Hello\ncount=2\n");
    assertEquals("Hello\n2", executeWithHome().output());
  }

  private Result executeWithHome(String... args) {
    StringWriter output = new StringWriter();
    StringWriter error = new StringWriter();
    String previousHome = System.getProperty("user.home");
    String previousPath = System.getProperty("picocli.defaults.cf-explorer.path");
    String previousCount = System.getProperty("CF_EXPLORER_COUNT");
    try {
      System.setProperty("user.home", home.toString());
      System.clearProperty("picocli.defaults.cf-explorer.path");
      System.setProperty("CF_EXPLORER_COUNT", "1");
      CommandLine command = new CommandLine(new CfExplorer() {
        @Override
        void showScreen(String message, int count) {
          output.write(message + "\n" + count);
        }
      })
          .setOut(new PrintWriter(output)).setErr(new PrintWriter(error));
      int exitCode = command.execute(args);
      return new Result(exitCode, output.toString().strip().replace("\r\n", "\n"), error.toString());
    } finally {
      System.setProperty("user.home", previousHome);
      if (previousPath == null) {
        System.clearProperty("picocli.defaults.cf-explorer.path");
      } else {
        System.setProperty("picocli.defaults.cf-explorer.path", previousPath);
      }
      if (previousCount == null) {
        System.clearProperty("CF_EXPLORER_COUNT");
      } else {
        System.setProperty("CF_EXPLORER_COUNT", previousCount);
      }
    }
  }

  private record Result(int exitCode, String output, String error) {}

  @Test
  void rejectsUnknownOptions() {
    StringWriter error = new StringWriter();
    CommandLine command = new CommandLine(new CfExplorer()).setErr(new PrintWriter(error));

    assertEquals(2, command.execute("--unknown"));
    assertTrue(error.toString().contains("--unknown"));
  }
}
