package eu.wohlben.qits.systemrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ImagePulls}' account of a running pull (qits-1693): when it started, the {@code STARTING}
 * words that stay the same for the whole pull, and the once-a-minute progress lines.
 */
class ImagePullsTest {

  private static final String IMAGE = "registry.example:5000/qits/ci-slot:2026.1027.1";
  private static final Instant STARTED = Instant.parse("2026-10-27T19:55:49.123Z");

  private final ExecutorService executor = Executors.newCachedThreadPool();
  private final ExecutorService warmExecutor = Executors.newSingleThreadExecutor();
  private final Semaphore settled = new Semaphore(0);
  private final MovingClock clock = new MovingClock(STARTED);
  private final ImagePulls pulls =
      new ImagePulls(executor, warmExecutor, settled::release, clock);

  @AfterEach
  void tearDown() {
    executor.shutdownNow();
    warmExecutor.shutdownNow();
  }

  @Test
  void aRunningPullSaysSinceWhenInWordsThatNeverChangeAndNothingOnceItLands() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    Supplier<Optional<String>> pull = blocking(release, Optional.empty());

    ImagePulls.Status first = pulls.status(IMAGE, pull);
    assertTrue(first.pulling(), first::toString);
    assertEquals("pulling " + IMAGE + " since 2026-10-27T19:55:49Z", first.detail());
    assertEquals(Optional.of(STARTED), pulls.pullingSince(IMAGE));

    clock.now = STARTED.plusSeconds(95);
    ImagePulls.Status later = pulls.status(IMAGE, pull);
    assertEquals(first, later, "the same words for the whole pull");
    assertEquals(Optional.of(STARTED), pulls.pullingSince(IMAGE));
    assertEquals(
        List.of("system-runner still pulls " + IMAGE + " (83.4s)"),
        pulls.stillPulling(STARTED.plusMillis(83_400)));

    release.countDown();
    assertTrue(settled.tryAcquire(10, TimeUnit.SECONDS), "the pull settled");
    assertEquals(Optional.empty(), pulls.pullingSince(IMAGE));
    assertEquals(List.of(), pulls.stillPulling(clock.instant()));
    assertEquals(ImagePulls.Status.READY, pulls.status(IMAGE, pull));
  }

  @Test
  void aWarmPullIsNamedAsOneInTheProgressLines() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    pulls.warm(IMAGE, blocking(release, Optional.empty()));

    assertEquals(Optional.of(STARTED), pulls.pullingSince(IMAGE));
    assertEquals(
        List.of("system-runner still pulls warm image " + IMAGE + " (120.0s)"),
        pulls.stillPulling(STARTED.plus(Duration.ofMinutes(2))));
    release.countDown();
  }

  @Test
  void aPullThatDidNotAnswerInTimeIsFailedWithThoseWords() throws Exception {
    String deadline = "docker pull " + IMAGE + " did not answer within 1s";
    Supplier<Optional<String>> pull = () -> Optional.of(deadline);

    assertTrue(pulls.status(IMAGE, pull).pulling());
    assertTrue(settled.tryAcquire(10, TimeUnit.SECONDS), "the pull settled");
    assertEquals(Optional.empty(), pulls.pullingSince(IMAGE));
    assertEquals(ImagePulls.Status.failed(deadline), pulls.status(IMAGE, pull));
  }

  private static Supplier<Optional<String>> blocking(
      CountDownLatch release, Optional<String> outcome) {
    return () -> {
      try {
        release.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return outcome;
    };
  }

  /** A clock a test moves. */
  private static final class MovingClock extends Clock {
    private volatile Instant now;

    private MovingClock(Instant now) {
      this.now = now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
