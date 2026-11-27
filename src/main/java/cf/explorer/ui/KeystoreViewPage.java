package cf.explorer.ui;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.list;
import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.text;

import cf.explorer.keystore.KeystoreEntry;
import cf.explorer.keystore.KeystoreService;
import cf.explorer.model.ShellPage;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Displays saved JKS path and certificate metadata without exposing secret values. */
public final class KeystoreViewPage implements ShellPage {
  private static final Duration EXPIRING_SOON = Duration.ofDays(30);

  private final KeystoreService.InspectResult result;
  private final Clock clock;
  private int selectedIndex;

  public KeystoreViewPage(KeystoreService.InspectResult result) {
    this(result, Clock.systemDefaultZone());
  }

  public KeystoreViewPage(KeystoreService.InspectResult result, Clock clock) {
    this.result = Objects.requireNonNull(result);
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public Object selected() {
    return result.entries().isEmpty() ? null : result.entries().get(selectedIndex);
  }

  @Override
  public EventResult handleKey(KeyEvent event) {
    if (result.entries().isEmpty()) {
      return EventResult.UNHANDLED;
    }
    if (event.isKey(KeyCode.UP)) {
      selectedIndex = Math.max(0, selectedIndex - 1);
      return EventResult.HANDLED;
    }
    if (event.isKey(KeyCode.DOWN)) {
      selectedIndex = Math.min(result.entries().size() - 1, selectedIndex + 1);
      return EventResult.HANDLED;
    }
    return EventResult.UNHANDLED;
  }

  @Override
  public Element render() {
    List<Element> header = new ArrayList<>();
    header.add(text(" Application:  " + result.appName()).fg(Color.BRIGHT_WHITE).bold());
    header.add(text(" Saved to:     " + result.jksPath().toAbsolutePath()).fg(Color.LIGHT_GREEN));
    if (result.clipboardRequested()) {
      if (result.clipboardCopied()) {
        header.add(text(" Clipboard:    File path copied to clipboard.").fg(Color.LIGHT_GREEN));
      } else {
        header.add(text(" Clipboard:    File saved, but copying path to clipboard failed.")
            .fg(Color.LIGHT_YELLOW));
      }
    } else {
      header.add(text(" Clipboard:    Copy disabled (--no-copy-to-clipboard).").dim());
    }

    if (result.inspectionFailed()) {
      header.add(text(""));
      header.add(text(" Warning:      Keystore file was saved, but certificate inspection failed.")
          .fg(Color.LIGHT_YELLOW).bold());
      header.add(text("               Verify the keystore format and configured password variable.")
          .dim());
      header.add(text(""));
      header.add(text(" Press Esc or /back to return to the app catalog.").dim());
      return panel(column(header.toArray(Element[]::new)))
          .title(" Keystore Saved (Inspection Failed) ").rounded().borderColor(Color.YELLOW).fill();
    }

    if (result.entries().isEmpty()) {
      header.add(text(""));
      header.add(text(" Certificates: No X.509 certificates found in keystore.").dim());
      header.add(text(""));
      header.add(text(" Press Esc or /back to return to the app catalog.").dim());
      return panel(column(header.toArray(Element[]::new)))
          .title(" Keystore Certificates ").rounded().borderColor(Color.CYAN).fill();
    }

    Instant now = clock.instant();
    KeystoreEntry current = result.entries().get(selectedIndex);
    List<String> aliasRows = result.entries().stream()
        .map(entry -> entry.alias() + "  [" + expiryLabel(entry, now) + "]").toList();

    Element certList = list(aliasRows)
        .title(" Certificates (" + result.entries().size() + ") ")
        .rounded().borderColor(Color.CYAN)
        .highlightSymbol("> ")
        .highlightStyle(Style.create().bg(Color.indexed(236)).bold())
        .selected(selectedIndex).autoScroll().focusable(false)
        .length(Math.min(6, result.entries().size() + 2));

    Element details = panel(column(
        text(" Alias:        " + current.alias()).fg(Color.BRIGHT_WHITE).bold(),
        text(" Subject:      " + current.subject()).fg(Color.WHITE),
        text(" Issuer:       " + current.issuer()).fg(Color.WHITE),
        text(" Valid from:   " + current.formattedNotBefore()).fg(Color.WHITE),
        text(" Valid until:  " + current.formattedNotAfter()
            + " (" + expiryLabel(current, now) + ")").fg(expiryColor(current, now)).bold()))
        .title(" Certificate Details ").rounded().borderColor(Color.CYAN).fill();

    List<Element> body = new ArrayList<>();
    body.add(panel(column(header.toArray(Element[]::new)))
        .title(" Keystore ").rounded().borderColor(Color.CYAN).length(header.size() + 2));
    body.add(certList);
    body.add(details);
    return column(body.toArray(Element[]::new)).fill();
  }

  private static String expiryLabel(KeystoreEntry entry, Instant now) {
    if (entry.notAfter().isBefore(now)) {
      return "EXPIRED";
    }
    if (entry.notAfter().isBefore(now.plus(EXPIRING_SOON))) {
      return "EXPIRING SOON";
    }
    return "VALID";
  }

  private static Color expiryColor(KeystoreEntry entry, Instant now) {
    if (entry.notAfter().isBefore(now)) {
      return Color.LIGHT_RED;
    }
    if (entry.notAfter().isBefore(now.plus(EXPIRING_SOON))) {
      return Color.LIGHT_YELLOW;
    }
    return Color.LIGHT_GREEN;
  }
}
