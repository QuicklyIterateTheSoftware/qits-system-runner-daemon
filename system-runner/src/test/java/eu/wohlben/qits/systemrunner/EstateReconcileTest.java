package eu.wohlben.qits.systemrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.runner.protocol.Hello;
import eu.wohlben.qits.runner.protocol.Reserve;
import eu.wohlben.qits.runner.protocol.RunnerCodec;
import eu.wohlben.qits.runner.toolkit.Decommission;
import eu.wohlben.qits.runner.toolkit.HostFacts;
import eu.wohlben.qits.runner.toolkit.Housekeeping;
import eu.wohlben.qits.runner.toolkit.HousekeepingContext;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerExit;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.RunnerRuntime;
import eu.wohlben.qits.runner.toolkit.RunnerSettings;
import eu.wohlben.qits.runner.toolkit.SelfUpdate;
import eu.wohlben.qits.runner.toolkit.Telemetry;
import eu.wohlben.qits.runner.toolkit.testing.FakeDocker;
import eu.wohlben.qits.runner.toolkit.testing.FakeRunnerHost;
import eu.wohlben.qits.systemrunner.protocol.Adopt;
import eu.wohlben.qits.systemrunner.protocol.DesiredState;
import eu.wohlben.qits.systemrunner.protocol.Estate;
import eu.wohlben.qits.systemrunner.protocol.Docker;
import eu.wohlben.qits.systemrunner.protocol.DockerOp;
import eu.wohlben.qits.systemrunner.protocol.DockerResult;
import eu.wohlben.qits.systemrunner.protocol.DockerStatus;
import eu.wohlben.qits.systemrunner.protocol.HealthCheck;
import eu.wohlben.qits.systemrunner.protocol.HealthChecked;
import eu.wohlben.qits.systemrunner.protocol.Inventory;
import eu.wohlben.qits.systemrunner.protocol.LeaseEntry;
import eu.wohlben.qits.systemrunner.protocol.LeaseReport;
import eu.wohlben.qits.systemrunner.protocol.LeaseSpec;
import eu.wohlben.qits.systemrunner.protocol.LeaseState;
import eu.wohlben.qits.systemrunner.protocol.Limits;
import eu.wohlben.qits.systemrunner.protocol.LoginPresence;
import eu.wohlben.qits.systemrunner.protocol.LoginState;
import eu.wohlben.qits.systemrunner.protocol.NodeMemory;
import eu.wohlben.qits.systemrunner.protocol.SignIn;
import eu.wohlben.qits.systemrunner.protocol.SignInProgress;
import eu.wohlben.qits.systemrunner.protocol.SignInState;
import eu.wohlben.qits.systemrunner.protocol.SlotImages;
import eu.wohlben.qits.systemrunner.protocol.SlotType;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerAck;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerBinary;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerCapabilities;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerCodec;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerMessage;
import io.vertx.core.Vertx;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.io.TempDir;

/**
 * The target runner's estate reconcile, end to end, against qits-runner-toolkit's own fixtures:
 * {@link FakeRunnerHost} (register door, token endpoint, control socket, each scripted here) and
 * {@link FakeDocker} (a script the production process seam forks as docker). The runtime is the
 * toolkit's {@link RunnerRuntime} with {@link SystemRunnerDomain}, exactly what {@code Main} hands
 * {@code RunnerLauncher}; only the node's clocks are shrunk. The shape is
 * qits-projects-desk-runner-daemon's {@code DeskRunnerTest}: start, recreate on a spec hash, remove
 * on ENDED, adopt — and the refusals a lease is FAILED for.
 */
@EnabledOnOs(OS.LINUX)
class EstateReconcileTest {

  private static final RunnerIdentity IDENTITY = SystemRunnerIdentity.IDENTITY;

  private static final RunnerCodec<SystemRunnerMessage> CODEC =
      new RunnerCodec<>(IDENTITY.vocabulary(), new SystemRunnerCodec());

  private static final String SELF = "0123456789ab";
  private static final String TARGET = "r1";

  private static final String REGISTRY = "registry.example:5000/qits/";
  private static final String WS_IMAGE = REGISTRY + "workspace:2026.1023.1";
  private static final String WS_NEXT = REGISTRY + "workspace:2026.1024.1";
  private static final String DESK_IMAGE = REGISTRY + "project-agent:2026.1023.1";
  private static final String WARM = REGISTRY + "workspace:2026.1027.1";
  private static final String WARM2 = REGISTRY + "workspace:2026.1027.2";
  private static final SystemRunnerAck ACK =
      new SystemRunnerAck(
          new SlotImages(REGISTRY + "ci-", REGISTRY + "workspace", REGISTRY + "project-agent"),
          WS_IMAGE,
          null);

  private static final String L1 = "8273c743-e0bb-4d93-90d4-391aa10d9151";
  private static final String L2 = "5ca0e7e9-7dd8-4128-bbdd-58aa40d9b397";
  private static final String DESK_ID = "9d6f1c2a-1b2c-4d5e-8f90-0a1b2c3d4e5f";

  private static final String H1 = "sha256-hash-one";
  private static final String H2 = "sha256-hash-two";

  private static final String DOT_CLAUDE = "qits-system-runner-dot-claude-r1";
  private static final String M2 = "qits-system-runner-m2-r1";
  private static final String PNPM = "qits-system-runner-pnpm-r1";

  private static final String CI_IMAGE = REGISTRY + "ci-slot@sha256:0123";
  private static final String CI_GIT = "qits-system-runner-ci-git-r1";
  private static final String CI_M2 = "qits-system-runner-ci-m2-r1";
  private static final String CI_PNPM = "qits-system-runner-ci-pnpm-r1";
  private static final String CI_DAEMON = "qits-system-runner-ci-daemon-r1";
  private static final String BUILDER = "qits-system-runner-buildkitd-r1";
  private static final String BUILD_NET = "qits-system-runner-build-r1";

  private static final String OLD_WS = "qits-workspaces-runner-ws-42-0f0e0d0c";

  private final Vertx vertx = Vertx.vertx();
  private FakeRunnerHost host;
  private FakeDocker docker;
  private SystemRunnerDomain domain;
  private RunnerRuntime<SystemRunnerMessage> runner;
  private CompletableFuture<Integer> exit;
  private final Map<String, String> extraEnv = new HashMap<>();

  /** No cgroup tree and no vmstat unless a test builds one: every memory fact null. */
  private CgroupSettings cgroups;

  @TempDir Path state;
  @TempDir Path dockerDir;

  @BeforeEach
  void setUp() throws Exception {
    host = new FakeRunnerHost(vertx, IDENTITY, CODEC);
    docker = new FakeDocker(dockerDir);
    docker.answerFor("run", "status", 0, "{\"loggedIn\": true}\n", "");
    docker.answerFor("run", LoginProbe.KIMI_CHECK, 0, "", "");
    docker.answer("info", 0, "ENGINE-ID-1|node-1\n", "");
    cgroups = new CgroupSettings(dockerDir.resolve("no-cgroup"), dockerDir.resolve("no-vmstat"));
  }

  @AfterEach
  void tearDown() throws Exception {
    if (runner != null) {
      runner.stop(RunnerExit.OK);
      exit.get(10, TimeUnit.SECONDS);
    }
    if (domain != null) {
      domain.close();
    }
    host.close();
    vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  // ---- register and hello -----------------------------------------------------------------------

  @Test
  void itRegistersAtTheSystemDoorAndSaysHelloWithTheTargetsCapabilities() throws Exception {
    estateThenAck();
    start();

    Hello hello = host.await(Hello.class);
    assertEquals(List.of("/system/api/targets/r1/register"), host.registerPaths);
    assertEquals(SystemRunnerBinary.VERSION, hello.runnerVersion());
    assertEquals(1, hello.capabilityVersion());
    assertEquals(
        new SystemRunnerCapabilities(
            true, "amd64", "linux", 2, "ENGINE-ID-1", "node-1", DOT_CLAUDE, true),
        SystemRunnerCapabilities.of(hello.capabilities()));
    assertFalse(CODEC.encode(hello).containsKey("heldLeases"), "absent on a first connection");
  }

  // ---- start ------------------------------------------------------------------------------------

  @Test
  void anEstateStartsAnAbsentWorkspaceLeaseUnderItsPolicyWithItsVolumesMadeLabelledFirst()
      throws Exception {
    docker.answerFor("image-inspect", WS_IMAGE, 1, "", "Error: No such image");
    estateThenAck(workspace(L1, H1));
    start();

    awaitTrue(() -> !runsOf(lease(L1)).isEmpty());
    List<String> run = runOf(lease(L1));
    assertEquals(
        List.of(
            "run",
            "-d",
            "--init",
            "--name",
            lease(L1),
            "--network",
            "bridge",
            "--user",
            "1000",
            "--label",
            "qits.system.runner=r1",
            "--label",
            "qits.system.runner.lease=" + L1,
            "--label",
            "qits.system.runner.mounts=" + mounts(work("ws-42")),
            "--label",
            "qits.system.runner.slot-type=WORKSPACE",
            "--label",
            "qits.system.runner.spec-hash=" + H1,
            "--memory",
            "4g",
            "--memory-swap",
            "4g",
            "--pids-limit",
            "512",
            "--cpus",
            "2",
            "--oom-score-adj",
            "800",
            "-v",
            work("ws-42") + ":/workspace",
            "-v",
            DOT_CLAUDE + ":/claude-home",
            "-v",
            M2 + ":/caches/m2",
            "-v",
            PNPM + ":/caches/pnpm",
            "-e",
            "CLAUDE_CONFIG_DIR=/claude-home/.claude",
            "-e",
            "QITS_TOKEN=qits_tok_LEASE",
            "-e",
            "TZ=UTC",
            WS_IMAGE),
        run,
        "the bridge, no extra host, no docker socket, --init (qits-1598), no restart, no sandbox flags");
    List<List<String>> calls = docker.calls();
    List<String> pull =
        calls.stream()
            .filter(c -> c.contains("pull") && c.contains(WS_IMAGE))
            .findFirst()
            .orElseThrow(() -> new AssertionError(calls));
    assertEquals("--config", pull.getFirst(), "pulled under the runner's own throwaway login");
    assertTrue(calls.indexOf(pull) < calls.indexOf(run), "pulled before it is run: " + calls);
    assertEquals(
        Map.of("qits.system.runner", TARGET, "qits.system.runner.work-key", "ws-42"),
        docker.volumes().get(work("ws-42")));
    for (String node : List.of(DOT_CLAUDE, M2, PNPM)) {
      assertEquals(Map.of("qits.system.runner", TARGET), docker.volumes().get(node), node);
    }
    // FakeDocker's inspect answers nothing unscripted, so the periodic look reads the new container
    // as gone; say it runs, and the next estate holds it for good.
    docker.answerFor(
        "inspect", lease(L1), 0, "c-" + L1 + "|running|0|" + L1 + "|WORKSPACE|" + H1 + "|"
            + mounts(work("ws-42")) + "\n", "");
    host.send(new Estate(List.of(workspace(L1, H1)), List.of()));
    awaitTrue(() -> runner.session().held().equals(List.of(L1)));
    assertTrue(host.all(Reserve.class).isEmpty(), "the server allocates: no reserve, ever");
  }

  @Test
  void aDeskLeaseRunsWithInitAndRestartUnlessStopped() throws Exception {
    estateThenAck(desk(L2, H1));
    start();

    awaitTrue(() -> !runsOf(lease(L2)).isEmpty());
    List<String> run = runOf(lease(L2));
    assertEquals(List.of("run", "-d", "--init", "--name", lease(L2)), run.subList(0, 5));
    int restart = run.indexOf("--restart");
    assertTrue(restart > 0 && run.get(restart + 1).equals("unless-stopped"), run::toString);
    assertTrue(run.contains(work("desk-" + DESK_ID) + ":/workspace"), run::toString);
    assertTrue(run.contains(DOT_CLAUDE + ":/claude-home"), run::toString);
    assertTrue(run.contains("qits.system.runner.slot-type=DESK"), run::toString);
  }

  @Test
  void aRunningLeaseOfTheSameSpecIsLeftAloneAndReportedRunning() throws Exception {
    seen(L1, "running", 0, "WORKSPACE", H1, mounts(work("ws-42")));
    estateThenAck(workspace(L1, H1));
    start();

    Inventory inventory =
        awaitInventory(i -> i.leases().stream().anyMatch(r -> r.leaseId().equals(L1)));
    assertEquals(
        new LeaseReport(L1, LeaseState.RUNNING, H1, null, null), report(inventory, L1));
    awaitTrue(() -> runner.session().held().equals(List.of(L1)));
    for (String verb : List.of("run", "start", "stop", "rm")) {
      assertFalse(touched(verb, lease(L1)), docker.calls()::toString);
    }
  }

  /**
   * qits-1868: the look keeps ticking after the runtime's run returned, so closing the domain
   * meets a docker call in flight. Closing waits for it — an interrupted call would return at once
   * while the process it forked runs on, still writing into the suite's directory — and starts no
   * other.
   */
  @Test
  void closingTheDomainWaitsForTheDockerCallInFlightAndStartsNoOther() throws Exception {
    seen(L1, "running", 0, "WORKSPACE", H1, mounts(work("ws-42")));
    estateThenAck(workspace(L1, H1));
    start();
    awaitInventory(i -> i.leases().stream().anyMatch(r -> r.leaseId().equals(L1)));
    runner.stop(RunnerExit.OK);
    exit.get(30, TimeUnit.SECONDS);
    runner = null;
    // The look's own inspect of the lease hangs, so the next look is inside it when closing comes.
    docker.hang("inspect+" + safe(lease(L1)), 3);
    Thread.sleep(500);

    domain.close();
    List<ProcessHandle> alive =
        ProcessHandle.current().descendants().filter(ProcessHandle::isAlive).toList();
    int calls = docker.calls().size();
    domain = null;

    assertEquals(
        List.of(),
        alive.stream().map(p -> p.info().commandLine().orElse(String.valueOf(p.pid()))).toList(),
        "no docker process outlives the close");
    Thread.sleep(1_000);
    assertEquals(calls, docker.calls().size(), docker.calls()::toString);
  }

  @Test
  void anExitedLeaseIsReportedWithItsExitCodeAndNeverRestarted() throws Exception {
    seen(L1, "exited", 137, "WORKSPACE", H1, mounts(work("ws-42")));
    estateThenAck(workspace(L1, H1));
    start();

    Inventory inventory =
        awaitInventory(
            i -> i.leases().stream().anyMatch(r -> r.state() == LeaseState.EXITED));
    assertEquals(new LeaseReport(L1, LeaseState.EXITED, H1, 137, null), report(inventory, L1));
    assertFalse(touched("start", lease(L1)), "its token may be gone; the owner ends it");
    assertFalse(touched("run", lease(L1)));
  }

  @Test
  void aLeasesOomKillsAreReportedWhileItRunsAndKeptWhenItExits() throws Exception {
    Path tree = Files.createDirectories(dockerDir.resolve("host-cgroup"));
    Files.writeString(tree.resolve("memory.events"), "oom 0\noom_kill 292\n");
    Files.writeString(tree.resolve("memory.max"), "max\n");
    Path vmstat = Files.writeString(dockerDir.resolve("vmstat"), "oom_kill 297\n");
    // FakeDocker's info names no cgroup driver: the scope is found by the search.
    Path scope =
        Files.createDirectories(tree.resolve("system.slice/docker-c-" + L1 + ".scope"));
    Files.writeString(scope.resolve("memory.events"), "oom 1\noom_kill 1\n");
    Files.writeString(scope.resolve("memory.peak"), "1073741824\n");
    cgroups = new CgroupSettings(tree, vmstat);
    seen(L1, "running", 0, "WORKSPACE", H1, mounts(work("ws-42")));
    estateThenAck(workspace(L1, H1));
    start();

    Inventory running = awaitInventory(i -> stateOf(i, L1) == LeaseState.RUNNING);
    assertEquals(
        new LeaseReport(L1, LeaseState.RUNNING, H1, null, null, 1L, 1L, 1L << 30, null),
        report(running, L1));
    assertEquals(new NodeMemory(292L, 297L, null), running.node());

    // The OOM kill ends it: the cgroup goes with the container, docker says OOMKilled.
    Files.writeString(scope.resolve("memory.events"), "oom 1\noom_kill 2\n");
    awaitInventory(i -> leaseOf(i, L1, r -> Long.valueOf(2L).equals(r.oomKills())));
    for (String f : List.of("memory.events", "memory.peak")) {
      Files.delete(scope.resolve(f));
    }
    Files.delete(scope);
    Files.writeString(tree.resolve("memory.events"), "oom 0\noom_kill 294\n");
    docker.answerFor(
        "inspect",
        lease(L1),
        0,
        "c-" + L1 + "|exited|137|" + L1 + "|WORKSPACE|" + H1 + "|" + mounts(work("ws-42"))
            + "|true\n",
        "");
    Inventory exited = awaitInventory(i -> stateOf(i, L1) == LeaseState.EXITED);
    assertEquals(
        new LeaseReport(L1, LeaseState.EXITED, H1, 137, null, 2L, 1L, 1L << 30, true),
        report(exited, L1));
    assertEquals(new NodeMemory(294L, 297L, null), exited.node());
  }

  @Test
  void anUnchangedPeakUnder64MiBSendsNoNewInventory() throws Exception {
    Path tree = Files.createDirectories(dockerDir.resolve("host-cgroup"));
    Path scope =
        Files.createDirectories(tree.resolve("system.slice/docker-c-" + L1 + ".scope"));
    Files.writeString(scope.resolve("memory.events"), "oom 0\noom_kill 0\n");
    Files.writeString(scope.resolve("memory.peak"), "1073741824\n");
    cgroups = new CgroupSettings(tree, dockerDir.resolve("no-vmstat"));
    seen(L1, "running", 0, "WORKSPACE", H1, mounts(work("ws-42")));
    estateThenAck(workspace(L1, H1));
    start();

    awaitInventory(i -> stateOf(i, L1) == LeaseState.RUNNING);
    awaitTrue(() -> runner.session().held().equals(List.of(L1)));
    Thread.sleep(500);
    int before = host.all(Inventory.class).size();
    Files.writeString(scope.resolve("memory.peak"), String.valueOf((1L << 30) + (63L << 20)));
    // Many looks (every 200 ms here), and a growth below the step: no frame for it.
    Thread.sleep(1_500);
    assertEquals(before, host.all(Inventory.class).size(), () -> host.all(Inventory.class).toString());
    Files.writeString(scope.resolve("memory.peak"), String.valueOf((1L << 30) + (64L << 20)));
    long grown = (1L << 30) + (64L << 20);
    awaitInventory(i -> leaseOf(i, L1, r -> Long.valueOf(grown).equals(r.memoryPeakBytes())));
    assertEquals(before + 1, host.all(Inventory.class).size(), "one frame for the step");
  }

  // ---- recreate on a spec hash --------------------------------------------------------------------

  @Test
  void aLeaseOfAnotherSpecHashIsStoppedRemovedAndRunAgainKeepingItsWorkVolume() throws Exception {
    seen(L1, "running", 0, "WORKSPACE", "an-older-hash", mounts(work("ws-42")));
    Map<String, String> labels =
        Map.of("qits.system.runner", TARGET, "qits.system.runner.work-key", "ws-42");
    docker.volume(work("ws-42"), labels);
    estateThenAck(workspace(L1, H1));
    start();

    awaitTrue(() -> !runsOf(lease(L1)).isEmpty());
    List<List<String>> calls = docker.calls().stream().map(FakeDocker::withoutConfig).toList();
    int stop = calls.indexOf(List.of("stop", lease(L1)));
    int removed = calls.indexOf(List.of("rm", "-f", lease(L1)));
    int run = calls.indexOf(runOf(lease(L1)));
    assertTrue(stop >= 0 && removed > stop && run > removed, "stop, rm, run: " + calls);
    assertTrue(runOf(lease(L1)).contains("qits.system.runner.spec-hash=" + H1));
    assertEquals(labels, docker.volumes().get(work("ws-42")), "the volume is the work; it stays");
    assertTrue(docker.calls("volume").stream().noneMatch(c -> c.contains("rm")), calls::toString);
  }

  // ---- remove on ENDED, and on absence ----------------------------------------------------------

  @Test
  void anEndedLeaseIsStoppedAndRemovedItsWorkVolumeKeptAndReportedEnded() throws Exception {
    seen(L1, "running", 0, "WORKSPACE", H1, mounts(work("ws-42")));
    docker.volume(
        work("ws-42"), Map.of("qits.system.runner", TARGET, "qits.system.runner.work-key", "ws-42"));
    estateThenAck(workspace(L1, H1));
    start();
    awaitTrue(() -> runner.session().held().equals(List.of(L1)));

    // Once removed, docker's inspect of the name answers nothing.
    host.send(
        new Estate(
            List.of(
                new LeaseEntry(L1, SlotType.WORKSPACE, DesiredState.ENDED, null, null, "ws-42", null)),
            List.of()));
    awaitCall(c -> has(c, List.of("rm", "-f", lease(L1))));
    docker.answerFor("inspect", lease(L1), 0, "", "");
    host.send(
        new Estate(
            List.of(
                new LeaseEntry(L1, SlotType.WORKSPACE, DesiredState.ENDED, null, null, "ws-42", null)),
            List.of()));
    Inventory inventory =
        awaitInventory(i -> i.leases().stream().anyMatch(r -> r.state() == LeaseState.ENDED));
    assertEquals(new LeaseReport(L1, LeaseState.ENDED, null, null, null), report(inventory, L1));
    awaitTrue(() -> runner.session().held().isEmpty());
    List<List<String>> calls = docker.calls().stream().map(FakeDocker::withoutConfig).toList();
    assertTrue(calls.indexOf(List.of("stop", lease(L1))) < calls.indexOf(List.of("rm", "-f", lease(L1))));
    assertTrue(docker.volumes().containsKey(work("ws-42")), "kept until a purge names it");
    assertEquals(List.of(work("ws-42")), inventory.volumes());
  }

  @Test
  void aHeldLeaseTheEstateNoLongerListsIsStoppedAndRemovedOnlyAfterAnEstate() throws Exception {
    seen(L1, "running", 0, "WORKSPACE", H1, mounts(work("ws-42")));
    estateThenAck();
    start();

    // FakeDocker records a call before it acts on its state, so the removal is awaited on the state
    // itself, not on the recorded rm.
    awaitTrue(() -> !docker.containers().containsKey(lease(L1)));
    List<List<String>> calls = docker.calls().stream().map(FakeDocker::withoutConfig).toList();
    assertTrue(calls.contains(List.of("rm", "-f", lease(L1))), calls::toString);
    int stop = calls.indexOf(List.of("stop", "c-" + L1));
    assertTrue(
        stop >= 0 && stop < calls.indexOf(List.of("rm", "-f", lease(L1))),
        "stopped before the reconcile: " + calls);
  }

  // ---- adopt ------------------------------------------------------------------------------------

  @Test
  void anAdoptingLeaseRemovesTheRetiredContainerAndMountsItsVolumeAsTheWorkVolume()
      throws Exception {
    docker.container(OLD_WS, Map.of("qits.workspaces.runner", "0f0e0d0c-old"));
    docker.answerFor("inspect", OLD_WS, 0, "old-container-id\n", "");
    docker.volume(OLD_WS, Map.of("qits.workspaces.runner", "0f0e0d0c-old"));
    docker.answerFor("volume-inspect", OLD_WS, 0, OLD_WS + "\n", "");
    LeaseEntry adopting =
        new LeaseEntry(
            L1,
            SlotType.WORKSPACE,
            DesiredState.RUNNING,
            spec(WS_IMAGE, env()),
            H1,
            "ws-42",
            new Adopt(OLD_WS, OLD_WS));
    estateThenAck(adopting);
    start();

    awaitTrue(() -> !runsOf(lease(L1)).isEmpty());
    List<List<String>> calls = docker.calls().stream().map(FakeDocker::withoutConfig).toList();
    int stop = calls.indexOf(List.of("stop", OLD_WS));
    int rm = calls.indexOf(List.of("rm", "-f", OLD_WS));
    int run = calls.indexOf(runOf(lease(L1)));
    assertTrue(stop >= 0 && rm > stop && run > rm, "stop, rm the retired container, run: " + calls);
    List<String> argv = runOf(lease(L1));
    assertTrue(argv.contains(OLD_WS + ":/workspace"), argv::toString);
    assertTrue(argv.contains("qits.system.runner.mounts=" + mounts(OLD_WS)), argv::toString);
    assertFalse(docker.volumes().containsKey(work("ws-42")), "no work volume of its own");
    assertEquals(
        Map.of("qits.workspaces.runner", "0f0e0d0c-old"),
        docker.volumes().get(OLD_WS),
        "the adopted volume is mounted, never created, relabelled or removed");
    assertFalse(docker.containers().containsKey(OLD_WS));
  }

  @Test
  void anAdoptOfANameOutsideTheRetiredRunnersPatternsFailsTheLeaseAndTouchesNothing()
      throws Exception {
    docker.container("postgres", Map.of());
    LeaseEntry adopting =
        new LeaseEntry(
            L1,
            SlotType.WORKSPACE,
            DesiredState.RUNNING,
            spec(WS_IMAGE, env()),
            H1,
            "ws-42",
            new Adopt("postgres", "pgdata"));
    estateThenAck(adopting);
    start();

    Inventory inventory =
        awaitInventory(i -> i.leases().stream().anyMatch(r -> r.state() == LeaseState.FAILED));
    assertEquals(
        new LeaseReport(L1, LeaseState.FAILED, null, null, "adopt name not allowed"),
        report(inventory, L1));
    assertTrue(docker.containers().containsKey("postgres"));
    assertFalse(touched("stop", "postgres") || touched("rm", "postgres"), docker.calls()::toString);
    assertTrue(runsOf(lease(L1)).isEmpty());
  }

  // ---- refusals ---------------------------------------------------------------------------------

  @Test
  void aCiLeaseStartsTheBuildPlaneThenRunsSandboxedOnBothNetworksWithItsCachesOnly()
      throws Exception {
    docker.answerFor("network-inspect", BUILD_NET, 1, "", "Error: No such network: " + BUILD_NET);
    docker.answerFor("inspect", BUILDER, 1, "", "Error: No such object: " + BUILDER);
    estateThenAck(ci(L1, H1));
    start();

    awaitTrue(() -> !runsOf(lease(L1)).isEmpty());
    awaitCall(calls -> has(calls, List.of("network", "connect", BUILD_NET, lease(L1))));
    List<String> run = runOf(lease(L1));
    assertEquals(List.of("run", "-d", "--init", "--name", lease(L1)), run.subList(0, 5));
    assertEquals("bridge", run.get(run.indexOf("--network") + 1));
    assertTrue(run.contains("--security-opt=no-new-privileges"), run::toString);
    assertTrue(run.contains("--cap-drop=ALL"), run::toString);
    assertFalse(run.contains("--restart"), "restart: no");
    assertTrue(run.contains("BUILDKIT_HOST=tcp://" + BUILDER + ":1234"), run::toString);
    assertTrue(run.contains("qits.system.runner.slot-type=CI"), run::toString);
    assertTrue(run.contains(CI_GIT + ":/cache/git"), run::toString);
    assertTrue(run.contains(CI_M2 + ":/caches/m2"), run::toString);
    assertTrue(run.contains(CI_PNPM + ":/caches/pnpm"), run::toString);
    assertTrue(run.contains(CI_DAEMON + ":/caches/ci-daemon"), run::toString);
    for (String never : List.of(DOT_CLAUDE, M2, PNPM, "/claude-home", "/workspace")) {
      assertTrue(run.stream().noneMatch(e -> e.contains(never)), never + " in " + run);
    }
    assertFalse(run.contains("CLAUDE_CONFIG_DIR=/claude-home/.claude"));
    for (String cache : List.of(CI_GIT, CI_M2, CI_PNPM, CI_DAEMON)) {
      assertEquals(Map.of("qits.system.runner", TARGET), docker.volumes().get(cache), cache);
    }
    List<List<String>> calls = docker.calls();
    List<String> builder =
        calls.stream()
            .filter(c -> c.size() > 4 && c.get(0).equals("run") && c.contains(BUILDER))
            .findFirst()
            .orElseThrow(() -> new AssertionError(calls));
    assertTrue(calls.indexOf(builder) < calls.indexOf(run), "the builder first: " + calls);
    assertTrue(builder.contains("--privileged"));
    assertTrue(builder.contains(BUILD_NET));
    assertTrue(
        calls.stream().flatMap(List::stream).noneMatch(e -> e.startsWith("qits-buildkitd")),
        "never the platform builder");
    docker.answerFor(
        "inspect", lease(L1), 0, "c-" + L1 + "|running|0|" + L1 + "|CI|" + H1 + "|" + ciMounts()
            + "\n", "");
    host.send(new Estate(List.of(ci(L1, H1)), List.of()));
    Inventory inventory =
        awaitInventory(i -> i.leases().stream().anyMatch(r -> r.state() == LeaseState.RUNNING));
    assertEquals(LeaseState.RUNNING, report(inventory, L1).state());
    awaitTrue(() -> runner.session().held().equals(List.of(L1)));
    assertEquals(1, runsOf(lease(L1)).size(), "the same spec and mounts: left alone");
  }

  @Test
  void aCiLeaseWhoseBuildPlaneCannotStartFailsAndRunsNothing() throws Exception {
    docker.answerFor("network-inspect", BUILD_NET, 0, "bridge\n", "");
    docker.answerFor("inspect", BUILDER, 0, "<no value>|running\n", "");
    estateThenAck(ci(L1, H1));
    start();

    Inventory inventory =
        awaitInventory(i -> i.leases().stream().anyMatch(r -> r.state() == LeaseState.FAILED));
    assertTrue(report(inventory, L1).detail().contains("not this runner's builder"));
    assertTrue(runsOf(lease(L1)).isEmpty());
  }

  // ---- pulls off the converge lock (qits-1543) ---------------------------------------------------

  @Test
  void aLeaseWhoseImageIsMissingIsStartingWhileItPullsAndAnEndBesideItIsProcessedAtOnce()
      throws Exception {
    missing(WS_NEXT);
    docker.answerFor("pull", WS_NEXT, 0, "", "");
    docker.hang("pull+" + safe(WS_NEXT), 6);
    seen(L2, "running", 0, "CI", H1, ciMounts());
    LeaseEntry ended = new LeaseEntry(L2, SlotType.CI, DesiredState.ENDED, null, H1, null, null);
    estateThenAck(workspace(L1, H1, "ws-42", WS_NEXT), ended);
    start();

    // The end comes after the pulling lease in the estate, and is done while the pull still hangs.
    awaitCall(calls -> has(calls, List.of("rm", "-f", lease(L2))));
    Inventory inventory =
        awaitInventory(
            i -> stateOf(i, L1) == LeaseState.STARTING && stateOf(i, L2) == LeaseState.ENDED);
    LeaseReport starting = report(inventory, L1);
    assertEquals(LeaseState.STARTING, starting.state());
    assertTrue(
        starting.detail().matches("pulling " + Pattern.quote(WS_NEXT) + " since \\S+Z"),
        starting.detail());
    assertEquals(
        List.of(starting.detail()),
        host.all(Inventory.class).stream()
            .map(i -> report(i, L1).detail())
            .filter(d -> d != null && d.startsWith("pulling "))
            .distinct()
            .toList(),
        "the same words in every inventory while the pull runs");
    awaitTrue(() -> docker.calls("pull").stream().anyMatch(c -> c.contains(WS_NEXT)));
    assertTrue(runsOf(lease(L1)).isEmpty(), "the convergence did not wait for the pull");

    // The pull lands: its end converges the estate again, with no new estate from the host.
    docker.answerFor("image-inspect", WS_NEXT, 0, "sha256:next\n", "");
    awaitTrue(() -> !runsOf(lease(L1)).isEmpty());
    assertEquals(WS_NEXT, runOf(lease(L1)).getLast());
  }

  @Test
  void twoLeasesOfOneMissingImageShareOnePullAndBothStartWhenItLands() throws Exception {
    missing(WS_NEXT);
    docker.answerFor("pull", WS_NEXT, 0, "", "");
    docker.hang("pull+" + safe(WS_NEXT), 2);
    estateThenAck(workspace(L1, H1, "ws-42", WS_NEXT), workspace(L2, H1, "ws-43", WS_NEXT));
    start();

    awaitTrue(() -> docker.calls("pull").stream().anyMatch(c -> c.contains(WS_NEXT)));
    awaitInventory(
        i -> stateOf(i, L1) == LeaseState.STARTING && stateOf(i, L2) == LeaseState.STARTING);
    assertTrue(runsOf(lease(L1)).isEmpty() && runsOf(lease(L2)).isEmpty(), "both wait");
    docker.answerFor("image-inspect", WS_NEXT, 0, "sha256:next\n", "");
    awaitTrue(() -> !runsOf(lease(L1)).isEmpty() && !runsOf(lease(L2)).isEmpty());
    assertEquals(
        1,
        docker.calls("pull").stream().filter(c -> c.contains(WS_NEXT)).count(),
        docker.calls()::toString);
  }

  @Test
  void aFailedPullFailsTheLeaseWithDockersWords() throws Exception {
    missing(WS_NEXT);
    docker.answerFor("pull", WS_NEXT, 1, "", "Error response from daemon: manifest unknown");
    estateThenAck(workspace(L1, H1, "ws-42", WS_NEXT));
    start();

    Inventory inventory = awaitInventory(i -> stateOf(i, L1) == LeaseState.FAILED);
    String detail = report(inventory, L1).detail();
    assertTrue(detail.startsWith("could not pull " + WS_NEXT + ": "), detail);
    assertTrue(detail.contains("manifest unknown"), detail);
    assertTrue(runsOf(lease(L1)).isEmpty());
  }

  @Test
  void aPullThatDoesNotAnswerWithinItsDeadlineFailsTheLeaseSayingSo() throws Exception {
    extraEnv.put(IDENTITY.env(RunnerSettings.PULL_TIMEOUT), "1");
    missing(WS_NEXT);
    docker.answerFor("pull", WS_NEXT, 0, "", "");
    docker.hang("pull+" + safe(WS_NEXT), 4);
    estateThenAck(workspace(L1, H1, "ws-42", WS_NEXT));
    start();

    Inventory inventory = awaitInventory(i -> stateOf(i, L1) == LeaseState.FAILED);
    String detail = report(inventory, L1).detail();
    assertTrue(detail.startsWith("could not pull " + WS_NEXT + ": "), detail);
    assertTrue(detail.contains("did not answer within 1s"), detail);
    assertTrue(runsOf(lease(L1)).isEmpty());
  }

  // ---- warm images (qits-1692) ------------------------------------------------------------------

  @Test
  void aMissingWarmImageStartsExactlyOnePullAndTheNextEstateIsConvergedWhileItRuns()
      throws Exception {
    missing(WARM);
    docker.answerFor("pull", WARM, 0, "", "");
    docker.hang("pull+" + safe(WARM), 6);
    seen(L1, "running", 0, "WORKSPACE", H1, mounts(work("ws-42")));
    seen(L2, "running", 0, "WORKSPACE", H1, mounts(work("ws-43")));
    estateThenAck(List.of(WARM), workspace(L1, H1), workspace(L2, H1, "ws-43", WS_IMAGE));
    start();

    awaitTrue(() -> pullsOf(WARM) == 1);
    awaitInventory(i -> stateOf(i, L1) == LeaseState.RUNNING);
    // The estate's convergence returned at once: the next one ends a lease while the pull hangs.
    LeaseEntry ended =
        new LeaseEntry(L2, SlotType.WORKSPACE, DesiredState.ENDED, null, H1, "ws-43", null);
    host.send(new Estate(List.of(workspace(L1, H1), ended), List.of(), List.of(WARM)));
    awaitCall(calls -> has(calls, List.of("rm", "-f", lease(L2))));
    Thread.sleep(300);
    assertEquals(1, pullsOf(WARM), "one pull per reference: " + docker.calls());
    assertTrue(runsOf(lease(L1)).isEmpty(), "a running lease is left alone");
  }

  @Test
  void aLeaseNeverWaitsBehindAWarmPullAndSharesTheOneOfItsOwnImage() throws Exception {
    for (String image : List.of(WARM, WARM2, WS_NEXT)) {
      missing(image);
      docker.answerFor("pull", image, 0, "", "");
      docker.hang("pull+" + safe(image), 8);
    }
    estateThenAck(List.of(WARM, WARM2));
    start();

    // The warm pool pulls one image at a time: WARM hangs, WARM2 waits its turn.
    awaitTrue(() -> pullsOf(WARM) == 1);
    Thread.sleep(300);
    assertEquals(0, pullsOf(WARM2), "warm pulls run one at a time: " + docker.calls());

    // Leases of a fresh image, of the queued warm image and of the in-flight one.
    String l3 = "0b7c1d2e-3f40-4a5b-8c6d-7e8f90a1b2c3";
    host.send(
        new Estate(
            List.of(
                workspace(L1, H1, "ws-42", WS_NEXT),
                workspace(L2, H1, "ws-43", WARM2),
                workspace(l3, H1, "ws-44", WARM)),
            List.of(),
            List.of(WARM, WARM2)));
    long sent = System.nanoTime();
    awaitTrue(() -> pullsOf(WS_NEXT) == 1 && pullsOf(WARM2) == 1);
    assertTrue(
        System.nanoTime() - sent < TimeUnit.SECONDS.toNanos(5),
        "the lease pulls started while the warm pull still hangs");
    awaitInventory(
        i ->
            stateOf(i, L1) == LeaseState.STARTING
                && stateOf(i, L2) == LeaseState.STARTING
                && stateOf(i, l3) == LeaseState.STARTING);
    assertEquals(1, pullsOf(WARM), "the lease shares the in-flight warm pull: " + docker.calls());
    assertEquals(1, pullsOf(WARM2), "a queued warm pull is the lease's now: " + docker.calls());
  }

  @Test
  void theKeptEstatesWarmImagesArePulledAgainAtTheAckOfANewConnection() throws Exception {
    missing(WARM);
    docker.answerFor("pull", WARM, 1, "", "Error response from daemon: connection refused");
    AtomicInteger hellos = new AtomicInteger();
    host.script =
        (h, message) -> {
          if (message instanceof Hello) {
            h.send(new Ack(IDENTITY.capabilityVersion(), 2, null, ACK.extensions()));
            if (hellos.incrementAndGet() == 1) {
              h.send(new Estate(List.of(), List.of(), List.of(WARM)));
            }
          }
        };
    start();
    awaitTrue(() -> pullsOf(WARM) == 1);
    // Settled, its failure kept: an Ack ahead of that would clear nothing and find it running.
    awaitTrue(() -> domain.pullingSince(WARM).isEmpty());

    // The reconnect's Ack warms the kept estate's images, before any estate on the new socket.
    docker.answerFor("pull", WARM, 0, "", "");
    host.closeSocket(1001, "going away");
    awaitTrue(() -> hellos.get() == 2);
    awaitTrue(() -> pullsOf(WARM) == 2);
  }

  @Test
  void aWarmImageAlreadyOnTheNodeStartsNoPull() throws Exception {
    docker.answerFor("image-inspect", WARM, 0, "sha256:warm\n", "");
    estateThenAck(List.of(WARM));
    start();

    awaitTrue(() -> docker.calls("image").stream().anyMatch(c -> c.contains(WARM)));
    Thread.sleep(300);
    assertEquals(0, pullsOf(WARM), docker.calls()::toString);
  }

  @Test
  void aFailedWarmPullFailsNoLeaseAndIsTriedAgainOnlyFromTheNextEstate() throws Exception {
    missing(WARM);
    docker.answerFor("pull", WARM, 1, "", "Error response from daemon: manifest unknown");
    seen(L1, "running", 0, "WORKSPACE", H1, mounts(work("ws-42")));
    estateThenAck(List.of(WARM), workspace(L1, H1));
    start();

    awaitTrue(() -> pullsOf(WARM) == 1);
    Thread.sleep(500);
    assertEquals(1, pullsOf(WARM), "no retry loop without an estate");
    host.send(new Estate(List.of(workspace(L1, H1)), List.of(), List.of(WARM)));
    awaitTrue(() -> pullsOf(WARM) == 2);
    awaitInventory(i -> stateOf(i, L1) == LeaseState.RUNNING);
    assertTrue(
        host.all(Inventory.class).stream()
            .flatMap(i -> i.leases().stream())
            .noneMatch(r -> r.state() == LeaseState.FAILED),
        "a warm pull fails no lease");
  }

  @Test
  void theOldLeaseImagesPassKeepsTheEstatesWarmImages() throws Exception {
    String old = REGISTRY + "workspace:2026.1001.1";
    docker.answerFor("image-inspect", WS_IMAGE, 0, "sha256:aaaa\n", "");
    docker.answerFor("image-inspect", WARM, 0, "sha256:bbbb\n", "");
    docker.answer(
        "image-ls",
        0,
        WS_IMAGE + "|sha256:aaaa\n" + WARM + "|sha256:bbbb\n" + old + "|sha256:cccc\n",
        "");
    estateThenAck(List.of(WARM));
    start();

    awaitTrue(() -> domain.currentImages().contains(WARM));
    new LeaseImagesPass(
            new RunnerDocker(IDENTITY, docker.binary(), Duration.ofSeconds(10)),
            domain::currentImages)
        .run(
            new HousekeepingContext(
                IDENTITY,
                TARGET,
                null,
                null,
                Duration.ofSeconds(10),
                state,
                Clock.systemUTC(),
                Logger.getLogger(EstateReconcileTest.class)));
    List<List<String>> removed =
        docker.calls("image").stream().filter(c -> c.get(1).equals("rm")).toList();
    assertEquals(List.of(List.of("image", "rm", old)), removed, docker.calls()::toString);
  }

  @Test
  void aCiLeaseIsStartingWhileTheBuilderImagePullsThenRunsOnceItLands() throws Exception {
    docker.answerFor("network-inspect", BUILD_NET, 1, "", "Error: No such network: " + BUILD_NET);
    docker.answerFor("inspect", BUILDER, 1, "", "Error: No such object: " + BUILDER);
    missing(BuildkitSettings.DEFAULT_IMAGE);
    docker.answerFor("pull", BuildkitSettings.DEFAULT_IMAGE, 0, "", "");
    docker.hang("pull+" + safe(BuildkitSettings.DEFAULT_IMAGE), 3);
    estateThenAck(ci(L1, H1));
    start();

    awaitInventory(i -> stateOf(i, L1) == LeaseState.STARTING);
    awaitCall(calls -> has(calls, List.of("pull", BuildkitSettings.DEFAULT_IMAGE)));
    assertTrue(runsOf(lease(L1)).isEmpty(), "no CI lease runs before its builder");
    assertTrue(runsOf(BUILDER).isEmpty(), "the builder waits for its image");

    docker.answerFor("image-inspect", BuildkitSettings.DEFAULT_IMAGE, 0, "sha256:bk\n", "");
    awaitTrue(() -> !runsOf(lease(L1)).isEmpty());
    assertFalse(runsOf(BUILDER).isEmpty());
    assertEquals(
        1,
        docker.calls("pull").stream().filter(c -> c.contains(BuildkitSettings.DEFAULT_IMAGE)).count(),
        "the Ack's pre-pull and the lease share one pull: " + docker.calls());
  }

  @Test
  void anEndedCiLeaseIsRemovedAndTheBuilderStays() throws Exception {
    seen(L1, "running", 0, "CI", H1, ciMounts());
    LeaseEntry ended =
        new LeaseEntry(L1, SlotType.CI, DesiredState.ENDED, null, H1, null, null);
    estateThenAck(ended);
    start();

    awaitCall(calls -> has(calls, List.of("rm", "-f", lease(L1))));
    assertFalse(touched("rm", BUILDER));
    assertFalse(touched("stop", BUILDER));
  }

  @Test
  void aSpecEnvCollidingWithAPolicyKeyFailsTheLeaseNamingTheKey() throws Exception {
    Map<String, String> env = env();
    env.put("HOME", "/root");
    estateThenAck(
        new LeaseEntry(
            L1, SlotType.DESK, DesiredState.RUNNING, spec(DESK_IMAGE, env), H1, "desk-" + DESK_ID, null));
    start();

    Inventory inventory =
        awaitInventory(i -> i.leases().stream().anyMatch(r -> r.state() == LeaseState.FAILED));
    LeaseReport failed = report(inventory, L1);
    assertEquals(LeaseState.FAILED, failed.state());
    assertTrue(failed.detail().contains("HOME"), failed.detail());
    assertTrue(runsOf(lease(L1)).isEmpty());
  }

  // ---- purges -----------------------------------------------------------------------------------

  @Test
  void aPurgeRemovesAnOwnWorkVolumeOrARetiredOneAndNothingElse() throws Exception {
    docker.volume(work("ws-9"), Map.of("qits.system.runner", TARGET));
    docker.volume(OLD_WS, Map.of());
    docker.volume("qits_shared_dot_claude", Map.of());
    docker.volume("qits-system-runner-work-ws-9-ffffffff", Map.of());
    host.script =
        (h, message) -> {
          if (message instanceof Hello) {
            h.send(new Ack(IDENTITY.capabilityVersion(), 2, null, ACK.extensions()));
            h.send(
                new Estate(
                    List.of(),
                    List.of(
                        work("ws-9"),
                        OLD_WS,
                        "qits_shared_dot_claude",
                        "qits-system-runner-work-ws-9-ffffffff")));
          }
        };
    start();

    awaitTrue(() -> !docker.volumes().containsKey(work("ws-9")));
    awaitTrue(() -> !docker.volumes().containsKey(OLD_WS));
    assertTrue(docker.volumes().containsKey("qits_shared_dot_claude"));
    assertTrue(docker.volumes().containsKey("qits-system-runner-work-ws-9-ffffffff"), "another target's");
  }

  // ---- login and health -------------------------------------------------------------------------

  @Test
  void theLoginIsProbedAtTheAckWithTheProbeImageAndTheHealthCheckReportsIt() throws Exception {
    estateThenAck();
    start();

    LoginState login = host.await(LoginState.class);
    assertEquals(LoginPresence.PRESENT, login.claude());
    assertEquals(LoginPresence.PRESENT, login.kimi());
    assertEquals(DOT_CLAUDE, login.volume());
    assertTrue(
        docker.calls("run").stream().anyMatch(c -> c.contains(WS_IMAGE) && c.contains("claude")));

    host.send(new HealthCheck("req-1"));
    HealthChecked checked = host.await(HealthChecked.class);
    assertEquals("req-1", checked.requestId());
    assertEquals(
        List.of("docker", "nodeInventory", "login", "session", "buildkit", "network"),
        checked.checks().stream().map(c -> c.name()).toList());
    assertTrue(checked.checks().get(2).ok(), "login reports, never fails");
    assertEquals(DOT_CLAUDE, checked.checks().get(2).data().get("volume"));
  }

  @Test
  void aDockerFrameIsAnsweredWithExactlyOneDockerResultOffTheSocketsThread() throws Exception {
    estateThenAck();
    start();
    host.await(LoginState.class);

    UUID id = UUID.randomUUID();
    host.send(new Docker(id, DockerOp.INSPECT_CONTAINER, "postgres", null, null, null, null));
    DockerResult result = host.await(DockerResult.class);
    assertEquals(
        DockerResult.of(id, DockerStatus.NOT_FOUND, "no container postgres of this target"),
        result);
    assertTrue(
        docker.calls().stream().noneMatch(c -> c.contains("postgres")), "never asked docker");
  }

  /**
   * qits-1593 end to end: a {@code signIn} forks the login through the real process seam — the
   * fake docker prints kimi's measured transcript and exits 0 — and the host reads its progress,
   * then a fresh {@code loginState}.
   */
  @Test
  void aSignInRunsTheLoginOnTheTargetAndRelaysItsProgressThenTheLoginState() throws Exception {
    docker.answerFor(
        "run",
        "login",
        0,
        "Opening browser for Kimi device login:"
            + " https://www.kimi.com/code/authorize_device?user_code=ABCD-1234\n"
            + "If the browser did not open, paste the URL above and enter code: ABCD-1234\n"
            + "Code expires in 1800s.\nWaiting for authorization to complete...\n",
        "");
    estateThenAck();
    start();
    host.await(LoginState.class);

    host.send(new SignIn("s-1", "kimi"));

    SignInProgress done = host.await(SignInProgress.class, 3);
    assertEquals(
        List.of(SignInState.STARTING, SignInState.AWAITING_BROWSER, SignInState.SUCCEEDED),
        host.all(SignInProgress.class).stream().map(SignInProgress::state).toList());
    SignInProgress awaiting = host.all(SignInProgress.class).get(1);
    assertEquals(
        "https://www.kimi.com/code/authorize_device?user_code=ABCD-1234", awaiting.url());
    assertEquals("ABCD-1234", awaiting.userCode());
    assertEquals("s-1", done.signInId());
    host.await(LoginState.class, 2);
    List<String> login = runOf("qits-system-runner-sign-in-kimi-r1");
    assertEquals(List.of("run", "--rm", "-i", "--name"), login.subList(0, 4));
    assertFalse(login.contains("--label"), "the reconcile must never take a sign-in");
  }

  /**
   * qits-1576: a host whose user namespace maps fewer uids/gids than the full 32-bit space is
   * healthy. There is no {@code idRange} check to fail it, and the hello carries no {@code idRange}
   * capability to feed one.
   */
  @Test
  void aNarrowUserNamespaceNeverFailsTheHealthCheckBecauseThereIsNoIdRangeCheck() throws Exception {
    estateThenAck();
    start();

    Hello hello = host.await(Hello.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> capabilities =
        (Map<String, Object>) CODEC.encode(hello).get("capabilities");
    assertFalse(capabilities.containsKey("idRange"), capabilities.toString());

    host.send(new HealthCheck("req-1576"));
    HealthChecked checked = host.await(HealthChecked.class);
    assertEquals("req-1576", checked.requestId());
    assertTrue(
        checked.checks().stream().noneMatch(c -> c.name().equals("idRange")),
        checked.checks().toString());
    // docker and nodeInventory are the toolkit's and read a docker the fake does not script; every
    // check this runner registers itself is ok, so nothing the domain adds can fail a narrow host.
    assertTrue(
        checked.checks().stream()
            .filter(c -> List.of("login", "session", "buildkit", "network").contains(c.name()))
            .allMatch(c -> c.ok()),
        checked.checks().toString());
  }

  @Test
  void beforeAnyEstateNothingIsRemoved() throws Exception {
    seen(L1, "running", 0, "WORKSPACE", H1, mounts(work("ws-42")));
    host.script =
        (h, message) -> {
          if (message instanceof Hello) {
            h.send(new Ack(IDENTITY.capabilityVersion(), 2, null, ACK.extensions()));
          }
        };
    start();

    awaitInventory(i -> i.leases().stream().anyMatch(r -> r.leaseId().equals(L1)));
    assertFalse(touched("rm", lease(L1)));
    assertFalse(touched("stop", lease(L1)));
  }

  // ---- the runner, the host's scripts and the node -------------------------------------------------

  private void start() throws Exception {
    Map<String, String> env = new HashMap<>();
    env.put(IDENTITY.env(RunnerSettings.URL), host.base());
    env.put(IDENTITY.env(RunnerSettings.ID), TARGET);
    env.put(IDENTITY.env(RunnerSettings.REGISTRATION_TOKEN), "token-1");
    env.put(IDENTITY.env(RunnerSettings.STATE_DIR), state.toString());
    env.put(IDENTITY.env(RunnerSettings.SLOTS), "2");
    env.put(IDENTITY.env(RunnerSettings.DOCKER_BINARY), docker.binary());
    env.put(IDENTITY.env(RunnerSettings.DOCKER_TIMEOUT), "10");
    env.putAll(extraEnv);
    RunnerSettings settings =
        RunnerSettings.parse(IDENTITY, env::get)
            .withTiming(new RunnerSettings.Timing(10_000, 50, 200, 5_000, 300_000, 5));
    domain =
        new SystemRunnerDomain(
            settings,
            ExternalVolumes.parse(IDENTITY, env::get),
            BuildkitSettings.DEFAULTS,
            cgroups,
            new SystemRunnerDomain.Timing(
                Duration.ofMillis(200), Duration.ofSeconds(60), Duration.ofSeconds(60)),
            Clock.systemUTC(),
            SignInProcesses.FORKED);
    runner =
        new RunnerRuntime<>(
            vertx,
            IDENTITY,
            settings,
            domain,
            new HostFacts(true, "amd64", "linux"),
            Telemetry.off(),
            new RunnerRuntime.NodeOptions(
                Optional.of(SELF),
                new SelfUpdate.Settings(100, 400, 3_000, 400, 50),
                new Decommission.Settings(200, 20, 2),
                new Housekeeping.Settings(TimeUnit.HOURS.toMillis(6)),
                Clock.systemUTC()));
    exit = CompletableFuture.supplyAsync(runner::run);
  }

  /** Ack every Hello with the slot and probe images, then the estate right after it. */
  private void estateThenAck(LeaseEntry... leases) {
    host.script =
        (h, message) -> {
          if (message instanceof Hello) {
            h.send(new Ack(IDENTITY.capabilityVersion(), 2, null, ACK.extensions()));
            h.send(new Estate(List.of(leases), List.of()));
          }
        };
  }

  /** {@link #estateThenAck(LeaseEntry...)}, the estate naming these images to keep warm. */
  private void estateThenAck(List<String> warm, LeaseEntry... leases) {
    host.script =
        (h, message) -> {
          if (message instanceof Hello) {
            h.send(new Ack(IDENTITY.capabilityVersion(), 2, null, ACK.extensions()));
            h.send(new Estate(List.of(leases), List.of(), warm));
          }
        };
  }

  private long pullsOf(String image) {
    return docker.calls("pull").stream().filter(c -> c.contains(image)).count();
  }

  private static Map<String, String> env() {
    Map<String, String> env = new LinkedHashMap<>();
    env.put("TZ", "UTC");
    env.put("QITS_TOKEN", "qits_tok_LEASE");
    return env;
  }

  private static LeaseSpec spec(String image, Map<String, String> env) {
    return new LeaseSpec(image, env, "1000", new Limits("4g", "4g", 512L, "2", 800));
  }

  private static LeaseEntry workspace(String leaseId, String hash) {
    return new LeaseEntry(
        leaseId, SlotType.WORKSPACE, DesiredState.RUNNING, spec(WS_IMAGE, env()), hash, "ws-42", null);
  }

  private static LeaseEntry workspace(String leaseId, String hash, String workKey, String image) {
    return new LeaseEntry(
        leaseId, SlotType.WORKSPACE, DesiredState.RUNNING, spec(image, env()), hash, workKey, null);
  }

  private static LeaseEntry desk(String leaseId, String hash) {
    return new LeaseEntry(
        leaseId,
        SlotType.DESK,
        DesiredState.RUNNING,
        spec(DESK_IMAGE, env()),
        hash,
        "desk-" + DESK_ID,
        null);
  }

  private static LeaseEntry ci(String leaseId, String hash) {
    return new LeaseEntry(
        leaseId,
        SlotType.CI,
        DesiredState.RUNNING,
        new LeaseSpec(CI_IMAGE, env(), "1001", new Limits("4g", "4g", 512L, "2", 800)),
        hash,
        null,
        null);
  }

  private static String ciMounts() {
    return new TargetVolumes(IDENTITY, TARGET, ExternalVolumes.NONE)
        .mountsHash(SlotPolicy.CI, null);
  }

  private static String lease(String leaseId) {
    return "qits-system-runner-lease-" + leaseId + "-r1";
  }

  private static String work(String key) {
    return "qits-system-runner-work-" + key + "-r1";
  }

  private static String mounts(String workVolume) {
    return new TargetVolumes(IDENTITY, TARGET, ExternalVolumes.NONE).mountsHash(workVolume);
  }

  /** The lease's container on the node, as docker's inspect answers it from now on. */
  private void seen(
      String leaseId, String status, int exitCode, String slotType, String specHash, String mounts)
      throws Exception {
    Map<String, String> labels = new HashMap<>();
    labels.put("qits.system.runner", TARGET);
    labels.put("qits.system.runner.lease", leaseId);
    labels.put("qits.system.runner.slot-type", slotType);
    labels.put("qits.system.runner.spec-hash", specHash);
    labels.put("qits.system.runner.mounts", mounts);
    docker.container(lease(leaseId), labels);
    docker.answerFor(
        "inspect",
        lease(leaseId),
        0,
        "c-" + leaseId + "|" + status + "|" + exitCode + "|" + leaseId + "|" + slotType + "|"
            + specHash + "|" + mounts + "\n",
        "");
  }

  private Inventory awaitInventory(Predicate<Inventory> condition) throws InterruptedException {
    awaitTrue(() -> host.all(Inventory.class).stream().anyMatch(condition));
    List<Inventory> all = new ArrayList<>(host.all(Inventory.class));
    return all.stream().filter(condition).reduce((a, b) -> b).orElseThrow();
  }

  private static boolean leaseOf(
      Inventory inventory, String leaseId, Predicate<LeaseReport> condition) {
    return inventory.leases().stream()
        .anyMatch(r -> r.leaseId().equals(leaseId) && condition.test(r));
  }

  private static LeaseState stateOf(Inventory inventory, String leaseId) {
    return inventory.leases().stream()
        .filter(r -> r.leaseId().equals(leaseId))
        .map(LeaseReport::state)
        .findFirst()
        .orElse(null);
  }

  /** {@code image} is not on the node: docker's inspect of it says so. */
  private void missing(String image) throws Exception {
    docker.answerFor("image-inspect", image, 1, "", "Error: No such image: " + image);
  }

  /** FakeDocker's spelling of an object in an answer key. */
  private static String safe(String object) {
    return object.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  private static LeaseReport report(Inventory inventory, String leaseId) {
    return inventory.leases().stream()
        .filter(r -> r.leaseId().equals(leaseId))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no " + leaseId + " in " + inventory));
  }

  private List<List<String>> runsOf(String name) {
    return docker.calls("run").stream().filter(c -> c.contains(name)).toList();
  }

  private List<String> runOf(String name) {
    List<List<String>> runs = runsOf(name);
    assertFalse(runs.isEmpty(), () -> "no run of " + name + ": " + docker.calls());
    return runs.getFirst();
  }

  private boolean touched(String verb, String name) {
    return docker.calls(verb).stream().anyMatch(c -> c.contains(name));
  }

  private void awaitCall(Predicate<List<List<String>>> condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (!condition.test(docker.calls()) && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertTrue(condition.test(docker.calls()), () -> "docker calls: " + docker.calls());
  }

  private static boolean has(List<List<String>> calls, List<String> call) {
    return calls.stream().map(FakeDocker::withoutConfig).anyMatch(call::equals);
  }

  private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertTrue(condition.getAsBoolean(), "the condition never held");
  }
}
