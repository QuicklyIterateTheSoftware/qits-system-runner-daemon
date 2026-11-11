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
 *   <li><b>RUNNING</b>, no container, its image (or a CI lease's builder image) not on the node →
 *       {@code STARTING}, {@code pulling <image> since <instant>}: the pull is started on {@link
 *       ImagePulls}, off the converge lock, and the convergence returns at once; the pull's end
 *       converges the estate again. A failed pull is {@code FAILED} with docker's words;
 *   <li><b>RUNNING</b>, no container, the images here → every external node volume asked after, the adopted container stopped and removed and the
 *       adopted volume asked after — or the work volume and the runner's own node volumes made
 *       labelled — then, for a WORKSPACE or DESK lease, Claude Code's first-run keys made sure of
 *       on the credential volume ({@link ClaudeFirstRun}), then {@code run};
 *   <li><b>RUNNING</b>, a container whose {@code spec-hash} and {@code mounts} labels are the
 *       entry's → nothing: {@code RUNNING}, {@code STARTING} while docker has it {@code created},
 *       or {@code EXITED} with its exit code — an exited lease is the owner's to end, never the
 *       runner's to restart (its token may already be gone);
 *   <li><b>RUNNING</b>, a container of another hash → {@code stop}, {@code rm} the container —
 *       <b>never its volume</b>, which holds the work — then {@code run} the new spec;
 *   <li><b>ENDED</b> → {@code stop}, {@code rm}; the work volume stays until a purge names it.
 * </ul>
 *
 * <p><b>Every lease runs on {@code bridge}</b>, and — where the private edge is visible on the
 * node's daemon — also on {@code qits-private} with the edge's VIP as its first resolver ({@link
 * PrivateEdge}, qits-2062): asked just before each {@code run}, joined by the driver right after
 * it ({@code ContainerSpec.extraNetworks}, {@code --dns}). A join that fails removes the container
 * and fails the lease. Off the platform host there is no overlay and the lease runs on {@code
 * bridge} with the node's resolvers — unless the node runs the node tunnel: where the tunnel's
 * DNS at {@code 100.96.0.1} answers, the lease stays on {@code bridge} alone and gets that address
 * as its first resolver, followed by the runner's own ({@link NodeTunnel}, qits-2068), so private
 * names resolve through the tunnel. Nothing of either is hashed: a running lease is not recreated
 * when the edge arrives or moves, or the tunnel comes or goes.
 *
 * <p><b>A CI lease</b> starts the target's {@link BuildPlane} first (on the first one, and
 * re-checked on every later one), makes the CI caches labelled, runs with {@code BUILDKIT_HOST}
 * injected, and is then joined to the build network with a {@code docker network connect} of the
 * runner's own ({@link BuildPlane#connect}) — the build network is the runner's, created and
 * checked by it, so its membership stays beside it. A connect that fails removes the container
 * again and fails the lease, so a CI container never runs without its builder. It mounts no
 * external node volume, so none is asked after.
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

    /** {@code STARTING}, saying what it waits on: {@code pulling <image> since <instant>}. */
    static Outcome starting(String detail) {
      return new Outcome(LeaseState.STARTING, null, detail);
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
  private final ImagePulls pulls;
  private final PrivateEdge privateEdge;
  private final NodeTunnel nodeTunnel;
  private final ClaudeFirstRun firstRun;
  private final Supplier<String> probeImage;

  /**
   * @param firstRun the login probe's: the same volume, container name and deadline, one run at a
   *     time with the probe's own
   * @param probeImage the latest {@code Ack.probeImage}, null when the host named none
   */
  LeaseLauncher(
      RunnerIdentity identity,
      RunnerDocker docker,
      TargetNames names,
      TargetVolumes volumes,
      LeaseContainers containers,
      Supplier<BuildPlane> buildPlane,
      ImagePulls pulls,
      PrivateEdge privateEdge,
      NodeTunnel nodeTunnel,
      ClaudeFirstRun firstRun,
      Supplier<String> probeImage) {
    this.identity = identity;
    this.docker = docker;
    this.names = names;
    this.volumes = volumes;
    this.containers = containers;
    this.buildPlane = buildPlane;
    this.pulls = pulls;
    this.privateEdge = privateEdge;
    this.nodeTunnel = nodeTunnel;
    this.firstRun = firstRun;
    this.probeImage = probeImage;
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

  /**
   * Empty when the lease's image — and a CI lease's builder image — are on the node; else {@code
   * STARTING} while a pull runs — {@code pulling <image> since <instant>}, the lease image's pull
   * before the builder's — or {@code FAILED} with docker's words. Never waits on a pull: both are
   * started together, on {@link ImagePulls}, and the convergence moves on.
   */
  private Optional<Outcome> awaitingImages(
      String leaseId, String image, BuildPlane plane, RunnerSession session) {
    ImagePulls.Status lease =
        NodeImages.present(docker, image)
            ? ImagePulls.Status.READY
            : pulls.status(image, () -> session.registry().pull(image));
    ImagePulls.Status builder =
        plane == null || plane.imagePresent()
            ? ImagePulls.Status.READY
            : pulls.status(plane.image(), plane::pullImage);
    if (lease.failed()) {
      return Optional.of(failed(leaseId, "could not pull " + image + ": " + brief(lease.detail())));
    }
    if (builder.failed()) {
      return Optional.of(failed(leaseId, "the build plane is not up: " + brief(builder.detail())));
    }
    if (lease.pulling() || builder.pulling()) {
      LOG.debugf("system-runner's lease %s is STARTING: its image is being pulled", leaseId);
      return Optional.of(Outcome.starting((lease.pulling() ? lease : builder).detail()));
    }
    return Optional.empty();
  }

  /**
   * Start pulling a lease's image ahead of its convergence, when it is not on the node: the
   * pre-pull at the {@code Ack}. Never waits.
   */
  void prefetch(String image, RunnerSession session) {
    if (image != null && !image.isBlank() && !NodeImages.present(docker, image)) {
      pulls.prefetch(image, () -> session.registry().pull(image));
    }
  }

  /**
   * Keep {@code image} warm: pull it in the background when it is not on the node, nobody waiting
   * on it ({@link ImagePulls#warm}). Never waits and never fails a lease.
   */
  void warm(String image, RunnerSession session) {
    if (!NodeImages.present(docker, image)) {
      pulls.warm(image, () -> session.registry().pull(image));
    }
  }

  /** Start pulling the builder image when it is not on the node: the pre-pull at the {@code Ack}. */
  void prefetchBuilder() {
    BuildPlane plane = buildPlane.get();
    if (!plane.imagePresent()) {
      pulls.prefetch(plane.image(), plane::pullImage);
    }
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
      build =
          new SlotPolicy.Build(
              plane.names().network(), plane.names().address(), plane.concurrentBuilds());
    }
    ContainerSpec container;
    try {
      container = policy.containerSpec(name, entry.spec(), volumes.mounts(workVolume), build);
    } catch (IllegalArgumentException | IllegalStateException refused) {
      return failed(leaseId, "the lease spec was refused: " + refused.getMessage());
    }
    Optional<Outcome> notYet = awaitingImages(leaseId, entry.spec().image(), plane, session);
    if (notYet.isPresent()) {
      return notYet.get();
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
    if (policy.agentVolumes()) {
      seedFirstRunKeys(leaseId, entry.spec().image(), driver);
    }
    Map<String, String> labels = new LinkedHashMap<>(session.own().labels(leaseId));
    labels.put(LeaseContainers.slotTypeLabel(identity), entry.slotType().name());
    labels.put(LeaseContainers.specHashLabel(identity), entry.specHash());
    labels.put(LeaseContainers.mountsLabel(identity), mounts);
    // Both asked at every create and never hashed: the labels above are the server's spec and the
    // runner's mounts, so a lease is not recreated when the edge's VIP moves (qits-2062) or the
    // node tunnel comes or goes (qits-2068). The tunnel only where the edge added no resolvers.
    ContainerSpec run = nodeTunnel.apply(privateEdge.apply(container, name), name);
    boolean onEdge = run.extraNetworks().contains(PrivateEdge.NETWORK);
    ContainersDriver.Started started = driver.run(run, name, labels, policy.lifecycle(), deadline);
    if (!started.started()) {
      // The driver joins qits-private after the run and removes the container when that fails,
      // answering the join's words as the run's.
      return failed(
          leaseId,
          "docker could not run "
              + name
              + (onEdge ? " and join it to " + PrivateEdge.NETWORK : "")
              + ": "
              + brief(started.detail()));
    }
    if (plane != null) {
      Optional<String> joined = plane.connect(name);
      if (joined.isPresent()) {
        stopAndRemove(driver, name, deadline);
        return failed(leaseId, brief(joined.get()));
      }
    }
    LOG.infof(
        "system-runner runs %s lease %s as %s%s",
        entry.slotType(),
        leaseId,
        name,
        onEdge
            ? " on " + PrivateEdge.NETWORK + ", resolvers " + run.dns()
            : NodeTunnel.on(run) ? " via the node tunnel, resolvers " + run.dns() : "");
    return Outcome.of(LeaseState.RUNNING);
  }

  /**
   * Claude Code's first-run keys on the credential volume an agent lease is about to mount
   * (qits-2088): they are not login state, so a volume nobody signed in on yet — a new target, or
   * one whose probe has not run — gets them before the lease's CLI could stop at its onboarding
   * screen. {@link ClaudeFirstRun#ensure} checks first, so a seeded volume costs one short {@code
   * docker run}, under the probe's deadline. Run in the probe image when it is on the node — never
   * pulled here, under the converge lock — else in the lease's own, which is. A merge that fails
   * never fails the lease: it is logged, and the next probe tries again.
   */
  private void seedFirstRunKeys(String leaseId, String leaseImage, ContainersDriver driver) {
    try {
      String probe = probeImage.get();
      String image =
          probe != null && !probe.isBlank() && NodeImages.present(docker, probe)
              ? probe
              : leaseImage;
      RunnerDocker.Result keys = firstRun.ensure(driver, image);
      if (!keys.ok()) {
        LOG.warnf(
            "system-runner could not write claude's first-run keys before lease %s (exit %d): %s",
            leaseId, keys.exitCode(), brief(keys.detail()));
      }
    } catch (RuntimeException e) {
      LOG.warnf(
          "system-runner could not write claude's first-run keys before lease %s: %s",
          leaseId, e.getMessage());
    }
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
