package cf.explorer.io;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;

@FunctionalInterface
public interface ClipboardSupport {
  boolean copy(String text);

  static ClipboardSupport system() {
    return text -> {
      try {
        Toolkit.getDefaultToolkit().getSystemClipboard()
            .setContents(new StringSelection(text), null);
        return true;
      } catch (Throwable ex) {
        return false;
      }
    };
  }
}
