package cf.explorer.ui;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.spinner;
import static dev.tamboui.toolkit.Toolkit.text;
import static dev.tamboui.toolkit.Toolkit.waveText;

import cf.explorer.model.Activity;
import dev.tamboui.style.Color;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.widgets.spinner.SpinnerState;
import dev.tamboui.widgets.spinner.SpinnerStyle;
import dev.tamboui.widgets.wavetext.WaveTextState;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Wait-state animations shared by every page.
 *
 * <p>TamboUI redraws on every tick (40 ms by default) and on every key press. Frames are computed
 * from the activity's elapsed time rather than advanced per render, so typing during a load does
 * not speed the animation up.
 */
public final class LoadingView {
  /** Delay before the reassurance line appears on a long wait. */
  static final Duration SLOW_AFTER = Duration.ofSeconds(10);
  private static final long SPINNER_FRAME_MILLIS = 80L;
  private static final long WAVE_FRAME_MILLIS = 40L;

  private LoadingView() {}

  /** Animated spinner for {@code activity}, for use inside rows. */
  public static Element spinnerFor(Activity activity, SpinnerStyle style) {
    // SpinnerElement advances its state once before drawing, so start one frame behind.
    return spinner(style).state(new SpinnerState(frame(activity, SPINNER_FRAME_MILLIS) - 1))
        .fg(Color.LIGHT_CYAN);
  }

  /** Current spinner glyph, for titles and other plain-text spots. */
  public static String glyph(Activity activity) {
    return SpinnerStyle.DOTS.frameSet().frame((int) frame(activity, SPINNER_FRAME_MILLIS));
  }

  /** Animated wave over {@code message}, timed by {@code activity}. */
  public static Element wave(Activity activity, String message) {
    return waveText(message, Color.LIGHT_CYAN)
        .state(new WaveTextState(frame(activity, WAVE_FRAME_MILLIS) - 1));
  }

  /** Full-page wait state: animated label, elapsed time, cancel hint, and slow-wait note. */
  public static Element page(Activity activity) {
    List<Element> lines = new ArrayList<>();
    lines.add(text(""));
    lines.add(row(text(" "), spinnerFor(activity, SpinnerStyle.BOUNCING_BAR), text(" "),
        wave(activity, activity.label() + "...")));
    lines.add(text(""));
    lines.add(text(" " + elapsedLabel(activity.elapsed())).dim());
    if (activity.elapsed().compareTo(SLOW_AFTER) >= 0) {
      lines.add(text(" Still working. Cloud Foundry can be slow on large foundations.")
          .fg(Color.LIGHT_YELLOW));
    }
    lines.add(text(""));
    lines.add(text(" Press Esc to cancel.").dim());
    return panel(column(lines.toArray(Element[]::new)))
        .title(" Working ").rounded().borderColor(Color.CYAN).fill();
  }

  /** {@code "Elapsed 4s"} or {@code "Elapsed 1m 05s"}; just {@code "Working"} under a second. */
  static String elapsedLabel(Duration elapsed) {
    long seconds = elapsed.toSeconds();
    if (seconds < 1) {
      return "Working";
    }
    return "Elapsed " + shortDuration(elapsed);
  }

  /** {@code "4s"} or {@code "1m 05s"}. */
  public static String shortDuration(Duration elapsed) {
    long seconds = elapsed.toSeconds();
    return seconds < 60 ? seconds + "s" : String.format("%dm %02ds", seconds / 60, seconds % 60);
  }

  private static long frame(Activity activity, long frameMillis) {
    return activity.elapsed().toMillis() / frameMillis;
  }
}
