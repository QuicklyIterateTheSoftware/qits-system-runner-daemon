package eu.wohlben.qits.systemrunner;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * <b>The builder's resolvers, steady against a failing probe</b> (qits-2260). The node tunnel's
 * probe ({@link NodeTunnel#resolvers()}) can miss its one-second answer for a minute after a
 * reconnect while the tunnel is fine. Asked raw, each miss moved the builder's stamp and each answer
 * moved it back, and every move replaced the builder under the builds running on it.
 *
 * <p>The rule is one-sided, because the two answers are not equally sure:
 *
 * <ul>
 *   <li>a non-empty answer is a reply the tunnel actually sent, so it is taken at once — the tunnel
 *       coming up, or a changed upstream, moves the stamp on the next ask, as in qits-2090;
 *   <li>an empty answer may only be a lost reply, so the last non-empty answer is kept until every
 *       ask for {@link #HOLD} has come back empty — only then is the tunnel gone.
 * </ul>
 *
 * <p>The first ask has nothing to keep and is taken as it is. Shared by every {@link BuildPlane}
 * the domain makes (one per {@code Ack}), so a reconnect does not reset it. Never throws.
 */
final class SteadyResolvers implements Supplier<List<String>> {

  private static final Logger LOG = Logger.getLogger(SteadyResolvers.class);

  /** How long the probe must fail on every ask before the tunnel counts as gone. */
  static final Duration HOLD = Duration.ofMinutes(5);

  private final Supplier<List<String>> source;
  private final Clock clock;
  private final Duration hold;

  /** The answer given; null before the first ask. */
  private List<String> held;

  /** When the probe first came back empty while {@link #held} was not; null otherwise. */
  private Instant emptySince;

  SteadyResolvers(Supplier<List<String>> source, Clock clock) {
    this(source, clock, HOLD);
  }

  SteadyResolvers(Supplier<List<String>> source, Clock clock, Duration hold) {
    this.source = source;
    this.clock = clock;
    this.hold = hold;
  }

  @Override
  public synchronized List<String> get() {
    List<String> answer = ask();
    if (held == null || !answer.isEmpty() || held.isEmpty()) {
      if (held != null && !held.equals(answer)) {
        LOG.infof("system-runner's builder resolvers change from %s to %s", held, answer);
      }
      held = answer;
      emptySince = null;
      return held;
    }
    Instant now = clock.instant();
    if (emptySince == null) {
      emptySince = now;
      LOG.infof(
          "system-runner keeps the builder resolvers %s although the node tunnel did not answer;"
              + " they go if it does not answer for %s",
          held,
          hold);
    } else if (!now.isBefore(emptySince.plus(hold))) {
      LOG.infof(
          "system-runner drops the builder resolvers %s: the node tunnel has not answered since %s",
          held,
          emptySince);
      held = List.of();
      emptySince = null;
    }
    return held;
  }

  private List<String> ask() {
    try {
      List<String> answer = source.get();
      return answer == null ? List.of() : List.copyOf(answer);
    } catch (RuntimeException e) {
      return List.of();
    }
  }
}
