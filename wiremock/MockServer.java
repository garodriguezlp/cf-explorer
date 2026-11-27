///usr/bin/env jbang "$0" "$@" ; exit $?

//JAVA 17

//COMPILE_OPTIONS -encoding UTF-8
//RUNTIME_OPTIONS -Dfile.encoding=UTF-8

//DEPS org.wiremock:wiremock-standalone:3.5.3
//DEPS info.picocli:picocli:4.7.6

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import wiremock.com.fasterxml.jackson.databind.JsonNode;
import wiremock.com.fasterxml.jackson.databind.node.ArrayNode;
import wiremock.com.fasterxml.jackson.databind.node.ObjectNode;

@Command(
    name = "mock-server",
    mixinStandardHelpOptions = true,
    description = "Starts a WireMock server with CF API / UAA stubs")
public class MockServer implements Callable<Integer> {
  private static final Pattern APP_ACTION_PATH =
      Pattern.compile("^/v3/apps/([^/]+)/actions/(start|stop|restart)$");

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
    System.out.println(" Token endpoint   : POST  http://localhost:" + port + "/oauth/token");
    System.out.println(" Organizations    : GET   http://localhost:" + port + "/v3/organizations");
    System.out.println(" Spaces           : GET   http://localhost:" + port + "/v3/spaces");
    System.out.println(" Apps             : GET   http://localhost:" + port + "/v3/apps");
    System.out.println(
        " App actions      : POST  http://localhost:"
            + port
            + "/v3/apps/*/actions/{start,stop,restart}");
    System.out.println(
        " App restage      : GET   /v3/apps/*/packages, POST/GET /v3/builds, PATCH current_droplet");
    System.out.println(
        " Env vars         : GET   http://localhost:" + port + "/v3/apps/*/environment_variables");
    System.out.println(
        " CF web app page  : GET   http://localhost:"
            + port
            + "/organizations/.../applications/...");
    System.out.println(" Mappings dir     : " + rootDir + "/mappings");
    System.out.println("================================================================");
    System.out.println();

    WireMockServer server =
        new WireMockServer(
            wireMockConfig()
                .port(port)
                .usingFilesUnderDirectory(rootDir)
                .extensions(new StatefulAppStateTransformer()));

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

  /** Remembers start/stop/restart mutations in memory so subsequent /v3/apps refreshes match. */
  private static final class StatefulAppStateTransformer
      implements ResponseDefinitionTransformerV2 {
    private final Map<String, String[]> statesByGuid = new ConcurrentHashMap<>();

    @Override
    public String getName() {
      return "stateful-app-state";
    }

    @Override
    public ResponseDefinition transform(ServeEvent serveEvent) {
      ResponseDefinition original = serveEvent.getResponseDefinition();
      if (original.getStatus() < 200 || original.getStatus() >= 300) {
        return original;
      }
      String method = serveEvent.getRequest().getMethod().getName();
      String path = serveEvent.getRequest().getUrl().split("\\?", 2)[0];
      Matcher actionMatcher = APP_ACTION_PATH.matcher(path);
      if ("POST".equals(method) && actionMatcher.matches()) {
        String guid = actionMatcher.group(1);
        String action = actionMatcher.group(2);
        String newState = "stop".equals(action) ? "STOPPED" : "STARTED";
        String now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        statesByGuid.put(guid, new String[] {newState, now});
        return original;
      }
      if ("GET".equals(method) && "/v3/apps".equals(path) && !statesByGuid.isEmpty()) {
        JsonNode body = original.getJsonBody();
        if (body != null && body.isObject() && body.path("resources").isArray()) {
          ObjectNode copy = ((ObjectNode) body).deepCopy();
          ArrayNode resources = (ArrayNode) copy.get("resources");
          for (JsonNode item : resources) {
            if (item.isObject()) {
              String[] override = statesByGuid.get(item.path("guid").asText());
              if (override != null) {
                ((ObjectNode) item).put("state", override[0]);
                ((ObjectNode) item).put("updated_at", override[1]);
              }
            }
          }
          return ResponseDefinitionBuilder.like(original)
              .but()
              .withJsonBody(copy)
              .build();
        }
      }
      return original;
    }
  }
}
