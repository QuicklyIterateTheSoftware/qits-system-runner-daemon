package eu.wohlben.qits.systemrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * {@link NodeResolvers} over two scripted choices and a clock the suite moves (qits-2091): the
 * private edge's before the node tunnel's, the node asked at most once a minute, and a choice that
 * throws read as none.
 */
class NodeResolversTest {

  private final AtomicInteger edgeAsked = new AtomicInteger();
  private final AtomicInteger tunnelAsked = new AtomicInteger();
  private final AtomicReference<Optional<String>> edge = new AtomicReference<>(Optional.empty());
  private final AtomicReference<Optional<String>> tunnel = new AtomicReference<>(Optional.empty());
  private final MovableClock clock = new MovableClock();

  private final NodeResolvers resolvers =
      new NodeResolvers(
          () -> {
            edgeAsked.incrementAndGet();
            return edge.get();
          },
          () -> {
            tunnelAsked.incrementAndGet();
            return tunnel.get();
          },
          clock);

  @Test
  void theNodeIsAskedOncePerMinuteAndTheAnswerKeptInBetween() {
    assertEquals(Optional.empty(), resolvers.last(), "nothing before the first ask");
    assertEquals(Optional.empty(), resolvers.choice());
    assertEquals(1, tunnelAsked.get());

    tunnel.set(Optional.of(NodeTunnel.DNS_ADDRESS));
    clock.advance(Duration.ofSeconds(59));
    assertEquals(Optional.empty(), resolvers.choice(), "still the cached answer");
    assertEquals(1, edgeAsked.get());
    assertEquals(1, tunnelAsked.get());

    clock.advance(Duration.ofSeconds(1));
    assertEquals(Optional.of(NodeTunnel.DNS_ADDRESS), resolvers.choice());
    assertEquals(Optional.of(NodeTunnel.DNS_ADDRESS), resolvers.last());
    assertEquals(2, tunnelAsked.get());
    resolvers.choice();
    resolvers.choice();
    assertEquals(2, edgeAsked.get(), "one ask per minute");
    assertEquals(2, tunnelAsked.get());
  }

  @Test
  void thePrivateEdgeWinsAndTheTunnelIsThenNotAsked() {
    edge.set(Optional.of("10.231.0.2"));
    tunnel.set(Optional.of(NodeTunnel.DNS_ADDRESS));

    assertEquals(Optional.of("10.231.0.2"), resolvers.choice());
    assertEquals(0, tunnelAsked.get());
  }

  @Test
  void aChoiceThatThrowsIsNoneAndTheNextMinuteAsksAgain() {
    NodeResolvers throwing =
        new NodeResolvers(
            () -> {
              edgeAsked.incrementAndGet();
              throw new IllegalStateException("docker is gone");
            },
            tunnel::get,
            clock);

    assertEquals(Optional.empty(), throwing.choice());
    clock.advance(NodeResolvers.CACHE_FOR);
    assertEquals(Optional.empty(), throwing.choice());
    assertEquals(2, edgeAsked.get());
  }

  /** A clock that stands still until the suite moves it. */
  static final class MovableClock extends Clock {

    private final AtomicReference<Instant> now =
        new AtomicReference<>(Instant.parse("2026-11-11T12:00:00Z"));

    void advance(Duration by) {
      now.updateAndGet(i -> i.plus(by));
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
      return now.get();
    }
  }
}
