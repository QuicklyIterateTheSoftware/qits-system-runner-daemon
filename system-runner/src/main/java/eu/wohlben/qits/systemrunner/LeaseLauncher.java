package eu.wohlben.qits.systemrunner;

import eu.wohlben.qits.containers.driver.ContainersDriver;
import eu.wohlben.qits.containers.driver.spec.ContainerSpec;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.RunnerSession;
import eu.wohlben.qits.systemrunner.protocol.Adopt;
import eu.wohlben.qits.systemrunner.protocol.DesiredState;
import eu.wohlben.qits.systemrunner.protocol.LeaseEntry;
import eu.wohlben.qits.systemrunner.protocol.LeaseState;
import eu.wohlben.qits.systemrunner.protocol.SlotImages;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * One lease of an {@code estate}, converged on the node through qits-containers-driver under the
 * runner's label namespace — qits-projects-desk-runner-daemon's {@code DeskLauncher} ladder, with
 * the slot type's {@link SlotPolicy} in place of the desk's fixed one:
 *
 * <ul>
 *   <li><b>RUNNING</b>, refused by its policy or its {@code adopt} ({@link SlotPolicy#refusal},
 *       {@link LegacyAdoption#refusal}) → {@code FAILED}, the node untouched;
 *   <li><b>RUNNING</b>, no container → the image pulled when absent (under the runner's own login),
 *       every external node volume asked after, the adopted container stopped and removed and the
 *       adopted volume asked after — or the work volume and the runner's own node volumes made
 *       labelled — then {@code run};
 *   <li><b>RUNNING</b>, a container whose {@code spec-hash} and {@code mounts} labels are the
 *       entry's → nothing: {@code RUNNING}, {@code STARTING} while docker has it {@code created},
 *       or {@code EXITED} with its exit code — an exited lease is the owner's to end, never the
 *       runner's to restart (its token may already be gone);
 *   <li><b>RUNNING</b>, a container of another hash → {@code stop}, {@code rm} the container —
 *       <b>never its volume</b>, which holds the work — then {@code run} the new spec;
 *   <li><b>ENDED</b> → {@code stop}, {@code rm}; the work volume stays until a purge names it.
 * </ul>
 *
 * <p><b>A CI lease</b> starts the target's {@link BuildPlane} first (on the first one, and
 * re-checked on every later one), makes the CI caches labelled, runs on {@code bridge} with {@code
 * BUILDKIT_HOST} injected, and is then joined to the build network with a {@code docker network
 * connect}: the driver's {@code ContainerSpec} carries one network (qits-1302's {@code
 * extraNetworks} was never built). A connect that fails removes the container again and fails the
 * lease, so a CI container never runs without its builder. It mounts no external node volume, so
 * none is asked after.
 *
 * <p>The hash is the server's, carried on the frame and written to the label as is: the runner
 * never computes one, so the two can never disagree about a canonical form.
 *
 * <p>The spec's environment carries the lease's own token and is never logged, here or anywhere
 * the runner writes.
 */
final class LeaseLauncher {

  private static final Logger LOG = Logger.getLogger(LeaseLauncher.class);

  /** What a convergence left. */
  record Outcome(LeaseState state, Integer exitCode, String detail) {

    static Outcome of(LeaseState state) {
      return new Outcome(state, null, null);
    }

    static Outcome exited(int exitCode) {
      return new Outcome(LeaseState.EXITED, exitCode, null);
    }

    static Outcome failed(String detail) {
      return new Outcome(LeaseState.FAILED, null, detail);
    }

    boolean running() {
      return state == LeaseState.RUNNING;
    }

    boolean failed() {
      return state == LeaseState.FAILED;
    }
  }

  private final RunnerIdentity identity;
  private final RunnerDocker docker;
  private final TargetNames names;
  private final TargetVolumes volumes;
  private final LeaseContainers containers;
  private final Supplier<BuildPlane> buildPlane;

  LeaseLauncher(
      RunnerIdentity identity,
      RunnerDocker docker,
      TargetNames names,
      TargetVolumes volumes,
      LeaseContainers containers,
      Supplier<BuildPlane> buildPlane) {
    this.identity = identity;
    this.docker = docker;
    this.names = names;
    this.volumes = volumes;
    this.containers = containers;
    this.buildPlane = buildPlane;
  }

  /**
   * The volume a lease's {@code /workspace} is: the adopted one when it takes one over, else its
   * derived work volume, else none (a type without one).
   */
  String workVolumeOf(LeaseEntry entry) {
    if (entry.adopt() != null) {
      return entry.adopt().volume();
    }
    SlotPolicy policy = SlotPolicy.of(entry.slotType());
    return policy.workVolume ? volumes.work(entry.workVolume()) : null;
  }

  /** Bring the node to the entry's desired state. Never throws: a failure is the outcome's. */
  Outcome converge(LeaseEntry entry, RunnerSession session, SlotImages images) {
    if (entry.desired() == DesiredState.ENDED) {
      return end(entry.leaseId(), session);
    }
    String leaseId = entry.leaseId();
    SlotPolicy policy = SlotPolicy.of(entry.slotType());
    Optional<String> refused =
        policy.refusal(entry, images).or(() -> LegacyAdoption.refusal(entry));
    if (refused.isPresent()) {
      return failed(leaseId, refused.get());
    }
    String name;
    String workVolume;
    Optional<LeaseContainers.Seen> seen;
    try {
      name = names.container(leaseId);
      workVolume = workVolumeOf(entry);
      seen = containers.inspect(name);
    } catch (RuntimeException e) {
      return failed(leaseId, e.getMessage());
    }
    String mounts = volumes.mountsHash(policy, workVolume);
    if (seen.isPresent()
        && entry.specHash().equals(seen.get().specHash())
        && mounts.equals(seen.get().mounts())) {
      LeaseContainers.Seen s = seen.get();
      if (s.running()) {
        return Outcome.of(LeaseState.RUNNING);
      }
      return s.starting() ? Outcome.of(LeaseState.STARTING) : Outcome.exited(s.exitCode());
    }
    // An external node volume is the operator's: asked after, never made — a -v naming one that is
    // not there would create it empty, unlabelled, and the lease would run with no login. Asked
    // before an old container goes, so a missing one never costs a lease that still runs.
    Optional<String> missing = Optional.empty();
    try {
      if (policy.agentVolumes()) {
        missing = volumes.missing(docker);
      }
    } catch (RuntimeException e) {
      return failed(leaseId, e.getMessage());
    }
    if (missing.isPresent()) {
      return failed(leaseId, missing.get());
    }
    ContainersDriver driver = session.driver();
    Duration deadline = docker.deadline();
    if (seen.isPresent()) {
      // Another spec: the container goes, its volume stays — the work lives there, not here. A
      // graceful stop first, because rm -f is a SIGKILL.
      Optional<String> gone = stopAndRemove(driver, name, deadline);
      if (gone.isPresent()) {
        return failed(leaseId, gone.get());
      }
      LOG.infof(
          "system-runner recreates lease %s: its %s changed",
          leaseId, entry.specHash().equals(seen.get().specHash()) ? "volumes" : "spec");
    } else if (entry.adopt() != null) {
      Optional<String> adopted = takeOver(leaseId, entry.adopt(), driver, deadline);
      if (adopted.isPresent()) {
        return failed(leaseId, adopted.get());
      }
    }
    return create(entry, policy, name, workVolume, mounts, session);
  }

  /** Stop and remove the lease's container, if there is one: the lease is over. */
  Outcome end(String leaseId, RunnerSession session) {
    String name;
    try {
      name = names.container(leaseId);
    } catch (IllegalArgumentException notAName) {
      // No container of this runner can carry a name it cannot derive: nothing is there to end.
      return Outcome.of(LeaseState.ENDED);
    }
    try {
      if (containers.inspect(name).isEmpty()) {
        return Outcome.of(LeaseState.ENDED);
      }
    } catch (RuntimeException e) {
      return failed(leaseId, e.getMessage());
    }
    Optional<String> gone = stopAndRemove(session.driver(), name, docker.deadline());
    if (gone.isPresent()) {
      return failed(leaseId, gone.get());
    }
    return Outcome.of(LeaseState.ENDED);
  }

  /**
   * The retired runner's container stopped and removed when it is there — its name passed {@link
   * LegacyAdoption} — and its volume asked after: an adopted volume is never created, so one that is
   * not there fails the lease rather than letting {@code -v} make it empty.
   */
  private Optional<String> takeOver(
      String leaseId, Adopt adopt, ContainersDriver driver, Duration deadline) {
    try {
      if (containers.exists(adopt.container())) {
        Optional<String> gone = stopAndRemove(driver, adopt.container(), deadline);
        if (gone.isPresent()) {
          return gone;
        }
        LOG.infof(
            "system-runner removed the retired runner's %s for lease %s", adopt.container(), leaseId);
      }
      return volumes.adoptedMissing(docker, adopt.volume());
    } catch (RuntimeException e) {
      return Optional.of(e.getMessage());
    }
  }

  private Outcome create(
      LeaseEntry entry,
      SlotPolicy policy,
      String name,
      String workVolume,
      String mounts,
      RunnerSession session) {
    String leaseId = entry.leaseId();
    ContainersDriver driver = session.driver();
    Duration deadline = docker.deadline();
    BuildPlane plane = null;
    SlotPolicy.Build build = null;
    if (policy.buildNetwork) {
      plane = buildPlane.get();
      build = new SlotPolicy.Build(plane.names().network(), plane.names().address());
    }
    ContainerSpec container;
    try {
      container = policy.containerSpec(name, entry.spec(), volumes.mounts(workVolume), build);
    } catch (IllegalArgumentException | IllegalStateException refused) {
      return failed(leaseId, "the lease spec was refused: " + refused.getMessage());
    }
    String image = entry.spec().image();
    Optional<String> notHere = NodeImages.ensure(docker, session.registry()::pull, image);
    if (notHere.isPresent()) {
      return failed(leaseId, "could not pull " + image + ": " + brief(notHere.get()));
    }
    if (policy.workVolume && entry.adopt() == null) {
      ContainersDriver.OpResult work = volumes.ensureWork(driver, entry.workVolume(), deadline);
      if (!work.ok()) {
        return failed(
            leaseId,
            "docker could not create the volume " + workVolume + ": " + brief(work.detail()));
      }
    }
    if (plane != null) {
      Optional<String> notUp = plane.ensure();
      if (notUp.isPresent()) {
        return failed(leaseId, "the build plane is not up: " + brief(notUp.get()));
      }
    }
    List<String> nodeVolumes = policy.agentVolumes() ? volumes.owned() : volumes.ciCaches();
    for (String node : nodeVolumes) {
      ContainersDriver.OpResult made = volumes.ensureNode(driver, node, deadline);
      if (!made.ok()) {
        return failed(
            leaseId, "docker could not create the volume " + node + ": " + brief(made.detail()));
      }
    }
    Map<String, String> labels = new LinkedHashMap<>(session.own().labels(leaseId));
    labels.put(LeaseContainers.slotTypeLabel(identity), entry.slotType().name());
    labels.put(LeaseContainers.specHashLabel(identity), entry.specHash());
    labels.put(LeaseContainers.mountsLabel(identity), mounts);
    ContainersDriver.Started started =
        driver.run(container, name, labels, policy.lifecycle(), deadline);
    if (!started.started()) {
      return failed(leaseId, "docker could not run " + name + ": " + brief(started.detail()));
    }
    if (plane != null) {
      Optional<String> joined = plane.connect(name);
      if (joined.isPresent()) {
        stopAndRemove(driver, name, deadline);
        return failed(leaseId, brief(joined.get()));
      }
    }
    LOG.infof("system-runner runs %s lease %s as %s", entry.slotType(), leaseId, name);
    return Outcome.of(LeaseState.RUNNING);
  }

  /** A graceful stop, then the removal; either already gone counts as gone. Empty on success. */
  private static Optional<String> stopAndRemove(
      ContainersDriver driver, String name, Duration deadline) {
    ContainersDriver.OpResult stopped = driver.stop(name, deadline);
    if (!stopped.ok() && !TargetVolumes.absent(stopped.detail())) {
      LOG.warnf("system-runner could not stop %s before removing it: %s", name, stopped.detail());
    }
    ContainersDriver.OpResult gone = driver.remove(name, deadline);
    if (!gone.ok() && !TargetVolumes.absent(gone.detail())) {
      return Optional.of("docker could not remove " + name + ": " + brief(gone.detail()));
    }
    return Optional.empty();
  }

  private static Outcome failed(String leaseId, String detail) {
    LOG.warnf("system-runner could not converge lease %s: %s", leaseId, detail);
    return Outcome.failed(detail);
  }

  /** The tail of docker's words: the last 400 characters, where its error is. */
  static String brief(String text) {
    String s = text == null ? "" : text.strip();
    return s.length() > 400 ? "…" + s.substring(s.length() - 400) : s;
  }
}
