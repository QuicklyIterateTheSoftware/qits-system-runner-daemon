package eu.wohlben.qits.systemrunner;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * The node's image pulls, run <b>off the converge lock</b> (qits-1543). A cold pull of a CI slot
 * image is gigabytes and can take up to the pull deadline (30 minutes by default); run inside a
 * convergence it held the lock that long, so no lease ended, no other lease started and no
 * inventory went out. Here a convergence only asks: a pull it needs is started on the bounded
 * executor, the lease is reported {@code STARTING} with {@code pulling <image> since <instant>} —
 * the same words for the whole pull, so the server sees one change — and the convergence moves on.
 * When the pull settles, {@code settled} runs, and the domain converges its latest {@code estate}
 * again — which then finds the image here and creates the container.
 *
 * <ul>
 *   <li><b>One pull per image reference at a time</b>: every lease of one image shares it.
 *   <li><b>A failed pull is kept</b> until a convergence has reported it — the lease is {@code
 *       FAILED} with docker's words, exactly as a synchronous pull failed it — and is tried again
 *       only from the next {@code estate} the server sends ({@link #retryReported}), so a lease is
 *       never left {@code STARTING} by a pull that failed before anybody saw it.
 *   <li><b>A pull that succeeded</b> answers {@code READY} once, even should docker not list the
 *       image afterwards, so a convergence never starts the same pull in a loop; the run's own
 *       {@code --pull missing} is the belt behind it.
 *   <li><b>A warm pull</b> ({@link #warm}, an {@code estate.warmImages} entry, qits-1692) is the
 *       same pull with nobody waiting on it, run <b>on a pool of its own</b>, one at a time, the
 *       rest queued here — so a lease's pull never waits behind one. A lease that needs an image a
 *       warm pull is fetching shares that pull; one whose warm pull is still queued takes it out of
 *       the queue and pulls it on the lease pool at once. A warm pull's end converges nothing
 *       again, its failure is never a lease's — a lease that asks for the image afterwards starts
 *       its own pull rather than inherit it — and is forgotten at the next {@code estate}, which
 *       names the image again.
 *   <li><b>A pull that runs long says so</b>: {@link #stillPulling} names every running pull and how
 *       long it has run, which the domain logs once a minute.
 * </ul>
 */
final class ImagePulls {

  private static final Logger LOG = Logger.getLogger(ImagePulls.class);

  /** Where one image's pull stands. */
  enum Phase {
    /** Pulled: the convergence may go on. */
    READY,
    /** A pull runs: the lease is {@code STARTING}; {@code detail} says which and since when. */
    PULLING,
    /** The last pull failed; {@code detail} is docker's words. */
    FAILED
  }

  record Status(Phase phase, String detail) {

    static final Status READY = new Status(Phase.READY, null);

    /** {@code pulling <image> since 2026-10-27T19:55:49Z}: no elapsed time, so it never changes. */
    static Status pulling(String image, Instant since) {
      return new Status(
          Phase.PULLING, "pulling " + image + " since " + since.truncatedTo(ChronoUnit.SECONDS));
    }

    static Status failed(String detail) {
      return new Status(Phase.FAILED, detail);
    }

    boolean failed() {
      return phase == Phase.FAILED;
    }

    boolean pulling() {
      return phase == Phase.PULLING;
    }
  }

  private static final class Failure {
    private final String detail;
    private final boolean warm;
    /** The {@link #session} it failed in. */
    private final long session;
    private boolean reported;

    private Failure(String detail, boolean warm, long session) {
      this.detail = detail;
      this.warm = warm;
      this.session = session;
    }
  }

  private final Executor executor;
  private final Executor warmExecutor;
  private final Runnable settled;
  private final Clock clock;

  // All below under this object's monitor.
  private final Set<String> running = new HashSet<>();
  /** When each of {@link #running} started. */
  private final Map<String, Instant> since = new HashMap<>();
  /** The running pulls {@link #warm} started that no convergence has asked for since. */
  private final Set<String> warmOnly = new HashSet<>();
  private final Set<String> pulled = new HashSet<>();
  private final Map<String, Failure> failed = new HashMap<>();
  /** The connection the pulls settle in now: {@link #newSession} counts them. */
  private long session;
  /** The warm pull on {@link #warmExecutor} now, or null: one at a time. */
  private String warmInFlight;
  /** The warm pulls waiting for {@link #warmInFlight} to settle, in the order they were named. */
  private final Map<String, Supplier<Optional<String>>> warmQueue = new LinkedHashMap<>();

  /**
   * @param executor the bounded pool the pulls a lease needs run on
   * @param warmExecutor the pool the warm pulls run on, one at a time: never {@code executor}
   * @param settled run after every pull a lease waits on, on the pull's thread, once its outcome is
   *     kept
   */
  ImagePulls(Executor executor, Executor warmExecutor, Runnable settled) {
    this(executor, warmExecutor, settled, Clock.systemUTC());
  }

  /** The same, with the clock a pull's start is read from. */
  ImagePulls(Executor executor, Executor warmExecutor, Runnable settled, Clock clock) {
    this.executor = executor;
    this.warmExecutor = warmExecutor;
    this.settled = settled;
    this.clock = clock;
  }

  /**
   * Where {@code image}'s pull stands for a convergence that needs it — started now when none runs
   * and none failed. Never waits on the pull.
   *
   * @param pull the pull itself: empty on success, else docker's words
   */
  synchronized Status status(String image, Supplier<Optional<String>> pull) {
    warmOnly.remove(image);
    Failure failure = failed.get(image);
    if (failure != null && failure.warm) {
      failed.remove(image);
      failure = null;
    }
    if (failure != null) {
      failure.reported = true;
      return Status.failed(failure.detail);
    }
    if (pulled.remove(image)) {
      return Status.READY;
    }
    if (!start(image, pull)) {
      return Status.failed(stopping());
    }
    return Status.pulling(image, since.getOrDefault(image, clock.instant()));
  }

  /** When the running pull of {@code image} started; empty when none runs. */
  synchronized Optional<Instant> pullingSince(String image) {
    return Optional.ofNullable(since.get(image));
  }

  /**
   * {@code system-runner still pulls <image> (83.4s)} for every running pull, by image, {@code warm
   * image } before a warm one's: what the domain logs once a minute.
   */
  synchronized List<String> stillPulling(Instant now) {
    List<String> lines = new ArrayList<>();
    new TreeMap<>(since)
        .forEach(
            (image, started) ->
                lines.add(
                    "system-runner still pulls "
                        + (warmOnly.contains(image) ? "warm image " : "")
                        + image
                        + " ("
                        + took(Duration.between(started, now).toNanos())
                        + ")"));
    return lines;
  }

  /** Log {@link #stillPulling} now. */
  void logProgress() {
    stillPulling(clock.instant()).forEach(LOG::info);
  }

  /**
   * Start pulling {@code image} ahead of any lease that needs it, when no pull of it runs and none
   * failed unreported: the pre-pull at the {@code Ack}.
   */
  synchronized void prefetch(String image, Supplier<Optional<String>> pull) {
    if (failed.containsKey(image) || pulled.contains(image)) {
      return;
    }
    start(image, pull);
  }

  /**
   * Start pulling {@code image} so it is here before any lease needs it: an {@code
   * estate.warmImages} entry not on the node. Nothing when a pull of it runs, or one settled since
   * the last {@code estate} — a failed one is tried again only from the next.
   */
  synchronized void warm(String image, Supplier<Optional<String>> pull) {
    if (running.contains(image)
        || failed.containsKey(image)
        || pulled.contains(image)
        || warmQueue.containsKey(image)) {
      return;
    }
    if (warmInFlight != null) {
      warmQueue.put(image, pull);
      return;
    }
    startWarm(image, pull);
  }

  /** {@code image}'s warm pull on the warm pool; a stopping pool drops it and the queue. */
  private void startWarm(String image, Supplier<Optional<String>> pull) {
    running.add(image);
    since.put(image, clock.instant());
    warmOnly.add(image);
    warmInFlight = image;
    try {
      warmExecutor.execute(() -> pull(image, pull));
    } catch (RejectedExecutionException shutDown) {
      running.remove(image);
      since.remove(image);
      warmOnly.remove(image);
      warmInFlight = null;
      warmQueue.clear();
      return;
    }
    LOG.infof("system-runner warms %s off the convergence", image);
  }

  /**
   * A new connection, before any of its messages: a pull that fails from here on is this
   * connection's own ({@link #retryWarm}).
   */
  synchronized void newSession() {
    session++;
  }

  /**
   * A new connection's {@code Ack}: a warm pull that failed on an earlier connection is tried again
   * when it is named again. One this connection's own estate already made fail is kept until the
   * next {@code estate} — the runtime runs the {@code Ack} and the estate concurrently, so the
   * estate's warm pull may have failed before the {@code Ack} got here, and clearing it would pull
   * the image twice on one connection.
   */
  synchronized void retryWarm() {
    failed.values().removeIf(f -> f.warm && f.session < session);
  }

  /**
   * A new {@code estate} from the server: every failed pull a convergence has already reported is
   * forgotten, so the next convergence that needs the image tries it again — and every failed warm
   * pull, so the estate's {@code warmImages} try it again.
   */
  synchronized void retryReported() {
    failed.values().removeIf(f -> f.reported || f.warm);
    pulled.clear();
  }

  /**
   * {@code image}'s pull on the lease pool — or the one already running, a warm one included. A
   * warm pull still queued is taken out of the queue: the lease does not wait its turn.
   */
  private boolean start(String image, Supplier<Optional<String>> pull) {
    warmQueue.remove(image);
    if (running.contains(image)) {
      return true;
    }
    running.add(image);
    since.put(image, clock.instant());
    try {
      executor.execute(() -> pull(image, pull));
    } catch (RejectedExecutionException shutDown) {
      running.remove(image);
      since.remove(image);
      return false;
    }
    LOG.infof("system-runner pulls %s off the convergence", image);
    return true;
  }

  private void pull(String image, Supplier<Optional<String>> pull) {
    long started = System.nanoTime();
    Optional<String> outcome;
    try {
      outcome = pull.get();
    } catch (RuntimeException e) {
      outcome = Optional.of(String.valueOf(e.getMessage()));
    }
    String took = took(System.nanoTime() - started);
    boolean warm;
    synchronized (this) {
      running.remove(image);
      since.remove(image);
      warm = warmOnly.remove(image);
      if (outcome.isPresent()) {
        failed.put(image, new Failure(outcome.get(), warm, session));
      } else {
        pulled.add(image);
      }
      if (image.equals(warmInFlight)) {
        warmInFlight = null;
        var next = warmQueue.entrySet().iterator();
        if (next.hasNext()) {
          var queued = next.next();
          next.remove();
          startWarm(queued.getKey(), queued.getValue());
        }
      }
    }
    if (outcome.isPresent()) {
      LOG.warnf(
          "system-runner could not pull %s%s: %s", warm ? "warm image " : "", image, outcome.get());
    } else {
      LOG.infof("system-runner pulled %s%s in %s", warm ? "warm image " : "", image, took);
    }
    if (warm) {
      return; // nobody waits on it: nothing to converge again
    }
    try {
      settled.run();
    } catch (RuntimeException e) {
      LOG.warnf("system-runner could not converge again after pulling %s: %s", image, e.getMessage());
    }
  }

  /** {@code 83.4s}: how long a pull took. */
  static String took(long nanos) {
    long tenths = Math.max(0, nanos) / 100_000_000L;
    return (tenths / 10) + "." + (tenths % 10) + "s";
  }

  private static String stopping() {
    return "the runner is stopping: nothing more is pulled";
  }
}
