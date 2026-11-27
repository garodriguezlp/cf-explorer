package cf.explorer.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ActivityTest {
  @Test
  void runsImmediatelyOnceTheMinimumHasPassed() {
    AtomicBoolean ran = new AtomicBoolean();
    Activity.start("Work").afterAtLeast(Duration.ZERO, () -> ran.set(true));
    assertTrue(ran.get());
  }

  @Test
  void delaysFastResultsWithoutBlockingTheCaller() throws InterruptedException {
    Activity activity = Activity.start("Work");
    CountDownLatch done = new CountDownLatch(1);
    AtomicLong ranAfter = new AtomicLong();
    activity.afterAtLeast(Duration.ofMillis(150), () -> {
      ranAfter.set(activity.elapsed().toMillis());
      done.countDown();
    });
    assertEquals(1, done.getCount(), "the caller returns before the minimum elapses");
    assertTrue(done.await(5, TimeUnit.SECONDS));
    assertTrue(ranAfter.get() >= 150, "ran after " + ranAfter.get() + " ms");
  }

  @Test
  void elapsedNeverGoesNegative() {
    Activity future = new Activity("Work", System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
    assertFalse(future.elapsed().isNegative());
  }
}
