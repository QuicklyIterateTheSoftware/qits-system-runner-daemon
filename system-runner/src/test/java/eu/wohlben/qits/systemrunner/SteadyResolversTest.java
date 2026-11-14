package eu.wohlben.qits.systemrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import eu.wohlben.qits.systemrunner.NodeResolversTest.MovableClock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The builder's resolvers against a probe that misses (qits-2260): a short miss keeps them and the
 * stamp, a lasting one drops them, and a reply is taken at once.
 */
class SteadyResolversTest {

  private static final List<String> TUNNEL = List.of("100.96.0.1", "10.255.255.254");

  private final MovableClock clock = new MovableClock();
  private final AtomicReference<List<String>> probe = new AtomicReference<>(TUNNEL);
  private final SteadyResolvers steady = new SteadyResolvers(probe::get, clock);

  @Test
  void aOneOffEmptyAnswerKeepsTheResolversAndTheStamp() {
    BuildPlane plane = BuildPlaneTest.planeOn(steady);
    String before = plane.configuredStamp();
    assertEquals(TUNNEL, steady.get());

    probe.set(List.of());
    clock.advance(Duration.ofSeconds(30));
    assertEquals(TUNNEL, steady.get(), "a missed reply is not the tunnel gone");
    clock.advance(Duration.ofSeconds(30));
    assertEquals(before, plane.configuredStamp());

    probe.set(TUNNEL);
    assertEquals(TUNNEL, steady.get());
    probe.set(List.of());
    clock.advance(SteadyResolvers.HOLD.minusSeconds(1));
    assertEquals(TUNNEL, steady.get(), "a reply in between starts the wait over");
    assertEquals(before, plane.configuredStamp());
  }

  @Test
  void aLastingEmptyAnswerDropsTheResolversAndMovesTheStamp() {
    BuildPlane plane = BuildPlaneTest.planeOn(steady);
    String tunnelled = plane.configuredStamp();

    probe.set(List.of());
    assertEquals(TUNNEL, steady.get());
    clock.advance(SteadyResolvers.HOLD);
    assertEquals(List.of(), steady.get(), "the tunnel is gone after the hold");
    assertEquals(plane.configuredStamp(List.of()), plane.configuredStamp());
    assertNotEquals(tunnelled, plane.configuredStamp());
  }

  @Test
  void aReplyIsTakenAtOnceAndTheFirstAnswerAsItIs() {
    probe.set(List.of());
    assertEquals(List.of(), steady.get(), "nothing to keep on the first ask");
    probe.set(TUNNEL);
    assertEquals(TUNNEL, steady.get(), "the tunnel coming up counts at once");
    List<String> moved = List.of("100.96.0.1", "192.168.188.1");
    probe.set(moved);
    assertEquals(moved, steady.get(), "a changed upstream counts at once");
  }

  @Test
  void aThrowingProbeCountsAsAnEmptyAnswer() {
    assertEquals(TUNNEL, steady.get());
    SteadyResolvers throwing =
        new SteadyResolvers(
            () -> {
              throw new IllegalStateException("boom");
            },
            clock);
    assertEquals(List.of(), throwing.get());
  }
}
