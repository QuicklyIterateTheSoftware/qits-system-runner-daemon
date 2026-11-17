package eu.wohlben.qits.systemrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.driver.spec.ContainerSpec;
import eu.wohlben.qits.runner.toolkit.DockerCommand;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link PrivateEdge} against a scripted {@link DockerCommand}: the three read-only questions, each
 * argv exactly and under the runner's deadline; all or nothing — no network, zero or two edge
 * services, no VIP on the overlay, no upstream, docker failing or timing out, each the spec
 * unchanged — and the resolv.conf's two spellings.
 */
class PrivateEdgeTest {

  private static final Duration DEADLINE = Duration.ofSeconds(7);
  private static final String NET_ID = "0a1b2c3d4e5f";
  private static final String SERVICE = "svc9edge";
  private static final String LEASE = "qits-system-runner-lease-l1-r1";

  private static final String NETWORK_INSPECT =
      "docker network inspect --format {{.Id}} qits-private";
  private static final String OVERLAY_LS =
      "docker service ls -q --filter label=qits.platform.deployments.app-name=qits-edge-overlay";
  private static final String SERVICE_LS =
      "docker service ls -q --filter label=qits.platform.deployments.app-name=qits-edge-private";
  private static final String SERVICE_INSPECT = "docker service inspect --format ";

  /** What the fake answers per argv prefix, and every argv it was asked, with its deadline. */
  private final Map<String, RunnerDocker.Result> answers = new LinkedHashMap<>();

  private final List<List<String>> calls = new ArrayList<>();
  private final List<Duration> deadlines = new ArrayList<>();

  @TempDir Path dir;
  private Path resolvConf;
  private PrivateEdge edge;

  @BeforeEach
  void setUp() throws Exception {
    resolvConf = dir.resolve("resolv.conf");
    Files.writeString(resolvConf, "nameserver 192.168.188.1\nsearch fritz.box\n");
    edge =
        new PrivateEdge(
            DockerCommand.of(
                "docker",
                DEADLINE,
                (argv, deadline) -> {
                  calls.add(argv);
                  deadlines.add(deadline);
                  for (Map.Entry<String, RunnerDocker.Result> a : answers.entrySet()) {
                    if (String.join(" ", argv).startsWith(a.getKey())) {
                      return a.getValue();
                    }
                  }
                  return new RunnerDocker.Result(
                      1, "", "Error response from daemon: No such object", false, false);
                }),
            resolvConf);
    // The shipped preference (qits-2304): the overlay edge first, the tunnel edge as fallback. No
    // test scripts the overlay edge unless it is about it, so its `service ls` answers nothing —
    // absent, not refused — and every lookup falls through to qits-edge-private exactly as before.
    answer(OVERLAY_LS, "");
  }

  private void answer(String argvPrefix, String stdout) {
    answers.put(argvPrefix, new RunnerDocker.Result(0, stdout, "", false, false));
  }

  /** The platform host: the overlay, one edge service, and its VIP on the overlay. */
  private void platformHost() {
    answer(NETWORK_INSPECT, NET_ID + "\n");
    answer(SERVICE_LS, SERVICE + "\n");
    answer(SERVICE_INSPECT, "ingress00 10.0.0.7/24\n" + NET_ID + " 10.231.0.2/16\n");
  }

  private static ContainerSpec spec() {
    return ContainerSpec.builder("registry.example:5000/qits/workspace:1")
        .name(LEASE)
        .network("bridge")
        .user("1000")
        .env("QITS_TOKEN", "qits_tok_LEASE")
        .mount("qits-system-runner-work-ws-42-r1", "/workspace")
        .build();
  }

  // ---- full success -----------------------------------------------------------------------------

  @Test
  void onThePlatformHostTheLeaseJoinsQitsPrivateWithTheEdgesVipFirstAndEverythingElseAsItWas() {
    platformHost();
    ContainerSpec spec = spec();

    ContainerSpec resolved = edge.apply(spec, LEASE);

    assertEquals(List.of("qits-private"), resolved.extraNetworks());
    assertEquals(List.of("10.231.0.2", "192.168.188.1"), resolved.dns());
    assertEquals(
        spec,
        new ContainerSpec(
            resolved.image(),
            resolved.entrypoint(),
            resolved.args(),
            resolved.env(),
            resolved.extraLabels(),
            resolved.network(),
            resolved.aliases(),
            resolved.addHosts(),
            resolved.volumeMounts(),
            resolved.sharedMounts(),
            resolved.hostDockerSocket(),
            resolved.security(),
            resolved.pullPolicy(),
            resolved.explicitName(),
            resolved.user(),
            resolved.init(),
            List.of(),
            List.of()),
        "every other component copied as it was");
    assertEquals(
        List.of(
            List.of("docker", "network", "inspect", "--format", "{{.Id}}", "qits-private"),
            List.of(
                "docker",
                "service",
                "ls",
                "-q",
                "--filter",
                "label=qits.platform.deployments.app-name=qits-edge-overlay"),
            List.of(
                "docker",
                "service",
                "ls",
                "-q",
                "--filter",
                "label=qits.platform.deployments.app-name=qits-edge-private"),
            List.of(
                "docker",
                "service",
                "inspect",
                "--format",
                "{{range .Endpoint.VirtualIPs}}{{.NetworkID}} {{.Addr}}{{\"\\n\"}}{{end}}",
                SERVICE)),
        calls,
        "the overlay edge is tried first and found absent, then the tunnel edge answers");
    assertEquals(
        List.of(DEADLINE, DEADLINE, DEADLINE, DEADLINE), deadlines, "under the runner's deadline");
  }

  @Test
  void theChoiceIsTheVipApplyPutsFirstAndNoneWhereApplyLeavesTheSpecUnchanged() throws Exception {
    assertEquals(Optional.empty(), edge.choice(), "no overlay");
    platformHost();
    assertEquals(Optional.of("10.231.0.2"), edge.choice());
    assertEquals(edge.choice().orElseThrow(), edge.apply(spec(), LEASE).dns().getFirst());
    Files.writeString(resolvConf, "nameserver 127.0.0.11\n");
    assertEquals(Optional.empty(), edge.choice(), "all or nothing: no upstream, no choice");
  }

  @Test
  void aSpecAlreadyOnQitsPrivateIsNotJoinedToItTwice() {
    platformHost();
    ContainerSpec primary =
        ContainerSpec.builder("registry.example:5000/qits/workspace:1")
            .network("qits-private")
            .build();
    ContainerSpec listed =
        ContainerSpec.builder("registry.example:5000/qits/workspace:1")
            .network("bridge")
            .extraNetwork("qits-private")
            .build();

    ContainerSpec onPrimary = edge.apply(primary, LEASE);
    ContainerSpec onListed = edge.apply(listed, LEASE);

    assertEquals(List.of(), onPrimary.extraNetworks());
    assertEquals(List.of("10.231.0.2", "192.168.188.1"), onPrimary.dns());
    assertEquals(List.of("qits-private"), onListed.extraNetworks());
    assertEquals(List.of("10.231.0.2", "192.168.188.1"), onListed.dns());
  }

  // ---- all or nothing ---------------------------------------------------------------------------

  @Test
  void withoutTheNetworkTheSpecIsUnchangedAndNothingElseIsAsked() {
    ContainerSpec spec = spec();

    assertSame(spec, edge.apply(spec, LEASE));
    assertEquals(1, calls.size(), calls::toString);
  }

  @Test
  void aNetworkInspectThatAnswersNoIdIsNoNetwork() {
    answer(NETWORK_INSPECT, "");
    ContainerSpec spec = spec();

    assertSame(spec, edge.apply(spec, LEASE));
    answer(NETWORK_INSPECT, "Not An Id!\n");
    assertSame(spec, edge.apply(spec, LEASE));
    assertEquals(2, calls.size(), calls::toString);
  }

  @Test
  void zeroOrTwoEdgeServicesLeaveTheSpecUnchanged() {
    platformHost();
    ContainerSpec spec = spec();

    answer(SERVICE_LS, "");
    assertSame(spec, edge.apply(spec, LEASE));
    answer(SERVICE_LS, SERVICE + "\nsvc2other\n");
    assertSame(spec, edge.apply(spec, LEASE));
    assertTrue(
        calls.stream().noneMatch(c -> String.join(" ", c).startsWith(SERVICE_INSPECT)),
        calls::toString);
  }

  @Test
  void noVipOnTheOverlayLeavesTheSpecUnchanged() {
    platformHost();
    ContainerSpec spec = spec();

    answer(SERVICE_INSPECT, "ingress00 10.0.0.7/24\n");
    assertSame(spec, edge.apply(spec, LEASE));
    answer(SERVICE_INSPECT, NET_ID + " 10.231.0.2/16\n" + NET_ID + " 10.231.0.3/16\n");
    assertSame(spec, edge.apply(spec, LEASE), "two VIPs on the overlay are not exactly one");
    answer(SERVICE_INSPECT, NET_ID + " edge/16\n");
    assertSame(spec, edge.apply(spec, LEASE), "an Addr that is no literal is no VIP");
  }

  @Test
  void noUpstreamLeavesTheSpecUnchangedRatherThanAVipOnlyResolverList() throws Exception {
    platformHost();
    ContainerSpec spec = spec();

    Files.writeString(resolvConf, "nameserver 127.0.0.53\nnameserver ::1\noptions edns0\n");
    assertSame(spec, edge.apply(spec, LEASE));
    Files.writeString(resolvConf, "nameserver 10.231.0.2\n");
    assertSame(spec, edge.apply(spec, LEASE), "the VIP itself is no upstream");
    Files.delete(resolvConf);
    assertSame(spec, edge.apply(spec, LEASE), "an unreadable resolv.conf names no upstream");
  }

  @Test
  void aDockerNonZeroExitOrTimeoutAtAnyQuestionLeavesTheSpecUnchanged() {
    ContainerSpec spec = spec();
    RunnerDocker.Result timedOut =
        new RunnerDocker.Result(
            -1, "", "docker network inspect did not answer within 7s", true, false);
    RunnerDocker.Result refused =
        new RunnerDocker.Result(
            1, "", "Error response from daemon: This node is not a swarm manager", false, false);

    answers.put(NETWORK_INSPECT, timedOut);
    assertSame(spec, edge.apply(spec, LEASE));

    platformHost();
    answers.put(SERVICE_LS, refused);
    assertSame(spec, edge.apply(spec, LEASE));
    answers.put(SERVICE_LS, timedOut);
    assertSame(spec, edge.apply(spec, LEASE));

    platformHost();
    answers.put(SERVICE_INSPECT, refused);
    assertSame(spec, edge.apply(spec, LEASE));
    answers.put(SERVICE_INSPECT, timedOut);
    assertSame(spec, edge.apply(spec, LEASE));
  }

  @Test
  void aDockerCommandThatThrowsLeavesTheSpecUnchanged() {
    PrivateEdge throwing =
        new PrivateEdge(
            DockerCommand.of(
                "docker",
                DEADLINE,
                (argv, deadline) -> {
                  throw new IllegalStateException("no docker here");
                }),
            resolvConf);
    ContainerSpec spec = spec();

    assertSame(spec, throwing.apply(spec, LEASE));
  }

  // ---- qits-2304: qits-edge-overlay is the preferred resolver, qits-edge-private the fallback ---

  private static final String OVERLAY_SERVICE = "svc8overlay";
  private static final String OVERLAY_NET_ID = "9f8e7d6c5b4a";

  /** The overlay edge's own service and VIP, distinct from the tunnel edge's. */
  private void overlayEdge(String vip) {
    answer(OVERLAY_LS, OVERLAY_SERVICE + "\n");
    answer(SERVICE_INSPECT, OVERLAY_NET_ID + " " + vip + "/16\n");
  }

  @Test
  void anOverlayEdgePresentIsTheFirstResolverAndTheTunnelEdgeIsNeverAsked() {
    answer(NETWORK_INSPECT, OVERLAY_NET_ID + "\n");
    overlayEdge("10.231.0.9");
    ContainerSpec spec = spec();

    ContainerSpec resolved = edge.apply(spec, LEASE);

    assertEquals(List.of("qits-private"), resolved.extraNetworks());
    assertEquals(
        List.of("10.231.0.9", "192.168.188.1"),
        resolved.dns(),
        "the overlay edge's VIP, not the tunnel edge's");
    assertTrue(
        calls.stream().noneMatch(c -> String.join(" ", c).startsWith(SERVICE_LS)),
        "the tunnel edge is not even asked once the overlay edge answered: " + calls);
  }

  @Test
  void noOverlayEdgeServiceYetFallsBackToTheTunnelEdge() {
    platformHost();
    // @BeforeEach already scripts the overlay edge's `service ls` as empty (absent).
    ContainerSpec spec = spec();

    ContainerSpec resolved = edge.apply(spec, LEASE);

    assertEquals(
        List.of("10.231.0.2", "192.168.188.1"),
        resolved.dns(),
        "the tunnel edge's VIP once the overlay edge has no service at all");
    assertTrue(
        calls.stream().anyMatch(c -> String.join(" ", c).startsWith(OVERLAY_LS)),
        "the preferred edge was asked first: " + calls);
    assertTrue(calls.stream().anyMatch(c -> String.join(" ", c).startsWith(SERVICE_LS)));
  }

  @Test
  void anOverlayEdgeLookupThatFailsNeverFallsThroughToTheTunnelEdge() {
    // Absent (no service) is skipped; a refused lookup is a different answer and must stop the
    // walk, or a passing docker hiccup would flip a live lease onto the fallback edge and then
    // back again at its next create.
    platformHost();
    answers.put(
        OVERLAY_LS,
        new RunnerDocker.Result(
            1, "", "Error response from daemon: This node is not a swarm manager", false, false));
    ContainerSpec spec = spec();

    ContainerSpec resolved = edge.apply(spec, LEASE);

    assertEquals(List.of(), resolved.dns(), "no resolver, never the tunnel edge's as a fallback");
    assertTrue(calls.stream().anyMatch(c -> String.join(" ", c).startsWith(OVERLAY_LS)));
    assertTrue(
        calls.stream().noneMatch(c -> String.join(" ", c).startsWith(SERVICE_LS)),
        "a refused lookup stops the walk: " + calls);
  }

  @Test
  void twoOverlayEdgeServicesAlsoStopsTheWalkWithNoResolver() {
    platformHost();
    answer(OVERLAY_LS, OVERLAY_SERVICE + "\nsvc2other\n");
    ContainerSpec spec = spec();

    ContainerSpec resolved = edge.apply(spec, LEASE);

    assertEquals(List.of(), resolved.dns());
    assertTrue(
        calls.stream().noneMatch(c -> String.join(" ", c).startsWith(SERVICE_LS)), calls::toString);
  }

  @Test
  void theChoiceIsTheOverlayEdgesVipOnceItExists() {
    // The node's resolver choice (qits-2091), which NodeResolvers reports resolversStale against,
    // follows the same preference: the overlay edge's VIP once it is deployed, not the tunnel
    // edge's, exactly as `apply` would put it first on a lease created now.
    platformHost();
    assertEquals(Optional.of("10.231.0.2"), edge.choice(), "the tunnel edge, before the overlay");

    calls.clear();
    answer(NETWORK_INSPECT, OVERLAY_NET_ID + "\n");
    overlayEdge("10.231.0.9");

    assertEquals(Optional.of("10.231.0.9"), edge.choice(), "the overlay edge, now that it exists");
    assertTrue(
        calls.stream().noneMatch(c -> String.join(" ", c).startsWith(SERVICE_LS)),
        "the tunnel edge is not asked once the overlay edge answered: " + calls);
  }

  // ---- the resolv.conf --------------------------------------------------------------------------

  @Test
  void theExtServersLineWinsOverTheNameserversWithHostFormsUnwrapped() {
    assertEquals(
        List.of("192.168.188.1", "1.1.1.1", "2606:4700:4700::1111"),
        PrivateEdge.upstreams(
            """
            # Generated by Docker Engine.
            nameserver 127.0.0.11
            options ndots:0

            # Based on host file: '/etc/resolv.conf' (internal resolver)
            # ExtServers: [host(192.168.188.1) 1.1.1.1 host(127.0.0.53) 2606:4700:4700::1111]
            # ExtServers: [9.9.9.9]
            # Overrides: []
            """));
  }

  @Test
  void withoutAnExtServersLineTheNameserversAreTheUpstreamsInOrder() {
    assertEquals(
        List.of("192.168.188.1", "8.8.8.8", "2001:4860:4860::8888"),
        PrivateEdge.upstreams(
            """
            # Generated by Docker Engine.
            nameserver 192.168.188.1
            nameserver 127.0.0.53
            nameserver 127.1.2.3
            nameserver ::1
            nameserver 8.8.8.8 # a trailing comment
            nameserver 192.168.188.1
            nameserver resolver.example
            nameserver 2001:4860:4860::8888
            search fritz.box
            """));
  }

  @Test
  void anExtServersLineOfOnlyLoopbackNamesNoUpstreamEvenBesideANameserver() {
    assertEquals(
        List.of(),
        PrivateEdge.upstreams("nameserver 192.168.1.1\n# ExtServers: [host(127.0.0.53)]\n"));
  }

  @Test
  void aVipListedAmongTheUpstreamsIsNotRepeated() throws Exception {
    platformHost();
    Files.writeString(
        resolvConf, "nameserver 10.231.0.2\nnameserver 192.168.188.1\nnameserver 10.231.0.2\n");

    ContainerSpec resolved = edge.apply(spec(), LEASE);

    assertEquals(List.of("10.231.0.2", "192.168.188.1"), resolved.dns());
  }

  @Test
  void theVipIsTheOneLineOnTheOverlayWithItsPrefixStripped() {
    assertEquals(
        Optional.of("10.231.0.2"),
        PrivateEdge.vipOn("ingress00 10.0.0.7/24\n" + NET_ID + " 10.231.0.2/16\n", NET_ID));
    assertEquals(Optional.of("10.231.0.2"), PrivateEdge.vipOn(NET_ID + " 10.231.0.2\n", NET_ID));
    assertEquals(Optional.empty(), PrivateEdge.vipOn("", NET_ID));
    assertEquals(Optional.empty(), PrivateEdge.vipOn("ingress00 10.0.0.7/24\n", NET_ID));
  }
}
