package cf.explorer.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Routes {@code java.util.logging} output to a file so warnings never write over the full-screen
 * terminal UI.
 */
public final class LogSetup {
  public static final String LOG_FILE = "cf-explorer.log";

  private LogSetup() {}

  /** Replaces console handlers with {@code <dir>/cf-explorer.log}, or discards logs on failure. */
  public static void toFile(Path dir) {
    Logger root = LogManager.getLogManager().getLogger("");
    for (Handler handler : root.getHandlers()) {
      root.removeHandler(handler);
      handler.close();
    }
    try {
      Files.createDirectories(dir);
      FileHandler file = new FileHandler(dir.resolve(LOG_FILE).toString(), 1_000_000, 1, true);
      file.setFormatter(new SimpleFormatter());
      file.setLevel(Level.INFO);
      root.addHandler(file);
    } catch (IOException | RuntimeException ex) {
      // Logging is diagnostic only; never block the UI because the log file is unavailable.
    }
  }
}
