package cf.explorer.ui;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.text;

import cf.explorer.export.EnvExportService;
import cf.explorer.model.ShellPage;
import dev.tamboui.style.Color;
import dev.tamboui.toolkit.element.Element;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Displays the result of an environment export without exposing any variable values. */
public final class EnvExportPage implements ShellPage {
  private final EnvExportService.ExportResult result;

  public EnvExportPage(EnvExportService.ExportResult result) {
    this.result = Objects.requireNonNull(result);
  }

  @Override
  public Element render() {
    List<Element> lines = new ArrayList<>();
    lines.add(text(" Application:  " + result.appName()).fg(Color.BRIGHT_WHITE).bold());
    lines.add(text(" Saved to:     " + result.path().toAbsolutePath()).fg(Color.LIGHT_GREEN));
    lines.add(text(" Exported:     " + result.writtenCount() + " variable(s)").fg(Color.WHITE));
    if (!result.excludedKeys().isEmpty()) {
      lines.add(text(" Excluded:     " + String.join(", ", result.excludedKeys()))
          .fg(Color.LIGHT_YELLOW));
    }
    if (!result.postProcessedKeys().isEmpty()) {
      lines.add(text(" Processed:    " + String.join(", ", result.postProcessedKeys()))
          .fg(Color.LIGHT_CYAN));
    }
    lines.add(text(""));
    if (result.clipboardRequested()) {
      if (result.clipboardCopied()) {
        lines.add(text(" Clipboard:    File path copied to clipboard.").fg(Color.LIGHT_GREEN));
      } else {
        lines.add(text(" Clipboard:    File saved, but copying path to clipboard failed.")
            .fg(Color.LIGHT_YELLOW));
      }
    } else {
      lines.add(text(" Clipboard:    Copy disabled (--no-copy-to-clipboard).").dim());
    }
    lines.add(text(""));
    lines.add(text(" Press Esc or /back to return to the app catalog.").dim());
    return panel(column(lines.toArray(Element[]::new)))
        .title(" Environment Exported ").rounded().borderColor(Color.CYAN).fill();
  }
}
