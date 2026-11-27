package cf.explorer.io;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@FunctionalInterface
public interface ClipboardSupport {
  Logger LOG = LoggerFactory.getLogger(ClipboardSupport.class);

  boolean copy(String text);

  static ClipboardSupport system() {
    return text -> {
      try {
        Toolkit.getDefaultToolkit().getSystemClipboard()
            .setContents(new StringSelection(text), null);
        return true;
      } catch (Throwable ex) {
        LOG.debug("System clipboard copy failed", ex);
        return false;
      }
    };
  }
}
