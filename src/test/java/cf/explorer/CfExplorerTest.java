package cf.explorer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class CfExplorerTest {
  @TempDir Path home;

  @Test
  void bundlesTheFutureConfigurationPlaceholder() throws Exception {
    try (InputStream resource = CfExplorer.class.getResourceAsStream("/.cf-explorer.properties")) {
      assertNotNull(resource);
      Properties properties = new Properties();
      properties.load(resource);
      assertTrue(properties.containsKey("cf.api.url"));
    }
  }

  @Test
  void readsPropertyDefaultsAndAllowsCommandLineOverrides() throws Exception {
    String previousHome = System.getProperty("user.home");
    String previousPath = System.getProperty("picocli.defaults.cf-explorer.path");
    String previousUrl = System.getProperty("CF_API_URL");
    try {
      System.setProperty("user.home", home.toString());
      System.clearProperty("picocli.defaults.cf-explorer.path");
      System.setProperty("CF_API_URL", "https://system.example");
      assertEquals("https://system.example", parseUrl());

      Files.writeString(home.resolve(".cf-explorer.properties"),
          "cf.api.url=https://configured.example\n");
      assertEquals("https://configured.example", parseUrl());
      assertEquals("https://cli.example", parseUrl("--cf-api-url", "https://cli.example"));

      Path custom = home.resolve("custom.properties");
      Files.writeString(custom, "cf.api.url=https://custom.example\n");
      System.setProperty("picocli.defaults.cf-explorer.path", custom.toString());
      assertEquals("https://custom.example", parseUrl());
      Files.writeString(custom, "unrelated=value\n");
      assertEquals("https://system.example", parseUrl());
    } finally {
      System.setProperty("user.home", previousHome);
      if (previousPath == null) {
        System.clearProperty("picocli.defaults.cf-explorer.path");
      } else {
        System.setProperty("picocli.defaults.cf-explorer.path", previousPath);
      }
      if (previousUrl == null) {
        System.clearProperty("CF_API_URL");
      } else {
        System.setProperty("CF_API_URL", previousUrl);
      }
    }
  }

  @Test
  void readsTheEnvironmentVariable() throws Exception {
    ProcessBuilder builder = new ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-Duser.home=" + home, "-cp",
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
        EnvironmentProbe.class.getName());
    builder.environment().put("CF_API_URL", "https://environment.example");
    builder.environment().remove("JAVA_TOOL_OPTIONS");
    Process process = builder.redirectErrorStream(true).start();
    try {
      assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Environment probe timed out");
      String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      assertEquals(0, process.exitValue(), output);
      assertEquals("https://environment.example", output.strip());
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        process.waitFor();
      }
    }
  }

  public static class EnvironmentProbe {
    public static void main(String[] args) {
      CfExplorer app = new CfExplorer();
      new CommandLine(app).parseArgs(args);
      System.out.println(app.cfApiUrl);
    }
  }

  private String parseUrl(String... args) {
    CfExplorer app = new CfExplorer();
    new CommandLine(app).parseArgs(args);
    return app.cfApiUrl;
  }

  @Test
  void startsTheScreenWithoutOptions() {
    AtomicBoolean started = new AtomicBoolean();
    CommandLine command = new CommandLine(new CfExplorer() {
      @Override
      void showScreen() {
        started.set(true);
      }
    });

    assertEquals(0, command.execute());
    assertTrue(started.get());
  }

  @Test
  void rejectsUnknownOptions() {
    StringWriter error = new StringWriter();
    CommandLine command = new CommandLine(new CfExplorer()).setErr(new PrintWriter(error));

    assertEquals(2, command.execute("--unknown"));
    assertTrue(error.toString().contains("--unknown"));
  }
}
