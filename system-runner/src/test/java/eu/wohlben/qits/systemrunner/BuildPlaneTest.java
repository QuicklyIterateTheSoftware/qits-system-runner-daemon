package eu.wohlben.qits.systemrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.RunnerSettings;
import eu.wohlben.qits.runner.toolkit.health.RunnerHealthCheck;
import eu.wohlben.qits.runner.toolkit.testing.FakeDocker;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The build plane against {@link FakeDocker}: started lazily with its network and state volume,
 * left alone when it is somebody else's, replaced when its stamp moved; the toml and the mirrors'
 * merge; the {@code buildkit} and {@code network} checks; and its settings.
 */
@EnabledOnOs(OS.LINUX)
class BuildPlaneTest {

  private static final RunnerIdentity IDENTITY = SystemRunnerIdentity.IDENTITY;
  private static final String TARGET = "r1";
  private static final String BUILDER = "qits-system-runner-buildkitd-r1";
  private static final String NETWORK = "qits-system-runner-build-r1";
  private static final String STATE = "qits-system-runner-buildkitd-state-r1";

  @TempDir Path dir;
  private FakeDocker docker;
  private BuildPlane plane;

  @BeforeEach
  void setUp() throws Exception {
    docker = new FakeDocker(dir);
    plane =
        new BuildPlane(
            IDENTITY,
            TARGET,
            new RunnerDocker(IDENTITY, docker.binary(), Duration.ofSeconds(10)),
            BuildkitSettings.DEFAULTS,
            1);
  }

  // --- ensure ------------------------------------------------------------------------------------

  @Test
  void theFirstEnsureCreatesTheNetworkTheStateVolumeAndTheBuilder() throws Exception {
    docker.answerFor("network-inspect", NETWORK, 1, "", "Error: No such network: " + NETWORK);
    docker.answerFor("inspect", BUILDER, 1, "", "Error: No such object: " + BUILDER);
    docker.answerFor("image-inspect", "moby/buildkit:v0.33.0", 1, "", "Error: No such image");

    assertEquals(Optional.empty(), plane.ensure());

    assertTrue(
        has(
            List.of(
                "network", "create", "--driver", "bridge", "--label", "qits.system.runner=r1",
                NETWORK)),
        docker.calls()::toString);
    assertEquals(Map.of("qits.system.runner.buildkitd", "state"), docker.volumes().get(STATE));
    assertTrue(has(List.of("pull", "moby/buildkit:v0.33.0")), docker.calls()::toString);
    List<String> run =
        docker.calls("run").stream().filter(c -> c.contains(BUILDER)).findFirst().orElseThrow();
    assertTrue(run.contains("--privileged"));
    assertEquals(
        Map.of("qits.system.runner.buildkitd", plane.configuredStamp()),
        docker.containers().get(BUILDER));
    assertFalse(
        docker.calls().stream().flatMap(List::stream).anyMatch(e -> e.startsWith("qits-buildkitd")),
        "never the platform builder");
  }

  @Test
  void aRunningBuilderOfTheConfiguredStampIsLeftAlone() throws Exception {
    docker.answerFor("network-inspect", NETWORK, 0, "bridge\n", "");
    docker.answerFor("inspect", BUILDER, 0, plane.configuredStamp() + "|running\n", "");

    assertEquals(Optional.empty(), plane.ensure());

    assertTrue(docker.calls("run").isEmpty(), docker.calls()::toString);
    assertTrue(docker.calls("rm").isEmpty());
    assertTrue(docker.calls("network").stream().noneMatch(c -> c.contains("create")));
  }

  @Test
  void aBuilderOfAnotherStampIsReplacedAndItsVolumeKept() throws Exception {
    docker.answerFor("network-inspect", NETWORK, 0, "bridge\n", "");
    docker.answerFor("inspect", BUILDER, 0, "0123456789abcdef|running\n", "");
    docker.volume(STATE, Map.of("qits.system.runner.buildkitd", "state"));

    assertEquals(Optional.empty(), plane.ensure());

    assertTrue(has(List.of("rm", "-f", BUILDER)), docker.calls()::toString);
    assertTrue(docker.calls("run").stream().anyMatch(c -> c.contains(BUILDER)));
    assertTrue(docker.volumes().containsKey(STATE));
  }

  @Test
  void aForeignContainerUnderTheBuildersNameIsRefusedAndNotTouched() throws Exception {
    docker.answerFor("network-inspect", NETWORK, 0, "bridge\n", "");
    docker.answerFor("inspect", BUILDER, 0, "<no value>|running\n", "");

    Optional<String> refused = plane.ensure();

    assertTrue(refused.orElseThrow().contains("is not this runner's builder"), refused::toString);
    assertTrue(docker.calls("rm").isEmpty());
    assertTrue(docker.calls("run").isEmpty());
  }

  @Test
  void aLeaseIsJoinedToTheBuildNetworkAndAlreadyJoinedCountsAsJoined() throws Exception {
    assertEquals(Optional.empty(), plane.connect("qits-system-runner-lease-l1-r1"));
    assertTrue(has(List.of("network", "connect", NETWORK, "qits-system-runner-lease-l1-r1")));

    docker.answer("network-connect", 1, "", "Error: endpoint with name x already exists in network");
    assertEquals(Optional.empty(), plane.connect("qits-system-runner-lease-l1-r1"));

    docker.answer("network-connect", 1, "", "Error: No such container: x");
    assertTrue(plane.connect("qits-system-runner-lease-l1-r1").isPresent());
  }

  // --- the node tunnel's resolvers (qits-2090) ----------------------------------------------------

  private static final List<String> TUNNEL = List.of("100.96.0.1", "192.168.188.1");

  private BuildPlane planeWith(Supplier<List<String>> resolvers) {
    return new BuildPlane(
        IDENTITY,
        TARGET,
        new RunnerDocker(IDENTITY, docker.binary(), Duration.ofSeconds(10)),
        BuildkitSettings.DEFAULTS,
        1,
        resolvers);
  }

  /** The stamp before qits-2090, restated: image, toml, bootstrap and state volume, nothing else. */
  private static String stampBeforeTheTunnel(BuildPlane plane) throws Exception {
    String material =
        plane.image() + "\n" + plane.toml() + "\n" + BuildPlane.BOOTSTRAP + "\n" + STATE;
    byte[] digest =
        MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(digest).substring(0, 16);
  }

  private List<String> builderRun() {
    return docker.calls("run").stream().filter(c -> c.contains(BUILDER)).findFirst().orElseThrow();
  }

  private void noBuilderYet() throws Exception {
    docker.answerFor("network-inspect", NETWORK, 0, "bridge\n", "");
    docker.answerFor("inspect", BUILDER, 1, "", "Error: No such object: " + BUILDER);
  }

  @Test
  void theTunnelsResolversAreOneDnsEachRightAfterTheNetworkAndMoveTheStamp() throws Exception {
    AtomicInteger asked = new AtomicInteger();
    BuildPlane tunnelled =
        planeWith(
            () -> {
              asked.incrementAndGet();
              return TUNNEL;
            });
    noBuilderYet();

    assertEquals(Optional.empty(), tunnelled.ensure());

    assertEquals(1, asked.get(), "decided once per ensure, for the stamp and the run alike");
    String stamp = tunnelled.configuredStamp(TUNNEL);
    List<String> expected = new ArrayList<>(tunnelled.runArgv(stamp));
    int network = expected.indexOf("--network");
    expected.addAll(network + 2, List.of("--dns", "100.96.0.1", "--dns", "192.168.188.1"));
    assertEquals(expected, tunnelled.runArgv(stamp, TUNNEL));
    List<String> run = builderRun();
    int at = run.indexOf("--network");
    assertEquals(
        List.of("--network", NETWORK, "--dns", "100.96.0.1", "--dns", "192.168.188.1", "--label"),
        run.subList(at, at + 7),
        run::toString);
    assertEquals(Map.of("qits.system.runner.buildkitd", stamp), docker.containers().get(BUILDER));
    assertNotEquals(tunnelled.configuredStamp(List.of()), stamp, "the resolvers move the stamp");
    assertNotEquals(stampBeforeTheTunnel(tunnelled), stamp);
    assertNotEquals(
        stamp,
        tunnelled.configuredStamp(List.of("100.96.0.1", "1.1.1.1")),
        "another upstream is another stamp");
  }

  @Test
  void noResolversAreTodaysArgvAndTodaysStampByteForByte() throws Exception {
    BuildPlane untunnelled = planeWith(List::of);
    noBuilderYet();

    assertEquals(Optional.empty(), untunnelled.ensure());

    String stamp = untunnelled.configuredStamp(List.of());
    assertEquals(stampBeforeTheTunnel(untunnelled), stamp);
    assertEquals(stamp, untunnelled.configuredStamp());
    assertEquals(plane.configuredStamp(), stamp, "the five-argument plane is the same builder");
    assertEquals(untunnelled.runArgv(stamp), untunnelled.runArgv(stamp, List.of()));
    List<String> run = builderRun();
    assertFalse(run.contains("--dns"), run::toString);
    int at = run.indexOf("--network");
    assertEquals(List.of("--network", NETWORK, "--label"), run.subList(at, at + 3), run::toString);
    assertEquals(Map.of("qits.system.runner.buildkitd", stamp), docker.containers().get(BUILDER));
  }

  @Test
  void aThrowingOrNullSeamIsNoResolvers() throws Exception {
    BuildPlane throwing =
        planeWith(
            () -> {
              throw new IllegalStateException("boom");
            });
    assertEquals(List.of(), throwing.resolvers());
    assertEquals(stampBeforeTheTunnel(throwing), throwing.configuredStamp());
    assertEquals(List.of(), planeWith(() -> null).resolvers());
  }

  @Test
  void theTunnelArrivingReplacesTheBuilderAsAnImageBumpDoes() throws Exception {
    BuildPlane tunnelled = planeWith(() -> TUNNEL);
    docker.answerFor("network-inspect", NETWORK, 0, "bridge\n", "");
    docker.answerFor(
        "inspect", BUILDER, 0, tunnelled.configuredStamp(List.of()) + "|running\n", "");
    docker.volume(STATE, Map.of("qits.system.runner.buildkitd", "state"));

    assertEquals(Optional.empty(), tunnelled.ensure());

    assertTrue(has(List.of("rm", "-f", BUILDER)), docker.calls()::toString);
    assertTrue(builderRun().contains("--dns"), docker.calls()::toString);
    assertTrue(docker.volumes().containsKey(STATE), "the state volume rides across");
  }

  @Test
  void theMirrorsMergeKeepsTheResolversSeam() {
    BuildPlane merged = planeWith(() -> TUNNEL).withRegistryMirrors(Map.of("a.example", "m:5000"));
    assertEquals(TUNNEL, merged.resolvers());
    assertEquals(merged.configuredStamp(TUNNEL), merged.configuredStamp());
  }

  // --- toml and mirrors --------------------------------------------------------------------------

  @Test
  void theTomlBoundsTheWorkerAndMergesOneTablePerHost() {
    String toml =
        BuildPlane.renderToml(
            List.of("registry.qits:5000"),
            List.of("registry.qits:5000=mirror:8080/hub", "docker.io=mirror:8080/dockerhub"),
            1);
    assertTrue(toml.contains("max-parallelism = 1"), toml);
    assertTrue(toml.contains("networkMode = \"host\""), toml);
    assertTrue(toml.contains("nameservers = [\"127.0.0.11\"]"), toml);
    assertTrue(
        toml.contains(
            "[registry.\"registry.qits:5000\"]\n  mirrors = [\"mirror:8080/hub\"]\n  http = true\n"),
        toml);
    assertTrue(toml.contains("[registry.\"docker.io\"]\n  mirrors = [\"mirror:8080/dockerhub\"]\n"));
    assertEquals(1, toml.split("\\[registry.\"registry.qits:5000\"]", -1).length - 1);
  }

  @Test
  void theAcksMirrorsWinPerSourceHostAndAlwaysMergeOverTheEnvironments() {
    BuildkitSettings settings =
        new BuildkitSettings(
            "moby/buildkit:v0.33.0",
            1,
            BuildkitSettings.DEFAULT_CONCURRENT_BUILDS,
            List.of(),
            List.of("a.example=env-a:5000", "b.example=env-b:5000"));
    BuildPlane base =
        new BuildPlane(
            IDENTITY,
            TARGET,
            new RunnerDocker(IDENTITY, "docker", Duration.ofSeconds(1)),
            settings,
            1);
    Map<String, String> ack = new LinkedHashMap<>();
    ack.put("a.example", "ack-a:5000");
    ack.put("c.example", "ack-c:5000/path");
    ack.put("bad\"host", "x");
    BuildPlane merged = base.withRegistryMirrors(ack);

    assertTrue(merged.toml().contains("mirrors = [\"ack-a:5000\"]"), merged.toml());
    assertFalse(merged.toml().contains("env-a"), merged.toml());
    assertTrue(merged.toml().contains("env-b:5000"));
    assertTrue(merged.toml().contains("ack-c:5000/path"));
    assertFalse(merged.toml().contains("bad"), "a pair outside the charsets is dropped");
    assertNotEquals(base.configuredStamp(), merged.configuredStamp(), "a new map moves the stamp");
    assertEquals(base.configuredStamp(), base.withRegistryMirrors(null).configuredStamp());
    assertEquals(
        base.configuredStamp(),
        base.withRegistryMirrors(Map.of()).configuredStamp(),
        "an empty map keeps the environment's mirrors");
  }

  @Test
  void unsetParallelismRendersTheRunnersSlotTotal() {
    BuildPlane withSlots =
        new BuildPlane(
            IDENTITY,
            TARGET,
            new RunnerDocker(IDENTITY, docker.binary(), Duration.ofSeconds(10)),
            BuildkitSettings.DEFAULTS,
            8);
    assertTrue(withSlots.toml().contains("max-parallelism = 8"), withSlots.toml());
  }

  @Test
  void anExplicitParallelismWinsOverTheSlotTotal() {
    BuildkitSettings settings =
        new BuildkitSettings(BuildkitSettings.DEFAULT_IMAGE, 3, BuildkitSettings.DEFAULT_CONCURRENT_BUILDS, List.of(), List.of());
    BuildPlane explicit =
        new BuildPlane(
            IDENTITY,
            TARGET,
            new RunnerDocker(IDENTITY, docker.binary(), Duration.ofSeconds(10)),
            settings,
            8);
    assertTrue(explicit.toml().contains("max-parallelism = 3"), explicit.toml());
  }

  @Test
  void configuredStampDiffersBetweenTheDerivedAndAnExplicitParallelism() {
    BuildPlane derived =
        new BuildPlane(
            IDENTITY,
            TARGET,
            new RunnerDocker(IDENTITY, docker.binary(), Duration.ofSeconds(10)),
            BuildkitSettings.DEFAULTS,
            8);
    BuildkitSettings explicitSettings =
        new BuildkitSettings(BuildkitSettings.DEFAULT_IMAGE, 3, BuildkitSettings.DEFAULT_CONCURRENT_BUILDS, List.of(), List.of());
    BuildPlane explicit =
        new BuildPlane(
            IDENTITY,
            TARGET,
            new RunnerDocker(IDENTITY, docker.binary(), Duration.ofSeconds(10)),
            explicitSettings,
            8);
    assertNotEquals(derived.configuredStamp(), explicit.configuredStamp());
  }

  // --- health ------------------------------------------------------------------------------------

  @Test
  void anAbsentBuilderAndNetworkAreOkUntilTheFirstCiLease() throws Exception {
    docker.answerFor("network-inspect", NETWORK, 1, "", "Error: No such network: " + NETWORK);
    docker.answerFor("inspect", BUILDER, 1, "", "Error: No such object: " + BUILDER);

    RunnerHealthCheck.Result buildkit = TargetHealthChecks.buildkit(plane);
    assertTrue(buildkit.ok(), buildkit.detail());
    assertEquals("ABSENT", buildkit.data().get("presence"));
    assertEquals(BUILDER, buildkit.data().get("container"));
    assertEquals("tcp://" + BUILDER + ":1234", buildkit.data().get("address"));
    assertEquals(STATE, buildkit.data().get("stateVolume"));
    RunnerHealthCheck.Result network = TargetHealthChecks.network(plane);
    assertTrue(network.ok(), network.detail());
    assertEquals("ABSENT", network.data().get("builder"));
    assertTrue(docker.calls("run").isEmpty(), "a check never starts anything");
  }

  @Test
  void aStaleOrStoppedBuilderFailsAndAMissingNetworkBesideABuilderFails() throws Exception {
    docker.answerFor("inspect", BUILDER, 0, "0123456789abcdef|running\n", "");
    assertFalse(TargetHealthChecks.buildkit(plane).ok());
    docker.answerFor("inspect", BUILDER, 0, plane.configuredStamp() + "|exited\n", "");
    assertFalse(TargetHealthChecks.buildkit(plane).ok());
    docker.answerFor("inspect", BUILDER, 0, plane.configuredStamp() + "|running\n", "");
    assertTrue(TargetHealthChecks.buildkit(plane).ok());

    docker.answerFor("network-inspect", NETWORK, 1, "", "Error: No such network: " + NETWORK);
    assertFalse(TargetHealthChecks.network(plane).ok());
    docker.answerFor("network-inspect", NETWORK, 0, "bridge\n", "");
    assertTrue(TargetHealthChecks.network(plane).ok());
  }

  // --- settings ----------------------------------------------------------------------------------

  @Test
  void theSettingsDefaultAndParseUnderTheTargetsPrefix() throws Exception {
    assertEquals(
        BuildkitSettings.DEFAULTS, BuildkitSettings.parse(IDENTITY, Map.<String, String>of()::get));
    assertEquals("moby/buildkit:v0.33.0", BuildkitSettings.DEFAULTS.image());
    assertNull(BuildkitSettings.DEFAULTS.maxParallelism());
    assertEquals(1, BuildkitSettings.DEFAULTS.concurrentBuilds(), "unset means 1");

    Map<String, String> env =
        Map.of(
            "QITS_SYSTEM_RUNNER_BUILDKIT_IMAGE", "moby/buildkit:v0.34.0",
            "QITS_SYSTEM_RUNNER_BUILDKIT_MAX_PARALLELISM", "3",
            "QITS_SYSTEM_RUNNER_BUILDER_CONCURRENT_BUILDS", "5",
            "QITS_SYSTEM_RUNNER_BUILDKIT_HTTP_REGISTRIES", "registry.qits:5000, mirror:8080",
            "QITS_SYSTEM_RUNNER_BUILDKIT_REGISTRY_MIRRORS", "docker.io=mirror:8080/hub");
    assertEquals(
        new BuildkitSettings(
            "moby/buildkit:v0.34.0",
            3,
            5,
            List.of("registry.qits:5000", "mirror:8080"),
            List.of("docker.io=mirror:8080/hub")),
        BuildkitSettings.parse(IDENTITY, env::get));
  }

  @Test
  void concurrentBuildsIsOneWhenUnsetAndAnExplicitValueWins() throws Exception {
    assertEquals(
        1, BuildkitSettings.parse(IDENTITY, Map.<String, String>of()::get).concurrentBuilds());
    assertEquals(
        7,
        BuildkitSettings.parse(
                IDENTITY, Map.of("QITS_SYSTEM_RUNNER_BUILDER_CONCURRENT_BUILDS", "7")::get)
            .concurrentBuilds());
  }

  @Test
  void aMalformedConcurrentBuildsIsRefusedNamingItsVariable() {
    for (String bad : List.of("0", "-1", "abc")) {
      RunnerSettings.Invalid invalid =
          assertThrows(
              RunnerSettings.Invalid.class,
              () ->
                  BuildkitSettings.parse(
                      IDENTITY,
                      Map.of("QITS_SYSTEM_RUNNER_BUILDER_CONCURRENT_BUILDS", bad)::get));
      assertTrue(
          invalid.getMessage().contains("QITS_SYSTEM_RUNNER_BUILDER_CONCURRENT_BUILDS"),
          invalid.getMessage());
    }
  }

  @Test
  void aMalformedSettingIsRefusedNamingItsVariable() {
    for (Map.Entry<String, String> bad :
        Map.of(
                "QITS_SYSTEM_RUNNER_BUILDKIT_MAX_PARALLELISM", "0",
                "QITS_SYSTEM_RUNNER_BUILDKIT_HTTP_REGISTRIES", "http://x",
                "QITS_SYSTEM_RUNNER_BUILDKIT_REGISTRY_MIRRORS", "docker.io",
                "QITS_SYSTEM_RUNNER_BUILDKIT_IMAGE", "moby/buildkit:v0.33.0\"")
            .entrySet()) {
      RunnerSettings.Invalid invalid =
          assertThrows(
              RunnerSettings.Invalid.class,
              () -> BuildkitSettings.parse(IDENTITY, Map.of(bad.getKey(), bad.getValue())::get));
      assertTrue(invalid.getMessage().contains(bad.getKey()), invalid.getMessage());
    }
  }

  @Test
  void aMalformedParallelismIsRefusedNamingItsVariable() {
    for (String bad : List.of("0", "-1", "abc")) {
      RunnerSettings.Invalid invalid =
          assertThrows(
              RunnerSettings.Invalid.class,
              () ->
                  BuildkitSettings.parse(
                      IDENTITY, Map.of("QITS_SYSTEM_RUNNER_BUILDKIT_MAX_PARALLELISM", bad)::get));
      assertTrue(
          invalid.getMessage().contains("QITS_SYSTEM_RUNNER_BUILDKIT_MAX_PARALLELISM"),
          invalid.getMessage());
    }
  }

  private boolean has(List<String> call) {
    return docker.calls().stream()
        .map(FakeDocker::withoutConfig)
        .anyMatch(call::equals);
  }
}
