package cf.explorer.io;

import cf.explorer.catalog.CloudFoundryException;
import cf.explorer.keystore.KeystoreService;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.net.ssl.SSLException;
import org.slf4j.MDC;

/**
 * Routes SLF4J ({@code slf4j-jdk14}) and {@code java.util.logging} output to a single-line file
 * format under the profile directory so log output never writes over the full-screen terminal UI.
 */
public final class LogSetup {
  public static final String LOG_FILE = "cf-explorer.log";
  private static final int MAX_LOG_BYTES = 10_000_000;
  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

  /** Strong reference so {@link LogManager} does not garbage-collect the configured package level. */
  private static final Logger APP_LOGGER = Logger.getLogger("cf.explorer");

  private LogSetup() {}

  /** Replaces console handlers with {@code <dir>/cf-explorer.log} at {@code INFO} level. */
  public static void toFile(Path dir) {
    toFile(dir, false);
  }

  /**
   * Replaces console handlers with {@code <dir>/cf-explorer.log}, enabling {@code DEBUG} output and
   * unredacted exception details when {@code debug} is {@code true}.
   */
  public static void toFile(Path dir, boolean debug) {
    Logger root = LogManager.getLogManager().getLogger("");
    for (Handler handler : root.getHandlers()) {
      root.removeHandler(handler);
      handler.close();
    }
    root.setLevel(Level.INFO);
    APP_LOGGER.setLevel(debug ? Level.FINE : Level.INFO);
    try {
      Files.createDirectories(dir);
      FileHandler file = new FileHandler(
          dir.resolve(LOG_FILE).toString(), MAX_LOG_BYTES, 1, true);
      file.setEncoding(StandardCharsets.UTF_8.name());
      file.setFormatter(new SingleLineFormatter(debug));
      file.setLevel(debug ? Level.FINE : Level.INFO);
      root.addHandler(file);
    } catch (IOException | RuntimeException ex) {
      // Logging is diagnostic only; never block the UI because the log file is unavailable.
    }
  }

  /** Flushes and closes active root file handlers (useful on shutdown and in Windows tests). */
  public static void close() {
    Logger root = LogManager.getLogManager().getLogger("");
    for (Handler handler : root.getHandlers()) {
      root.removeHandler(handler);
      handler.close();
    }
  }

  static final class SingleLineFormatter extends Formatter {
    private final boolean debug;

    SingleLineFormatter(boolean debug) {
      this.debug = debug;
    }

    @Override
    public String format(LogRecord record) {
      StringBuilder line = new StringBuilder(192);
      line.append(TIMESTAMP.format(Instant.ofEpochMilli(record.getMillis())))
          .append(' ')
          .append(levelLabel(record.getLevel()))
          .append(" [")
          .append(Thread.currentThread().getName())
          .append("] ")
          .append(abbreviateLogger(record.getLoggerName()));

      Map<String, String> mdc = MDC.getCopyOfContextMap();
      if (mdc != null && !mdc.isEmpty()) {
        String context = new TreeMap<>(mdc).entrySet().stream()
            .map(e -> e.getKey() + "=" + sanitizeInline(e.getValue()))
            .collect(Collectors.joining(", "));
        line.append(" [").append(context).append(']');
      }

      String message = formatMessage(record);
      line.append(" - ").append(message == null ? "" : sanitizeInline(message))
          .append(System.lineSeparator());

      Throwable thrown = record.getThrown();
      if (thrown != null) {
        if (debug) {
          StringWriter sw = new StringWriter();
          thrown.printStackTrace(new PrintWriter(sw));
          line.append(sw);
        } else {
          appendSafeStackTrace(line, thrown, "", Collections.newSetFromMap(new IdentityHashMap<>()));
        }
      }
      return line.toString();
    }

    private static String levelLabel(Level level) {
      int value = level.intValue();
      if (value >= Level.SEVERE.intValue()) {
        return "ERROR";
      }
      if (value >= Level.WARNING.intValue()) {
        return "WARN ";
      }
      if (value >= Level.INFO.intValue()) {
        return "INFO ";
      }
      return "DEBUG";
    }

    static String abbreviateLogger(String name) {
      if (name == null || name.isBlank()) {
        return "root";
      }
      String[] parts = name.split("\\.");
      if (parts.length <= 1) {
        return name;
      }
      StringBuilder out = new StringBuilder();
      for (int i = 0; i < parts.length - 1; i++) {
        if (!parts[i].isEmpty()) {
          out.append(parts[i].charAt(0)).append('.');
        }
      }
      out.append(parts[parts.length - 1]);
      return out.toString();
    }

    private static String sanitizeInline(String text) {
      if (text == null) {
        return "";
      }
      return text.replace("\r\n", "\\n").replace('\n', ' ').replace('\r', ' ');
    }

    private static void appendSafeStackTrace(
        StringBuilder out, Throwable error, String prefix, Set<Throwable> seen) {
      if (error == null || !seen.add(error)) {
        return;
      }
      String nl = System.lineSeparator();
      out.append(prefix).append(error.getClass().getName());
      String message = safeExceptionMessage(error);
      if (message != null && !message.isBlank()) {
        out.append(": ").append(sanitizeInline(message));
      }
      out.append(nl);
      for (StackTraceElement frame : error.getStackTrace()) {
        out.append("\tat ").append(frame).append(nl);
      }
      for (Throwable suppressed : error.getSuppressed()) {
        appendSafeStackTrace(out, suppressed, "\tSuppressed: ", seen);
      }
      if (error.getCause() != null) {
        appendSafeStackTrace(out, error.getCause(), "Caused by: ", seen);
      }
    }

    private static String safeExceptionMessage(Throwable error) {
      if (error instanceof CloudFoundryException
          || error instanceof KeystoreService.SafeKeystoreException
          || error instanceof SSLException
          || error instanceof GeneralSecurityException
          || error instanceof ConnectException
          || error instanceof SocketTimeoutException
          || error instanceof HttpTimeoutException) {
        return error.getMessage();
      }
      return error.getMessage() == null
          ? null
          : "<message omitted; run with --debug for full details>";
    }
  }
}
