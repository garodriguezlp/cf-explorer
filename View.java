package cf.explorer;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.dock;
import static dev.tamboui.toolkit.Toolkit.gauge;
import static dev.tamboui.toolkit.Toolkit.list;
import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.spinner;
import static dev.tamboui.toolkit.Toolkit.text;
import static dev.tamboui.toolkit.Toolkit.waveText;
import static java.util.Comparator.comparingInt;

import dev.tamboui.style.Color;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.StyledElement;
import dev.tamboui.widgets.spinner.SpinnerStyle;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Renders the UI from the current {@link AppState} exposed by {@link Controller}.
 *
 * <p>The view is split into nested classes per screen phase. Stateful animated widgets are owned by
 * long-lived instances so animation state survives across render calls.
 */
public final class View {

  private final Controller controller;
  private final KeyHandler keyHandler;

  /** Holds loading animation state across render calls. */
  private final CatalogLoading catalogLoading = new CatalogLoading();

  /** Holds exporting animation state across render calls. */
  private final EnvExporting envExporting = new EnvExporting();

  /** Holds keystore-exporting animation state across render calls. */
  private final KeystoreExporting keystoreExporting = new KeystoreExporting();

  View(Controller controller, KeyHandler keyHandler) {
    this.controller = controller;
    this.keyHandler = keyHandler;
  }

  Element render() {
    var state = controller.state();
    if (state instanceof AppState.CatalogLoading s) return catalogLoading.render(s);
    if (state instanceof AppState.CatalogLoadFailed s)
      return CatalogLoadFailed.render(s, keyHandler);
    if (state instanceof AppState.Browsing s) return Browsing.render(s, keyHandler);
    if (state instanceof AppState.EnvExporting s) return envExporting.render(s);
    if (state instanceof AppState.ExportDone s) return ExportDone.render(s, keyHandler);
    if (state instanceof AppState.ExportFailed s) return ExportFailed.render(s, keyHandler);
    if (state instanceof AppState.KeystoreExporting s) return keystoreExporting.render(s);
    if (state instanceof AppState.KeystoreDone s) return KeystoreDone.render(s, keyHandler);
    if (state instanceof AppState.KeystoreFailed s) return KeystoreFailed.render(s, keyHandler);
    throw new IllegalStateException("Unhandled state: " + state);
  }

  static final class CatalogLoading {

    private final Element spinnerWidget = spinner(SpinnerStyle.BOUNCING_BAR).cyan();
    private final Element waveWidget =
        waveText("  Fetching Cloud Foundry data...").color(Color.CYAN);

    Element render(AppState.CatalogLoading s) {
      return dock()
          .top(Shared.title(s.orgsCount(), s.spacesCount(), s.appsCount()))
          .center(body(s))
          .bottom(progressBar(s));
    }

    private Element body(AppState.CatalogLoading s) {
      return panel(
              column(
                  row(spinnerWidget, waveWidget),
                  text(""),
                  resourceRow("Organizations", s.orgsLoaded(), s.orgsCount()),
                  resourceRow("Spaces", s.spacesLoaded(), s.spacesCount()),
                  resourceRow("Applications", s.appsLoaded(), s.appsCount()),
                  text("")))
          .rounded()
          .borderColor(Color.CYAN)
          .title("Loading...")
          .fill(1);
    }

    private Element progressBar(AppState.CatalogLoading s) {
      int done = (s.orgsLoaded() ? 1 : 0) + (s.spacesLoaded() ? 1 : 0) + (s.appsLoaded() ? 1 : 0);
      return panel(
              row(
                  text("  Loading:  ").dim(),
                  gauge(s.progress()).cyan().fill(1),
                  text("  " + done + " / 3  ").dim()))
          .rounded()
          .borderColor(Color.DARK_GRAY);
    }

    private static Element resourceRow(String label, boolean done, int count) {
      var status = done ? text("[done]").green().bold() : text("[    ]").dim();
      var found = count > 0 ? text("" + count + " found").yellow() : text("waiting...").dim();
      return row(status, text("  " + label + ":  ").dim(), found);
    }
  }

  static final class CatalogLoadFailed {

    static Element render(AppState.CatalogLoadFailed s, KeyHandler keyHandler) {
      return dock()
          .top(Shared.title(s.header().orgs(), s.header().spaces(), s.header().apps()))
          .center(body(s, keyHandler))
          .bottom(Shared.quitHint());
    }

    private static Element body(AppState.CatalogLoadFailed s, KeyHandler keyHandler) {
      return panel(
              column(
                  text(""),
                  text("  Unable to load Cloud Foundry data").bold().red(),
                  text(""),
                  text("  We're sorry, an error occurred while communicating with the CF API.")
                      .dim(),
                  text("  Please verify your connection settings and try again.").dim(),
                  text(""),
                  text("  " + s.errorMessage()).dim()))
          .rounded()
          .borderColor(Color.RED)
          .title("Connection Error")
          .fill(1)
          .focusable()
          .onKeyEvent(keyHandler::handle);
    }
  }

  static final class Browsing {

    static Element render(AppState.Browsing s, KeyHandler keyHandler) {
      return dock()
          .top(Shared.title(s.header().orgs(), s.header().spaces(), s.header().apps()))
          .center(main(s, keyHandler))
          .bottom(column(filterBar(s), footer()));
    }

    private static Element main(AppState.Browsing s, KeyHandler keyHandler) {
      return column(appList(s, keyHandler), details(s));
    }

    private static Element appList(AppState.Browsing s, KeyHandler keyHandler) {
      var tokens = s.filterTokens();
      var listWidget =
          list()
              .highlightSymbol("> ")
              .highlightColor(Color.CYAN)
              .autoScroll()
              .scrollbar()
              .scrollbarThumbColor(Color.CYAN)
              .selected(s.selectedIndex());
      for (var line : displayLines(s)) {
        listWidget.add(MatchHighlight.build(line, tokens));
      }
      return panel(listWidget)
          .rounded()
          .borderColor(s.hasFilter() ? Color.YELLOW : Color.CYAN)
          .focusedBorderColor(Color.YELLOW)
          .title("Applications (" + s.filteredApps().size() + ")")
          .focusable()
          .onKeyEvent(keyHandler::handle)
          .fill(1);
    }

    private static List<String> displayLines(AppState.Browsing s) {
      return s.filteredApps().stream().map(View.Browsing::formatAppLine).toList();
    }

    private static String formatAppLine(App app) {
      return String.format(
          "%-55s  %-8s  %-10s  %s",
          truncate(app.name(), 55),
          app.state(),
          app.updatedAt() != null ? app.updatedAt().substring(0, 10) : "\u2014",
          app.orgName() + " \u203a " + app.spaceName());
    }

    private static String truncate(String s, int max) {
      return s.length() <= max ? s : s.substring(0, max - 1) + "\u2026";
    }

    private static Element details(AppState.Browsing s) {
      var app = s.selectedApp();
      if (app == null) {
        return panel(text("  Select an app to see details").dim())
            .rounded()
            .borderColor(Color.DARK_GRAY)
            .title("Details");
      }
      return panel(
              column(
                  row(
                      text("  " + app.name()).bold().cyan(),
                      text("  "),
                      Shared.stateChip(app.state())),
                  text("  " + app.guid()).dim(),
                  text(""),
                  row(
                      Shared.labelValue("  created   ", text(dateOnly(app.createdAt()))),
                      text("  \u2502  ").dim(),
                      Shared.labelValue("updated   ", text(dateOnly(app.updatedAt())))),
                  text(""),
                  Shared.labelValue("  org     ", text(app.orgName())),
                  Shared.labelValue("  space   ", text(app.spaceName()))))
          .rounded()
          .title("Details")
          .borderColor(Color.CYAN);
    }

    private static Element filterBar(AppState.Browsing s) {
      if (!s.hasFilter()) {
        return panel(
                row(
                    text("  Type to filter...  ").dim(),
                    text("[" + s.filteredApps().size() + " apps]").dim()))
            .rounded()
            .borderColor(Color.DARK_GRAY)
            .title("Filter");
      }
      return panel(
              row(
                  text("> ").bold().yellow(),
                  text(s.filterQuery()).yellow(),
                  text("_").dim(),
                  text("   [" + s.filteredApps().size() + " matches]").dim()))
          .rounded()
          .borderColor(Color.YELLOW)
          .title("Filter");
    }

    private static Element footer() {
      var hint =
          "\u2191\u2193 navigate  |  Enter export .env  |  Ctrl+O open in browser  |  Ctrl+K"
              + " inspect keystore  |  type to filter  |  Esc clear  |  Ctrl+C quit";
      return panel(text(hint).dim()).rounded().borderColor(Color.DARK_GRAY);
    }

    private static String dateOnly(String iso) {
      return iso != null && iso.length() >= 10 ? iso.substring(0, 10) : "\u2014";
    }
  }

  static final class EnvExporting {

    private final Element spinnerWidget = spinner(SpinnerStyle.BOUNCING_BAR).cyan();

    Element render(AppState.EnvExporting s) {
      return dock()
          .top(Shared.title(s.header().orgs(), s.header().spaces(), s.header().apps()))
          .center(body(s))
          .bottom(Shared.waitHint());
    }

    private Element body(AppState.EnvExporting s) {
      return panel(
              column(
                  text(""),
                  row(
                      spinnerWidget,
                      text("  Fetching environment for  ").dim(),
                      text(s.appName()).bold().cyan(),
                      text("...").dim()),
                  text("")))
          .rounded()
          .borderColor(Color.CYAN)
          .title("Exporting...")
          .fill(1);
    }
  }

  static final class ExportDone {

    static Element render(AppState.ExportDone s, KeyHandler keyHandler) {
      return dock()
          .top(Shared.title(s.header().orgs(), s.header().spaces(), s.header().apps()))
          .center(body(s, keyHandler))
          .bottom(Shared.backOrQuitHint());
    }

    private static Element body(AppState.ExportDone s, KeyHandler keyHandler) {
      var excluded = s.excludedKeys();
      var postProcessed = s.postProcessedKeys();
      var items = new ArrayList<Element>();

      items.add(text(""));
      items.add(
          row(
              text("  ✓  ").green().bold(),
              text("source ").dim(),
              text(s.filePath()).bold().cyan()));
      if (s.clipboardCopied()) {
        items.add(clipboardHint());
      }
      if (!excluded.isEmpty() || !postProcessed.isEmpty()) {
        items.add(text(""));
      }
      if (!excluded.isEmpty()) {
        items.add(row(text("  excluded       ").dim(), text(String.join(", ", excluded)).yellow()));
      }
      if (!postProcessed.isEmpty()) {
        items.add(
            row(text("  post-processed ").dim(), text(String.join(", ", postProcessed)).yellow()));
      }

      return panel(column(items.toArray(new Element[0])))
          .rounded()
          .borderColor(Color.GREEN)
          .title("Done!")
          .fill(1)
          .focusable()
          .onKeyEvent(keyHandler::handle);
    }

    private static Element clipboardHint() {
      return row(text("     \uD83D\uDCCB  ").dim(), text("path copied to clipboard").dim());
    }
  }

  static final class ExportFailed {

    static Element render(AppState.ExportFailed s, KeyHandler keyHandler) {
      return dock()
          .top(Shared.title(s.header().orgs(), s.header().spaces(), s.header().apps()))
          .center(body(s, keyHandler))
          .bottom(Shared.backOrQuitHint());
    }

    private static Element body(AppState.ExportFailed s, KeyHandler keyHandler) {
      return panel(
              column(
                  text(""),
                  text("  Export failed").bold().red(),
                  text(""),
                  text("  " + s.errorMessage()).dim()))
          .rounded()
          .borderColor(Color.RED)
          .title("Error")
          .fill(1)
          .focusable()
          .onKeyEvent(keyHandler::handle);
    }
  }

  static final class KeystoreExporting {

    private final Element spinnerWidget = spinner(SpinnerStyle.BOUNCING_BAR).cyan();

    Element render(AppState.KeystoreExporting s) {
      return dock()
          .top(Shared.title(s.header().orgs(), s.header().spaces(), s.header().apps()))
          .center(body(s))
          .bottom(Shared.waitHint());
    }

    private Element body(AppState.KeystoreExporting s) {
      return panel(
              column(
                  text(""),
                  row(
                      spinnerWidget,
                      text("  Inspecting keystore for  ").dim(),
                      text(s.appName()).bold().cyan(),
                      text("...").dim()),
                  text("")))
          .rounded()
          .borderColor(Color.CYAN)
          .title("Inspecting keystore...")
          .fill(1);
    }
  }

  static final class KeystoreDone {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    static Element render(AppState.KeystoreDone s, KeyHandler keyHandler) {
      return dock()
          .top(Shared.title(s.header().orgs(), s.header().spaces(), s.header().apps()))
          .center(body(s, keyHandler))
          .bottom(Shared.backOrQuitHint());
    }

    private static Element body(AppState.KeystoreDone s, KeyHandler keyHandler) {
      var items = new ArrayList<Element>();
      items.add(text(""));
      items.add(
          row(
              text("  \u2713  ").green().bold(),
              text("saved to ").dim(),
              text(s.result().jksPath().toString()).bold().cyan()));
      if (s.clipboardCopied()) {
        items.add(clipboardHint());
      }
      items.add(text(""));

      if (!s.result().inspected()) {
        items.add(
            row(
                text("  \u26a0  ").yellow().bold(),
                text("Could not inspect keystore: ").dim(),
                text(s.result().inspectionError()).yellow()));
        items.add(text(""));
        items.add(text("  The file was saved but could not be inspected (wrong password or corrupted JKS).").dim());
        items.add(text(""));
        return panel(column(items.toArray(new Element[0])))
            .rounded()
            .borderColor(Color.YELLOW)
            .title("Keystore saved — " + s.appName())
            .fill(1)
            .focusable()
            .onKeyEvent(keyHandler::handle);
      }

      var today = LocalDate.now();
      for (var entry : s.result().entries()) {
        var expiry = java.time.LocalDateTime.parse(entry.notAfter(), DATE_FMT).toLocalDate();
        var expiryText = !expiry.isAfter(today)
            ? text(entry.notAfter()).red().bold()
            : expiry.minusDays(30).isBefore(today)
                ? text(entry.notAfter()).yellow().bold()
                : text(entry.notAfter()).green().bold();
        items.add(row(text("  Alias:       ").dim(), text(entry.alias()).cyan().bold()));
        items.add(row(text("    Subject:     ").dim(), text(entry.subjectDN())));
        items.add(row(text("    Issuer:      ").dim(), text(entry.issuer()).dim()));
        items.add(row(text("    Valid from:  ").dim(), text(entry.notBefore()).dim()));
        items.add(row(text("    Valid until: ").dim(), expiryText));
        items.add(text(""));
      }

      return panel(column(items.toArray(new Element[0])))
          .rounded()
          .borderColor(Color.GREEN)
          .title("Keystore — " + s.appName())
          .fill(1)
          .focusable()
          .onKeyEvent(keyHandler::handle);
    }

    private static Element clipboardHint() {
      return row(text("     \uD83D\uDCCB  ").dim(), text("path copied to clipboard").dim());
    }
  }

  static final class KeystoreFailed {

    static Element render(AppState.KeystoreFailed s, KeyHandler keyHandler) {
      return dock()
          .top(Shared.title(s.header().orgs(), s.header().spaces(), s.header().apps()))
          .center(body(s, keyHandler))
          .bottom(Shared.backOrQuitHint());
    }

    private static Element body(AppState.KeystoreFailed s, KeyHandler keyHandler) {
      return panel(
              column(
                  text(""),
                  text("  Keystore inspection failed").bold().red(),
                  text(""),
                  text("  " + s.errorMessage()).dim()))
          .rounded()
          .borderColor(Color.RED)
          .title("Keystore Error")
          .fill(1)
          .focusable()
          .onKeyEvent(keyHandler::handle);
    }
  }

  private static final class Shared {

    static Element title(int orgs, int spaces, int apps) {
      return panel(
              row(
                  text(" CF App Browser ").bold().cyan(),
                  text("  |  ").dim(),
                  text(orgs + " orgs").dim(),
                  text("  ").dim(),
                  text(spaces + " spaces").dim(),
                  text("  ").dim(),
                  text(apps + " apps").dim()))
          .rounded()
          .borderColor(Color.CYAN);
    }

    static Element stateChip(String state) {
      return "STARTED".equals(state)
          ? text("\u25cf STARTED").green().bold()
          : text("\u25cf STOPPED").dim();
    }

    static Element labelValue(String label, Element content) {
      return row(text(label).dim(), content);
    }

    static Element quitHint() {
      return panel(text("  q quit  |  Ctrl+C quit").dim()).rounded().borderColor(Color.DARK_GRAY);
    }

    static Element waitHint() {
      return panel(text("Please wait...").dim()).rounded().borderColor(Color.DARK_GRAY);
    }

    static Element backOrQuitHint() {
      return panel(text("  Esc back to list  |  q quit  |  Ctrl+C quit").dim())
          .rounded()
          .borderColor(Color.DARK_GRAY);
    }
  }

  /**
   * Builds a styled row by highlighting token matches in bold green while keeping unmatched text
   * unstyled.
   */
  private static final class MatchHighlight {

    static StyledElement<?> build(String input, List<String> tokens) {
      if (tokens.isEmpty()) return text(input);
      var ranges = matchRanges(input, tokens);
      if (ranges.isEmpty()) return text(input);

      var spans = new ArrayList<Element>();
      int pos = 0;
      for (var r : ranges) {
        if (pos < r[0]) spans.add(text(input.substring(pos, r[0])));
        spans.add(text(input.substring(r[0], r[1])).fg(Color.LIGHT_GREEN).bold());
        pos = r[1];
      }
      if (pos < input.length()) spans.add(text(input.substring(pos)));
      return row(spans.toArray(new Element[0]));
    }

    private static List<int[]> matchRanges(String input, List<String> tokens) {
      var lower = input.toLowerCase(Locale.ROOT);
      var ranges = new ArrayList<int[]>();
      for (var token : tokens) {
        int idx = 0;
        while ((idx = lower.indexOf(token, idx)) >= 0) {
          ranges.add(new int[] {idx, idx + token.length()});
          idx += token.length();
        }
      }
      ranges.sort(comparingInt(r -> r[0]));
      var merged = new ArrayList<int[]>();
      for (var r : ranges) {
        if (merged.isEmpty() || merged.get(merged.size() - 1)[1] < r[0]) {
          merged.add(new int[] {r[0], r[1]});
        } else {
          merged.get(merged.size() - 1)[1] = Math.max(merged.get(merged.size() - 1)[1], r[1]);
        }
      }
      return merged;
    }
  }
}
