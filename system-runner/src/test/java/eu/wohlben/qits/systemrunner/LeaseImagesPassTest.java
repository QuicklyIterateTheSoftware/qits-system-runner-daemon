package eu.wohlben.qits.systemrunner;

import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.runner.protocol.Hello;
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
import eu.wohlben.qits.systemrunner.protocol.Estate;
import eu.wohlben.qits.systemrunner.protocol.Inventory;
import eu.wohlben.qits.systemrunner.protocol.SlotImages;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerAck;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerCodec;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerMessage;
import io.vertx.core.Vertx;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link LeaseImagesPass} in the one window qits-2513 opened: after the first {@code Ack}, before
 * the first {@code estate}. Before qits-runner-javalib's Ack gate, the runtime ran the {@code Ack}
 * and the estate concurrently and this window barely existed; now domain frames are withheld until
 * {@link SystemRunnerDomain#onAck} returns, so a housekeeping pass — the toolkit starts its first
 * right after the {@code Ack} — can genuinely run with only the probe image known. {@link
 * SystemRunnerDomain#currentImages} must answer empty then, or the pass would read the probe
 * image's repository (the workspace image) as the whole truth and remove a tag the coming estate is
 * about to keep warm or run. The harness is {@link EstateReconcileTest}'s: a real {@link
 * RunnerRuntime} against {@link FakeRunnerHost} and {@link FakeDocker}.
 */
@EnabledOnOs(OS.LINUX)
class LeaseImagesPassTest {

  private static final RunnerIdentity IDENTITY = SystemRunnerIdentity.IDENTITY;

  private static final RunnerCodec<SystemRunnerMessage> CODEC =
      new RunnerCodec<>(IDENTITY.vocabulary(), new SystemRunnerCodec());

  private static final String SELF = "0123456789ab";
  private static final String TARGET = "r1";

  private static final String REGISTRY = "registry.example:5000/qits/";
  private static final String WS_IMAGE = REGISTRY + "workspace:2026.1023.1";
  private static final String OLD_TAG = REGISTRY + "workspace:2026.1001.1";

  private static final SystemRunnerAck ACK =
      new SystemRunnerAck(
          new SlotImages(REGISTRY + "ci-", REGISTRY + "workspace", REGISTRY + "project-agent"),
          WS_IMAGE,
          null);

  private final Vertx vertx = Vertx.vertx();
  private FakeRunnerHost host;
  private FakeDocker docker;
  private SystemRunnerDomain domain;
  private RunnerRuntime<SystemRunnerMessage> runner;
  private CompletableFuture<Integer> exit;

  private CgroupSettings cgroups;

  @TempDir Path state;
  @TempDir Path dockerDir;

  private Path resolvConf;

  @BeforeEach
  void setUp() throws Exception {
    host = new FakeRunnerHost(vertx, IDENTITY, CODEC);
    docker = new FakeDocker(dockerDir);
    docker.answerFor("run", "status", 0, "{\"loggedIn\": true}\n", "");
    docker.answerFor("run", LoginProbe.KIMI_CHECK, 0, "", "");
    docker.answer("info", 0, "ENGINE-ID-1|node-1\n", "");
    cgroups = new CgroupSettings(dockerDir.resolve("no-cgroup"), dockerDir.resolve("no-vmstat"));
    resolvConf = dockerDir.resolve("resolv.conf");
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

  @Test
  void noImageIsCurrentUntilTheFirstEstateArrivesSoTheOldTagSurvivesTheAckAloneButNotTheEstate()
      throws Exception {
    docker.answerFor("image-inspect", WS_IMAGE, 0, "sha256:aaaa\n", "");
    docker.answer(
        "image-ls", 0, WS_IMAGE + "|sha256:aaaa\n" + OLD_TAG + "|sha256:cccc\n", "");
    // The Ack alone, nothing sent after it: exactly the toolkit's window between the Ack and the
    // first estate (qits-2513), where a housekeeping pass may already run.
    host.script =
        (h, message) -> {
          if (message instanceof Hello) {
            h.send(new Ack(IDENTITY.capabilityVersion(), 2, null, ACK.extensions()));
          }
        };
    start();

    // sendInventory runs synchronously inside onAck, so an Inventory frame proves the Ack's keys
    // are already taken: currentImages still answers empty only because no estate has arrived.
    awaitTrue(() -> host.all(Inventory.class).size() >= 1);
    assertTrue(domain.currentImages().isEmpty(), domain.currentImages()::toString);

    runPass();
    assertTrue(
        docker.calls().stream().noneMatch(this::isRemoval),
        () -> "no removal before an estate: " + docker.calls());

    // Now the first estate arrives: the probe image is current, and the old tag of its repository
    // is not, so the pass removes it, exactly as before qits-2513. The runtime's own first pass may
    // run beside this one; either may be the one that removes it, so only the outcome is judged.
    host.send(new Estate(List.of(), List.of()));
    awaitTrue(() -> domain.currentImages().contains(WS_IMAGE));
    runPass();
    assertTrue(
        docker.calls().stream()
            .map(FakeDocker::withoutConfig)
            .anyMatch(c -> c.equals(List.of("image", "rm", OLD_TAG))),
        docker.calls()::toString);
  }

  private boolean isRemoval(List<String> call) {
    List<String> c = FakeDocker.withoutConfig(call);
    return c.size() > 1 && c.get(0).equals("image") && c.get(1).equals("rm");
  }

  private void runPass() {
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
                Logger.getLogger(LeaseImagesPassTest.class)));
  }

  private void start() throws Exception {
    Map<String, String> env = new HashMap<>();
    env.put(IDENTITY.env(RunnerSettings.URL), host.base());
    env.put(IDENTITY.env(RunnerSettings.ID), TARGET);
    env.put(IDENTITY.env(RunnerSettings.REGISTRATION_TOKEN), "token-1");
    env.put(IDENTITY.env(RunnerSettings.STATE_DIR), state.toString());
    env.put(IDENTITY.env(RunnerSettings.SLOTS), "2");
    env.put(IDENTITY.env(RunnerSettings.DOCKER_BINARY), docker.binary());
    env.put(IDENTITY.env(RunnerSettings.DOCKER_TIMEOUT), "10");
    RunnerSettings settings =
        RunnerSettings.parse(IDENTITY, env::get)
            .withTiming(new RunnerSettings.Timing(10_000, 50, 200, 5_000, 300_000, 5));
    domain =
        new SystemRunnerDomain(
            settings,
            NodeVolumeRecord.resolve(IDENTITY, settings, env::get),
            BuildkitSettings.DEFAULTS,
            cgroups,
            new SystemRunnerDomain.Timing(
                Duration.ofMillis(200), Duration.ofSeconds(60), Duration.ofSeconds(60)),
            Clock.systemUTC(),
            SignInProcesses.FORKED,
            resolvConf,
            NodeTunnel.NEVER);
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

  private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertTrue(condition.getAsBoolean(), "the condition never held");
  }
}
