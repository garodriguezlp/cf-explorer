package cf.explorer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cf.explorer.config.EnvProcessor;
import cf.explorer.config.ExplorerConfig;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
    AtomicReference<ExplorerConfig> captured = new AtomicReference<>();
    CommandLine command = new CommandLine(new CfExplorer() {
      @Override
      void showScreen(ExplorerConfig config) {
        started.set(true);
        captured.set(config);
      }
    });

    assertEquals(0, command.execute());
    assertTrue(started.get());
    ExplorerConfig config = captured.get();
    assertNotNull(config);
    assertFalse(config.fresh());
    assertTrue(config.copyToClipboard());
    assertEquals(List.of("TRUSTSTORE"), config.excludeKeys());
    assertEquals(Map.of("SPRING_APPLICATION_JSON", EnvProcessor.JSON), config.postProcessors());
    assertEquals("KEYSTORE", config.keystoreVar());
    assertEquals("KEYSTORE_PASSWORD", config.keystorePasswordVar());
  }

  @Test
  void validatesProfileNameAndResolvesProfilePrecedenceAndPaths() {
    StringWriter error = new StringWriter();
    CommandLine invalid = new CommandLine(new CfExplorer() {
      @Override
      void showScreen(ExplorerConfig config) {}
    }).setErr(new PrintWriter(error));
    assertEquals(1, invalid.execute("--env", "../bad/profile"));
    assertTrue(error.toString().contains("--env"));

    CfExplorer app = new CfExplorer();
    new CommandLine(app).parseArgs("--env", "prod", "--cf-api-url", "https://cli-api.example",
        "--fresh", "--no-copy-to-clipboard",
        "--exclude-key", "A", "--exclude-key", "B",
        "--post-process", "CUSTOM=JSON",
        "--keystore-var", "KS", "--keystore-password-var", "KS_PASS");

    Map<String, String> sysProps = Map.of(
        "user.home", home.toString(),
        "CF_UAA_URL_PROD", "https://sys-uaa-prod.example");
    Map<String, String> envVars = Map.of(
        "CF_UAA_URL_PROD", "https://env-uaa-prod.example",
        "CF_API_URL_PROD", "https://env-api-prod.example",
        "CF_USERNAME_PROD", "prod-user",
        "CF_PASSWORD_PROD", "prod-pass",
        "CF_WEB_URL_PROD", "https://apps-prod.example");

    ExplorerConfig config = app.buildConfig(sysProps::get, envVars::get);
    assertEquals("prod", config.env());
    assertEquals("https://cli-api.example", config.cfApiUrl());
    assertEquals("https://sys-uaa-prod.example", config.uaaUrl());
    assertEquals("prod-user", config.cfUsername());
    assertEquals("prod-pass", config.cfPassword());
    assertEquals("https://apps-prod.example", config.cfWebUrl());
    assertTrue(config.fresh());
    assertFalse(config.copyToClipboard());
    assertEquals(List.of("A", "B"), config.excludeKeys());
    assertEquals(Map.of("CUSTOM", EnvProcessor.JSON), config.postProcessors());
    assertEquals("KS", config.keystoreVar());
    assertEquals("KS_PASS", config.keystorePasswordVar());
    assertEquals(home.resolve(".cf-explorer").resolve("prod"), config.profileDir());
    assertEquals(home.resolve(".cf-explorer").resolve("prod").resolve("cache"), config.cacheDir());
    assertEquals(home.resolve(".cf-explorer").resolve("prod").resolve("envs"), config.envsDir());
    assertEquals(home.resolve(".cf-explorer").resolve("prod").resolve("jks"), config.jksDir());
  }

  @Test
  void rejectsUnknownOptions() {
    StringWriter error = new StringWriter();
    CommandLine command = new CommandLine(new CfExplorer()).setErr(new PrintWriter(error));

    assertEquals(2, command.execute("--unknown"));
    assertTrue(error.toString().contains("--unknown"));
  }
}
