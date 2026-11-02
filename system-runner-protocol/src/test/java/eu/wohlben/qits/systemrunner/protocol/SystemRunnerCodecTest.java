package eu.wohlben.qits.systemrunner.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.runner.protocol.Hello;
import eu.wohlben.qits.runner.protocol.RunnerCodec;
import eu.wohlben.qits.runner.protocol.RunnerDecodeException;
import eu.wohlben.qits.runner.protocol.RunnerMessage;
import eu.wohlben.qits.runner.protocol.health.CheckResult;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The target frames' contract, framework-free: every frame survives {@code encode → decode} through
 * the base codec and this delegate unchanged; each frame's JSON spelling is pinned, key order
 * included; the {@code Hello} and {@code Ack} extensions round-trip through the base; unknown
 * fields are ignored and an unknown type is the base's typed error; and the lists that are desired
 * or observed state refuse to be read as empty when they are absent.
 *
 * <p>The lifecycle frames' byte identity is qits-runner-protocol's and is not repeated here.
 */
class SystemRunnerCodecTest {

  private static final RunnerCodec<SystemRunnerMessage> CODEC =
      new RunnerCodec<>(SystemRunnerProtocol.VOCABULARY, new SystemRunnerCodec());

  private static final String WS_IMAGE = "registry.qits.example.eu/qits/workspace:2026.1023.1";
  private static final String DESK_IMAGE = "registry.qits.example.eu/qits/project-agent:2026.1023.1";

  private static final String L1 = "8273c743-e0bb-4d93-90d4-391aa10d9151";
  private static final String L2 = "5ca0e7e9-7dd8-4128-bbdd-58aa40d9b397";
  private static final String OLD_WS = "qits-workspaces-runner-ws-42-1a2b3c4d";

  private static final String S1 = "0b9c7a3e-5d1f-4e2a-9b8c-7d6e5f4a3b2c";
  private static final String CLAUDE_URL =
      "https://claude.com/cai/oauth/authorize?code=true&client_id=x&state=y";
  private static final String KIMI_URL =
      "https://www.kimi.com/code/authorize_device?user_code=ABCD-1234";

  private static final UUID R1 = UUID.fromString("6f1e2d3c-4b5a-4968-8776-655443322110");
  private static final String LEASE_CONTAINER = "qits-system-runner-lease-" + L1 + "-1a2b3c4d";

  private static LeaseSpec fullSpec() {
    Map<String, String> env = new LinkedHashMap<>();
    env.put("QITS_TOKEN", "qits_tok_X");
    env.put("TZ", "Europe/Berlin");
    return new LeaseSpec(WS_IMAGE, env, "1000", new Limits("4g", "8g", 4096L, "2", 600));
  }

  private static LeaseSpec bareSpec() {
    return new LeaseSpec(DESK_IMAGE, Map.of(), null, null);
  }

  private static LeaseEntry workspace() {
    return new LeaseEntry(
        L1,
        SlotType.WORKSPACE,
        DesiredState.RUNNING,
        fullSpec(),
        "sha256:aaa",
        "ws-42",
        new Adopt(OLD_WS, OLD_WS));
  }

  private static final String WARM_TAG = "registry.qits.example/qits/workspace:2026.1027.193351";
  private static final String WARM_DIGEST =
      "registry.qits.example/qits/ci-slot@sha256:"
          + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  private static LeaseEntry endedDesk() {
    return new LeaseEntry(L2, SlotType.DESK, DesiredState.ENDED, null, null, "desk-7", null);
  }

  /** One of every frame, and a second shape where a field is nullable or a list can be empty. */
  private static List<SystemRunnerMessage> everyFrame() {
    return List.of(
        new Estate(
            List.of(
                workspace(),
                endedDesk(),
                new LeaseEntry(
                    "l3", SlotType.CI, DesiredState.RUNNING, bareSpec(), "h", null, null)),
            List.of("qits-system-runner-work-ws-9-1a2b3c4d"),
            List.of(WARM_TAG, WARM_DIGEST)),
        new Estate(List.of(), List.of()),
        new ProbeLogin(),
        new HealthCheck("req-1"),
        new HealthCheck(null),
        new Inventory(
            List.of(
                new LeaseReport(L1, LeaseState.RUNNING, "sha256:aaa", null, null),
                new LeaseReport(L2, LeaseState.EXITED, "sha256:bbb", 137, null),
                new LeaseReport("l3", LeaseState.FAILED, null, null, "no space left on device"),
                new LeaseReport("l4", LeaseState.STARTING, null, null, null),
                new LeaseReport("l5", LeaseState.ENDED, null, null, null)),
            List.of("qits-system-runner-work-ws-42-1a2b3c4d")),
        new Inventory(List.of(), List.of()),
        new Inventory(
            List.of(
                new LeaseReport(
                    L1, LeaseState.RUNNING, "sha256:aaa", null, null, 2L, 1L, 3L << 30, null),
                new LeaseReport(
                    L2, LeaseState.EXITED, "sha256:bbb", 137, null, 1L, 1L, 4L << 30, true),
                new LeaseReport("l3", LeaseState.EXITED, "h", 0, null, 0L, 0L, null, false)),
            List.of(),
            new NodeMemory(292L, 297L, 32L << 30)),
        new Inventory(List.of(), List.of(), new NodeMemory(null, 297L, null)),
        new LoginState(
            LoginPresence.PRESENT,
            LoginPresence.ABSENT,
            "2026-10-23T07:00:00Z",
            "qits_shared_dot_claude"),
        new LoginState(LoginPresence.UNKNOWN, LoginPresence.UNKNOWN, null, null),
        new HealthChecked(
            true,
            "every check passed",
            "req-1",
            List.of(new CheckResult("docker", true, "29.0.0", Map.of("serverVersion", "29.0.0")))),
        new HealthChecked(false, "it never ran", null, List.of()),
        new SignIn(S1, "claude"),
        new SignInCode(S1, "abc#def"),
        new SignInCancel(S1),
        new SignInProgress(S1, "claude", SignInState.AWAITING_CODE, CLAUDE_URL, null, null),
        new SignInProgress(
            S1, "kimi", SignInState.AWAITING_BROWSER, KIMI_URL, "ABCD-1234", null),
        new SignInProgress(S1, null, SignInState.FAILED, null, null, "Login failed"),
        new Docker(R1, DockerOp.LIST_CONTAINERS, null, null, null, null, null),
        new Docker(R1, DockerOp.LOGS, LEASE_CONTAINER, 500, "10m", null, null),
        new Docker(R1, DockerOp.EXEC, LEASE_CONTAINER, null, null, List.of("ls", "-la", "/"), 60),
        new DockerResult(R1, DockerStatus.OK, "[]", false, null, null),
        new DockerResult(R1, DockerStatus.OK, "total 0\n", true, 2, "ls: oops"),
        DockerResult.of(R1, DockerStatus.NOT_FOUND, "no container x of this target"));
  }

  @Test
  void everyFrameRoundTripsThroughTheBaseCodecAndTheDelegate() {
    for (SystemRunnerMessage frame : everyFrame()) {
      RunnerMessage back = CODEC.decode(CODEC.encode(frame));
      assertEquals(frame, back, () -> "round trip of " + frame);
    }
  }

  @Test
  void theRoundTripCoversEveryPermittedFrame() {
    Set<String> permitted =
        Arrays.stream(SystemRunnerMessage.class.getPermittedSubclasses())
            .map(Class::getSimpleName)
            .collect(Collectors.toSet());
    Set<String> covered =
        everyFrame().stream().map(f -> f.getClass().getSimpleName()).collect(Collectors.toSet());
    assertEquals(permitted, covered, "a frame was added without a round trip");
    assertEquals(
        SystemRunnerProtocol.Type.ALL,
        everyFrame().stream()
            .map(f -> (String) CODEC.encode(f).get("type"))
            .collect(Collectors.toSet()),
        "every wire type is spoken by a frame, and no frame by an undeclared type");
  }

  @Test
  void theHelloCapabilitiesAndTheAckExtensionsRoundTripThroughTheBaseCodec() {
    SystemRunnerCapabilities caps =
        new SystemRunnerCapabilities(
            true, "amd64", "linux", 4, "ENGINE:ID", "node-1", "qits_shared_dot_claude", false);
    Hello hello =
        new Hello(
            SystemRunnerBinary.VERSION,
            SystemRunnerProtocol.CAPABILITY_VERSION,
            4,
            caps.toWire(),
            List.of(L1),
            Map.of());
    Map<String, Object> wire = CODEC.encode(hello);
    assertEquals(List.of(L1), wire.get("heldLeases"));
    Hello back = (Hello) CODEC.decode(wire);
    assertEquals(caps, SystemRunnerCapabilities.of(back.capabilities()));
    @SuppressWarnings("unchecked")
    Map<String, Object> capabilities = (Map<String, Object>) wire.get("capabilities");
    assertEquals(
        List.of(
            "docker",
            "arch",
            "os",
            "labels",
            "slots",
            "engineId",
            "hostname",
            "credentialVolume",
            "buildkit"),
        List.copyOf(capabilities.keySet()));

    SystemRunnerAck ext =
        new SystemRunnerAck(
            new SlotImages(
                "registry.qits.example.eu/qits/ci-",
                "registry.qits.example.eu/qits/workspace",
                "registry.qits.example.eu/qits/project-agent"),
            WS_IMAGE,
            Map.of("docker.io", "mirror.qits.example.eu/docker"));
    Ack ack = new Ack(1, 4, List.of(L1), ext.extensions());
    Map<String, Object> ackWire = CODEC.encode(ack);
    assertEquals(List.of(L1), ackWire.get("adoptedLeases"));
    Ack ackBack = (Ack) CODEC.decode(ackWire);
    assertEquals(ext, SystemRunnerAck.of(ackBack));
    assertEquals(
        "{\"type\":\"ack\",\"capabilityVersion\":1,\"slots\":4,\"slotImages\":{\"ci\":"
            + "\"registry.qits.example.eu/qits/ci-\",\"workspace\":"
            + "\"registry.qits.example.eu/qits/workspace\",\"desk\":"
            + "\"registry.qits.example.eu/qits/project-agent\"},\"probeImage\":\""
            + WS_IMAGE
            + "\",\"registryMirrors\":{\"docker.io\":\"mirror.qits.example.eu/docker\"},"
            + "\"adoptedLeases\":[\""
            + L1
            + "\"]}",
        Json.write(ackWire));

    SystemRunnerAck none = SystemRunnerAck.of(new Ack(1, 4));
    assertEquals(SlotImages.NONE, none.slotImages());
    assertNull(none.probeImage());
    assertNull(none.registryMirrors(), "not sent is not empty");
  }

  // --- the golden spelling ---------------------------------------------------------------------

  private static final String FULL_SPEC_JSON =
      "{\"image\":\""
          + WS_IMAGE
          + "\",\"env\":{\"QITS_TOKEN\":\"qits_tok_X\",\"TZ\":\"Europe/Berlin\"},\"user\":\"1000\","
          + "\"limits\":{\"memory\":\"4g\",\"memorySwap\":\"8g\",\"pidsLimit\":4096,\"cpus\":\"2\","
          + "\"oomScoreAdj\":600}}";

  @Test
  void eachFrameIsSpelledExactlyAsTheDossierSaysKeyOrderIncluded() {
    assertJson(
        "{\"type\":\"estate\",\"leases\":["
            + "{\"leaseId\":\""
            + L1
            + "\",\"slotType\":\"WORKSPACE\",\"desired\":\"RUNNING\",\"spec\":"
            + FULL_SPEC_JSON
            + ",\"specHash\":\"sha256:aaa\",\"workVolume\":\"ws-42\",\"adopt\":{\"container\":\""
            + OLD_WS
            + "\",\"volume\":\""
            + OLD_WS
            + "\"}},"
            + "{\"leaseId\":\""
            + L2
            + "\",\"slotType\":\"DESK\",\"desired\":\"ENDED\",\"spec\":null,\"specHash\":null,"
            + "\"workVolume\":\"desk-7\",\"adopt\":null}],"
            + "\"purges\":[\"qits-system-runner-work-ws-9-1a2b3c4d\"],"
            + "\"warmImages\":[\""
            + WARM_TAG
            + "\",\""
            + WARM_DIGEST
            + "\"]}",
        new Estate(
            List.of(workspace(), endedDesk()),
            List.of("qits-system-runner-work-ws-9-1a2b3c4d"),
            List.of(WARM_TAG, WARM_DIGEST)));
    assertJson(
        "{\"type\":\"estate\",\"leases\":[],\"purges\":[],\"warmImages\":[]}",
        new Estate(List.of(), null, null));
    assertJson("{\"type\":\"probeLogin\"}", new ProbeLogin());
    assertJson("{\"type\":\"healthCheck\",\"requestId\":\"req-1\"}", new HealthCheck("req-1"));
    assertJson(
        "{\"type\":\"inventory\",\"leases\":["
            + "{\"leaseId\":\""
            + L1
            + "\",\"state\":\"RUNNING\",\"specHash\":\"sha256:aaa\",\"exitCode\":null,"
            + "\"detail\":null},"
            + "{\"leaseId\":\"l2\",\"state\":\"EXITED\",\"specHash\":\"h\",\"exitCode\":137,"
            + "\"detail\":null}],"
            + "\"volumes\":[\"qits-system-runner-work-ws-42-1a2b3c4d\"]}",
        new Inventory(
            List.of(
                new LeaseReport(L1, LeaseState.RUNNING, "sha256:aaa", null, null),
                new LeaseReport("l2", LeaseState.EXITED, "h", 137, null)),
            List.of("qits-system-runner-work-ws-42-1a2b3c4d")));
    assertJson(
        "{\"type\":\"loginState\",\"claude\":\"PRESENT\",\"kimi\":\"UNKNOWN\","
            + "\"checkedAt\":\"2026-10-23T07:00:00Z\",\"volume\":\"qits-system-runner-dot-claude-1a2b3c4d\"}",
        new LoginState(
            LoginPresence.PRESENT,
            LoginPresence.UNKNOWN,
            "2026-10-23T07:00:00Z",
            "qits-system-runner-dot-claude-1a2b3c4d"));
    assertJson(
        "{\"type\":\"healthChecked\",\"ok\":true,\"detail\":\"fine\",\"requestId\":\"req-1\","
            + "\"checks\":[{\"name\":\"login\",\"ok\":true,\"detail\":\"x\",\"data\":{}}]}",
        new HealthChecked(
            true, "fine", "req-1", List.of(new CheckResult("login", true, "x", Map.of()))));
  }

  @Test
  void theInventorysMemoryFactsAreSpelledAfterTheOldFieldsAndLeftOutWhenNull() {
    assertJson(
        "{\"type\":\"inventory\",\"leases\":["
            + "{\"leaseId\":\"l2\",\"state\":\"EXITED\",\"specHash\":\"h\",\"exitCode\":137,"
            + "\"detail\":null,\"oomKills\":1,\"oomEvents\":1,\"memoryPeakBytes\":4294967296,"
            + "\"oomKilled\":true},"
            + "{\"leaseId\":\"l3\",\"state\":\"RUNNING\",\"specHash\":\"h\",\"exitCode\":null,"
            + "\"detail\":null,\"oomKills\":0}],"
            + "\"volumes\":[],"
            + "\"node\":{\"nodeOomKills\":292,\"kernelOomKills\":297,"
            + "\"memoryMaxBytes\":34359738368}}",
        new Inventory(
            List.of(
                new LeaseReport("l2", LeaseState.EXITED, "h", 137, null, 1L, 1L, 4L << 30, true),
                new LeaseReport("l3", LeaseState.RUNNING, "h", null, null, 0L, null, null, null)),
            List.of(),
            new NodeMemory(292L, 297L, 32L << 30)));
    // A bare host's root has no memory.events and no limit: only the kernel's count is left.
    assertJson(
        "{\"type\":\"inventory\",\"leases\":[],\"volumes\":[],"
            + "\"node\":{\"kernelOomKills\":297}}",
        new Inventory(List.of(), List.of(), new NodeMemory(null, 297L, null)));
  }

  @Test
  void theSignInFramesAreSpelledExactlyAsTheContractSaysKeyOrderIncluded() {
    assertJson(
        "{\"type\":\"signIn\",\"signInId\":\"" + S1 + "\",\"harness\":\"kimi\"}",
        new SignIn(S1, "kimi"));
    assertJson(
        "{\"type\":\"signInCode\",\"signInId\":\"" + S1 + "\",\"code\":\"abc#def\"}",
        new SignInCode(S1, "abc#def"));
    assertJson(
        "{\"type\":\"signInCancel\",\"signInId\":\"" + S1 + "\"}", new SignInCancel(S1));
    assertJson(
        "{\"type\":\"signInProgress\",\"signInId\":\""
            + S1
            + "\",\"harness\":\"kimi\",\"state\":\"AWAITING_BROWSER\",\"url\":\""
            + KIMI_URL
            + "\",\"userCode\":\"ABCD-1234\",\"detail\":null}",
        new SignInProgress(
            S1, "kimi", SignInState.AWAITING_BROWSER, KIMI_URL, "ABCD-1234", null));
  }

  @Test
  void theDockerFramesAreSpelledExactlyAsTheDossierSaysKeyOrderIncluded() {
    assertJson(
        "{\"type\":\"docker\",\"requestId\":\""
            + R1
            + "\",\"op\":\"EXEC\",\"name\":\""
            + LEASE_CONTAINER
            + "\",\"tail\":null,\"since\":null,\"argv\":[\"cat\",\"/etc/os-release\"],"
            + "\"timeoutSeconds\":30}",
        new Docker(
            R1, DockerOp.EXEC, LEASE_CONTAINER, null, null, List.of("cat", "/etc/os-release"), 30));
    assertJson(
        "{\"type\":\"docker\",\"requestId\":\""
            + R1
            + "\",\"op\":\"LIST_VOLUMES\",\"name\":null,\"tail\":null,\"since\":null,"
            + "\"argv\":null,\"timeoutSeconds\":null}",
        new Docker(R1, DockerOp.LIST_VOLUMES, null, null, null, null, null));
    assertJson(
        "{\"type\":\"docker-result\",\"requestId\":\""
            + R1
            + "\",\"status\":\"OK\",\"body\":\"hi\\n\",\"truncated\":false,\"exitCode\":0,"
            + "\"stderr\":\"\"}",
        new DockerResult(R1, DockerStatus.OK, "hi\n", false, 0, ""));
    assertJson(
        "{\"type\":\"docker-result\",\"requestId\":\""
            + R1
            + "\",\"status\":\"REFUSED\",\"body\":\"\",\"truncated\":false,\"exitCode\":null,"
            + "\"stderr\":null}",
        new DockerResult(R1, DockerStatus.REFUSED, null, false, null, null));
  }

  @Test
  void aDockerFrameWithoutAUuidRequestIdOrWithAnUnknownOpOrStatusIsMalformed() {
    assertMalformed(Map.of("type", "docker", "op", "LIST_CONTAINERS"));
    assertMalformed(Map.of("type", "docker", "requestId", "req-1", "op", "LIST_CONTAINERS"));
    assertMalformed(Map.of("type", "docker", "requestId", R1.toString()));
    assertMalformed(Map.of("type", "docker", "requestId", R1.toString(), "op", "PRUNE"));
    assertMalformed(
        Map.of("type", "docker", "requestId", R1.toString(), "op", "LOGS", "tail", "x"));
    assertMalformed(Map.of("type", "docker-result", "status", "OK", "body", ""));
    assertMalformed(Map.of("type", "docker-result", "requestId", R1.toString()));
    assertMalformed(
        Map.of("type", "docker-result", "requestId", R1.toString(), "status", "MAYBE"));
  }

  @Test
  void aDockerResultReadsAnAbsentBodyAsEmptyAndEitherBoxForItsNumbers() {
    assertEquals(
        new DockerResult(R1, DockerStatus.OK, "", false, 3, null),
        CODEC.decode(
            Map.of(
                "type", "docker-result", "requestId", R1.toString(), "status", "OK", "exitCode",
                3L)));
    assertEquals(
        new Docker(R1, DockerOp.LOGS, LEASE_CONTAINER, 5000, null, null, null),
        CODEC.decode(
            Map.of(
                "type", "docker", "requestId", R1.toString(), "op", "LOGS", "name", LEASE_CONTAINER,
                "tail", 5000.0)));
  }

  @Test
  void aSignInFrameWithoutItsIdHarnessCodeOrAKnownStateIsMalformed() {
    assertMalformed(Map.of("type", "signIn", "harness", "claude"));
    assertMalformed(Map.of("type", "signIn", "signInId", S1));
    assertMalformed(Map.of("type", "signInCode", "signInId", S1));
    assertMalformed(Map.of("type", "signInCode", "code", "abc"));
    assertMalformed(Map.of("type", "signInCancel"));
    assertMalformed(Map.of("type", "signInProgress", "signInId", S1));
    assertMalformed(Map.of("type", "signInProgress", "signInId", S1, "state", "WAITING"));
    assertMalformed(Map.of("type", "signInProgress", "state", "STARTING"));
  }

  @Test
  void aSignInCodeNeverPrintsItsCodeAndTheTerminalStatesAreTheFourEnds() {
    assertEquals(
        "SignInCode[signInId=" + S1 + ", code=<redacted>]",
        new SignInCode(S1, "secret-code").toString());
    assertEquals(
        Set.of(
            SignInState.SUCCEEDED, SignInState.FAILED, SignInState.EXPIRED, SignInState.CANCELLED),
        Arrays.stream(SignInState.values())
            .filter(SignInState::terminal)
            .collect(Collectors.toSet()));
  }

  private static void assertJson(String expected, SystemRunnerMessage frame) {
    assertEquals(expected, Json.write(CODEC.encode(frame)), () -> "spelling of " + frame);
  }

  // --- tolerance and refusals --------------------------------------------------------------------

  @Test
  void unknownFieldsAreIgnoredAndEitherBoxForANumberIsFine() {
    Map<String, Object> estate =
        new LinkedHashMap<>(CODEC.encode(new Estate(List.of(workspace()), List.of())));
    estate.put("priority", 7);
    @SuppressWarnings("unchecked")
    Map<String, Object> lease =
        new LinkedHashMap<>(((List<Map<String, Object>>) estate.get("leases")).getFirst());
    @SuppressWarnings("unchecked")
    Map<String, Object> spec = new LinkedHashMap<>((Map<String, Object>) lease.get("spec"));
    @SuppressWarnings("unchecked")
    Map<String, Object> limits = new LinkedHashMap<>((Map<String, Object>) spec.get("limits"));
    limits.put("pidsLimit", 4096); // an Integer, as Jackson decodes a small number
    spec.put("limits", limits);
    spec.put("network", "qits-net"); // a field this version cannot express is not obeyed
    spec.put("hostDockerSocket", true);
    spec.put("mounts", List.of(Map.of("source", "/", "target", "/host")));
    lease.put("spec", spec);
    estate.put("leases", List.of(lease));
    assertEquals(new Estate(List.of(workspace()), List.of()), CODEC.decode(estate));
  }

  @Test
  void anInventoryOfTheOldShapeDecodesWithEveryMemoryFactNull() {
    Map<String, Object> lease = new LinkedHashMap<>();
    lease.put("leaseId", L1);
    lease.put("state", "EXITED");
    lease.put("specHash", "h");
    lease.put("exitCode", 137);
    lease.put("detail", null);
    Map<String, Object> old = new LinkedHashMap<>();
    old.put("type", "inventory");
    old.put("leases", List.of(lease));
    old.put("volumes", List.of());
    Inventory inventory = (Inventory) CODEC.decode(old);
    assertEquals(
        new Inventory(
            List.of(new LeaseReport(L1, LeaseState.EXITED, "h", 137, null)), List.of(), null),
        inventory);
    LeaseReport report = inventory.leases().getFirst();
    assertNull(report.oomKills());
    assertNull(report.oomEvents());
    assertNull(report.memoryPeakBytes());
    assertNull(report.oomKilled());
    assertNull(inventory.node());

    // Either box for a number, a null node, and a node that is not an object all read tolerantly.
    lease.put("oomKills", 3);
    lease.put("memoryPeakBytes", 4.294967296E9);
    lease.put("oomKilled", "yes");
    old.put("node", "not-an-object");
    Inventory tolerant = (Inventory) CODEC.decode(old);
    assertEquals(3L, tolerant.leases().getFirst().oomKills());
    assertEquals(4L << 30, tolerant.leases().getFirst().memoryPeakBytes());
    assertNull(tolerant.leases().getFirst().oomKilled());
    assertNull(tolerant.node());
    old.put("node", null);
    assertNull(((Inventory) CODEC.decode(old)).node());
    old.put("node", Map.of("kernelOomKills", 5));
    assertEquals(new NodeMemory(null, 5L, null), ((Inventory) CODEC.decode(old)).node());
  }

  @Test
  void anUnknownTypeIsTheBasesTypedError() {
    RunnerDecodeException e =
        assertThrows(
            RunnerDecodeException.class, () -> CODEC.decode(Map.of("type", "take", "leaseId", L1)));
    assertEquals(RunnerDecodeException.Reason.UNKNOWN_TYPE, e.reason());
  }

  @Test
  void anEstateWithoutWarmImagesNamesNoneAsEveryServerBeforeItSends() {
    Map<String, Object> old = new LinkedHashMap<>();
    old.put("type", "estate");
    old.put("leases", List.of());
    old.put("purges", List.of());
    assertEquals(List.of(), ((Estate) CODEC.decode(old)).warmImages());
    old.put("warmImages", null);
    assertEquals(List.of(), ((Estate) CODEC.decode(old)).warmImages());
    old.put("warmImages", List.of(WARM_TAG));
    assertEquals(List.of(WARM_TAG), ((Estate) CODEC.decode(old)).warmImages());
    assertMalformed(Map.of("type", "estate", "leases", List.of(), "warmImages", "not-a-list"));
  }

  @Test
  void anEstateOrInventoryWithoutItsListIsMalformedNeverEmpty() {
    // Read as empty, an absent `leases` would have the runner end every lease on its node.
    assertMalformed(Map.of("type", "estate", "purges", List.of()));
    assertMalformed(Map.of("type", "estate", "leases", Map.of()));
    assertMalformed(Map.of("type", "inventory", "volumes", List.of()));
    assertMalformed(Map.of("type", "inventory", "leases", List.of()));
    assertMalformed(
        Map.of(
            "type", "inventory", "leases", List.of(Map.of("state", "RUNNING")), "volumes", List.of()));
  }

  @Test
  void aMissingLeaseTypeDesiredSpecHashOrImageAndAnUnknownStateAreMalformed() {
    Map<String, Object> spec = Map.of("image", WS_IMAGE);
    assertMalformed(estateOf(Map.of("slotType", "WORKSPACE", "desired", "RUNNING", "spec", spec, "specHash", "h")));
    assertMalformed(estateOf(Map.of("leaseId", L1, "desired", "RUNNING", "spec", spec, "specHash", "h")));
    assertMalformed(estateOf(Map.of("leaseId", L1, "slotType", "BATCH", "desired", "RUNNING", "spec", spec, "specHash", "h")));
    assertMalformed(estateOf(Map.of("leaseId", L1, "slotType", "WORKSPACE", "spec", spec, "specHash", "h")));
    assertMalformed(estateOf(Map.of("leaseId", L1, "slotType", "WORKSPACE", "desired", "PAUSED", "spec", spec, "specHash", "h")));
    assertMalformed(estateOf(Map.of("leaseId", L1, "slotType", "WORKSPACE", "desired", "RUNNING", "specHash", "h")));
    assertMalformed(estateOf(Map.of("leaseId", L1, "slotType", "WORKSPACE", "desired", "RUNNING", "spec", spec)));
    assertMalformed(
        estateOf(
            Map.of(
                "leaseId", L1, "slotType", "WORKSPACE", "desired", "RUNNING", "spec",
                Map.of("env", Map.of()), "specHash", "h")));
    assertMalformed(
        Map.of(
            "type",
            "inventory",
            "leases",
            List.of(Map.of("leaseId", L1, "state", "EXPLODED")),
            "volumes",
            List.of()));
  }

  @Test
  void anEndedEntryNeedsNoSpecAndAnUnknownLoginPresenceIsUnknown() {
    Estate estate =
        (Estate) CODEC.decode(estateOf(Map.of("leaseId", L2, "slotType", "DESK", "desired", "ENDED")));
    assertEquals(
        new LeaseEntry(L2, SlotType.DESK, DesiredState.ENDED, null, null, null, null),
        estate.leases().getFirst());
    assertEquals(
        new LoginState(LoginPresence.UNKNOWN, LoginPresence.UNKNOWN, null, null),
        CODEC.decode(Map.of("type", "loginState", "claude", "EXPIRED")));
  }

  private static Map<String, Object> estateOf(Map<String, Object> lease) {
    return Map.of("type", "estate", "leases", List.of(lease), "purges", List.of());
  }

  private static void assertMalformed(Map<String, Object> wire) {
    RunnerDecodeException e =
        assertThrows(RunnerDecodeException.class, () -> CODEC.decode(wire), wire::toString);
    assertEquals(RunnerDecodeException.Reason.MALFORMED, e.reason(), wire::toString);
  }

  /**
   * The JSON a map becomes, in its own iteration order — what Jackson and Vert.x both write for
   * these shapes. Test-only and deliberately minimal: strings, numbers, booleans, null, maps, lists.
   */
  static final class Json {

    static String write(Object value) {
      StringBuilder out = new StringBuilder();
      write(value, out);
      return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
      switch (value) {
        case null -> out.append("null");
        case String s -> {
          out.append('"');
          for (char c : s.toCharArray()) {
            switch (c) {
              case '"' -> out.append("\\\"");
              case '\\' -> out.append("\\\\");
              case '\n' -> out.append("\\n");
              default -> out.append(c);
            }
          }
          out.append('"');
        }
        case Number n -> out.append(n);
        case Boolean b -> out.append(b);
        case Map<?, ?> m -> {
          out.append('{');
          boolean first = true;
          for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!first) {
              out.append(',');
            }
            first = false;
            write(String.valueOf(e.getKey()), out);
            out.append(':');
            write(e.getValue(), out);
          }
          out.append('}');
        }
        case List<?> l -> {
          out.append('[');
          for (int i = 0; i < l.size(); i++) {
            if (i > 0) {
              out.append(',');
            }
            write(l.get(i), out);
          }
          out.append(']');
        }
        default -> throw new IllegalArgumentException("not a wire value: " + value.getClass());
      }
    }
  }
}
