package cf.explorer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LaunchIT {
  @TempDir Path jbangCache;

  @Test
  void launchesThroughEachSupportedPath() throws Exception {
    String expected = "cf-explorer: next version (phase 1)\n1";
    assertEquals(expected, run(List.of("java", "-jar", "target/cf-explorer.jar")));
    assertEquals(expected, run(command("mvn", "-q", "compile", "exec:java")));
    assertEquals(expected, run(command("jbang", "--quiet", "src/main/java/cf/explorer/CfExplorer.java")));
    assertEquals(expected, run(command("jbang", "--quiet", "cf-explorer")));
  }

  @Test
  void executableJarLoadsUserDefaults() throws Exception {
    Files.writeString(jbangCache.resolve(".cf-explorer.properties"), "the.message=Configured message\n");
    String home = "-Duser.home=" + jbangCache;

    assertEquals("Configured message\n1", run(List.of("java", home, "-jar", "target/cf-explorer.jar")));
    assertEquals("Command-line message\n1", run(List.of(
        "java", home, "-jar", "target/cf-explorer.jar", "--message", "Command-line message")));
  }

  @Test
  void countSupportsEnvironmentDefaultsAndCommandLineOverrides() throws Exception {
    Files.writeString(jbangCache.resolve(".cf-explorer.properties"),
        "the.message=Hello\ncount=${CF_EXPLORER_COUNT:-1}\n");
    String home = "-Duser.home=" + jbangCache;
    List<String> command = List.of("java", home, "-jar", "target/cf-explorer.jar");

    assertEquals("Hello\n1", run(command, null));
    assertEquals("Hello\n3", run(command, "3"));
    assertEquals("Hello\n2", run(List.of(
        "java", home, "-jar", "target/cf-explorer.jar", "--count", "2"), "3"));
    Files.writeString(jbangCache.resolve(".cf-explorer.properties"), "the.message=Hello\ncount=2\n");
    assertEquals("Hello\n2", run(command, "3"));
    Files.writeString(jbangCache.resolve(".cf-explorer.properties"), "the.message=Hello\n");
    assertEquals("Hello\n3", run(command, "3"));
  }

  private List<String> command(String executable, String... arguments) {
    boolean windows = System.getProperty("os.name").startsWith("Windows");
    List<String> command = new ArrayList<>();
    if (windows) {
      command.add("cmd.exe");
      command.add("/c");
      command.add(executable.equals("jbang") ? "jbang.cmd" : "mvn.cmd");
    } else {
      command.add(executable.equals("jbang") ? "./jbang" : executable);
    }
    command.addAll(List.of(arguments));
    return command;
  }

  private String run(List<String> command) throws Exception {
    return run(command, null);
  }

  private String run(List<String> command, String count) throws Exception {
    Path outputFile = jbangCache.resolve("output.txt");
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true)
        .redirectOutput(outputFile.toFile());
    builder.environment().put("JBANG_CACHE_DIR", jbangCache.toString());
    if (count == null) {
      builder.environment().remove("CF_EXPLORER_COUNT");
    } else {
      builder.environment().put("CF_EXPLORER_COUNT", count);
    }
    Process process = builder.start();
    boolean finished = process.waitFor(120, TimeUnit.SECONDS);
    if (!finished) {
      process.destroyForcibly();
    }
    assertTrue(finished, "Timed out: " + command);
    String output = Files.readString(outputFile, StandardCharsets.UTF_8);
    assertEquals(0, process.exitValue(), command + ":\n" + output);
    return output.strip().replace("\r\n", "\n");
  }
}
