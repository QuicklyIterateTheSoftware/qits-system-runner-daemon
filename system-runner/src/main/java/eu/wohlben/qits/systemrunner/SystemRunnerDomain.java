package eu.wohlben.qits.systemrunner;

import eu.wohlben.qits.containers.driver.ContainersDriver;
import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.runner.protocol.DomainCodec;
import eu.wohlben.qits.runner.protocol.RunnerCapabilities;
import eu.wohlben.qits.runner.toolkit.HostFacts;
import eu.wohlben.qits.runner.toolkit.HousekeepingPass;
import eu.wohlben.qits.runner.toolkit.OwnResources;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerDomain;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.RunnerSession;
import eu.wohlben.qits.runner.toolkit.RunnerSettings;
import eu.wohlben.qits.runner.toolkit.TeardownItem;
import eu.wohlben.qits.runner.toolkit.health.HealthChecks;
import eu.wohlben.qits.runner.toolkit.health.HealthContext;
import eu.wohlben.qits.runner.toolkit.health.SessionFacts;
import eu.wohlben.qits.systemrunner.protocol.DesiredState;
import eu.wohlben.qits.systemrunner.protocol.Estate;
import eu.wohlben.qits.systemrunner.protocol.HealthCheck;
import eu.wohlben.qits.systemrunner.protocol.HealthChecked;
import eu.wohlben.qits.systemrunner.protocol.Inventory;
import eu.wohlben.qits.systemrunner.protocol.LeaseEntry;
import eu.wohlben.qits.systemrunner.protocol.LeaseReport;
import eu.wohlben.qits.systemrunner.protocol.LeaseState;
import eu.wohlben.qits.systemrunner.protocol.LoginPresence;
import eu.wohlben.qits.systemrunner.protocol.LoginState;
import eu.wohlben.qits.systemrunner.protocol.ProbeLogin;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerAck;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerCapabilities;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerCodec;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerMessage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.jboss.logging.Logger;

/**
 * <b>What the target runner is for</b>, as qits-runner-toolkit's {@link RunnerDomain}: the toolkit
 * owns registration, the bearer, the socket, the slot ledger, self-update, decommission, the
 * heartbeat and housekeeping; this class owns the target frames and the lease containers. Its shape
 * is qits-projects-desk-runner-daemon's {@code DeskRunnerDomain}: the server pushes an {@code
 * estate}, the runner answers with an {@code inventory}. There is no pull: qits-system allocates,
 * and never sends a {@code backlog}, so the ledger never sends a {@code reserve}.
 *
 * <p><b>The {@code estate} is the whole desired state</b>: every lease placed on this target, each
 * converged by {@link LeaseLauncher} under its slot type's {@link SlotPolicy}. A lease the runner
 * does not hold is started; a held lease that is missing is stopped and removed ({@link
 * OwnResources#reconcile}, never before an {@code estate} on the current connection), one that is
 * {@code ENDED} likewise; a {@code specHash} change recreates the container. A work volume is never
 * removed with its lease, only on an {@code estate.purges} entry ({@link #purge}).
 *
 * <p><b>A slot is a running lease</b>, counted by the toolkit's slot ledger and fed from docker, so
 * the {@code Hello}'s {@code heldLeases} names what a reconnecting runner still runs. Nothing here
 * decides placement: the server takes {@code capabilities.slots} as the target's total.
 *
 * <p><b>Rollover never drains.</b> {@link #quiescent()} is "no convergence in flight", never "holds
 * nothing": a lease container is node state that outlives the runner process, and its successor
 * adopts it by label at its session start.
 *
 * <p><b>The {@code inventory}</b> goes out at the {@code Ack}, around every convergence, and when
 * the periodic look sees a change; a listing docker did not answer is never sent — read as empty,
 * it would tell the server every lease is gone.
 */
public final class SystemRunnerDomain implements RunnerDomain<SystemRunnerMessage> {

  private static final Logger LOG = Logger.getLogger(SystemRunnerDomain.class);

  private static final SystemRunnerCodec CODEC = new SystemRunnerCodec();

  /**
   * The node's clocks: how often docker is looked at for a change to report, and the login probe's
   * — every five minutes while either login is not PRESENT, hourly otherwise.
   */
  record Timing(Duration lookEvery, Duration loginAbsentEvery, Duration loginPresentEvery) {

    static Timing defaults() {
      return new Timing(Duration.ofSeconds(15), Duration.ofMinutes(5), Duration.ofHours(1));
    }
  }

  private final RunnerIdentity identity = SystemRunnerIdentity.IDENTITY;
  private final RunnerDocker docker;
  private final String targetId;
  private final int slots;
  private final TargetNames names;
  private final TargetVolumes volumes;
  private final LeaseContainers containers;
  private final LeaseLauncher launcher;
  private final LoginProbe loginProbe;

  /** The build plane under the environment's settings alone: what every {@code Ack} merges over. */
  private final BuildPlane basePlane;

  /** The build plane with the latest {@code Ack}'s registry mirrors merged in. */
  private volatile BuildPlane buildPlane;

  private final HealthChecks healthChecks;
  private final Timing timing;
  private final Clock clock;

  /** The look tick and the login schedule; daemon threads, so they never hold the process. */
  private final ScheduledExecutorService timer;

  /** Estates under way or waiting: the rollover waits for zero, and only that. */
  private final AtomicInteger inFlight = new AtomicInteger();

  /** One convergence at a time: two frames never work on the node at once. */
  private final ReentrantLock converging = new ReentrantLock(true);

  /** The last convergence's failure per lease, for {@code inventory}'s FAILED; cleared by success. */
  private final Map<String, String> failures = new ConcurrentHashMap<>();

  /** The latest {@code estate}, kept across reconnects; null before the first. */
  private volatile Estate estate;

  /** This connection's {@code estate} arrived: only then is anything removed. */
  private volatile boolean estateThisSession;

  /** The latest {@code Ack}'s keys: the slot images and the probe image. */
  private volatile SystemRunnerAck ack = new SystemRunnerAck(null, null, null);

  /** The docker engine's id and host name, once docker said them. */
  private volatile String engineId;

  private volatile String engineHost;

  /** The last login probe's answer, for the health check's {@code login}; null before one. */
  private volatile LoginState lastLogin;

  /** The last {@code inventory} sent, so the look reports only a change. */
  private volatile Inventory lastInventory;

  /** When this connection's {@code Ack} came, and its slots: the health check's session facts. */
  private volatile Instant connectedSince;

  private volatile Integer ackSlots;

  /** Between this session's {@code Ack} and its loss. */
  private volatile boolean live;

  /** Set once this runner learns it was deleted: from then on it starts nothing. */
  private volatile boolean decommissioning;

  /** The handle the toolkit gave: the same across reconnects, null before the first session. */
  private volatile RunnerSession session;

  private final Object inventoryLock = new Object();

  private ScheduledFuture<?> tick;
  private ScheduledFuture<?> nextProbe;

  /** Built by {@code RunnerLauncher} from the parsed settings, as every embedder's domain is. */
  public SystemRunnerDomain(RunnerSettings settings) {
    this(settings, ExternalVolumes.NONE);
  }

  /** The same, with the external node volumes {@link Main} parsed beside the settings. */
  SystemRunnerDomain(RunnerSettings settings, ExternalVolumes external) {
    this(settings, external, BuildkitSettings.DEFAULTS);
  }

  /**
   * The same, with the external node volumes and the build plane's settings {@link Main} parsed.
   */
  SystemRunnerDomain(RunnerSettings settings, ExternalVolumes external, BuildkitSettings buildkit) {
    this(settings, external, buildkit, Timing.defaults(), Clock.systemUTC());
  }

  /** The same domain with external node volumes and the node's clocks a suite shrinks. */
  SystemRunnerDomain(
      RunnerSettings settings, ExternalVolumes external, Timing timing, Clock clock) {
    this(settings, external, BuildkitSettings.DEFAULTS, timing, clock);
  }

  SystemRunnerDomain(
      RunnerSettings settings,
      ExternalVolumes external,
      BuildkitSettings buildkit,
      Timing timing,
      Clock clock) {
    this.docker = RunnerDocker.of(identity, settings);
    this.targetId = settings.runnerId();
    this.slots = settings.slots();
    this.names = new TargetNames(identity, settings.runnerId());
    this.volumes = new TargetVolumes(identity, settings.runnerId(), external);
    this.containers = new LeaseContainers(identity, settings.runnerId(), docker);
    this.basePlane = new BuildPlane(identity, settings.runnerId(), docker, buildkit);
    this.buildPlane = basePlane;
    this.launcher =
        new LeaseLauncher(identity, docker, names, volumes, containers, () -> buildPlane);
    this.loginProbe =
        new LoginProbe(
            identity,
            settings.runnerId(),
            new RunnerDocker(identity, settings.dockerBinary(), LoginProbe.DEADLINE),
            volumes,
            clock);
    this.healthChecks = new HealthChecks(docker.deadline().multipliedBy(3));
    TargetHealthChecks.registerDefaults(
        healthChecks, identity, targetId, () -> lastLogin, () -> buildPlane);
    this.timing = timing;
    this.clock = clock;
    ScheduledThreadPoolExecutor pool =
        new ScheduledThreadPoolExecutor(
            2,
            r -> {
              Thread t = new Thread(r, "system-runner-node");
              t.setDaemon(true);
              return t;
            });
    pool.setRemoveOnCancelPolicy(true);
    this.timer = pool;
  }

  @Override
  public DomainCodec<SystemRunnerMessage> codec() {
    return CODEC;
  }

  /**
   * The base's three facts and this target's own: {@code slots} (the server's total), {@code
   * engineId} and {@code hostname} from {@code docker info} (asked until docker answers once), the
   * credential volume every agent lease mounts, and {@code buildkit: true}: every CI lease gets the
   * target's own builder ({@link BuildPlane}).
   */
  @Override
  public RunnerCapabilities capabilities(HostFacts host) {
    if (engineId == null) {
      readEngine();
    }
    return capabilities(host.docker(), host.arch(), host.os()).toWire();
  }

  SystemRunnerCapabilities capabilities(boolean dockerHere, String arch, String os) {
    return new SystemRunnerCapabilities(
        dockerHere, arch, os, slots, engineId, engineHost, volumes.credential(), true);
  }

  private void readEngine() {
    try {
      RunnerDocker.Result info =
          docker.run(List.of(docker.binary(), "info", "--format", "{{.ID}}|{{.Name}}"));
      if (!info.ok()) {
        LOG.warnf("system-runner could not read the docker engine's id: %s", info.detail());
        return;
      }
      String[] f = info.stdout().strip().split("\\|", -1);
      String id = f.length > 0 ? f[0].strip() : "";
      String name = f.length > 1 ? f[1].strip() : "";
      engineHost = name.isEmpty() ? null : name;
      engineId = id.isEmpty() ? null : id;
    } catch (RuntimeException e) {
      LOG.warnf("system-runner could not read the docker engine's id: %s", e.getMessage());
    }
  }

  /**
   * The running lease containers, read from docker, each reported to the slot ledger as held.
   * Nothing is removed here — the keep set is the server's {@code estate}, which arrives after the
   * {@code Ack}.
   */
  @Override
  public void onSessionStart(RunnerSession session, List<String> carried) {
    this.session = session;
    live = false;
    estateThisSession = false;
    holdRunning(session, null);
  }

  /**
   * Admitted: the {@code Ack}'s slot and probe images are taken, the runner's own credential
   * volume and caches are made labelled (before an operator's login command could make the
   * credential volume unlabelled) — an external one is the operator's and is never made — the
   * {@code inventory} goes out, the look ticks from here on, and the login is probed.
   */
  @Override
  public void onAck(Ack ack, RunnerSession session) {
    this.session = session;
    try {
      this.ack = SystemRunnerAck.of(ack);
    } catch (IllegalArgumentException malformed) {
      LOG.warnf("system-runner ignored the Ack's own keys: %s", malformed.getMessage());
    }
    // Merged over the environment's plane, never the previous merge: the host sends its whole map.
    // A changed map changes the stamp, so the next CI lease replaces the builder.
    buildPlane = basePlane.withRegistryMirrors(this.ack.registryMirrors());
    connectedSince = clock.instant();
    ackSlots = ack.slots();
    if (decommissioning) {
      return;
    }
    live = true;
    for (String node : volumes.owned()) {
      ContainersDriver.OpResult made = volumes.ensureNode(session.driver(), node, docker.deadline());
      if (!made.ok()) {
        LOG.warnf("system-runner could not create %s: %s", node, made.detail());
      }
    }
    sendInventory(session, true);
    startTick();
    scheduleProbe(Duration.ZERO);
  }

  /**
   * The host did not adopt these carried leases, so the ledger let their slots go — but a lease
   * container that still runs still holds one. Whether it is still this target's is the next
   * {@code estate}'s to say.
   */
  @Override
  public void onDropped(List<String> workIds, RunnerSession session) {
    holdRunning(session, Set.copyOf(workIds));
  }

  @Override
  public void onSessionLost() {
    live = false;
    estateThisSession = false;
  }

  @Override
  public void onMessage(SystemRunnerMessage message, RunnerSession session) {
    this.session = session;
    switch (message) {
      case Estate e -> onEstate(e, session);
      case ProbeLogin _ -> scheduleProbe(Duration.ZERO);
      case HealthCheck check -> onHealthCheck(check, session);
      default ->
          // inventory, loginState and healthChecked are runner → server; a host echoing one is
          // not a conversation this has.
          LOG.debugf("system-runner ignored a %s from its host", message.getClass().getSimpleName());
    }
  }

  @Override
  public boolean quiescent() {
    return inFlight.get() == 0;
  }

  /**
   * Stop every lease container, so nothing of this target runs on past its deletion; the toolkit's
   * decommission helper then removes every container and volume labelled {@code
   * qits.system.runner=<id>} — the leases', their work volumes, the credential volume and the
   * caches — the state volume and the runner's own container. An external node volume and an
   * adopted legacy volume carry neither the label nor the runner's name prefix, so the helper can
   * never reach them — they stay.
   */
  @Override
  public void onDecommission(RunnerSession session) {
    decommissioning = true;
    live = false;
    timer.shutdownNow();
    List<LeaseContainers.Seen> seen;
    try {
      seen = containers.list();
    } catch (RuntimeException e) {
      LOG.warnf("system-runner could not list its leases to stop them: %s", e.getMessage());
      return;
    }
    for (LeaseContainers.Seen s : seen) {
      if (!s.running()) {
        continue;
      }
      ContainersDriver.OpResult stopped = session.driver().stop(s.id(), docker.deadline());
      if (!stopped.ok()) {
        LOG.warnf("system-runner could not stop lease %s: %s", s.leaseId(), stopped.detail());
      }
    }
  }

  /**
   * The build plane's builder, network and state volume, in that order — named, because none of
   * them carries the owner label the helper removes by — then the first-run keys' container, which
   * carries none either. The CI caches do carry it, and go with the labelled volumes.
   */
  @Override
  public List<TeardownItem> teardown() {
    List<TeardownItem> items = new ArrayList<>(basePlane.names().teardown());
    items.add(new TeardownItem(TeardownItem.Type.CONTAINER, names.firstRun()));
    return List.copyOf(items);
  }

  /** The build plane now: the environment's settings with the latest {@code Ack}'s mirrors. */
  BuildPlane buildPlane() {
    return buildPlane;
  }

  /** The old lease images, after the toolkit's own passes. */
  @Override
  public List<HousekeepingPass> housekeeping() {
    return List.of(new LeaseImagesPass(docker, this::currentImages));
  }

  /** The images in use now: every spec image of the latest {@code estate}, and the probe image. */
  Set<String> currentImages() {
    Set<String> images = new LinkedHashSet<>();
    Estate e = estate;
    if (e != null) {
      for (LeaseEntry entry : e.leases()) {
        if (entry.spec() != null) {
          images.add(entry.spec().image());
        }
      }
    }
    String probe = ack.probeImage();
    if (probe != null && !probe.isBlank()) {
      images.add(probe);
    }
    return images;
  }

  /** Stop the node's timers; the process ending does the same. For a suite's teardown. */
  void close() {
    timer.shutdownNow();
    healthChecks.close();
  }

  // --- the frames ---------------------------------------------------------------------------------

  /**
   * The server's whole list. An {@code inventory} first, saying which leases are now STARTING; then
   * lease containers it does not list go — stopped, then removed — every listed lease is converged,
   * the purges are made, and the {@code inventory} says what is left.
   */
  private void onEstate(Estate e, RunnerSession session) {
    estate = e;
    if (decommissioning) {
      return;
    }
    inFlight.incrementAndGet();
    converging.lock();
    try {
      Set<String> listed = new LinkedHashSet<>();
      e.leases().forEach(entry -> listed.add(entry.leaseId()));
      estateThisSession = true;
      LOG.infof("system-runner's estate: %d lease(s), %d purge(s)", listed.size(), e.purges().size());
      failures.keySet().retainAll(listed);
      sendInventory(session, false);
      if (stopOutside(listed, session)) {
        session.own().reconcile(listed);
      }
      for (LeaseEntry entry : e.leases()) {
        converge(entry, session);
      }
      purge(e, session);
    } finally {
      converging.unlock();
      inFlight.decrementAndGet();
    }
    look(session, true);
  }

  /**
   * One entry converged, under {@link #converging}: its failure kept for the inventory, and the
   * slot held exactly while the lease runs.
   */
  private void converge(LeaseEntry entry, RunnerSession session) {
    String leaseId = entry.leaseId();
    LeaseLauncher.Outcome outcome = launcher.converge(entry, session, ack.slotImages());
    if (outcome.failed()) {
      failures.put(leaseId, outcome.detail());
    } else {
      failures.remove(leaseId);
    }
    boolean held = session.held().contains(leaseId);
    if (outcome.running() && !held) {
      session.taken(leaseId);
    } else if (!outcome.running() && held) {
      session.released(leaseId);
    }
  }

  /**
   * The estate's purges: each a work volume of this runner or a retired runner's ({@link
   * LegacyAdoption#purgeable}) — anything else is refused and logged — and never one a RUNNING
   * lease of this estate mounts. Already gone counts as gone; docker's own refusal to remove a
   * volume a container still mounts is the last belt.
   */
  private void purge(Estate e, RunnerSession session) {
    if (e.purges().isEmpty()) {
      return;
    }
    Set<String> mounted = new LinkedHashSet<>();
    for (LeaseEntry entry : e.leases()) {
      if (entry.desired() == DesiredState.RUNNING) {
        try {
          String work = launcher.workVolumeOf(entry);
          if (work != null) {
            mounted.add(work);
          }
        } catch (IllegalArgumentException unnameable) {
          // a lease whose work key names nothing mounts nothing
        }
      }
    }
    for (String volume : e.purges()) {
      if (!volumes.isWork(volume) && !LegacyAdoption.purgeable(volume)) {
        LOG.warnf("system-runner refused to purge %s: not a work volume it may remove", volume);
        continue;
      }
      if (mounted.contains(volume)) {
        LOG.warnf("system-runner kept %s: a running lease of the estate mounts it", volume);
        continue;
      }
      ContainersDriver.OpResult gone = session.driver().removeVolume(volume, docker.deadline());
      if (gone.ok()) {
        LOG.infof("system-runner purged %s", volume);
      } else if (!TargetVolumes.absent(gone.detail())) {
        LOG.warnf("system-runner could not purge %s: %s", volume, gone.detail());
      }
    }
  }

  /**
   * Stop every running lease container {@link OwnResources#reconcile} is about to remove — those
   * whose lease is not in {@code keep} — so the reconcile's {@code rm -f} finds them stopped rather
   * than SIGKILLing them.
   *
   * @return false when docker did not answer the listing — the reconcile then waits for the next
   *     {@code estate} rather than remove what nothing stopped
   */
  private boolean stopOutside(Set<String> keep, RunnerSession session) {
    List<LeaseContainers.Seen> seen;
    try {
      seen = containers.list();
    } catch (RuntimeException e) {
      LOG.warnf(
          "system-runner could not list its leases; reconciling nothing this time: %s",
          e.getMessage());
      return false;
    }
    for (LeaseContainers.Seen s : seen) {
      if (!s.running() || keep.contains(s.leaseId())) {
        continue;
      }
      ContainersDriver.OpResult stopped = session.driver().stop(s.id(), docker.deadline());
      if (!stopped.ok() && !TargetVolumes.absent(stopped.detail())) {
        LOG.warnf("system-runner could not stop lease %s: %s", s.leaseId(), stopped.detail());
      }
    }
    return true;
  }

  /**
   * Every registered check, each under its own deadline, answered as one {@code healthChecked}
   * echoing the request id: ok only when every check is.
   */
  private void onHealthCheck(HealthCheck check, RunnerSession session) {
    if (decommissioning) {
      return;
    }
    HealthContext ctx =
        HealthContext.of(session, docker, () -> new SessionFacts(live, connectedSince, ackSlots));
    session.send(HealthChecked.of(HealthChecks.answer(check.requestId(), healthChecks.run(ctx))));
  }

  /** The probe {@code after} from now, off the frame's thread: a pull can take minutes. */
  private void scheduleProbe(Duration after) {
    synchronized (timer) {
      if (nextProbe != null) {
        nextProbe.cancel(false);
      }
      if (timer.isShutdown()) {
        return;
      }
      nextProbe = timer.schedule(this::probeLogin, after.toMillis(), TimeUnit.MILLISECONDS);
    }
  }

  /**
   * Probe now, send {@code loginState}, and schedule the next: in {@link Timing#loginAbsentEvery}
   * while either login is not PRESENT, else in {@link Timing#loginPresentEvery}.
   */
  private void probeLogin() {
    RunnerSession s = session;
    if (s == null || !live || decommissioning) {
      return;
    }
    LoginState state = loginProbe.probe(s.driver(), ack.probeImage(), s.registry()::pull);
    lastLogin = state;
    try {
      s.send(state);
    } catch (RuntimeException e) {
      LOG.debugf("system-runner could not send its loginState: %s", e.getMessage());
    }
    boolean bothPresent =
        state.claude() == LoginPresence.PRESENT && state.kimi() == LoginPresence.PRESENT;
    scheduleProbe(bothPresent ? timing.loginPresentEvery() : timing.loginAbsentEvery());
  }

  /** The last login probe's answer, or null before one. */
  LoginState lastLogin() {
    return lastLogin;
  }

  /** The registry the health check runs; a suite adds a check of its own through it. */
  HealthChecks healthChecks() {
    return healthChecks;
  }

  // --- the node, as docker reports it -------------------------------------------------------------

  /**
   * Report every running lease container as held — of {@code only}, when given. A listing that
   * failed reports nothing.
   */
  private void holdRunning(RunnerSession session, Set<String> only) {
    List<LeaseContainers.Seen> seen;
    try {
      seen = containers.list();
    } catch (RuntimeException e) {
      LOG.warnf("system-runner could not list its leases: %s", e.getMessage());
      return;
    }
    List<String> held = session.held();
    for (LeaseContainers.Seen s : seen) {
      String leaseId = s.leaseId();
      if (s.running() && !held.contains(leaseId) && (only == null || only.contains(leaseId))) {
        session.taken(leaseId);
      }
    }
  }

  private void startTick() {
    synchronized (timer) {
      if (tick != null || timer.isShutdown()) {
        return;
      }
      long every = timing.lookEvery().toMillis();
      tick = timer.scheduleWithFixedDelay(this::tickOnce, every, every, TimeUnit.MILLISECONDS);
    }
  }

  /** The periodic look; skipped while a convergence holds the node, which reports for itself. */
  private void tickOnce() {
    RunnerSession s = session;
    if (s == null || !live || decommissioning) {
      return;
    }
    if (!converging.tryLock()) {
      return;
    }
    try {
      look(s, false);
    } catch (RuntimeException e) {
      LOG.debugf("system-runner's look failed: %s", e.getMessage());
    } finally {
      converging.unlock();
    }
  }

  /**
   * Release the slot of every held lease docker no longer reports running — one that exited by
   * itself, or one the reconcile removed — then send the {@code inventory}: always when {@code
   * force}, else only when it changed.
   */
  private void look(RunnerSession s, boolean force) {
    List<LeaseContainers.Seen> now;
    try {
      now = containers.list();
    } catch (RuntimeException e) {
      LOG.warnf("system-runner sends no inventory: %s", e.getMessage());
      return;
    }
    Set<String> running = new LinkedHashSet<>();
    now.stream().filter(LeaseContainers.Seen::running).forEach(seen -> running.add(seen.leaseId()));
    for (String held : s.held()) {
      if (!running.contains(held)) {
        s.released(held);
      }
    }
    sendInventory(s, now, force);
  }

  /** The {@code inventory} from a fresh listing; nothing when docker did not answer it. */
  private void sendInventory(RunnerSession session, boolean force) {
    List<LeaseContainers.Seen> now;
    try {
      now = containers.list();
    } catch (RuntimeException e) {
      LOG.warnf("system-runner sends no inventory: %s", e.getMessage());
      return;
    }
    sendInventory(session, now, force);
  }

  private void sendInventory(RunnerSession session, List<LeaseContainers.Seen> now, boolean force) {
    synchronized (inventoryLock) {
      Optional<Inventory> inventory = inventory(now);
      if (inventory.isEmpty()) {
        return;
      }
      if (!force && inventory.get().equals(lastInventory)) {
        return;
      }
      try {
        session.send(inventory.get());
        lastInventory = inventory.get();
      } catch (RuntimeException e) {
        LOG.debugf("system-runner could not send its inventory: %s", e.getMessage());
      }
    }
  }

  /**
   * What of the leases is on this node, sorted by lease: every lease container RUNNING, STARTING
   * ({@code created}) or EXITED with its exit code; every lease whose last convergence failed
   * FAILED with why; every RUNNING lease of the latest {@code estate} with no container yet
   * STARTING, and every ENDED one with none ENDED. Then the work volumes on the node: the runner's
   * own and every adopted one an estate entry names. Empty when docker did not answer.
   */
  Optional<Inventory> inventory(List<LeaseContainers.Seen> now) {
    Map<String, LeaseReport> leases = new HashMap<>();
    for (LeaseContainers.Seen seen : now) {
      LeaseState state =
          seen.running()
              ? LeaseState.RUNNING
              : seen.starting() ? LeaseState.STARTING : LeaseState.EXITED;
      leases.put(
          seen.leaseId(),
          new LeaseReport(
              seen.leaseId(),
              state,
              seen.specHash(),
              state == LeaseState.EXITED ? seen.exitCode() : null,
              null));
    }
    failures.forEach(
        (leaseId, detail) -> {
          LeaseReport seen = leases.get(leaseId);
          leases.put(
              leaseId,
              new LeaseReport(
                  leaseId, LeaseState.FAILED, seen == null ? null : seen.specHash(), null, detail));
        });
    Set<String> volumesHere = new TreeSet<>();
    Estate e = estate;
    if (e != null) {
      for (LeaseEntry entry : e.leases()) {
        LeaseState none =
            entry.desired() == DesiredState.ENDED ? LeaseState.ENDED : LeaseState.STARTING;
        leases.putIfAbsent(
            entry.leaseId(), new LeaseReport(entry.leaseId(), none, null, null, null));
      }
    }
    try {
      for (String volume : session().own().volumes()) {
        if (volumes.isWork(volume)) {
          volumesHere.add(volume);
        }
      }
      if (e != null) {
        for (LeaseEntry entry : e.leases()) {
          if (entry.adopt() != null
              && LegacyAdoption.allowed(entry.slotType(), entry.adopt().volume())
              && TargetVolumes.exists(docker, entry.adopt().volume())) {
            volumesHere.add(entry.adopt().volume());
          }
        }
      }
    } catch (RuntimeException listingFailed) {
      LOG.warnf("system-runner sends no inventory: %s", listingFailed.getMessage());
      return Optional.empty();
    }
    List<LeaseReport> sorted = new ArrayList<>(leases.values());
    sorted.sort(Comparator.comparing(LeaseReport::leaseId));
    return Optional.of(new Inventory(sorted, List.copyOf(volumesHere)));
  }

  private RunnerSession session() {
    RunnerSession s = session;
    if (s == null) {
      throw new IllegalStateException("no session yet");
    }
    return s;
  }
}
