///usr/bin/env jbang "$0" "$@" ; exit $?

//JAVA 17

//COMPILE_OPTIONS -encoding UTF-8
//RUNTIME_OPTIONS -Dfile.encoding=UTF-8

//DEPS org.wiremock:wiremock-standalone:3.5.3
//DEPS info.picocli:picocli:4.7.6

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
    name = "mock-server",
    mixinStandardHelpOptions = true,
    description = "Starts a WireMock server with CF API / UAA stubs")
public class MockServer implements Callable<Integer> {

  @Option(
      names = "--port",
      description = "Port to listen on.",
      defaultValue = "${MOCK_SERVER_PORT:-9090}")
  private int port;

  @Option(
      names = "--root-dir",
      description = "WireMock root directory (must contain a mappings/ subfolder).",
      defaultValue = "${MOCK_SERVER_ROOT_DIR:-wiremock}")
  private String rootDir;

  public static void main(String[] args) {
    int exitCode = new CommandLine(new MockServer()).execute(args);
    System.exit(exitCode);
  }

  @Override
  public Integer call() throws InterruptedException {
    System.out.println("================================================================");
    System.out.println(" WireMock 3.5.3 — CF API / UAA Simulator");
    System.out.println("================================================================");
    System.out.println(" Token endpoint   : POST http://localhost:" + port + "/oauth/token");
    System.out.println(" Organizations    : GET  http://localhost:" + port + "/v3/organizations");
    System.out.println(" Spaces           : GET  http://localhost:" + port + "/v3/spaces");
    System.out.println(" Apps             : GET  http://localhost:" + port + "/v3/apps");
    System.out.println(
        " Env vars         : GET  http://localhost:" + port + "/v3/apps/*/environment_variables");
    System.out.println(
        " CF web app page  : GET  http://localhost:"
            + port
            + "/organizations/.../applications/...");
    System.out.println(" Mappings dir     : " + rootDir + "/mappings");
    System.out.println("================================================================");
    System.out.println();

    WireMockServer server =
        new WireMockServer(wireMockConfig().port(port).usingFilesUnderDirectory(rootDir));

    server.start();
    System.out.println("WireMock listening on port " + port + " — press Ctrl+C to stop");

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  System.out.println("\nShutting down WireMock...");
                  server.stop();
                }));

    Thread.currentThread().join();
    return 0;
  }
}
