package eu.wohlben.qits.systemrunner;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * The node's image pulls, run <b>off the converge lock</b> (qits-1543). A cold pull of a CI slot
 * image is gigabytes and can take up to the pull deadline (30 minutes by default); run inside a
 * convergence it held the lock that long, so no lease ended, no other lease started and no
 * inventory went out. Here a convergence only asks: a pull it needs is started on the bounded
 * executor, the lease is reported {@code STARTING}, and the convergence moves on. When the pull
 * settles, {@code settled} runs, and the domain converges its latest {@code estate} again — which
 * then finds the image here and creates the container.
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
 * </ul>
 */
final class ImagePulls {

  private static final Logger LOG = Logger.getLogger(ImagePulls.class);

  /** Where one image's pull stands. */
  enum Phase {
    /** Pulled: the convergence may go on. */
    READY,
    /** A pull runs: the lease is {@code STARTING}. */
    PULLING,
    /** The last pull failed; {@code detail} is docker's words. */
    FAILED
  }

  record Status(Phase phase, String detail) {

    static final Status READY = new Status(Phase.READY, null);
    static final Status PULLING = new Status(Phase.PULLING, null);

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
    private boolean reported;

    private Failure(String detail) {
      this.detail = detail;
    }
  }

  private final Executor executor;
  private final Runnable settled;

  // All three under this object's monitor.
  private final Set<String> running = new HashSet<>();
  private final Set<String> pulled = new HashSet<>();
  private final Map<String, Failure> failed = new HashMap<>();

  /**
   * @param executor the bounded pool the pulls run on
   * @param settled run after every pull, on the pull's thread, once its outcome is kept
   */
  ImagePulls(Executor executor, Runnable settled) {
    this.executor = executor;
    this.settled = settled;
  }

  /**
   * Where {@code image}'s pull stands for a convergence that needs it — started now when none runs
   * and none failed. Never waits on the pull.
   *
   * @param pull the pull itself: empty on success, else docker's words
   */
  synchronized Status status(String image, Supplier<Optional<String>> pull) {
    Failure failure = failed.get(image);
    if (failure != null) {
      failure.reported = true;
      return Status.failed(failure.detail);
    }
    if (pulled.remove(image)) {
      return Status.READY;
    }
    return start(image, pull) ? Status.PULLING : Status.failed(stopping());
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
   * A new {@code estate} from the server: every failed pull a convergence has already reported is
   * forgotten, so the next convergence that needs the image tries it again.
   */
  synchronized void retryReported() {
    failed.values().removeIf(f -> f.reported);
    pulled.clear();
  }

  private boolean start(String image, Supplier<Optional<String>> pull) {
    if (running.contains(image)) {
      return true;
    }
    running.add(image);
    try {
      executor.execute(() -> pull(image, pull));
    } catch (RejectedExecutionException shutDown) {
      running.remove(image);
      return false;
    }
    LOG.infof("system-runner pulls %s off the convergence", image);
    return true;
  }

  private void pull(String image, Supplier<Optional<String>> pull) {
    Optional<String> outcome;
    try {
      outcome = pull.get();
    } catch (RuntimeException e) {
      outcome = Optional.of(String.valueOf(e.getMessage()));
    }
    synchronized (this) {
      running.remove(image);
      if (outcome.isPresent()) {
        failed.put(image, new Failure(outcome.get()));
      } else {
        pulled.add(image);
      }
    }
    if (outcome.isPresent()) {
      LOG.warnf("system-runner could not pull %s: %s", image, outcome.get());
    } else {
      LOG.infof("system-runner pulled %s", image);
    }
    try {
      settled.run();
    } catch (RuntimeException e) {
      LOG.warnf("system-runner could not converge again after pulling %s: %s", image, e.getMessage());
    }
  }

  private static String stopping() {
    return "the runner is stopping: nothing more is pulled";
  }
}
