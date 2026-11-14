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
import eu.wohlben.qits.systemrunner.protocol.Docker;
import eu.wohlben.qits.systemrunner.protocol.DockerResult;
import eu.wohlben.qits.systemrunner.protocol.DockerStatus;
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
import eu.wohlben.qits.systemrunner.protocol.SignIn;
import eu.wohlben.qits.systemrunner.protocol.SignInCancel;
import eu.wohlben.qits.systemrunner.protocol.SignInCode;
import eu.wohlben.qits.systemrunner.protocol.SignInProgress;
import eu.wohlben.qits.systemrunner.protocol.SignInState;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerAck;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerCapabilities;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerCodec;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerMessage;
import java.nio.file.Path;
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
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * <p><b>No pull runs under the converge lock</b> (qits-1543): a lease whose image, or whose
 * builder's image, is not on the node is reported {@code STARTING}, {@code pulling <image> since
 * <instant>}, while {@link ImagePulls} pulls it on its own threads, and the convergence goes on to
 * the next entry — so an end, a lease whose image is here and the {@code inventory} never wait
 * behind a multi-gigabyte cold pull. The pull's end converges the latest {@code estate} again. A
 * running pull is logged once a minute with how long it has run.
 *
 * <p><b>Rollover never drains.</b> {@link #quiescent()} is "no convergence in flight", never "holds
 * nothing": a lease container is node state that outlives the runner process, and its successor
 * adopts it by label at its session start.
 *
 * <p><b>The {@code inventory}</b> goes out at the {@code Ack}, around every convergence, and when
 * the periodic look sees a change; a listing docker did not answer is never sent — read as empty,
 * it would tell the server every lease is gone.
 *
 * <p><b>Each lease's resolvers are reported, never acted on</b> (qits-2091): a lease container's
 * {@code --dns} servers ride its report, with {@code resolversStale} when the node's resolver
 * choice ({@link NodeResolvers}, asked from the periodic look off the converge lock and kept a
 * minute) is not the container's first one — a lease created before the overlay existed, or while
 * the node tunnel did not answer. One WARN per lease that turns stale, none per look; the runner
 * never recreates a lease for it. The owner does, by raising the entry's {@code generation}.
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
  private final SignIns signIns;

  /** The node's and the lease containers' memory facts, sampled before every inventory. */
  private final MemorySampler memory;

  /** At most this many image pulls run at once; one per image reference ({@link ImagePulls}). */
  static final int PULL_THREADS = 2;

  /** How often every running pull is logged with how long it has run. */
  static final Duration PULL_PROGRESS_EVERY = Duration.ofMinutes(1);

  /** The image pulls' threads: bounded, off the converge lock. */
  private final ThreadPoolExecutor pullWork;

  /** The warm pulls' one thread (qits-1692): never the lease pulls', so a lease never waits. */
  private final ThreadPoolExecutor warmWork;

  /** The one thread a settled pull converges the latest estate again on. */
  private final ThreadPoolExecutor reconvergeWork;

  /** A re-convergence is queued: settled pulls coalesce into one. */
  private final AtomicBoolean reconvergeQueued = new AtomicBoolean();

  private final ImagePulls pulls;

  /** The sign-ins' slow steps: a thread each while one runs, so none waits on a probe or a tick. */
  private final ExecutorService signInWork;

  /**
   * How long {@link #close} waits for its threads once it has interrupted them; the toolkit's
   * {@code RunnerRuntime} waits as long for its own.
   */
  static final long INTERRUPTED_THREADS_MILLIS = 5_000;

  /** At most this many {@code docker} frames run at once; the rest wait their turn. */
  static final int DOCKER_THREADS = 4;

  /** The {@code docker} frames' threads: an exec may take minutes, so never the socket's. */
  private final ThreadPoolExecutor dockerWork;

  private final DockerOperations dockerOps;

  /** The build plane under the environment's settings alone: what every {@code Ack} merges over. */
  private final BuildPlane basePlane;

  /**
   * Whether the node tunnel answers, and its resolvers: a lease's at its create ({@link
   * LeaseLauncher}), the builder's at each {@link BuildPlane#ensure} (through {@link
   * SteadyResolvers}, qits-2260) and the successor's at each
   * rollover ({@link #successorDns()}) — always asked afresh (qits-2068, qits-2090).
   */
  private final NodeTunnel nodeTunnel;

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

  /** The node's resolver choice, asked from the look tick and read by {@code inventory}. */
  private final NodeResolvers nodeResolvers;

  /**
   * The leases whose stale resolvers were warned of (qits-2091): one WARN when a lease turns stale,
   * forgotten when it clears or its container goes, so the look never repeats it.
   */
  private final Set<String> staleWarned = ConcurrentHashMap.newKeySet();

  /** The last convergence's STARTING words per lease for {@code inventory}: the pull it awaits. */
  private final Map<String, String> pullingDetails = new ConcurrentHashMap<>();

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

  /**
   * Set first thing in {@link #close}: from then on no pool of this domain starts another docker
   * call, so the ones in flight are the last.
   */
  private volatile boolean closing;

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
    this(settings, external, buildkit, timing, clock, SignInProcesses.FORKED);
  }

  /** The same, with the sign-ins' process seam a suite replaces. */
  SystemRunnerDomain(
      RunnerSettings settings,
      ExternalVolumes external,
      BuildkitSettings buildkit,
      Timing timing,
      Clock clock,
      SignInProcesses processes) {
    this(settings, external, buildkit, CgroupSettings.DEFAULTS, timing, clock, processes);
  }

  /** The same, with where the node's memory facts are read from ({@link CgroupSettings}). */
  SystemRunnerDomain(
      RunnerSettings settings,
      ExternalVolumes external,
      BuildkitSettings buildkit,
      CgroupSettings cgroups) {
    this(
        settings,
        external,
        buildkit,
        cgroups,
        Timing.defaults(),
        Clock.systemUTC(),
        SignInProcesses.FORKED);
  }

  /** The same, with the runner's own resolv.conf, which {@link PrivateEdge} reads. */
  SystemRunnerDomain(
      RunnerSettings settings,
      ExternalVolumes external,
      BuildkitSettings buildkit,
      CgroupSettings cgroups,
      Timing timing,
      Clock clock,
      SignInProcesses processes) {
    this(
        settings,
        external,
        buildkit,
        cgroups,
        timing,
        clock,
        processes,
        PrivateEdge.RESOLV_CONF,
        NodeTunnel.UDP);
  }

  /**
   * The suite's: another resolv.conf, and a node without the node tunnel ({@link
   * NodeTunnel#NEVER}), so a lease off the private edge runs with the node's resolvers.
   */
  SystemRunnerDomain(
      RunnerSettings settings,
      ExternalVolumes external,
      BuildkitSettings buildkit,
      CgroupSettings cgroups,
      Timing timing,
      Clock clock,
      SignInProcesses processes,
      Path resolvConf) {
    this(
        settings,
        external,
        buildkit,
        cgroups,
        timing,
        clock,
        processes,
        resolvConf,
        NodeTunnel.NEVER);
  }

  /**
   * Every seam: the root every other constructor ends in. {@code resolvConf} is where {@link
   * PrivateEdge} and {@link NodeTunnel} read the upstream resolvers a lease gets after the edge's
   * VIP or the tunnel's address, and {@code tunnelProbe} asks whether the node tunnel's DNS
   * answers — both another for the suite only.
   */
  SystemRunnerDomain(
      RunnerSettings settings,
      ExternalVolumes external,
      BuildkitSettings buildkit,
      CgroupSettings cgroups,
      Timing timing,
      Clock clock,
      SignInProcesses processes,
      Path resolvConf,
      NodeTunnel.Probe tunnelProbe) {
    this.memory = new MemorySampler(cgroups);
    this.docker = RunnerDocker.of(identity, settings);
    this.targetId = settings.runnerId();
    this.slots = settings.slots();
    this.names = new TargetNames(identity, settings.runnerId());
    this.volumes = new TargetVolumes(identity, settings.runnerId(), external);
    this.containers = new LeaseContainers(identity, settings.runnerId(), docker);
    this.nodeTunnel = new NodeTunnel(tunnelProbe, NodeTunnel.hostOf(settings.url()), resolvConf);
    this.basePlane =
        new BuildPlane(
            identity,
            settings.runnerId(),
            docker,
            buildkit,
            this.slots,
            new SteadyResolvers(nodeTunnel::resolvers, clock));
    this.buildPlane = basePlane;
    this.pullWork = daemonPool("system-runner-pull", PULL_THREADS);
    this.reconvergeWork = daemonPool("system-runner-reconverge", 1);
    this.warmWork = daemonPool("system-runner-warm", 1);
    this.pulls =
        new ImagePulls(
            unlessClosing(pullWork), unlessClosing(warmWork), this::reconvergeSoon, clock);
    this.dockerWork = daemonPool("system-runner-docker", DOCKER_THREADS);
    this.dockerOps = new DockerOperations(identity, settings.runnerId(), docker);
    PrivateEdge privateEdge = new PrivateEdge(docker, resolvConf);
    this.loginProbe =
        new LoginProbe(
            identity,
            settings.runnerId(),
            new RunnerDocker(identity, settings.dockerBinary(), LoginProbe.DEADLINE),
            volumes,
            clock);
    this.launcher =
        new LeaseLauncher(
            identity,
            docker,
            names,
            volumes,
            containers,
            () -> buildPlane,
            pulls,
            privateEdge,
            nodeTunnel,
            loginProbe.firstRun(),
            () -> ack.probeImage());
    this.nodeResolvers = new NodeResolvers(privateEdge, nodeTunnel, clock);
    this.healthChecks = new HealthChecks(docker.deadline().multipliedBy(3));
    TargetHealthChecks.registerDefaults(
        healthChecks, identity, targetId, () -> lastLogin, () -> buildPlane, resolvConf);
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
    // A login probe or a sign-in's expiry still waiting when the domain closes never runs.
    pool.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    this.timer = pool;
    long progressEvery = PULL_PROGRESS_EVERY.toMillis();
    timer.scheduleWithFixedDelay(
        pulls::logProgress, progressEvery, progressEvery, TimeUnit.MILLISECONDS);
    this.signInWork =
        Executors.newCachedThreadPool(
            r -> {
              Thread t = new Thread(r, "system-runner-sign-in");
              t.setDaemon(true);
              return t;
            });
    this.signIns =
        new SignIns(
            loginProbe,
            names,
            volumes,
            new RunnerDocker(identity, settings.dockerBinary(), LoginProbe.DEADLINE),
            processes,
            signInWork,
            timer,
            SignIns.DEADLINE,
            this::sendSignIn,
            () -> scheduleProbe(Duration.ZERO));
  }

  private static ThreadPoolExecutor daemonPool(String name, int threads) {
    ThreadPoolExecutor pool =
        new ThreadPoolExecutor(
            threads,
            threads,
            60,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            r -> {
              Thread t = new Thread(r, name);
              t.setDaemon(true);
              return t;
            });
    pool.allowCoreThreadTimeOut(true);
    return pool;
  }

  /**
   * {@code pool}, with every task it dequeues once the domain is closing dropped unrun: a pull
   * queued behind the running ones is not started by the pool's orderly shutdown.
   */
  private Executor unlessClosing(ExecutorService pool) {
    return task ->
        pool.execute(
            () -> {
              if (!closing) {
                task.run();
              }
            });
  }

  /**
   * The resolvers the toolkit starts this runner's successor with, asked at each rollover on the
   * self-update thread (qits-2090): the node tunnel's ({@link NodeTunnel#resolvers()}) where it
   * answers, so the next runner reaches the private edge's names through the tunnel as its leases
   * do; empty — the toolkit's default, no {@code --dns} — on the platform host and on a node
   * without the tunnel. Never throws.
   */
  @Override
  public List<String> successorDns() {
    List<String> dns = nodeTunnel.resolvers();
    if (!dns.isEmpty()) {
      LOG.infof("system-runner starts its successor via the node tunnel, resolvers %s", dns);
    }
    return dns;
  }

  @Override
  public DomainCodec<SystemRunnerMessage> codec() {
    return CODEC;
  }

  /**
   * The base's three facts and this target's own: {@code slots} (the server's total), {@code
   * engineId} and {@code hostname} from {@code docker info} (asked until docker answers once, and
   * the cgroup driver with them, for {@link MemorySampler}), the
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
          docker.run(
              List.of(
                  docker.binary(), "info", "--format", "{{.ID}}|{{.Name}}|{{.CgroupDriver}}"));
      if (!info.ok()) {
        LOG.warnf("system-runner could not read the docker engine's id: %s", info.detail());
        return;
      }
      String[] f = info.stdout().strip().split("\\|", -1);
      String id = f.length > 0 ? f[0].strip() : "";
      String name = f.length > 1 ? f[1].strip() : "";
      memory.driver(f.length > 2 ? f[2].strip() : null);
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
    pulls.newSession();
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
    prePull(session);
  }

  /**
   * The images a CI lease will need, pulled in the background before one is placed: the builder's
   * when this target has slots and the host names a CI slot image, and the spec image of every
   * RUNNING lease of the estate kept from the last connection. The {@code Ack} carries the CI slot
   * image's repository prefix only ({@link SlotImages#ci()}), never a pinned reference, so the CI
   * image itself is pulled once a lease names it — off the converge lock, like this. The kept
   * estate's {@code warmImages} go to the warm pool after them, a warm pull that failed on an
   * earlier connection tried again. The kept estate may already be this connection's own: the
   * runtime runs the {@code Ack} and the estate concurrently, so the estate can arrive, and its warm
   * pull fail, before this runs — which is why {@link ImagePulls#retryWarm} is per connection, and
   * keeps a failure this connection's estate made until the next estate.
   */
  private void prePull(RunnerSession session) {
    try {
      if (ackSlots != null && ackSlots > 0 && ack.slotImages().ci() != null) {
        launcher.prefetchBuilder();
      }
      Estate kept = estate;
      if (kept != null) {
        for (LeaseEntry entry : kept.leases()) {
          if (entry.desired() == DesiredState.RUNNING && entry.spec() != null) {
            launcher.prefetch(entry.spec().image(), session);
          }
        }
        pulls.retryWarm();
        warm(kept, session);
      }
    } catch (RuntimeException e) {
      LOG.warnf("system-runner could not start its pre-pulls: %s", e.getMessage());
    }
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
      case SignIn signIn -> onSignIn(signIn, session);
      case SignInCode code -> signIns.code(code);
      case SignInCancel cancel -> signIns.cancel(cancel);
      case Docker request -> onDocker(request, session);
      default ->
          // inventory, loginState, healthChecked, signInProgress and docker-result are runner →
          // server; a host
          // echoing one is not a conversation this has.
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
    signIns.stopAll("the target is being decommissioned");
    timer.shutdownNow();
    pullWork.shutdownNow();
    warmWork.shutdownNow();
    reconvergeWork.shutdownNow();
    dockerWork.shutdownNow();
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
   * them carries the owner label the helper removes by — then the first-run keys' container and
   * each harness's sign-in container, which carry none either. The CI caches do carry it, and go
   * with the labelled volumes.
   */
  @Override
  public List<TeardownItem> teardown() {
    List<TeardownItem> items = new ArrayList<>(basePlane.names().teardown());
    items.add(new TeardownItem(TeardownItem.Type.CONTAINER, names.firstRun()));
    for (String harness : SignIns.HARNESSES) {
      items.add(new TeardownItem(TeardownItem.Type.CONTAINER, names.signIn(harness)));
    }
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

  /** When the running pull of {@code image} started; empty once it settled, its outcome kept. */
  Optional<Instant> pullingSince(String image) {
    return pulls.pullingSince(image);
  }

  /**
   * The images in use now: every spec image of the latest {@code estate}, its warm images, and the
   * probe image.
   */
  Set<String> currentImages() {
    Set<String> images = new LinkedHashSet<>();
    Estate e = estate;
    if (e != null) {
      for (LeaseEntry entry : e.leases()) {
        if (entry.spec() != null) {
          images.add(entry.spec().image());
        }
      }
      images.addAll(warmImages(e));
    }
    String probe = ack.probeImage();
    if (probe != null && !probe.isBlank()) {
      images.add(probe);
    }
    return images;
  }

  /**
   * Stop every thread this domain started and wait for them, so a suite deletes the fake docker's
   * directory only once nothing of this domain still runs docker there (qits-1868). The look keeps
   * ticking after the runtime's run returned — nothing ends the session on a plain stop — so the
   * domain is told it is closing first, and none of its pools starts another docker call; the
   * sign-ins are cancelled, their processes killed and their containers removed on their own
   * threads.
   * Nothing is interrupted at first, for qits-860's reason: an interrupted docker call returns at
   * once while the docker process it forked runs on, unwatched, and the next call on that thread is
   * forked before its interrupt kills it. So the work in flight is let finish, up to one docker
   * deadline — no single docker call runs longer — and only a thread still busy past that is
   * interrupted, as the last resort it is. For a suite's teardown; the process ending does the same.
   */
  void close() {
    closing = true;
    signIns.stopAll("the runner stops");
    synchronized (timer) {
      if (tick != null) {
        tick.cancel(false);
      }
      if (nextProbe != null) {
        nextProbe.cancel(false);
      }
    }
    List<ExecutorService> pools =
        List.of(timer, pullWork, warmWork, reconvergeWork, dockerWork, signInWork);
    pools.forEach(ExecutorService::shutdown);
    long deadline = System.nanoTime() + docker.deadline().toNanos();
    try {
      if (terminated(pools, deadline)) {
        healthChecks.close();
        return;
      }
      LOG.warnf(
          "system-runner's work was still running %ds after it stopped; interrupting it",
          docker.deadline().toSeconds());
      pools.forEach(ExecutorService::shutdownNow);
      if (!terminated(
          pools, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(INTERRUPTED_THREADS_MILLIS))) {
        LOG.warnf("system-runner closes with work still running");
      }
    } catch (InterruptedException e) {
      pools.forEach(ExecutorService::shutdownNow);
      Thread.currentThread().interrupt();
    }
    healthChecks.close();
  }

  /** Every pool terminated by {@code deadline}, a {@link System#nanoTime} instant. */
  private static boolean terminated(List<ExecutorService> pools, long deadline)
      throws InterruptedException {
    for (ExecutorService pool : pools) {
      if (!pool.awaitTermination(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)) {
        return false;
      }
    }
    return true;
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
    // A failed pull already reported is tried again from the server's next estate, never from a
    // re-convergence of the runner's own: that would pull in a loop.
    pulls.retryReported();
    convergeEstate(e, session, false);
    warm(e, session);
  }

  /**
   * The estate's {@code warmImages} not on the node, pulled in the background after the convergence
   * — so a lease's own pull is queued first — and never waited on (qits-1692). A failed one is
   * logged and tried again from the next estate that names it.
   */
  private void warm(Estate e, RunnerSession session) {
    if (decommissioning) {
      return;
    }
    for (String image : warmImages(e)) {
      try {
        launcher.warm(image, session);
      } catch (RuntimeException ex) {
        LOG.warnf("system-runner could not warm %s: %s", image, ex.getMessage());
      }
    }
  }

  /** An estate's warm images, blanks dropped: a reference that names nothing is never pulled. */
  private static Set<String> warmImages(Estate e) {
    Set<String> images = new LinkedHashSet<>();
    for (String image : e.warmImages()) {
      if (image != null && !image.isBlank()) {
        images.add(image.strip());
      }
    }
    return images;
  }

  /**
   * A pull settled: converge the latest {@code estate} again, on the re-convergence thread, so the
   * lease whose image arrived is created. Coalesced: one queued at a time.
   */
  private void reconvergeSoon() {
    if (!reconvergeQueued.compareAndSet(false, true)) {
      return;
    }
    try {
      reconvergeWork.execute(
          () -> {
            reconvergeQueued.set(false);
            RunnerSession s = session;
            if (s != null && !closing) {
              convergeEstate(null, s, true);
            }
          });
    } catch (RejectedExecutionException stopping) {
      reconvergeQueued.set(false);
    }
  }

  /**
   * One convergence of an estate under {@link #converging}: {@code given}, or — for a re-convergence —
   * the latest one, and then only while this connection has had one (nothing is removed before an
   * {@code estate} arrived on the current connection).
   */
  private void convergeEstate(Estate given, RunnerSession session, boolean again) {
    if (decommissioning || closing) {
      return;
    }
    boolean converged = false;
    inFlight.incrementAndGet();
    converging.lock();
    try {
      Estate e = given != null ? given : estate;
      if (e == null || decommissioning || closing || (again && (!live || !estateThisSession))) {
        return;
      }
      Set<String> listed = new LinkedHashSet<>();
      e.leases().forEach(entry -> listed.add(entry.leaseId()));
      estateThisSession = true;
      LOG.infof(
          "system-runner's estate%s: %d lease(s), %d purge(s)",
          again ? " again, after a pull" : "", listed.size(), e.purges().size());
      failures.keySet().retainAll(listed);
      pullingDetails.keySet().retainAll(listed);
      sendInventory(session, false);
      if (stopOutside(listed, session)) {
        session.own().reconcile(listed);
      }
      for (LeaseEntry entry : e.leases()) {
        if (closing) {
          return;
        }
        converge(entry, session);
      }
      if (closing) {
        return;
      }
      purge(e, session);
      converged = true;
    } finally {
      converging.unlock();
      inFlight.decrementAndGet();
    }
    if (converged && !closing) {
      look(session, true);
    }
  }

  /**
   * One entry converged, under {@link #converging}: its failure or the pull it waits on kept for the
   * inventory, and the slot held exactly while the lease runs.
   */
  private void converge(LeaseEntry entry, RunnerSession session) {
    String leaseId = entry.leaseId();
    LeaseLauncher.Outcome outcome = launcher.converge(entry, session, ack.slotImages());
    if (outcome.failed()) {
      failures.put(leaseId, outcome.detail());
    } else {
      failures.remove(leaseId);
    }
    if (outcome.state() == LeaseState.STARTING && outcome.detail() != null) {
      pullingDetails.put(leaseId, outcome.detail());
    } else {
      pullingDetails.remove(leaseId);
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

  /**
   * A {@code signIn}, against the probe image and the session's driver and pull; refused while the
   * target is being decommissioned. Everything slow runs on the sign-ins' own threads.
   */
  private void onSignIn(SignIn signIn, RunnerSession session) {
    if (decommissioning) {
      sendSignIn(
          new SignInProgress(
              signIn.signInId(),
              signIn.harness(),
              SignInState.FAILED,
              null,
              null,
              "the target is being decommissioned"));
      return;
    }
    signIns.start(signIn, ack.probeImage(), session.driver(), session.registry()::pull);
  }

  /** A sign-in's progress, to the current session; dropped while there is none. */
  private void sendSignIn(SignInProgress progress) {
    RunnerSession s = session;
    if (s == null) {
      return;
    }
    try {
      s.send(progress);
    } catch (RuntimeException e) {
      LOG.debugf("system-runner could not send sign-in progress: %s", e.getMessage());
    }
  }

  /**
   * A {@code docker} request, run on the docker frames' own threads ({@link DockerOperations}) and
   * answered with exactly one {@code docker-result} on the session it came on: refused while the
   * target is being decommissioned, failed when the runner is stopping.
   */
  private void onDocker(Docker request, RunnerSession session) {
    if (decommissioning) {
      sendDocker(
          session,
          DockerResult.of(
              request.requestId(), DockerStatus.REFUSED, "the target is being decommissioned"));
      return;
    }
    try {
      dockerWork.execute(
          () ->
              sendDocker(
                  session,
                  closing
                      ? DockerResult.of(
                          request.requestId(), DockerStatus.FAILED, "the runner is stopping")
                      : dockerOps.answer(request)));
    } catch (RejectedExecutionException stopping) {
      sendDocker(
          session,
          DockerResult.of(request.requestId(), DockerStatus.FAILED, "the runner is stopping"));
    }
  }

  /** A {@code docker-result}; dropped when the socket it answers went away. */
  private static void sendDocker(RunnerSession session, DockerResult result) {
    try {
      session.send(result);
    } catch (RuntimeException e) {
      LOG.debugf("system-runner could not send a docker result: %s", e.getMessage());
    }
  }

  /** The sign-ins, for a suite. */
  SignIns signIns() {
    return signIns;
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
    if (s == null || !live || decommissioning || closing) {
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

  /**
   * The periodic look; skipped while a convergence holds the node, which reports for itself. The
   * node's resolver choice is asked first, outside the converge lock — at most once a minute, the
   * rest of the time {@link NodeResolvers} answers from its cache — so a probe that waits never
   * holds a convergence up.
   */
  private void tickOnce() {
    RunnerSession s = session;
    if (s == null || !live || decommissioning || closing) {
      return;
    }
    nodeResolvers.choice();
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
      memory.sample(now);
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
   * STARTING — with {@code pulling <image> since <instant>} while its image is pulled — and every
   * ENDED one with none ENDED. Then the work volumes on the node: the runner's
   * own and every adopted one an estate entry names. Empty when docker did not answer.
   *
   * <p>A lease with a container carries its memory facts as {@link MemorySampler} last read them
   * — an EXITED one the last values read while it ran, and docker's {@code OOMKilled} — and the
   * frame the node's (qits-1818).
   *
   * <p>A lease with a container carries its resolvers, and whether they are stale against the
   * node's last resolver choice — warned of once when one turns stale (qits-2091).
   */
  Optional<Inventory> inventory(List<LeaseContainers.Seen> now) {
    Map<String, LeaseReport> leases = new HashMap<>();
    Optional<String> choice = nodeResolvers.last();
    Set<String> stale = new LinkedHashSet<>();
    for (LeaseContainers.Seen seen : now) {
      boolean resolversStale = stale(seen.dns(), choice);
      if (resolversStale) {
        stale.add(seen.leaseId());
      }
      LeaseState state =
          seen.running()
              ? LeaseState.RUNNING
              : seen.starting() ? LeaseState.STARTING : LeaseState.EXITED;
      MemorySampler.LeaseMemory m = memory.lease(seen.leaseId());
      leases.put(
          seen.leaseId(),
          new LeaseReport(
              seen.leaseId(),
              state,
              seen.specHash(),
              state == LeaseState.EXITED ? seen.exitCode() : null,
              null,
              m.oomKills(),
              m.oomEvents(),
              m.memoryPeakBytes(),
              state == LeaseState.EXITED ? seen.oomKilled() : null,
              seen.dns(),
              resolversStale));
    }
    warnStale(stale, choice);
    failures.forEach(
        (leaseId, detail) -> {
          LeaseReport seen = leases.get(leaseId);
          leases.put(
              leaseId,
              seen == null
                  ? new LeaseReport(leaseId, LeaseState.FAILED, null, null, detail)
                  : new LeaseReport(
                      leaseId,
                      LeaseState.FAILED,
                      seen.specHash(),
                      null,
                      detail,
                      seen.oomKills(),
                      seen.oomEvents(),
                      seen.memoryPeakBytes(),
                      null,
                      seen.resolvers(),
                      seen.resolversStale()));
        });
    Set<String> volumesHere = new TreeSet<>();
    Estate e = estate;
    if (e != null) {
      for (LeaseEntry entry : e.leases()) {
        LeaseState none =
            entry.desired() == DesiredState.ENDED ? LeaseState.ENDED : LeaseState.STARTING;
        String detail = none == LeaseState.STARTING ? pullingDetails.get(entry.leaseId()) : null;
        leases.putIfAbsent(
            entry.leaseId(), new LeaseReport(entry.leaseId(), none, null, null, detail));
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
    return Optional.of(new Inventory(sorted, List.copyOf(volumesHere), memory.node()));
  }

  /**
   * Whether a container resolving through {@code dns} is stale against the node's {@code choice}:
   * the node offers a resolver and it is not the container's first — none at all included.
   */
  static boolean stale(List<String> dns, Optional<String> choice) {
    return choice.isPresent() && (dns.isEmpty() || !dns.getFirst().equals(choice.get()));
  }

  /**
   * One WARN for each lease in {@code stale} not warned of yet; every lease warned of that is no
   * longer stale — cleared, or its container gone — forgotten, so it warns again if it turns stale
   * again. Never one per look.
   */
  private void warnStale(Set<String> stale, Optional<String> choice) {
    staleWarned.retainAll(stale);
    if (choice.isEmpty()) {
      return;
    }
    String address = choice.get();
    String which = NodeTunnel.DNS_ADDRESS.equals(address) ? "node tunnel" : "private edge";
    for (String leaseId : stale) {
      if (staleWarned.add(leaseId)) {
        LOG.warnf(
            "system-runner's lease %s runs without the %s resolver although %s answers",
            leaseId, which, address);
      }
    }
  }

  private RunnerSession session() {
    RunnerSession s = session;
    if (s == null) {
      throw new IllegalStateException("no session yet");
    }
    return s;
  }
}
