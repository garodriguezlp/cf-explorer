package cf.explorer.model;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Background work the user is waiting for: a label and the moment it started.
 *
 * <p>Views derive animation frames and elapsed time from {@link #elapsed()}, so the animation
 * speed does not depend on how often the screen redraws.
 */
public record Activity(String label, long startedNanos) {
  public Activity {
    Objects.requireNonNull(label);
  }

  public static Activity start(String label) {
    return new Activity(label, System.nanoTime());
  }

  public Duration elapsed() {
    return Duration.ofNanos(Math.max(0L, System.nanoTime() - startedNanos));
  }

  /**
   * Runs {@code action} once at least {@code minimum} has passed since the activity started.
   * Fast results wait on a timer thread instead of the caller, so a spinner never flickers for a
   * single frame and no thread is blocked.
   */
  public void afterAtLeast(Duration minimum, Runnable action) {
    Objects.requireNonNull(action);
    long remaining = minimum.toNanos() - (System.nanoTime() - startedNanos);
    if (remaining <= 0) {
      action.run();
    } else {
      CompletableFuture.delayedExecutor(remaining, TimeUnit.NANOSECONDS).execute(action);
    }
  }
}
