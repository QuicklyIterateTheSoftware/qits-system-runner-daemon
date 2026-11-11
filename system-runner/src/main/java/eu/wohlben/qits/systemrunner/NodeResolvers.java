package eu.wohlben.qits.systemrunner;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * <b>The node's current resolver choice</b> (qits-2091): the address a lease created now would get
 * as its first {@code --dns} — {@link PrivateEdge#choice}, the edge's VIP, where the overlay offers
 * one, else {@link NodeTunnel#choice}, {@value NodeTunnel#DNS_ADDRESS}, where the tunnel answers,
 * else none. The same order {@code LeaseLauncher.create} applies the two in, so a lease created
 * now is never reported stale against itself.
 *
 * <p>Asked from the periodic look, off the converge lock, and the answer kept for {@link #CACHE_FOR}:
 * the edge's lookup is three docker calls and the tunnel's a probe that may wait out its second, and
 * the look runs every few seconds. Against it the look compares each lease container's resolvers
 * ({@code LeaseReport.resolversStale}) — only to report: the runner never recreates a lease because
 * its resolvers are stale. The owner does, by raising the lease's {@code LeaseEntry.generation}.
 *
 * <p>Never throws: a choice that cannot be made is none, and the previous answer is not kept past
 * its time.
 */
final class NodeResolvers {

  private static final Logger LOG = Logger.getLogger(NodeResolvers.class);

  /** How long one answer is kept: a minute, against a look every fifteen seconds. */
  static final Duration CACHE_FOR = Duration.ofSeconds(60);

  private final Supplier<Optional<String>> edge;
  private final Supplier<Optional<String>> tunnel;
  private final Clock clock;

  /** The last answer, and until when it holds; null before the first. */
  private Optional<String> cached;

  private Instant until;

  /** {@link #cached} for {@link #last}, which never waits on an asking {@link #choice}. */
  private volatile Optional<String> last = Optional.empty();

  NodeResolvers(PrivateEdge edge, NodeTunnel tunnel, Clock clock) {
    this(edge::choice, tunnel::choice, clock);
  }

  /** With the two choices as seams — another pair for the suite only. */
  NodeResolvers(
      Supplier<Optional<String>> edge, Supplier<Optional<String>> tunnel, Clock clock) {
    this.edge = edge;
    this.tunnel = tunnel;
    this.clock = clock;
  }

  /**
   * The address a lease created now would resolve through first, or empty when it would keep the
   * node's own resolvers; asked of the node at most once per {@link #CACHE_FOR}. Never throws.
   */
  synchronized Optional<String> choice() {
    Instant now = clock.instant();
    if (cached != null && now.isBefore(until)) {
      return cached;
    }
    Optional<String> answer;
    try {
      answer = edge.get().or(tunnel);
    } catch (RuntimeException e) {
      LOG.debugf("system-runner could not tell its node's resolver choice: %s", e.getMessage());
      answer = Optional.empty();
    }
    cached = answer;
    until = now.plus(CACHE_FOR);
    last = answer;
    return answer;
  }

  /**
   * The last answer without asking the node, and without waiting for a {@link #choice} that is
   * asking it: empty before the first. What the {@code inventory} reads.
   */
  Optional<String> last() {
    return last;
  }
}
