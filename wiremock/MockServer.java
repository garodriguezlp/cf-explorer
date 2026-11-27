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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import wiremock.com.fasterxml.jackson.databind.JsonNode;
import wiremock.com.fasterxml.jackson.databind.ObjectMapper;
import wiremock.com.fasterxml.jackson.databind.node.ArrayNode;
import wiremock.com.fasterxml.jackson.databind.node.ObjectNode;

@Command(
    name = "mock-server",
    mixinStandardHelpOptions = true,
    description = "Starts a stateful WireMock Cloud Foundry v3 & UAA simulator")
public class MockServer implements Callable<Integer> {
  private static final Pattern APP_ACTION_PATH =
      Pattern.compile("^/v3/apps/([^/]+)/actions/(start|stop|restart)$");
  private static final Pattern APP_ENV_VARS_PATH =
      Pattern.compile("^/v3/apps/([^/]+)/environment_variables$");
  private static final Pattern APP_PACKAGES_PATH =
      Pattern.compile("^/v3/apps/([^/]+)/packages$");
  private static final Pattern BUILD_POLL_PATH =
      Pattern.compile("^/v3/builds/([^/]+)$");
  private static final Pattern APP_DROPLET_PATH =
      Pattern.compile("^/v3/apps/([^/]+)/relationships/current_droplet$");
  private static final Pattern WEB_APP_PATH =
      Pattern.compile("^/organizations/([^/]+)/spaces/([^/]+)/applications/([^/]+)$");

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
    FoundationSeed seed = FoundationSeed.load(Path.of(rootDir, "mappings"));

    System.out.println("================================================================");
    System.out.println(" WireMock 3.5.3 — Stateful Cloud Foundry v3 & UAA Simulator");
    System.out.println("================================================================");
    System.out.println(" Foundation       : " + seed.orgs.size() + " orgs, "
        + seed.spaces.size() + " spaces, " + seed.apps.size() + " apps (all stateful)");
    System.out.println(" Credentials      : admin / admin");
    System.out.println(" Token endpoint   : POST      http://localhost:" + port + "/oauth/token");
    System.out.println(" Catalog          : GET       http://localhost:" + port
        + "/v3/{organizations,spaces,apps}");
    System.out.println(" Lifecycle        : POST      http://localhost:" + port
        + "/v3/apps/*/actions/{start,stop,restart}");
    System.out.println(" Restage          : GET/POST  /v3/apps/*/packages, /v3/builds/*, current_droplet");
    System.out.println(" Env editor/export: GET/PATCH http://localhost:" + port
        + "/v3/apps/*/environment_variables");
    System.out.println(" Keystore         : Pre-seeded KEYSTORE + KEYSTORE_PASSWORD on all 16 apps");
    System.out.println(" Apps Manager UI  : GET       http://localhost:" + port
        + "/organizations/*/spaces/*/applications/*");
    System.out.println("================================================================");
    System.out.println();

    WireMockServer server =
        new WireMockServer(
            wireMockConfig()
                .port(port)
                .usingFilesUnderDirectory(rootDir)
                .extensions(new StatefulFoundationTransformer(seed)));

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

  private record SpaceMeta(String guid, String name, String orgGuid) {}

  private record AppMeta(
      String guid,
      String name,
      String initialState,
      String createdAt,
      String initialUpdatedAt,
      String spaceGuid,
      ObjectNode rawTemplate) {}

  private static final class BuildTrack {
    final String buildGuid;
    final String dropletGuid;
    final AtomicInteger polls = new AtomicInteger();

    BuildTrack(String buildGuid, String dropletGuid) {
      this.buildGuid = buildGuid;
      this.dropletGuid = dropletGuid;
    }
  }

  private static final class FoundationSeed {
    private static final ObjectMapper JSON = new ObjectMapper();
    final Map<String, String> orgs;
    final Map<String, SpaceMeta> spaces;
    final Map<String, AppMeta> apps;
    final Map<String, String> baseEnvVars;

    private FoundationSeed(
        Map<String, String> orgs,
        Map<String, SpaceMeta> spaces,
        Map<String, AppMeta> apps,
        Map<String, String> baseEnvVars) {
      this.orgs = orgs;
      this.spaces = spaces;
      this.apps = apps;
      this.baseEnvVars = baseEnvVars;
    }

    static FoundationSeed load(Path mappingsDir) {
      Map<String, String> orgs = new LinkedHashMap<>();
      Map<String, SpaceMeta> spaces = new LinkedHashMap<>();
      Map<String, AppMeta> apps = new LinkedHashMap<>();
      Map<String, String> baseVars = new LinkedHashMap<>();

      JsonNode orgResources = readMappingBody(mappingsDir.resolve("v3-organizations.json"))
          .path("resources");
      if (orgResources.isArray()) {
        for (JsonNode org : orgResources) {
          orgs.put(org.path("guid").asText(), org.path("name").asText());
        }
      }

      JsonNode spaceResources = readMappingBody(mappingsDir.resolve("v3-spaces.json"))
          .path("resources");
      if (spaceResources.isArray()) {
        for (JsonNode sp : spaceResources) {
          String guid = sp.path("guid").asText();
          String name = sp.path("name").asText();
          String orgGuid = sp.path("relationships").path("organization").path("data")
              .path("guid").asText();
          spaces.put(guid, new SpaceMeta(guid, name, orgGuid));
        }
      }

      JsonNode appResources = readMappingBody(mappingsDir.resolve("v3-apps.json"))
          .path("resources");
      if (appResources.isArray()) {
        for (JsonNode app : appResources) {
          if (app.isObject()) {
            String guid = app.path("guid").asText();
            String spaceGuid = app.path("relationships").path("space").path("data")
                .path("guid").asText();
            apps.put(guid, new AppMeta(
                guid,
                app.path("name").asText(),
                app.path("state").asText("STOPPED"),
                app.path("created_at").asText("2024-01-01T00:00:00Z"),
                app.path("updated_at").asText("2024-01-01T00:00:00Z"),
                spaceGuid,
                ((ObjectNode) app).deepCopy()));
          }
        }
      }

      JsonNode varNode = readMappingBody(mappingsDir.resolve("v3-app-env-vars.json")).path("var");
      if (varNode.isObject()) {
        Iterator<Map.Entry<String, JsonNode>> fields = varNode.fields();
        while (fields.hasNext()) {
          Map.Entry<String, JsonNode> entry = fields.next();
          JsonNode v = entry.getValue();
          baseVars.put(entry.getKey(), v == null || v.isNull() ? "" : v.asText());
        }
      }

      return new FoundationSeed(
          Collections.unmodifiableMap(orgs),
          Collections.unmodifiableMap(spaces),
          Collections.unmodifiableMap(apps),
          Collections.unmodifiableMap(baseVars));
    }

    /** Synthesizes realistic, distinct environment variables for each of the 16 apps. */
    Map<String, String> buildInitialEnvForApp(String appGuid) {
      AppMeta app = apps.get(appGuid);
      if (app == null) {
        return baseEnvVars;
      }
      SpaceMeta space = spaces.get(app.spaceGuid());
      String spaceName = space != null ? space.name() : "development";
      String orgName = space != null ? orgs.getOrDefault(space.orgGuid(), "cf-org") : "cf-org";
      boolean prod = "production".equalsIgnoreCase(spaceName);

      Map<String, String> vars = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
      vars.put("APP_NAME", app.name());
      vars.put("APP_ENV", spaceName);
      vars.put("CF_ORG_NAME", orgName);
      vars.put("LOG_LEVEL", prod ? "INFO" : "DEBUG");
      vars.put("SPRING_PROFILES_ACTIVE", prod ? "cloud,prod" : "cloud," + spaceName);
      vars.put("JAVA_OPTS", prod
          ? "-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"
          : "-XX:MaxRAMPercentage=70.0 -Dspring.devtools.restart.enabled=false");
      vars.put("MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE", "health,info,metrics,prometheus");
      vars.put("SPRING_APPLICATION_JSON",
          "{\n"
              + "  \"spring.application.name\": \"" + app.name() + "\",\n"
              + "  \"info.environment\": \"" + spaceName + "\",\n"
              + "  \"server.shutdown\": \"graceful\",\n"
              + "  \"management.tracing.sampling.probability\": " + (prod ? "0.1" : "1.0") + "\n"
              + "}");

      // Domain-specific realistic variables tailored per app
      switch (appGuid) {
        case "app-guid-0001" -> { // customer-portal-frontend
          vars.put("API_GATEWAY_URL", "https://api.platform.internal/v2");
          vars.put("CDN_ASSET_BASE_URL", "https://cdn.example.com/portal");
          vars.put("SESSION_COOKIE_SECURE", "true");
          vars.put("FEATURE_NEW_CHECKOUT", "true");
        }
        case "app-guid-0002" -> { // payment-gateway-api
          vars.put("DATABASE_URL", "postgres://payments-db.internal:5432/payments");
          vars.put("REDIS_URL", "redis://payments-cache.internal:6379/0");
          vars.put("STRIPE_API_BASE_URL", "https://api.stripe.com/v1");
          vars.put("WEBHOOK_SIGNING_SECRET", "whsec_sim_9f8e7d6c5b4a3210");
          vars.put("PAYMENT_IDEMPOTENCY_TTL_SEC", "86400");
        }
        case "app-guid-0003" -> { // notification-service
          vars.put("SMTP_RELAY_HOST", "smtp.staging.internal:587");
          vars.put("SLACK_WEBHOOK_URL", "https://hooks.slack.com/services/T000/B000/sim-token");
          vars.put("RABBITMQ_URI", "amqps://mq.staging.internal:5671/notifications");
          vars.put("RATE_LIMIT_PER_MINUTE", "120");
        }
        case "app-guid-0004" -> { // auth-service-v2
          vars.put("OAUTH2_ISSUER_URI", "https://login.sandbox.internal/oauth/token");
          vars.put("JWT_ACCESS_TOKEN_TTL_SEC", "900");
          vars.put("JWT_SIGNING_KEY_ID", "sandbox-rsa-2026-03");
          vars.put("MFA_ENFORCED", "false");
        }
        case "app-guid-0005" -> { // data-pipeline-worker
          vars.put("KAFKA_BOOTSTRAP_SERVERS", "kafka-1.analytics.internal:9092,kafka-2.analytics.internal:9092");
          vars.put("S3_CHECKPOINT_BUCKET", "s3://analytics-prod-checkpoints/pipeline");
          vars.put("WORKER_CONCURRENCY", "16");
          vars.put("DLQ_TOPIC_NAME", "analytics.events.dlq");
        }
        case "app-guid-0006" -> { // report-generator
          vars.put("WAREHOUSE_JDBC_URL", "jdbc:postgresql://warehouse-ro.analytics.internal:5432/bi");
          vars.put("REPORT_OUTPUT_BUCKET", "s3://analytics-prod-reports/pdf");
          vars.put("PDF_RENDER_TIMEOUT_MS", "45000");
        }
        case "app-guid-0007" -> { // ml-inference-api
          vars.put("MODEL_ARTIFACT_URI", "s3://ml-models-integration/churn-v4/model.onnx");
          vars.put("INFERENCE_BATCH_MAX_SIZE", "32");
          vars.put("INFERENCE_TIMEOUT_MS", "1500");
          vars.put("FEATURE_STORE_URL", "https://features.analytics.internal/v1");
        }
        case "app-guid-0008" -> { // support-ticket-processor
          vars.put("ZENDESK_API_URL", "https://support.example.com/api/v2");
          vars.put("ZENDESK_API_TOKEN", "zd_sim_token_customer_success_prod");
          vars.put("AUTO_TRIAGE_ENABLED", "true");
          vars.put("ESCALATION_SLA_MINUTES", "30");
        }
        case "app-guid-0009" -> { // batch-export-service
          vars.put("EXPORT_CHUNK_SIZE", "5000");
          vars.put("EXPORT_SFTP_HOST", "sftp.partners.internal:22");
          vars.put("EXPORT_RETENTION_DAYS", "14");
        }
        case "app-guid-0010" -> { // infra-health-monitor
          vars.put("PROMETHEUS_QUERY_URL", "http://prometheus.ops.internal:9090");
          vars.put("PAGERDUTY_ROUTING_KEY", "pd_sim_sandbox_routing_key_0010");
          vars.put("PROBE_INTERVAL_SECONDS", "15");
        }
        default -> { // long-named enterprise apps 0011..0016
          vars.put("DATABASE_URL", "postgres://db." + orgName + ".internal:5432/"
              + app.name().replace('-', '_'));
          vars.put("BATCH_WINDOW_CRON", "0 */15 * * * *");
          vars.put("MAX_RETRY_ATTEMPTS", "5");
          vars.put("CIRCUIT_BREAKER_THRESHOLD", "50");
        }
      }

      // Preserve TRUSTSTORE + valid JKS KEYSTORE & KEYSTORE_PASSWORD so /export and /keystore work on every app
      for (String sharedKey : new String[] {"TRUSTSTORE", "KEYSTORE", "KEYSTORE_PASSWORD"}) {
        String val = baseEnvVars.get(sharedKey);
        if (val != null) {
          vars.put(sharedKey, val);
        }
      }
      return Collections.unmodifiableMap(new LinkedHashMap<>(vars));
    }

    private static JsonNode readMappingBody(Path file) {
      if (!Files.isRegularFile(file)) {
        return JSON.createObjectNode();
      }
      try {
        return JSON.readTree(Files.readString(file)).path("response").path("jsonBody");
      } catch (IOException ex) {
        return JSON.createObjectNode();
      }
    }
  }

  /**
   * Stateful transformer backing every feature in {@code cf-explorer}:
   * <ul>
   *   <li>{@code /v3/apps} reflects live {@code state} and {@code updated_at} mutations</li>
   *   <li>{@code /start}, {@code /stop}, {@code /restart} return complete v3 App JSON with real GUIDs and timestamps</li>
   *   <li>{@code /restage} tracks independent per-build staging lifecycles and active droplets</li>
   *   <li>{@code /env}, {@code /export}, {@code /keystore} read &amp; persist per-app environment variables</li>
   *   <li>{@code /open} renders a live Apps Manager HTML dashboard showing current app state &amp; env vars</li>
   * </ul>
   */
  private static final class StatefulFoundationTransformer
      implements ResponseDefinitionTransformerV2 {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final FoundationSeed seed;
    private final Map<String, String[]> statesByGuid = new ConcurrentHashMap<>();
    private final Map<String, String> dropletsByAppGuid = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> envVarsByGuid = new ConcurrentHashMap<>();
    private final Map<String, BuildTrack> buildsByGuid = new ConcurrentHashMap<>();
    private final AtomicInteger buildSeq = new AtomicInteger(100);

    private StatefulFoundationTransformer(FoundationSeed seed) {
      this.seed = seed;
    }

    @Override
    public String getName() {
      return "stateful-foundation";
    }

    @Override
    public ResponseDefinition transform(ServeEvent serveEvent) {
      ResponseDefinition original = serveEvent.getResponseDefinition();
      if (original.getStatus() < 200 || original.getStatus() >= 300) {
        return original;
      }
      String method = serveEvent.getRequest().getMethod().getName();
      String path = serveEvent.getRequest().getUrl().split("\\?", 2)[0];

      // 1. GET /v3/apps — overlay live state and updated_at
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
          return ResponseDefinitionBuilder.like(original).but().withJsonBody(copy).build();
        }
      }

      // 2. POST /v3/apps/{guid}/actions/{start|stop|restart}
      Matcher actionMatcher = APP_ACTION_PATH.matcher(path);
      if ("POST".equals(method) && actionMatcher.matches()) {
        String guid = actionMatcher.group(1);
        String action = actionMatcher.group(2);
        AppMeta app = seed.apps.get(guid);
        if (app == null) {
          return notFound("App not found: " + guid);
        }
        String newState = "stop".equals(action) ? "STOPPED" : "STARTED";
        String now = nowIso();
        statesByGuid.put(guid, new String[] {newState, now});
        ObjectNode appJson = app.rawTemplate().deepCopy();
        appJson.put("state", newState);
        appJson.put("updated_at", now);
        System.out.printf("[mock-server] %-7s %s (%s -> %s)%n",
            method, path, app.name(), newState);
        return ResponseDefinitionBuilder.like(original)
            .but()
            .withTransformers()
            .withJsonBody(appJson)
            .build();
      }

      // 3. Restage flow: packages, builds, and current_droplet
      Matcher pkgMatcher = APP_PACKAGES_PATH.matcher(path);
      if ("GET".equals(method) && pkgMatcher.matches()) {
        String appGuid = pkgMatcher.group(1);
        if (!seed.apps.containsKey(appGuid)) {
          return notFound("App not found: " + appGuid);
        }
        ObjectNode body = JSON.createObjectNode();
        ArrayNode resources = body.putArray("resources");
        ObjectNode pkg = resources.addObject();
        pkg.put("guid", "pkg-" + appGuid);
        pkg.put("state", "READY");
        return ResponseDefinitionBuilder.like(original).but().withJsonBody(body).build();
      }

      if ("POST".equals(method) && "/v3/builds".equals(path)) {
        int id = buildSeq.incrementAndGet();
        String buildGuid = "build-guid-" + id;
        String dropletGuid = "droplet-guid-" + id;
        buildsByGuid.put(buildGuid, new BuildTrack(buildGuid, dropletGuid));
        ObjectNode buildJson = JSON.createObjectNode();
        buildJson.put("guid", buildGuid);
        buildJson.put("state", "STAGING");
        System.out.printf("[mock-server] POST    /v3/builds -> %s (STAGING)%n", buildGuid);
        return ResponseDefinitionBuilder.like(original).but().withJsonBody(buildJson).build();
      }

      Matcher buildMatcher = BUILD_POLL_PATH.matcher(path);
      if ("GET".equals(method) && buildMatcher.matches()) {
        String buildGuid = buildMatcher.group(1);
        BuildTrack track = buildsByGuid.computeIfAbsent(
            buildGuid, g -> new BuildTrack(g, "droplet-for-" + g));
        int pollNum = track.polls.incrementAndGet();
        ObjectNode buildJson = JSON.createObjectNode();
        buildJson.put("guid", track.buildGuid);
        if (pollNum < 2) {
          buildJson.put("state", "STAGING");
        } else {
          buildJson.put("state", "STAGED");
          buildJson.putObject("droplet").put("guid", track.dropletGuid);
        }
        return ResponseDefinitionBuilder.like(original).but().withJsonBody(buildJson).build();
      }

      Matcher dropletMatcher = APP_DROPLET_PATH.matcher(path);
      if ("PATCH".equals(method) && dropletMatcher.matches()) {
        String appGuid = dropletMatcher.group(1);
        AppMeta app = seed.apps.get(appGuid);
        if (app == null) {
          return notFound("App not found: " + appGuid);
        }
        String dropletGuid = "droplet-guid-default";
        try {
          JsonNode req = JSON.readTree(serveEvent.getRequest().getBodyAsString());
          String parsed = req.path("data").path("guid").asText("");
          if (!parsed.isBlank()) {
            dropletGuid = parsed;
          }
        } catch (IOException ignored) {
          // Use fallback droplet guid
        }
        dropletsByAppGuid.put(appGuid, dropletGuid);
        bumpUpdatedAt(app);
        ObjectNode out = JSON.createObjectNode();
        out.putObject("data").put("guid", dropletGuid);
        return ResponseDefinitionBuilder.like(original).but().withJsonBody(out).build();
      }

      // 4. Environment variables: GET and PATCH per app
      Matcher envMatcher = APP_ENV_VARS_PATH.matcher(path);
      if (envMatcher.matches()) {
        String guid = envMatcher.group(1);
        AppMeta app = seed.apps.get(guid);
        if (app == null && !seed.apps.isEmpty()) {
          return notFound("App not found: " + guid);
        }
        if ("GET".equals(method)) {
          Map<String, String> current = envVarsFor(guid);
          return ResponseDefinitionBuilder.like(original)
              .but()
              .withJsonBody(toVarObject(current))
              .build();
        }
        if ("PATCH".equals(method)) {
          int[] counts = new int[3]; // [added, updated, deleted]
          Map<String, String> updated = envVarsByGuid.compute(guid, (k, previous) -> {
            Map<String, String> merged = new LinkedHashMap<>(
                previous != null ? previous : seed.buildInitialEnvForApp(k));
            try {
              JsonNode req = JSON.readTree(serveEvent.getRequest().getBodyAsString());
              JsonNode varPatch = req.path("var");
              if (varPatch.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = varPatch.fields();
                while (fields.hasNext()) {
                  Map.Entry<String, JsonNode> entry = fields.next();
                  String key = entry.getKey();
                  JsonNode val = entry.getValue();
                  if (val == null || val.isNull()) {
                    if (merged.remove(key) != null) {
                      counts[2]++;
                    }
                  } else {
                    if (merged.containsKey(key)) {
                      counts[1]++;
                    } else {
                      counts[0]++;
                    }
                    merged.put(key, val.asText());
                  }
                }
              }
            } catch (IOException ignoredEx) {
              // Keep existing map on malformed JSON
            }
            return Collections.unmodifiableMap(merged);
          });
          if (app != null) {
            bumpUpdatedAt(app);
            System.out.printf(
                "[mock-server] PATCH   %s (%s: +%d new, ~%d updated, -%d deleted -> %d total vars)%n",
                path, app.name(), counts[0], counts[1], counts[2], updated.size());
          }
          return ResponseDefinitionBuilder.like(original)
              .but()
              .withJsonBody(toVarObject(updated))
              .build();
        }
      }

      // 5. Apps Manager web dashboard (/open)
      Matcher webMatcher = WEB_APP_PATH.matcher(path);
      if ("GET".equals(method) && webMatcher.matches()) {
        String orgGuid = webMatcher.group(1);
        String spaceGuid = webMatcher.group(2);
        String appGuid = webMatcher.group(3);
        String html = renderAppsManagerHtml(orgGuid, spaceGuid, appGuid);
        return ResponseDefinitionBuilder.like(original)
            .but()
            .withHeader("Content-Type", "text/html; charset=UTF-8")
            .withBody(html)
            .build();
      }

      return original;
    }

    private Map<String, String> envVarsFor(String appGuid) {
      return envVarsByGuid.computeIfAbsent(appGuid, seed::buildInitialEnvForApp);
    }

    private void bumpUpdatedAt(AppMeta app) {
      String now = nowIso();
      statesByGuid.compute(app.guid(), (g, prev) ->
          new String[] {prev != null ? prev[0] : app.initialState(), now});
    }

    private String renderAppsManagerHtml(String orgGuid, String spaceGuid, String appGuid) {
      AppMeta app = seed.apps.get(appGuid);
      String orgName = seed.orgs.getOrDefault(orgGuid, orgGuid);
      SpaceMeta space = seed.spaces.get(spaceGuid);
      String spaceName = space != null ? space.name() : spaceGuid;
      String appName = app != null ? app.name() : appGuid;
      String[] override = statesByGuid.get(appGuid);
      String state = override != null ? override[0]
          : (app != null ? app.initialState() : "UNKNOWN");
      String updatedAt = override != null ? override[1]
          : (app != null ? app.initialUpdatedAt() : "-");
      String createdAt = app != null ? app.createdAt() : "-";
      String droplet = dropletsByAppGuid.getOrDefault(appGuid, "droplet-guid-0001");
      Map<String, String> vars = envVarsFor(appGuid);

      boolean started = "STARTED".equalsIgnoreCase(state);
      String badgeBg = started ? "#14532d" : "#3f3f46";
      String badgeFg = started ? "#4ade80" : "#f87171";

      StringBuilder rows = new StringBuilder();
      for (Map.Entry<String, String> e : vars.entrySet()) {
        String val = e.getValue();
        String displayVal = val.length() > 140 ? val.substring(0, 140) + "…" : val;
        rows.append("<tr><td class='k'>")
            .append(escapeHtml(e.getKey()))
            .append("</td><td class='v'><code>")
            .append(escapeHtml(displayVal))
            .append("</code></td></tr>");
      }

      return "<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>"
          + "<title>" + escapeHtml(appName) + " — CF Apps Manager (Mock)</title>"
          + "<style>"
          + "body{font-family:system-ui,-apple-system,sans-serif;background:#0f172a;color:#e2e8f0;margin:0;padding:32px;}"
          + ".wrap{max-width:980px;margin:0 auto;}"
          + ".crumb{color:#94a3b8;font-size:14px;margin-bottom:8px;}"
          + ".hdr{display:flex;align-items:center;gap:14px;margin-bottom:20px;}"
          + "h1{margin:0;font-size:26px;color:#f8fafc;}"
          + ".pill{padding:4px 12px;border-radius:999px;font-weight:700;font-size:12px;background:"
          + badgeBg + ";color:" + badgeFg + ";}"
          + ".meta{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:12px;"
          + "background:#1e293b;border:1px solid #334155;border-radius:10px;padding:16px;margin-bottom:24px;}"
          + ".meta div span{display:block;color:#94a3b8;font-size:12px;margin-bottom:4px;}"
          + ".meta div strong{font-size:14px;color:#f1f5f9;font-family:monospace;}"
          + "h2{font-size:16px;color:#38bdf8;margin:0 0 12px 0;}"
          + "table{width:100%;border-collapse:collapse;background:#1e293b;border:1px solid #334155;border-radius:10px;overflow:hidden;}"
          + "th,td{text-align:left;padding:10px 14px;border-bottom:1px solid #334155;font-size:13px;vertical-align:top;}"
          + "th{background:#0f172a;color:#94a3b8;font-weight:600;}"
          + "td.k{font-family:monospace;color:#f8fafc;white-space:nowrap;width:28%;}"
          + "td.v code{font-family:monospace;color:#cbd5e1;word-break:break-all;}"
          + "</style></head><body><div class='wrap'>"
          + "<div class='crumb'>Cloud Foundry Simulator &rsaquo; "
          + escapeHtml(orgName) + " &rsaquo; " + escapeHtml(spaceName) + "</div>"
          + "<div class='hdr'><h1>" + escapeHtml(appName) + "</h1><span class='pill'>"
          + escapeHtml(state) + "</span></div>"
          + "<div class='meta'>"
          + "<div><span>App GUID</span><strong>" + escapeHtml(appGuid) + "</strong></div>"
          + "<div><span>Active Droplet</span><strong>" + escapeHtml(droplet) + "</strong></div>"
          + "<div><span>Created</span><strong>" + escapeHtml(createdAt) + "</strong></div>"
          + "<div><span>Last Updated</span><strong>" + escapeHtml(updatedAt) + "</strong></div>"
          + "</div>"
          + "<h2>User-Provided Environment Variables (" + vars.size() + ")</h2>"
          + "<table><thead><tr><th>Key</th><th>Value</th></tr></thead><tbody>"
          + rows
          + "</tbody></table></div></body></html>";
    }

    private static ResponseDefinition notFound(String detail) {
      ObjectNode err = JSON.createObjectNode();
      err.putArray("errors").addObject().put("detail", detail);
      return ResponseDefinitionBuilder.responseDefinition()
          .withStatus(404)
          .withHeader("Content-Type", "application/json")
          .withJsonBody(err)
          .build();
    }

    private static ObjectNode toVarObject(Map<String, String> vars) {
      ObjectNode root = JSON.createObjectNode();
      ObjectNode varNode = root.putObject("var");
      vars.forEach(varNode::put);
      return root;
    }

    private static String nowIso() {
      return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    private static String escapeHtml(String s) {
      if (s == null) {
        return "";
      }
      return s.replace("&", "&amp;")
          .replace("<", "&lt;")
          .replace(">", "&gt;")
          .replace("\"", "&quot;")
          .replace("'", "&#39;");
    }
  }
}
