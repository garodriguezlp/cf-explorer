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
  @TempDir Path tempDir;

  @Test
  void launchesAndExitsThroughEachSupportedPath() throws Exception {
    run(List.of("java", "-jar", "target/cf-explorer.jar", "--message", "Launch test"));
    run(command("mvn", "-q", "compile", "exec:java", "-Dexec.args=--message=LaunchTest"));
    run(command("jbang", "--quiet", "src/main/java/cf/explorer/CfExplorer.java",
        "--message", "Launch test"));
    run(command("jbang", "--quiet", "cf-explorer", "--message", "Launch test"));
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

  private void run(List<String> command) throws Exception {
    Path outputFile = tempDir.resolve("output.txt");
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true)
        .redirectOutput(outputFile.toFile());
    builder.environment().put("JBANG_CACHE_DIR", tempDir.toString());
    builder.environment().put("TERM", "xterm-256color");
    builder.environment().remove("CF_EXPLORER_COUNT");
    Process process = builder.start();
    try {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
      String output = "";
      while (System.nanoTime() < deadline && process.isAlive()) {
        output = new String(Files.readAllBytes(outputFile), StandardCharsets.UTF_8);
        if (output.contains("\u001b[?25l")) {
          break;
        }
        Thread.sleep(100);
      }
      assertTrue(output.contains("\u001b[?25l"), command + " did not start the TUI:\n" + output);
      process.getOutputStream().write('q');
      process.getOutputStream().flush();
      assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Timed out exiting: " + command);
      output = Files.readString(outputFile, StandardCharsets.UTF_8);
      assertEquals(0, process.exitValue(), command + ":\n" + output);
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        process.waitFor();
      }
    }
  }
}
