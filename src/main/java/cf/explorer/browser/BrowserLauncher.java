package cf.explorer.browser;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Launches a URL in the operating system's default browser. */
@FunctionalInterface
public interface BrowserLauncher {
  void open(String url) throws IOException;

  static BrowserLauncher system() {
    return url -> {
      Objects.requireNonNull(url);
      List<String> command = commandForOs(System.getProperty("os.name", ""), url);
      new ProcessBuilder(command).start();
    };
  }

  static List<String> commandForOs(String osName, String url) {
    String normalized = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
    if (normalized.contains("win")) {
      return List.of("cmd", "/c", "start", "", url);
    }
    if (normalized.contains("mac")) {
      return List.of("open", url);
    }
    return List.of("xdg-open", url);
  }
}
