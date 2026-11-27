package cf.explorer.model;

import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.event.KeyEvent;

/** A shell page owns its presentation; navigation and input remain in the shell. */
public interface ShellPage {
  Element render();

  default Object selected() {
    return null;
  }

  default String inputValue() {
    return null;
  }

  default int inputCursor() {
    return 0;
  }

  /** Title shown on the shell's bottom input box when {@link #inputValue()} is non-null. */
  default String inputTitle() {
    return "Filter";
  }

  /**
   * Whether {@code /} should be delivered to {@link #handleKey} as text instead of opening the
   * command view (for example, while entering an environment variable value containing a URL).
   */
  default boolean capturesSlash() {
    return false;
  }

  /**
   * Gives an opened page a chance to cancel an inline prompt or clear its filter on {@code Esc}
   * before the shell navigates back. Returns {@code true} when consumed.
   */
  default boolean onEscape(ShellState state) {
    return false;
  }

  /**
   * Optional confirmation prompt shown before {@link ShellState#back()} leaves this page (for
   * example, when unapplied edits would be discarded), or {@code null} when safe to leave.
   */
  default String confirmDiscardPrompt() {
    return null;
  }

  /** Background work this page is running, such as a refresh, or {@code null}. */
  default Activity activity() {
    return null;
  }

  default EventResult handleKey(KeyEvent event) {
    return EventResult.UNHANDLED;
  }
}
