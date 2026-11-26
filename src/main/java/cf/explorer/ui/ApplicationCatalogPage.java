package cf.explorer.ui;

import static dev.tamboui.toolkit.Toolkit.list;
import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.richText;
import static dev.tamboui.toolkit.Toolkit.text;

import cf.explorer.catalog.ApplicationCatalog;
import cf.explorer.model.ShellPage;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
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

public final class ApplicationCatalogPage implements ShellPage {
  private static final Logger LOG = Logger.getLogger(ApplicationCatalogPage.class.getName());
  private ApplicationCatalog catalog;
  private boolean failed;
  private long loadGeneration;
  private String filter = "";
  private int cursor;
  private String selectedGuid;

  public void startLoading(Supplier<ApplicationCatalog> load, Executor worker,
      Consumer<Runnable> dispatch, BooleanSupplier active) {
    Objects.requireNonNull(load);
    Objects.requireNonNull(worker);
    Objects.requireNonNull(dispatch);
    Objects.requireNonNull(active);
    long generation = ++loadGeneration;
    catalog = null;
    failed = false;
    try {
      CompletableFuture.supplyAsync(() -> Objects.requireNonNull(load.get()), worker)
          .whenComplete((result, error) -> {
            if (error != null) {
              logFailure("Catalog loading", error);
            }
            try {
              dispatch.accept(() -> {
                if (active.getAsBoolean() && generation == loadGeneration) {
                  if (error == null) {
                    loaded(result);
                  } else {
                    failed();
                  }
                }
              });
            } catch (RuntimeException ex) {
              logFailure("Render dispatch", ex);
            }
          });
    } catch (RejectedExecutionException ex) {
      logFailure("Catalog loading", ex);
      failed();
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
    failed = true;
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
      return panel(text(" Catalog loading failed. Check connection settings and try again."))
          .title(" Error ").rounded().borderColor(Color.CYAN).fill();
    }
    if (catalog == null) {
      return panel(text(" Loading Cloud Foundry catalog..."))
          .title(" Loading ").rounded().borderColor(Color.CYAN).fill();
    }
    List<ApplicationCatalog.App> visible = catalog.filter(filter);
    String title = " Apps  " + visible.size() + "/" + catalog.apps().size()
        + "  Orgs " + catalog.organizations() + "  Spaces " + catalog.spaces() + " ";
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
    return list().data(visible, app -> richText(highlight(row(app), matches)))
        .title(title).rounded().borderColor(Color.CYAN).highlightSymbol("> ")
        .highlightStyle(Style.create().bg(Color.indexed(236)).bold())
        .selected(selected).autoScroll().focusable(false).fill();
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

  private static String row(ApplicationCatalog.App app) {
    return app.name() + "  |  " + app.state() + "  |  " + app.orgName() + " / "
        + app.spaceName() + "  |  Created " + date(app.createdAt())
        + "  |  Updated " + date(app.updatedAt());
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
