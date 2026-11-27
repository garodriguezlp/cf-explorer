package cf.explorer.ui;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.list;
import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.richText;
import static dev.tamboui.toolkit.Toolkit.text;

import cf.explorer.catalog.ApplicationCatalog;
import cf.explorer.model.ShellPage;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.element.Size;
import dev.tamboui.toolkit.element.StyledElement;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Root page listing every application in the catalog, with a token filter, match highlighting,
 * and a selection tracked by app GUID so it survives filtering and catalog refreshes.
 */
public final class ApplicationCatalogPage implements ShellPage {
  private static final Logger LOG = Logger.getLogger(ApplicationCatalogPage.class.getName());
  private ApplicationCatalog catalog;
  private boolean failed;
  private String failureReason = "";
  private boolean refreshing;
  private long loadGeneration;
  private String filter = "";
  private int cursor;
  private String selectedGuid;

  public void startLoading(Supplier<ApplicationCatalog> load, Executor worker,
      Consumer<Runnable> dispatch, BooleanSupplier active) {
    startLoading(load, worker, dispatch, active, false, message -> {});
  }

  public void startRefresh(Supplier<ApplicationCatalog> load, Executor worker,
      Consumer<Runnable> dispatch, BooleanSupplier active, Consumer<String> statusConsumer) {
    startLoading(load, worker, dispatch, active, catalog != null, statusConsumer);
  }

  private void startLoading(Supplier<ApplicationCatalog> load, Executor worker,
      Consumer<Runnable> dispatch, BooleanSupplier active, boolean keepPrevious,
      Consumer<String> statusConsumer) {
    Objects.requireNonNull(load);
    Objects.requireNonNull(worker);
    Objects.requireNonNull(dispatch);
    Objects.requireNonNull(active);
    Objects.requireNonNull(statusConsumer);
    long generation = ++loadGeneration;
    if (!keepPrevious) {
      catalog = null;
      failed = false;
      refreshing = false;
    } else {
      refreshing = true;
      statusConsumer.accept("Refreshing catalog...");
    }
    try {
      CompletableFuture.supplyAsync(() -> Objects.requireNonNull(load.get()), worker)
          .whenComplete((result, error) -> {
            if (error != null) {
              logFailure(keepPrevious ? "Catalog refresh" : "Catalog loading", error);
            }
            try {
              dispatch.accept(() -> {
                if (active.getAsBoolean() && generation == loadGeneration) {
                  refreshing = false;
                  if (error == null) {
                    loaded(result);
                    if (keepPrevious) {
                      statusConsumer.accept("Catalog refreshed.");
                    }
                  } else if (keepPrevious && catalog != null) {
                    statusConsumer.accept("Catalog refresh failed: "
                        + ErrorMessages.describe(error) + " Keeping previous catalog.");
                  } else {
                    failed(ErrorMessages.describe(error));
                  }
                }
              });
            } catch (RuntimeException ex) {
              logFailure("Render dispatch", ex);
            }
          });
    } catch (RejectedExecutionException ex) {
      logFailure(keepPrevious ? "Catalog refresh" : "Catalog loading", ex);
      refreshing = false;
      if (keepPrevious && catalog != null) {
        statusConsumer.accept("Catalog refresh failed. Keeping previous catalog.");
      } else {
        failed("The background worker is unavailable.");
      }
    }
  }

  private static void logFailure(String operation, Throwable error) {
    Throwable cause = error instanceof CompletionException && error.getCause() != null
        ? error.getCause() : error;
    LOG.warning(operation + " failed (" + cause.getClass().getName() + ")");
  }

  public void loaded(ApplicationCatalog catalog) {
    this.catalog = Objects.requireNonNull(catalog);
    failed = false;
    reconcile();
  }

  public void failed() {
    failed("");
  }

  /** Shows the failure screen with a user-safe reason. */
  public void failed(String reason) {
    failed = true;
    failureReason = Objects.requireNonNull(reason);
  }

  @Override
  public ApplicationCatalog.App selected() {
    if (catalog == null || selectedGuid == null) {
      return null;
    }
    return catalog.apps().stream().filter(app -> app.guid().equals(selectedGuid))
        .findFirst().orElse(null);
  }

  @Override
  public String inputValue() {
    return filter;
  }

  @Override
  public int inputCursor() {
    return cursor;
  }

  @Override
  public Element render() {
    if (failed) {
      List<Element> lines = new ArrayList<>();
      lines.add(text(" Catalog loading failed.").fg(Color.LIGHT_RED).bold());
      if (!failureReason.isBlank()) {
        lines.add(text(" " + failureReason));
      }
      lines.add(text(""));
      lines.add(text(" Check the connection settings, then run /refresh to retry.").dim());
      return panel(column(lines.toArray(Element[]::new)))
          .title(" Connection Error ").rounded().borderColor(Color.RED).fill();
    }
    if (catalog == null) {
      return panel(text(" Loading Cloud Foundry catalog..."))
          .title(" Loading ").rounded().borderColor(Color.CYAN).fill();
    }
    return new CatalogLayout().fill();
  }

  private Element renderCatalog(int width) {
    List<ApplicationCatalog.App> visible = catalog.filter(filter);
    String title = " Apps  " + visible.size() + "/" + catalog.apps().size()
        + "  Orgs " + catalog.organizations() + "  Spaces " + catalog.spaces()
        + (refreshing ? "  [refreshing...] " : " ");
    if (visible.isEmpty()) {
      return panel(text(filter.isBlank() ? " No apps." : " No matching apps.").dim())
          .title(title).rounded().borderColor(Color.CYAN).fill();
    }
    int selected = 0;
    for (int i = 0; i < visible.size(); i++) {
      if (visible.get(i).guid().equals(selectedGuid)) {
        selected = i;
        break;
      }
    }
    Pattern matches = matchPattern(filter);
    int widestMetadata = visible.stream().map(app -> metadata(app, filter))
        .mapToInt(value -> value.codePointCount(0, value.length())).max().orElse(0);
    int nameWidth = Math.max(8, Math.min(45, width - widestMetadata - 4));
    return list().data(visible, app -> richText(highlight(row(app, matches, nameWidth), matches)))
        .title(title).rounded().borderColor(Color.CYAN).highlightSymbol("> ")
        .highlightStyle(Style.create().bg(Color.indexed(236)).bold())
        .selected(selected).autoScroll().focusable(false).fill();
  }

  private final class CatalogLayout extends StyledElement<CatalogLayout> {
    @Override
    public Size preferredSize(int width, int height, RenderContext context) {
      return Size.UNKNOWN;
    }

    @Override
    protected void renderContent(Frame frame, Rect area, RenderContext context) {
      context.renderChild(renderCatalog(area.width()), frame, area);
    }
  }

  @Override
  public EventResult handleKey(KeyEvent event) {
    if (catalog == null || failed) {
      return EventResult.HANDLED;
    }
    if (event.isKey(KeyCode.ESCAPE)) {
      filter = "";
      cursor = 0;
    } else if (event.isKey(KeyCode.UP) || event.isKey(KeyCode.DOWN)) {
      List<ApplicationCatalog.App> visible = catalog.filter(filter);
      if (!visible.isEmpty()) {
        int index = 0;
        for (int i = 0; i < visible.size(); i++) {
          if (visible.get(i).guid().equals(selectedGuid)) {
            index = i;
            break;
          }
        }
        index = Math.max(0, Math.min(visible.size() - 1,
            index + (event.isKey(KeyCode.UP) ? -1 : 1)));
        selectedGuid = visible.get(index).guid();
      }
    } else if (event.isKey(KeyCode.LEFT) && cursor > 0) {
      cursor = filter.offsetByCodePoints(cursor, -1);
    } else if (event.isKey(KeyCode.RIGHT) && cursor < filter.length()) {
      cursor = filter.offsetByCodePoints(cursor, 1);
    } else if (event.isKey(KeyCode.HOME)) {
      cursor = 0;
    } else if (event.isKey(KeyCode.END)) {
      cursor = filter.length();
    } else if (event.isKey(KeyCode.BACKSPACE) && cursor > 0) {
      int start = filter.offsetByCodePoints(cursor, -1);
      filter = filter.substring(0, start) + filter.substring(cursor);
      cursor = start;
    } else if (event.isKey(KeyCode.DELETE) && cursor < filter.length()) {
      int end = filter.offsetByCodePoints(cursor, 1);
      filter = filter.substring(0, cursor) + filter.substring(end);
    } else if (event.isTyping() && !event.hasNonShiftModifier()) {
      String value = new String(Character.toChars(event.codePoint()));
      filter = filter.substring(0, cursor) + value + filter.substring(cursor);
      cursor += value.length();
    } else {
      return EventResult.UNHANDLED;
    }
    reconcile();
    return EventResult.HANDLED;
  }

  private void reconcile() {
    List<ApplicationCatalog.App> visible = catalog.filter(filter);
    if (visible.stream().noneMatch(app -> app.guid().equals(selectedGuid))) {
      selectedGuid = visible.isEmpty() ? null : visible.get(0).guid();
    }
  }

  private String row(ApplicationCatalog.App app, Pattern matches, int nameWidth) {
    String name = displayName(app.name(), matches, nameWidth);
    int padding = Math.max(0, nameWidth - name.codePointCount(0, name.length()));
    return name + " ".repeat(padding) + metadata(app, filter);
  }

  private static String metadata(ApplicationCatalog.App app, String filter) {
    String identifiers = matchingIdentifiers(app, filter);
    return "  " + identifiers + String.format("%-8s", app.state())
        + "  c: " + date(app.createdAt()) + " \u203a u: " + date(app.updatedAt())
        + "  " + app.orgName() + " \u203a " + app.spaceName();
  }

  private static String matchingIdentifiers(ApplicationCatalog.App app, String filter) {
    if (filter.isBlank()) {
      return "";
    }
    String displayed = String.join(" ", app.name(), app.state(), app.orgName(), app.spaceName(),
        date(app.createdAt()), date(app.updatedAt())).toLowerCase(Locale.ROOT);
    Set<String> identifiers = new LinkedHashSet<>();
    for (String token : filter.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
      if (!displayed.contains(token)) {
        for (String id : List.of(app.guid(), app.orgGuid(), app.spaceGuid())) {
          if (id.toLowerCase(Locale.ROOT).contains(token)) {
            identifiers.add(id);
          }
        }
      }
    }
    return identifiers.isEmpty() ? "" : "id: " + String.join(", ", identifiers) + "  ";
  }

  private static String displayName(String name, Pattern matches, int width) {
    int length = name.codePointCount(0, name.length());
    if (length <= width) {
      return name;
    }
    int start = 0;
    if (matches != null) {
      Matcher matcher = matches.matcher(name);
      if (matcher.find()) {
        int matchEnd = name.codePointCount(0, matcher.end());
        if (matchEnd > width - 1) {
          start = Math.max(1, matchEnd - (width - 2));
        }
      }
    }
    int end = Math.min(length, start + (start == 0 ? width - 1 : width - 2));
    String visible = name.substring(name.offsetByCodePoints(0, start),
        name.offsetByCodePoints(0, end));
    return (start == 0 ? "" : "\u2026") + visible + (end == length ? "" : "\u2026");
  }

  private static Pattern matchPattern(String filter) {
    if (filter.isBlank()) {
      return null;
    }
    String alternatives = Arrays.stream(filter.trim().split("\\s+"))
        .sorted(Comparator.comparingInt(String::length).reversed())
        .map(Pattern::quote).collect(Collectors.joining("|"));
    return Pattern.compile(alternatives, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  }

  private static Text highlight(String row, Pattern matches) {
    if (matches == null) {
      return Text.from(row);
    }
    List<Span> spans = new ArrayList<>();
    Matcher matcher = matches.matcher(row);
    int end = 0;
    while (matcher.find()) {
      spans.add(Span.raw(row.substring(end, matcher.start())));
      spans.add(Span.styled(matcher.group(), Style.create().fg(Color.LIGHT_YELLOW).bold()));
      end = matcher.end();
    }
    spans.add(Span.raw(row.substring(end)));
    return Text.from(Line.from(spans));
  }

  private static String date(String value) {
    return value != null && value.length() >= 10 ? value.substring(0, 10) : "-";
  }
}
