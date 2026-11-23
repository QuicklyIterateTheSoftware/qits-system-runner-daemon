package eu.wohlben.qits.systemrunner;

import eu.wohlben.qits.containers.driver.ContainersDriver;
import eu.wohlben.qits.containers.driver.docker.DockerArgv;
import eu.wohlben.qits.containers.driver.spec.ContainerSpec;
import eu.wohlben.qits.containers.driver.spec.LabelNamespace;
import eu.wohlben.qits.containers.driver.spec.LifecyclePolicy;
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
 *       labelled — then, for a WORKSPACE, DESK or AGENT lease, Claude Code's first-run keys made
 *       sure of on the credential volume ({@link ClaudeFirstRun}), then {@code create}, each network join and
 *       {@code start} ({@link #launch});
 *   <li><b>RUNNING</b>, a container whose {@code spec-hash}, {@code mounts} and {@code generation}
 *       labels are the entry's — an absent generation label is {@code 0}, an entry's absent
 *       generation too, so a container from before qits-2091 matches — → nothing: {@code
 *       RUNNING}, {@code STARTING} while docker has it {@code created}, or {@code EXITED} with its
 *       exit code — an exited lease is the owner's to end, never the runner's to restart (its
 *       token may already be gone);
 *   <li><b>RUNNING</b>, a container of another hash, other volumes or another generation → {@code
 *       stop}, {@code rm} the container — <b>never its volume</b>, which holds the work — then
 *       create and start the new spec, asking {@link PrivateEdge} and {@link NodeTunnel} again. Another
 *       generation is the owner's refresh (qits-2091): nothing of the spec changed, the lease is
 *       recreated to pick up the resolvers the node offers now;
 *   <li><b>ENDED</b> → {@code stop}, {@code rm}; the work volume stays until a purge names it.
 * </ul>
 *
 * <p><b>A WORKSPACE, DESK or AGENT lease runs on the lease network</b> ({@link LeaseNetwork},
 * qits-2269), made sure of before each of their {@code docker create}s and named as the create's
 * {@code --network} — one that cannot be made fails the lease with docker's words, never falling
 * back to {@code bridge}; <b>only a CI lease is on {@code bridge}</b>. Where a preferred resolver
 * edge is visible on the node's daemon (qits-2062, qits-2304), a lease is also on {@code
 * qits-private} with that edge's VIP as its first resolver ({@link PrivateEdge}): asked just before
 * each create, and joined ({@code ContainerSpec.extraNetworks}, {@code --dns}) after the {@code
 * docker create} and before the {@code docker start}, so no process runs before the join
 * (qits-2270). A join that fails removes the container and fails the lease. Off the platform host
 * there is no overlay and the lease runs on its create's network with the node's resolvers — unless
 * the node runs the node tunnel: where the tunnel's DNS at {@code 100.96.0.1} answers, the lease
 * stays on that network alone and gets that address as its first resolver, followed by the runner's
 * own ({@link NodeTunnel}, qits-2068), so private names resolve through the tunnel — on the lease
 * network through docker's embedded DNS, which asks them in that order. Nothing of either is
 * hashed, still: the runner does not recreate a running lease when the edge arrives or moves, or
 * the tunnel comes or goes. Each lease's resolvers are reported instead, with whether they are
 * stale against the node's choice now ({@link NodeResolvers}), and refreshed only by the owner
 * through the lease's generation (qits-2091).
 *
 * <p><b>A CI lease</b> starts the target's {@link BuildPlane} first (on the first one, and
 * re-checked on every later one), makes the CI caches labelled, runs with {@code BUILDKIT_HOST}
 * injected, and is joined to the build network with a {@code docker network connect} of the
 * runner's own ({@link BuildPlane#connect}) — the build network is the runner's, created and
 * checked by it, so its membership stays beside it. The join comes after the {@code docker create}
 * and before the {@code docker start} (qits-2270): on a tunnel node the lease's {@code bridge} alone
 * would give it the tunnel's resolver and a public one side by side, and musl in the ci-slot image
 * asks both at once, so a lease started before the join could resolve a private app to the public
 * edge. Joined first, it only ever sees docker's embedded DNS, which asks them in order. A connect
 * that fails removes the container again and fails the lease, so a CI container never runs without
 * its builder. It mounts no
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
  private final LeaseNetwork leaseNetwork;
  private final ClaudeFirstRun firstRun;
  private final Supplier<String> probeImage;

  /**
   * @param leaseNetwork the network WORKSPACE, DESK and AGENT leases run on (qits-2269)
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
      LeaseNetwork leaseNetwork,
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
    this.leaseNetwork = leaseNetwork;
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
        && mounts.equals(seen.get().mounts())
        && entry.generation() == seen.get().generation()) {
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
      LOG.infof("system-runner recreates lease %s: %s", leaseId, why(entry, seen.get(), mounts));
    } else if (entry.adopt() != null) {
      Optional<String> adopted = takeOver(leaseId, entry.adopt(), driver, deadline);
      if (adopted.isPresent()) {
        return failed(leaseId, adopted.get());
      }
    }
    return create(entry, policy, name, workVolume, mounts, session);
  }

  /** Why a lease's container is recreated, for the log line: the spec first, then its volumes. */
  private static String why(LeaseEntry entry, LeaseContainers.Seen seen, String mounts) {
    if (!entry.specHash().equals(seen.specHash())) {
      return "its spec changed";
    }
    if (!mounts.equals(seen.mounts())) {
      return "its volumes changed";
    }
    return "refreshed (generation " + entry.generation() + ")";
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
      container =
          policy.containerSpec(
              name, entry.spec(), volumes.mounts(workVolume), build, leaseNetwork.name());
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
    if (policy.onLeaseNetwork()) {
      // Made sure of at every create, so one removed by hand is made again; no bridge fallback,
      // which would bring back the resolv.conf the lease network exists to avoid (qits-2269).
      Optional<String> noNetwork = leaseNetwork.ensure();
      if (noNetwork.isPresent()) {
        return failed(leaseId, brief(noNetwork.get()));
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
    if (entry.generation() > 0) {
      // Only above 0: a lease the owner never refreshed keeps the labels it had before qits-2091.
      labels.put(LeaseContainers.generationLabel(identity), Long.toString(entry.generation()));
    }
    // Both asked at every create and still never hashed: the labels above are the server's spec,
    // the runner's mounts and the owner's generation, so the runner does not recreate a lease when
    // the edge's VIP moves (qits-2062) or the node tunnel comes or goes (qits-2068) — it reports a
    // lease whose resolvers went stale, and the owner refreshes it through the generation
    // (qits-2091). The tunnel only where the edge added no resolvers.
    ContainerSpec run = nodeTunnel.apply(privateEdge.apply(container, name), name);
    boolean onEdge = run.extraNetworks().contains(PrivateEdge.NETWORK);
    Optional<String> notUp = launch(run, name, labels, policy.lifecycle(), plane, driver, deadline);
    if (notUp.isPresent()) {
      return failed(leaseId, notUp.get());
    }
    LOG.infof(
        "system-runner runs %s lease %s as %s on %s%s",
        entry.slotType(),
        leaseId,
        name,
        run.network(),
        onEdge
            ? " and " + PrivateEdge.NETWORK + ", resolvers " + run.dns()
            : NodeTunnel.on(run) ? " via the node tunnel, resolvers " + run.dns() : "");
    return Outcome.of(LeaseState.RUNNING);
  }

  /**
   * Make the lease's container, join it to each further network, and only then start it
   * (qits-2270). Empty on success, else the lease's failure detail.
   *
   * <p>The order is the fix. A container on {@code bridge} alone has the {@code --dns} servers in
   * its {@code /etc/resolv.conf} as they are. Once it is on a second, user-defined network, docker
   * writes {@code 127.0.0.11} there: its embedded DNS, which asks the servers in order. A container
   * started before the join runs with the raw list for a moment. musl (the ci-slot image) asks all
   * servers at once and takes the first answer, so a public upstream could answer a private name
   * with the public edge. Joined before the start, the process only ever sees the embedded DNS.
   *
   * <p>So no {@code docker run}: the driver's run starts the container before its {@code
   * extraNetworks} join. The create argv is the driver's own ({@link DockerArgv#create}: its run
   * argv, flag for flag, as a {@code docker create}), so the container's shape stays the driver's.
   * The joins are {@code qits-private} first, then the build network. Any failure removes the
   * container, which never started.
   */
  private Optional<String> launch(
      ContainerSpec run,
      String name,
      Map<String, String> labels,
      LifecyclePolicy lifecycle,
      BuildPlane plane,
      ContainersDriver driver,
      Duration deadline) {
    List<String> create;
    try {
      create =
          DockerArgv.create(
              docker.binary(),
              new LabelNamespace(identity.driverNamespace()),
              name,
              run,
              labels,
              lifecycle);
    } catch (RuntimeException refused) {
      return Optional.of("docker could not run " + name + ": " + refused.getMessage());
    }
    RunnerDocker.Result created = docker.run(create, deadline);
    if (!created.ok()) {
      // A create that failed half way can leave the container behind.
      discard(driver, name, deadline);
      return Optional.of("docker could not run " + name + ": " + brief(created.detail()));
    }
    for (String network : run.extraNetworks()) {
      RunnerDocker.Result joined =
          docker.run(DockerArgv.networkConnect(docker.binary(), network, name), deadline);
      if (!joined.ok()) {
        discard(driver, name, deadline);
        return Optional.of(
            "docker could not run " + name + " and join it to " + network + ": "
                + brief(joined.detail()));
      }
    }
    if (plane != null) {
      Optional<String> joined = plane.connect(name);
      if (joined.isPresent()) {
        discard(driver, name, deadline);
        return Optional.of(brief(joined.get()));
      }
    }
    ContainersDriver.OpResult started = driver.start(name, deadline);
    if (!started.ok()) {
      discard(driver, name, deadline);
      return Optional.of("docker could not start " + name + ": " + brief(started.detail()));
    }
    return Optional.empty();
  }

  /** Remove a container that never started; one already gone counts as gone. */
  private static void discard(ContainersDriver driver, String name, Duration deadline) {
    ContainersDriver.OpResult gone = driver.remove(name, deadline);
    if (!gone.ok() && !TargetVolumes.absent(gone.detail())) {
      LOG.warnf("system-runner could not remove %s after a failed start: %s", name, gone.detail());
    }
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
