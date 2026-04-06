package cf.explorer;

import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.bindings.ActionHandler;
import dev.tamboui.tui.bindings.Actions;
import dev.tamboui.tui.bindings.BindingSets;
import dev.tamboui.tui.bindings.Bindings;
import dev.tamboui.tui.bindings.KeyTrigger;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

/**
 * Routes key events to the action handler that matches the current application state.
 *
 * <p>All methods are called from the render thread. Each screen phase is served by a dedicated
 * {@link ActionHandler} so that the same key can mean different things depending on the current
 * state.
 */
final class KeyHandler {

  private final Controller controller;
  private final ActionHandler browsingHandler;
  private final ActionHandler exportResultHandler;

  KeyHandler(Controller controller) {
    this.controller = controller;
    this.browsingHandler = new BrowsingHandlers(controller).build();
    this.exportResultHandler = new ExportResultHandlers(controller).build();
  }

  EventResult handle(KeyEvent event) {
    var state = controller.state();
    if (state instanceof AppState.CatalogLoading || state instanceof AppState.EnvExporting)
      return EventResult.HANDLED;
    if (state instanceof AppState.Browsing) return dispatch(event, browsingHandler);
    if (state instanceof AppState.ExportDone || state instanceof AppState.ExportFailed)
      return dispatch(event, exportResultHandler);
    return EventResult.UNHANDLED;
  }

  private static EventResult dispatch(KeyEvent event, ActionHandler handler) {
    return handler.dispatch(event) ? EventResult.HANDLED : EventResult.UNHANDLED;
  }

  private static final class BrowsingHandlers {

    private static final String APPEND_FILTER_CHARACTER = "appendFilterCharacter";
    private static final String OPEN_IN_BROWSER = "openInBrowser";

    private static final Bindings BINDINGS =
        BindingSets.defaults().toBuilder()
            .bind(KeyTrigger.key(KeyCode.CHAR), BrowsingHandlers.APPEND_FILTER_CHARACTER)
            .rebind(KeyTrigger.key(KeyCode.ENTER), Actions.SELECT)
            .rebind(KeyTrigger.ctrl('h'), Actions.DELETE_BACKWARD)
            .bind(KeyTrigger.ctrl('o'), BrowsingHandlers.OPEN_IN_BROWSER)
            .build();

    private final Controller controller;

    BrowsingHandlers(Controller controller) {
      this.controller = controller;
    }

    ActionHandler build() {
      return new ActionHandler(BINDINGS)
          .on(Actions.MOVE_UP, this::handleMoveUp)
          .on(Actions.MOVE_DOWN, this::handleMoveDown)
          .on(APPEND_FILTER_CHARACTER, this::handleAppendFilterCharacter)
          .on(Actions.SELECT, this::handleSelectCurrentApp)
          .on(OPEN_IN_BROWSER, this::handleOpenCurrentAppInBrowser)
          .on(Actions.DELETE_BACKWARD, this::handleBackspaceFilter)
          .on(Actions.CANCEL, this::handleClearFilter);
    }

    private void handleMoveUp(Event event) {
      controller.moveUp();
    }

    private void handleMoveDown(Event event) {
      controller.moveDown();
    }

    private void handleAppendFilterCharacter(Event event) {
      controller.appendFilter(((KeyEvent) event).character());
    }

    private void handleSelectCurrentApp(Event event) {
      var app = selectedApp();
      if (app != null) controller.selectApp(app);
    }

    private void handleOpenCurrentAppInBrowser(Event event) {
      var app = selectedApp();
      if (app != null) controller.openInBrowser(app);
    }

    private void handleBackspaceFilter(Event event) {
      controller.backspaceFilter();
    }

    private void handleClearFilter(Event event) {
      controller.clearFilter();
    }

    private App selectedApp() {
      return browsing().selectedApp();
    }

    private AppState.Browsing browsing() {
      return (AppState.Browsing) controller.state();
    }
  }

  private static final class ExportResultHandlers {
    private final Controller controller;

    ExportResultHandlers(Controller controller) {
      this.controller = controller;
    }

    ActionHandler build() {
      return new ActionHandler(BindingSets.defaults())
          .on(Actions.CANCEL, this::handleReturnToBrowsing);
    }

    private void handleReturnToBrowsing(Event event) {
      controller.returnToBrowsing();
    }
  }
}
