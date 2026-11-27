package cf.explorer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cf.explorer.model.Activity;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import dev.tamboui.toolkit.element.Element;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LoadingViewTest {
  @Test
  void formatsElapsedTime() {
    assertEquals("Working", LoadingView.elapsedLabel(Duration.ofMillis(900)));
    assertEquals("Elapsed 4s", LoadingView.elapsedLabel(Duration.ofSeconds(4)));
    assertEquals("Elapsed 1m 05s", LoadingView.elapsedLabel(Duration.ofSeconds(65)));
  }

  @Test
  void pageShowsLabelElapsedTimeAndCancelHint() {
    String screen = render(LoadingView.page(startedAgo("Fetching data", 3)));
    assertTrue(screen.contains("Fetching data..."), screen);
    assertTrue(screen.contains("Elapsed 3s"), screen);
    assertTrue(screen.contains("Press Esc to cancel."), screen);
    assertFalse(screen.contains("Still working"), screen);
  }

  @Test
  void longWaitsAddAReassuranceLine() {
    String screen = render(LoadingView.page(startedAgo("Fetching data", 12)));
    assertTrue(screen.contains("Still working."), screen);
  }

  @Test
  void spinnerFrameFollowsElapsedTimeNotRedrawCount() {
    Activity activity = startedAgo("Work", 0);
    String first = LoadingView.glyph(activity);
    assertEquals(first, LoadingView.glyph(activity), "redraws in the same frame match");
    Activity later = new Activity("Work",
        activity.startedNanos() - TimeUnit.MILLISECONDS.toNanos(80));
    assertNotEquals(first, LoadingView.glyph(later));
  }

  private static Activity startedAgo(String label, long seconds) {
    return new Activity(label, System.nanoTime() - TimeUnit.SECONDS.toNanos(seconds));
  }

  private static String render(Element element) {
    Rect area = new Rect(0, 0, 80, 12);
    Buffer buffer = Buffer.empty(area);
    element.render(Frame.forTesting(buffer), area, DefaultRenderContext.createEmpty());
    StringBuilder result = new StringBuilder();
    for (int y = 0; y < area.height(); y++) {
      for (int x = 0; x < area.width(); x++) {
        result.append(buffer.get(x, y).symbol());
      }
      result.append('\n');
    }
    return result.toString();
  }
}
