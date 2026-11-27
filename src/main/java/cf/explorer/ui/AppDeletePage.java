package cf.explorer.ui;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.richText;
import static dev.tamboui.toolkit.Toolkit.text;

import cf.explorer.catalog.ApplicationCatalog;
import cf.explorer.model.ShellPage;
import cf.explorer.model.ShellState;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Second and final step of {@code /delete}: a dedicated danger-zone page that spells out which
 * application, organization, space, and profile will be affected, and only deletes once the user
 * types {@value #CONFIRMATION_WORD} and presses Enter. Esc returns to the catalog untouched.
 */
public final class AppDeletePage implements ShellPage {
  /** The word the user must type to delete the application. */
  public static final String CONFIRMATION_WORD = "yes";

  private final ApplicationCatalog.App app;
  private final String envProfile;
  private final ShellState shell;
  private final Runnable onConfirmed;
  private String input = "";
  private int cursor;

  public AppDeletePage(ApplicationCatalog.App app, String envProfile, ShellState shell,
      Runnable onConfirmed) {
    this.app = Objects.requireNonNull(app);
    this.envProfile = envProfile != null && !envProfile.isBlank() ? envProfile : null;
    this.shell = Objects.requireNonNull(shell);
    this.onConfirmed = Objects.requireNonNull(onConfirmed);
  }

  public ApplicationCatalog.App app() {
    return app;
  }

  /** Whether the typed text arms the deletion. */
  public boolean armed() {
    return input.trim().toLowerCase(Locale.ROOT).equals(CONFIRMATION_WORD);
  }

  /** No selection, so app commands stay unavailable while this page is open. */
  @Override
  public Object selected() {
    return null;
  }

  @Override
  public String inputValue() {
    return input;
  }

  @Override
  public int inputCursor() {
    return cursor;
  }

  @Override
  public String inputTitle() {
    return "Type " + CONFIRMATION_WORD + " to delete " + app.name();
  }

  /** Every key is typed into the confirmation box; Esc is the only way out. */
  @Override
  public boolean capturesSlash() {
    return true;
  }

  @Override
  public boolean onEscape(ShellState state) {
    state.backNow();
    state.showStatus("Deletion cancelled. " + app.name() + " was not deleted.");
    return true;
  }

  @Override
  public EventResult handleKey(KeyEvent event) {
    if (event.isKey(KeyCode.ENTER)) {
      submit();
    } else if (event.isKey(KeyCode.LEFT) && cursor > 0) {
      cursor = input.offsetByCodePoints(cursor, -1);
    } else if (event.isKey(KeyCode.RIGHT) && cursor < input.length()) {
      cursor = input.offsetByCodePoints(cursor, 1);
    } else if (event.isKey(KeyCode.HOME)) {
      cursor = 0;
    } else if (event.isKey(KeyCode.END)) {
      cursor = input.length();
    } else if (event.isKey(KeyCode.BACKSPACE) && cursor > 0) {
      int start = input.offsetByCodePoints(cursor, -1);
      input = input.substring(0, start) + input.substring(cursor);
      cursor = start;
    } else if (event.isKey(KeyCode.DELETE) && cursor < input.length()) {
      int end = input.offsetByCodePoints(cursor, 1);
      input = input.substring(0, cursor) + input.substring(end);
    } else if (event.isTyping() && !event.hasNonShiftModifier()) {
      String ch = new String(Character.toChars(event.codePoint()));
      input = input.substring(0, cursor) + ch + input.substring(cursor);
      cursor += ch.length();
    }
    return EventResult.HANDLED;
  }

  private void submit() {
    if (armed()) {
      onConfirmed.run();
      return;
    }
    boolean empty = input.isBlank();
    input = "";
    cursor = 0;
    shell.showError(empty
        ? "Type " + CONFIRMATION_WORD + " and press Enter to delete " + app.name()
            + ", or press Esc to cancel."
        : "Not deleted: you must type " + CONFIRMATION_WORD + " exactly. Press Esc to cancel.");
  }

  @Override
  public Element render() {
    List<Element> lines = new ArrayList<>();
    lines.add(text(""));
    lines.add(text("  \u26a0  YOU ARE ABOUT TO PERMANENTLY DELETE AN APPLICATION  \u26a0")
        .fg(Color.LIGHT_RED).bold());
    lines.add(text(""));
    lines.add(field("App", app.name(), Style.create().fg(Color.BRIGHT_WHITE).bold()));
    lines.add(field("Org", app.orgName(), Style.create().fg(Color.WHITE)));
    lines.add(field("Space", app.spaceName(), Style.create().fg(Color.WHITE)));
    if (envProfile != null) {
      lines.add(field("Profile", envProfile, Style.create().fg(Color.LIGHT_YELLOW).bold()));
    }
    lines.add(field("State", app.state(), Style.create().fg(Color.WHITE)));
    lines.add(field("GUID", app.guid(), Style.create().fg(Color.GRAY)));
    lines.add(text(""));
    lines.add(text("  Cloud Foundry will:"));
    lines.add(text("    \u2022 stop every running instance and remove the app"));
    lines.add(text("    \u2022 delete its processes, packages, droplets, and service bindings"));
    lines.add(text("    \u2022 unmap its routes (routes and service instances are kept)"));
    lines.add(text(""));
    lines.add(text("  This cannot be undone.").fg(Color.LIGHT_YELLOW).bold());
    lines.add(text(""));
    lines.add(text("  Type " + CONFIRMATION_WORD + " below and press Enter to delete. "
        + "Press Esc to cancel and keep the app.").fg(Color.BRIGHT_WHITE));
    lines.add(armed()
        ? text("  \u25cf ARMED: Enter will delete " + app.name() + " now.")
            .fg(Color.LIGHT_RED).bold()
        : text("  \u25cb Not armed: Enter does nothing until you type " + CONFIRMATION_WORD + ".")
            .fg(Color.GRAY));
    return panel(column(lines.toArray(Element[]::new)))
        .title(" \u26a0 Delete Application: Step 2 of 2 ")
        .rounded()
        .borderColor(Color.RED)
        .fill();
  }

  private static Element field(String label, String value, Style valueStyle) {
    return richText(Text.from(Line.from(
        Span.styled(String.format("    %-9s", label + ":"), Style.create().fg(Color.GRAY)),
        Span.styled(value != null ? value : "-", valueStyle))));
  }
}
