package cf.explorer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LaunchIT {
  @TempDir Path tempDir;

  @Test
  void launchesAndExitsThroughEachSupportedPath() throws Exception {
    Path root = Path.of("").toAbsolutePath();
    Path sourceOnly = tempDir.resolve("source-only");
    try (Stream<Path> paths = Files.walk(root.resolve("src/main"))) {
      for (Path path : paths.toList()) {
        Path destination = sourceOnly.resolve(root.relativize(path));
        if (Files.isDirectory(path)) {
          Files.createDirectories(destination);
        } else {
          Files.copy(path, destination);
        }
      }
    }
    Files.copy(root.resolve("jbang-catalog.json"), sourceOnly.resolve("jbang-catalog.json"));

    run(root, List.of("java", "-jar", "target/cf-explorer.jar"));
    run(root, command("mvn", "-q", "compile", "exec:java"));
    run(sourceOnly, command("jbang", "--quiet", "src/main/java/cf/explorer/CfExplorer.java"));
    run(sourceOnly, command("jbang", "--quiet", "cf-explorer"));

    Set<String> requested = ConcurrentHashMap.newKeySet();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      try (exchange) {
        String path = exchange.getRequestURI().getPath();
        Path file = sourceOnly.resolve(path.substring(1)).normalize();
        if (!file.startsWith(sourceOnly) || !Files.isRegularFile(file)) {
          exchange.sendResponseHeaders(404, -1);
        } else {
          requested.add(path);
          byte[] bytes = Files.readAllBytes(file);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
        }
      }
    });
    server.start();
    try {
      Path empty = Files.createDirectory(tempDir.resolve("empty"));
      String catalog = "cf-explorer@http://127.0.0.1:" + server.getAddress().getPort()
          + "/jbang-catalog.json";
      run(empty, command("jbang", "--quiet", catalog));
      assertTrue(requested.containsAll(Set.of(
          "/jbang-catalog.json",
          "/src/main/java/cf/explorer/CfExplorer.java",
          "/src/main/java/cf/explorer/ExplorerCommands.java",
          "/src/main/java/cf/explorer/ExplorerScreen.java",
          "/src/main/java/cf/explorer/browser/AppsManagerUrl.java",
          "/src/main/java/cf/explorer/browser/BrowserLauncher.java",
          "/src/main/java/cf/explorer/catalog/ApplicationCatalog.java",
          "/src/main/java/cf/explorer/catalog/CachedCatalogService.java",
          "/src/main/java/cf/explorer/catalog/CatalogCacheStore.java",
          "/src/main/java/cf/explorer/catalog/CloudFoundryCatalogClient.java",
          "/src/main/java/cf/explorer/config/EnvProcessor.java",
          "/src/main/java/cf/explorer/config/ExplorerConfig.java",
          "/src/main/java/cf/explorer/example/ExampleFeatures.java",
          "/src/main/java/cf/explorer/export/EnvExportService.java",
          "/src/main/java/cf/explorer/export/EnvFileWriter.java",
          "/src/main/java/cf/explorer/io/ClipboardSupport.java",
          "/src/main/java/cf/explorer/keystore/KeystoreEntry.java",
          "/src/main/java/cf/explorer/keystore/KeystoreService.java",
          "/src/main/java/cf/explorer/model/ShellCommand.java",
          "/src/main/java/cf/explorer/model/ShellCommands.java",
          "/src/main/java/cf/explorer/model/ShellPage.java",
          "/src/main/java/cf/explorer/model/ShellState.java",
          "/src/main/java/cf/explorer/ui/ApplicationCatalogPage.java",
          "/src/main/java/cf/explorer/ui/EnvExportPage.java",
          "/src/main/java/cf/explorer/ui/KeystoreViewPage.java",
          "/src/main/java/cf/explorer/ui/ShellView.java",
          "/src/main/resources/.cf-explorer.properties")), requested.toString());
    } finally {
      server.stop(0);
    }
  }

  private List<String> command(String executable, String... arguments) {
    boolean windows = System.getProperty("os.name").startsWith("Windows");
    List<String> command = new ArrayList<>();
    if (windows) {
      command.add("cmd.exe");
      command.add("/c");
      command.add(executable.equals("jbang") ? Path.of("jbang.cmd").toAbsolutePath().toString() : "mvn.cmd");
    } else {
      command.add(executable.equals("jbang") ? Path.of("jbang").toAbsolutePath().toString() : executable);
    }
    command.addAll(List.of(arguments));
    return command;
  }

  private void run(Path directory, List<String> command) throws Exception {
    Path outputFile = tempDir.resolve("output.txt");
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true)
        .redirectOutput(outputFile.toFile()).directory(directory.toFile());
    builder.environment().put("JBANG_CACHE_DIR", Files.createTempDirectory(tempDir, "cache-").toString());
    builder.environment().put("TERM", "xterm-256color");
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
      process.getOutputStream().write("/quit\n".getBytes(StandardCharsets.UTF_8));
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
