package cf.explorer.io;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cf.explorer.catalog.CloudFoundryException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class LogSetupTest {
  private static final Logger LOG = LoggerFactory.getLogger(LogSetupTest.class);

  @TempDir Path temp;

  @AfterEach
  void closeHandlers() {
    LogSetup.close();
  }

  @Test
  void writesConventionalSingleLineEntriesWithMdcAndSafeStackTracesByDefault() throws Exception {
    Path dir = temp.resolve("default");
    LogSetup.toFile(dir, false);

    try (var op = MDC.putCloseable("op", "restage");
         var app = MDC.putCloseable("app", "billing-api")) {
      LOG.info("Staging started");
      LOG.debug("Hidden in non-debug mode: secret-token");
    }

    LOG.warn("CF call failed", new CloudFoundryException(
        "Start app failed (HTTP 422).",
        new SSLHandshakeException("PKIX path building failed")));
    LOG.error("Unexpected failure", new IllegalStateException("password=hunter2"));
    LogSetup.close();

    Path logFile = dir.resolve(LogSetup.LOG_FILE);
    assertTrue(Files.exists(logFile));
    String content = Files.readString(logFile);
    List<String> lines = Files.readAllLines(logFile);

    assertTrue(lines.get(0).matches(
        "^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} INFO  \\[.+] "
            + "c\\.e\\.i\\.LogSetupTest \\[app=billing-api, op=restage] - Staging started$"),
        lines.get(0));
    assertFalse(content.contains("secret-token"), content);
    assertTrue(content.contains("Start app failed (HTTP 422)."), content);
    assertTrue(content.contains("Caused by: javax.net.ssl.SSLHandshakeException: PKIX path building failed"),
        content);
    assertTrue(content.contains("\tat cf.explorer.io.LogSetupTest."), content);
    assertFalse(content.contains("hunter2"), content);
    assertTrue(content.contains("<message omitted; run with --debug for full details>"), content);
  }

  @Test
  void writesDebugEntriesAndUnredactedExceptionsWhenDebugIsEnabled() throws IOException {
    Path dir = temp.resolve("debug");
    LogSetup.toFile(dir, true);

    LOG.debug("--> #1 POST https://uaa.example/oauth/token body=password=s3cr3t");
    LOG.warn("Raw failure", new IllegalStateException("raw-secret-detail"));
    LogSetup.close();

    String content = Files.readString(dir.resolve(LogSetup.LOG_FILE));
    assertTrue(content.contains("DEBUG ["), content);
    assertTrue(content.contains("body=password=s3cr3t"), content);
    assertTrue(content.contains("java.lang.IllegalStateException: raw-secret-detail"), content);
  }
}
