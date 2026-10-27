package eu.wohlben.qits.systemrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.driver.docker.ContainerProcess;
import eu.wohlben.qits.runner.toolkit.DockerCommand;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.systemrunner.protocol.Docker;
import eu.wohlben.qits.systemrunner.protocol.DockerOp;
import eu.wohlben.qits.systemrunner.protocol.DockerResult;
import eu.wohlben.qits.systemrunner.protocol.DockerStatus;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link DockerOperations} against a fake {@link DockerCommand}: each op's argv exactly, ownership
 * by name and by label before anything else runs, the runner's own container refused for the
 * mutating ops, the limits and caps, and the container's own secrets redacted from logs and exec.
 */
class DockerOperationsTest {

  private static final RunnerIdentity IDENTITY = SystemRunnerIdentity.IDENTITY;
  private static final String TARGET = "r1";
  private static final UUID ID = UUID.fromString("6f1e2d3c-4b5a-4968-8776-655443322110");
  private static final String LEASE = "qits-system-runner-lease-l1-r1";
  private static final String SELF = "qits-system-runner-r1";
  private static final String VOLUME = "qits-system-runner-work-ws-42-r1";
  private static final String TOKEN = "qits_tok_SECRET123";
  private static final String PASSWORD = "hunter22";
  private static final String LEGACY = "qits-workspaces-runner-ws-5053-8d45fd1d";
  private static final String LEGACY_ID = "a".repeat(64);

  /** What the fake answers per argv key, and every argv it was asked, with its deadline. */
  private final Map<String, RunnerDocker.Result> answers = new LinkedHashMap<>();

  private final List<List<String>> calls = new ArrayList<>();
  private final List<Duration> deadlines = new ArrayList<>();

  private final DockerOperations ops =
      new DockerOperations(
          IDENTITY,
          TARGET,
          DockerCommand.of(
              "docker",
              Duration.ofSeconds(10),
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
              }));

  private void answer(String argvPrefix, String stdout) {
    answer(argvPrefix, 0, stdout, "");
  }

  private void answer(String argvPrefix, int exit, String stdout, String stderr) {
    answers.put(argvPrefix, new RunnerDocker.Result(exit, stdout, stderr, false, false));
  }

  private static String inspect(Map<String, String> labels, String... env) {
    JsonObject container =
        new JsonObject()
            .put("Id", "abc")
            .put("Name", "/x")
            .put(
                "Config",
                new JsonObject()
                    .put("Env", new JsonArray(List.of(env)))
                    .put("Labels", new JsonObject(new LinkedHashMap<>(labels))));
    return new JsonArray().add(container).encode();
  }

  /** A lease container of this target, its environment carrying a token and a password. */
  private void leaseContainer() {
    answer(
        "docker inspect --type container " + LEASE,
        inspect(
            Map.of("qits.system.runner", TARGET, "qits.system.runner.lease", "l1"),
            "QITS_TOKEN=" + TOKEN,
            "DB_PASSWORD=" + PASSWORD,
            "GH_AUTH=short",
            "TZ=Europe/Berlin",
            "PATH=/usr/bin"));
  }

  /** {@code docker volume inspect} of {@code name}, with these labels. */
  private void volume(String name, Map<String, String> labels) {
    answer(
        "docker volume inspect " + name,
        new JsonArray()
            .add(
                new JsonObject()
                    .put("Name", name)
                    .put("Mountpoint", "/var/lib/docker/volumes/" + name + "/_data")
                    .put("Labels", new JsonObject(new LinkedHashMap<>(labels))))
            .encode());
  }

  /**
   * One owned lease container, adopted from the retired qits-workspaces-runner, mounting {@link
   * #LEGACY} and a bind; {@code docker ps -q} names it.
   */
  private void adoptedLease() {
    answer("docker ps -a -q --no-trunc --filter label=qits.system.runner=r1", LEGACY_ID + "\n");
    JsonObject container =
        new JsonObject()
            .put("Id", LEGACY_ID)
            .put(
                "Config",
                new JsonObject().put("Labels", new JsonObject().put("qits.system.runner", TARGET)))
            .put(
                "Mounts",
                new JsonArray()
                    .add(
                        new JsonObject()
                            .put("Type", "volume")
                            .put("Name", LEGACY)
                            .put("Destination", "/workspace"))
                    .add(
                        new JsonObject()
                            .put("Type", "bind")
                            .put("Source", "/var/run/docker.sock")
                            .put("Destination", "/var/run/docker.sock"))
                    .add(
                        new JsonObject()
                            .put("Type", "volume")
                            .put("Name", VOLUME)
                            .put("Destination", "/caches")));
    answer("docker inspect --type container " + LEGACY_ID, new JsonArray().add(container).encode());
  }

  /** No container of this target at all. */
  private void noOwnedContainers() {
    answer("docker ps -a -q", "");
  }

  private DockerResult ask(DockerOp op, String name) {
    return ops.answer(new Docker(ID, op, name, null, null, null, null));
  }

  // --- the listings ------------------------------------------------------------------------------

  @Test
  void theContainerListingSelectsByTheOwnerLabelAndAnswersOneJsonArray() {
    answer("docker ps", "{\"Names\":\"" + LEASE + "\",\"State\":\"running\"}\n{\"Names\":\"b\"}\n");

    DockerResult result = ask(DockerOp.LIST_CONTAINERS, null);

    assertEquals(
        List.of(
            List.of(
                "docker",
                "ps",
                "-a",
                "--filter",
                "label=qits.system.runner=r1",
                "--format",
                "{{json .}}")),
        calls);
    assertEquals(DockerStatus.OK, result.status());
    assertEquals(ID, result.requestId());
    JsonArray rows = new JsonArray(result.body());
    assertEquals(2, rows.size());
    assertEquals(LEASE, rows.getJsonObject(0).getString("Names"));
    assertFalse(result.truncated());
  }

  @Test
  void theVolumeListingSelectsByTheOwnerLabelAndAFailedListingIsFailedNeverEmpty() {
    noOwnedContainers();
    answer("docker volume ls", "{\"Name\":\"" + VOLUME + "\"}\n");
    DockerResult result = ask(DockerOp.LIST_VOLUMES, null);
    assertEquals(
        List.of(
            "docker",
            "volume",
            "ls",
            "--filter",
            "label=qits.system.runner=r1",
            "--format",
            "{{json .}}"),
        calls.getFirst());
    assertEquals(VOLUME, new JsonArray(result.body()).getJsonObject(0).getString("Name"));
    assertEquals("label", new JsonArray(result.body()).getJsonObject(0).getString("ownedBy"));
    assertEquals(2, calls.size(), "the labelled listing and the owned containers: " + calls);

    answers.clear();
    answer("docker volume ls", 1, "", "Cannot connect to the Docker daemon");
    DockerResult failed = ask(DockerOp.LIST_VOLUMES, null);
    assertEquals(DockerStatus.FAILED, failed.status());
    assertEquals("Cannot connect to the Docker daemon", failed.body());
  }

  // --- ownership ---------------------------------------------------------------------------------

  @Test
  void aNameOutsideTheRunnersPrefixIsNotFoundWithoutAskingDocker() {
    for (DockerOp op :
        List.of(DockerOp.INSPECT_CONTAINER, DockerOp.LOGS, DockerOp.STOP, DockerOp.REMOVE)) {
      for (String name : List.of("postgres", "qits-buildkitd", "qits-system-runner", "")) {
        assertEquals(DockerStatus.NOT_FOUND, ask(op, name).status(), op + " " + name);
      }
      DockerResult unnamed = ask(op, null);
      assertEquals(DockerStatus.NOT_FOUND, unnamed.status(), op + ": " + unnamed.body());
    }
    assertEquals(
        DockerStatus.NOT_FOUND,
        ops.answer(new Docker(ID, DockerOp.EXEC, "postgres", null, null, List.of("id"), null))
            .status());
    for (String name : List.of("", "--all", "a b")) {
      assertEquals(DockerStatus.NOT_FOUND, ask(DockerOp.INSPECT_VOLUME, name).status(), name);
    }
    assertEquals(DockerStatus.NOT_FOUND, ask(DockerOp.INSPECT_VOLUME, null).status());
    assertEquals(List.of(), calls, "docker never heard of them");
  }

  @Test
  void aVolumeOutsideThePrefixThatNoOwnedContainerMountsIsNotFoundAndNeverInspected() {
    adoptedLease();
    volume("postgres-data", Map.of());

    for (String name : List.of("postgres-data", "qits-buildkitd", "qits-system-runner")) {
      assertEquals(DockerStatus.NOT_FOUND, ask(DockerOp.INSPECT_VOLUME, name).status(), name);
    }
    assertTrue(
        calls.stream().noneMatch(c -> c.contains("volume")),
        "only the owned containers were read: " + calls);
  }

  @Test
  void aPrefixedContainerOrVolumeOfAnotherTargetIsNotFoundAndNothingIsDoneToIt() {
    answer(
        "docker inspect --type container " + LEASE,
        inspect(Map.of("qits.system.runner", "other-target"), "QITS_TOKEN=" + TOKEN));
    answer(
        "docker volume inspect " + VOLUME,
        new JsonArray()
            .add(
                new JsonObject()
                    .put("Name", VOLUME)
                    .put("Labels", new JsonObject().put("qits.system.runner", "other-target")))
            .encode());

    for (DockerOp op :
        List.of(DockerOp.INSPECT_CONTAINER, DockerOp.LOGS, DockerOp.STOP, DockerOp.REMOVE)) {
      DockerResult result = ask(op, LEASE);
      assertEquals(DockerStatus.NOT_FOUND, result.status(), op.name());
      assertFalse(result.body().contains(TOKEN));
    }
    noOwnedContainers();
    assertEquals(DockerStatus.NOT_FOUND, ask(DockerOp.INSPECT_VOLUME, VOLUME).status());
    assertTrue(
        calls.stream()
            .allMatch(c -> c.contains("inspect") || String.join(" ", c).startsWith("docker ps")),
        "only inspected: " + calls);
    assertTrue(calls.stream().noneMatch(c -> c.contains("stop") || c.contains("rm")));
  }

  @Test
  void aContainerDockerDoesNotKnowIsNotFoundAndADockerThatDidNotAnswerIsFailed() {
    assertEquals(DockerStatus.NOT_FOUND, ask(DockerOp.INSPECT_CONTAINER, LEASE).status());
    answers.put(
        "docker inspect",
        new RunnerDocker.Result(-1, "", "docker inspect did not answer within 10s", true, false));
    DockerResult failed = ask(DockerOp.INSPECT_CONTAINER, LEASE);
    assertEquals(DockerStatus.FAILED, failed.status());
    assertEquals("docker inspect did not answer within 10s", failed.body());
  }

  @Test
  void theRunnersOwnContainerMayBeReadButIsRefusedForExecStopAndRemove() {
    answer(
        "docker inspect --type container " + SELF,
        inspect(Map.of("qits.system.runner.process", TARGET), "QITS_SYSTEM_RUNNER_ID=r1"));
    answer("docker logs", "runner says hello\n");

    assertEquals(DockerStatus.OK, ask(DockerOp.INSPECT_CONTAINER, SELF).status());
    assertEquals("runner says hello\n", ask(DockerOp.LOGS, SELF).body());
    for (DockerOp op : List.of(DockerOp.STOP, DockerOp.REMOVE)) {
      assertEquals(DockerStatus.REFUSED, ask(op, SELF).status(), op.name());
    }
    assertEquals(
        DockerStatus.REFUSED,
        ops.answer(new Docker(ID, DockerOp.EXEC, SELF, null, null, List.of("sh"), null)).status());
    assertTrue(
        calls.stream().noneMatch(c -> List.of("stop", "rm", "exec").contains(c.get(1))),
        calls::toString);
  }

  // --- one container -----------------------------------------------------------------------------

  @Test
  void anInspectAnswersTheContainersObjectAsDockerSaidIt() {
    leaseContainer();
    DockerResult result = ask(DockerOp.INSPECT_CONTAINER, LEASE);
    assertEquals(DockerStatus.OK, result.status());
    assertEquals(List.of(List.of("docker", "inspect", "--type", "container", LEASE)), calls);
    JsonObject inspected = new JsonObject(result.body());
    assertEquals("abc", inspected.getString("Id"));
  }

  @Test
  void logsDefaultToTwoHundredLinesCapAtFiveThousandAndCarrySince() {
    leaseContainer();
    answer("docker logs", "out\n");

    ask(DockerOp.LOGS, LEASE);
    ops.answer(new Docker(ID, DockerOp.LOGS, LEASE, 100_000, "10m", null, null));
    ops.answer(new Docker(ID, DockerOp.LOGS, LEASE, 42, "2026-10-26T10:00:00Z", null, null));

    List<List<String>> logs = calls.stream().filter(c -> c.get(1).equals("logs")).toList();
    assertEquals(
        List.of(
            List.of("docker", "logs", "--tail", "200", LEASE),
            List.of("docker", "logs", "--tail", "5000", "--since", "10m", LEASE),
            List.of("docker", "logs", "--tail", "42", "--since", "2026-10-26T10:00:00Z", LEASE)),
        logs);
  }

  @Test
  void aSinceThatIsNotATimeIsRefusedBeforeDockerIsAsked() {
    leaseContainer();
    DockerResult result =
        ops.answer(new Docker(ID, DockerOp.LOGS, LEASE, null, "--follow", null, null));
    assertEquals(DockerStatus.REFUSED, result.status());
    assertEquals(List.of(), calls);
  }

  @Test
  void logsCombineBothStreamsAndRedactTheContainersOwnSecrets() {
    leaseContainer();
    answer(
        "docker logs",
        0,
        "starting with token " + TOKEN + " in TZ Europe/Berlin\n",
        "auth failed for password " + PASSWORD + " (short)\n");

    DockerResult result = ask(DockerOp.LOGS, LEASE);

    assertEquals(DockerStatus.OK, result.status());
    assertEquals(
        "starting with token *** in TZ Europe/Berlin\nauth failed for password *** (short)\n",
        result.body(),
        "a secret-named value is gone; a short one and a plain one stay");
    assertFalse(result.truncated());
  }

  @Test
  void logsPastTheCapAreCutAndSaySo() {
    leaseContainer();
    String big = "x".repeat(DockerOperations.OUTPUT_CAP + 10);
    answers.put("docker logs", new RunnerDocker.Result(0, big, "", false, false));
    DockerResult result = ask(DockerOp.LOGS, LEASE);
    assertEquals(DockerOperations.OUTPUT_CAP, result.body().length());
    assertTrue(result.truncated());

    answers.put(
        "docker logs",
        new RunnerDocker.Result(
            0, "head", "e".repeat(ContainerProcess.STDERR_TAIL_CHARS), false, false));
    assertTrue(ask(DockerOp.LOGS, LEASE).truncated(), "the stderr tail is full: some went before");

    answers.put("docker logs", new RunnerDocker.Result(0, "head", "", false, true));
    assertTrue(ask(DockerOp.LOGS, LEASE).truncated(), "docker's own stdout cap");
  }

  @Test
  void theCapNeverSplitsACharacter() {
    DockerOperations.Capped capped = DockerOperations.cap("aé€", 4);
    assertEquals("aé", capped.text());
    assertTrue(capped.truncated());
    assertEquals(new DockerOperations.Capped("aé€", false), DockerOperations.cap("aé€", 6));
  }

  @Test
  void anExecRunsTheArgvWithoutTtyOrStdinUnderItsTimeoutAndAnswersBothStreamsRedacted() {
    leaseContainer();
    answer("docker exec", 3, "env says " + TOKEN + "\n", "and " + PASSWORD + " too\n");

    DockerResult result =
        ops.answer(
            new Docker(
                ID, DockerOp.EXEC, LEASE, null, null, List.of("sh", "-c", "env; exit 3"), 60));

    assertEquals(
        List.of("docker", "exec", LEASE, "sh", "-c", "env; exit 3"),
        calls.getLast(),
        "no -i, no -t");
    assertEquals(Duration.ofSeconds(60), deadlines.getLast());
    assertEquals(DockerStatus.OK, result.status(), "the command ran; its exit code is its own");
    assertEquals(3, result.exitCode());
    assertEquals("env says ***\n", result.body());
    assertEquals("and *** too\n", result.stderr());
    assertFalse(result.truncated());
  }

  @Test
  void anExecTimeoutDefaultsToThirtySecondsAndNeverExceedsTwoMinutes() {
    leaseContainer();
    answer("docker exec", "");
    ops.answer(new Docker(ID, DockerOp.EXEC, LEASE, null, null, List.of("id"), null));
    assertEquals(Duration.ofSeconds(30), deadlines.getLast());
    ops.answer(new Docker(ID, DockerOp.EXEC, LEASE, null, null, List.of("id"), 3600));
    assertEquals(Duration.ofSeconds(120), deadlines.getLast());
  }

  @Test
  void anExecWithoutACommandIsRefusedAndOneThatTimedOutIsFailed() {
    leaseContainer();
    assertEquals(
        DockerStatus.REFUSED,
        ops.answer(new Docker(ID, DockerOp.EXEC, LEASE, null, null, List.of(), null)).status());
    assertEquals(
        DockerStatus.REFUSED,
        ops.answer(new Docker(ID, DockerOp.EXEC, LEASE, null, null, null, null)).status());

    answers.put(
        "docker exec",
        new RunnerDocker.Result(-1, "partial", "did not answer within 5s", true, false));
    DockerResult timedOut =
        ops.answer(new Docker(ID, DockerOp.EXEC, LEASE, null, null, List.of("sleep", "9"), 5));
    assertEquals(DockerStatus.FAILED, timedOut.status());
    assertEquals("the exec did not finish within 5s", timedOut.body());
    assertNull(timedOut.exitCode());
  }

  @Test
  void stopGivesTenSecondsAndRemoveForcesBothAfterTheOwnershipCheck() {
    leaseContainer();
    answer("docker stop", LEASE + "\n");
    answer("docker rm", LEASE + "\n");

    DockerResult stopped = ask(DockerOp.STOP, LEASE);
    assertEquals(List.of("docker", "stop", "-t", "10", LEASE), calls.getLast());
    assertEquals(Duration.ofSeconds(20), deadlines.getLast(), "docker's deadline plus the grace");
    assertEquals(new DockerResult(ID, DockerStatus.OK, LEASE, false, null, null), stopped);

    DockerResult removed = ask(DockerOp.REMOVE, LEASE);
    assertEquals(List.of("docker", "rm", "-f", LEASE), calls.getLast());
    assertEquals(DockerStatus.OK, removed.status());
    assertEquals(
        List.of("inspect", "stop", "inspect", "rm"), calls.stream().map(c -> c.get(1)).toList());
  }

  // --- one volume --------------------------------------------------------------------------------

  @Test
  void aVolumeInspectIsDockersVolumeInspectAloneWithoutAnyDf() {
    volume(VOLUME, Map.of("qits.system.runner", TARGET));

    DockerResult result = ask(DockerOp.INSPECT_VOLUME, VOLUME);

    assertEquals(DockerStatus.OK, result.status());
    JsonObject volume = new JsonObject(result.body());
    assertEquals(VOLUME, volume.getString("Name"));
    assertFalse(volume.containsKey("Size"));
    assertEquals(List.of(List.of("docker", "volume", "inspect", VOLUME)), calls);
    assertTrue(calls.stream().noneMatch(c -> c.contains("system") || c.contains("df")));
  }

  @Test
  void anUnlabelledVolumeAnOwnedContainerMountsIsListedAndInspectable() {
    adoptedLease();
    answer(
        "docker volume ls --filter",
        "{\"Name\":\"" + VOLUME + "\",\"Driver\":\"local\",\"Scope\":\"local\"}\n");
    answer(
        "docker volume ls --format",
        "{\"Name\":\"postgres-data\",\"Driver\":\"local\",\"Scope\":\"local\"}\n"
            + "{\"Name\":\""
            + LEGACY
            + "\",\"Driver\":\"local\",\"Scope\":\"local\"}\n"
            + "{\"Name\":\""
            + VOLUME
            + "\",\"Driver\":\"local\",\"Scope\":\"local\"}\n");

    DockerResult listed = ask(DockerOp.LIST_VOLUMES, null);

    assertEquals(DockerStatus.OK, listed.status());
    JsonArray rows = new JsonArray(listed.body());
    assertEquals(2, rows.size(), "each volume once, no foreign one: " + rows);
    assertEquals(VOLUME, rows.getJsonObject(0).getString("Name"));
    assertEquals("label", rows.getJsonObject(0).getString("ownedBy"));
    assertEquals(LEGACY, rows.getJsonObject(1).getString("Name"));
    assertEquals("mount", rows.getJsonObject(1).getString("ownedBy"));
    assertEquals("local", rows.getJsonObject(1).getString("Driver"));
    assertEquals(
        List.of(
            List.of(
                "docker",
                "volume",
                "ls",
                "--filter",
                "label=qits.system.runner=r1",
                "--format",
                "{{json .}}"),
            List.of(
                "docker",
                "ps",
                "-a",
                "-q",
                "--no-trunc",
                "--filter",
                "label=qits.system.runner=r1"),
            List.of("docker", "inspect", "--type", "container", LEGACY_ID),
            List.of("docker", "volume", "ls", "--format", "{{json .}}")),
        calls);

    calls.clear();
    volume(LEGACY, Map.of());
    DockerResult inspected = ask(DockerOp.INSPECT_VOLUME, LEGACY);
    assertEquals(DockerStatus.OK, inspected.status(), inspected.body());
    assertEquals(LEGACY, new JsonObject(inspected.body()).getString("Name"));
    assertEquals(List.of("docker", "volume", "inspect", LEGACY), calls.getLast());
    assertTrue(calls.stream().noneMatch(c -> c.contains("df")));
  }

  @Test
  void aPrefixedVolumeWithoutTheLabelIsAdmittedOnlyWhenAnOwnedContainerMountsIt() {
    String unmounted = "qits-system-runner-work-ws-7-r1";
    adoptedLease();
    volume(unmounted, Map.of());

    assertEquals(DockerStatus.NOT_FOUND, ask(DockerOp.INSPECT_VOLUME, unmounted).status());
  }

  @Test
  void theOwnedContainersThatCannotBeReadFailTheVolumeOpsNeverWidenThem() {
    answer("docker ps -a -q", 1, "", "Cannot connect to the Docker daemon");
    answer("docker volume ls", "");
    assertEquals(DockerStatus.FAILED, ask(DockerOp.LIST_VOLUMES, null).status());
    assertEquals(DockerStatus.FAILED, ask(DockerOp.INSPECT_VOLUME, LEGACY).status());
  }

  @Test
  void anUnreadableAnswerIsFailedNeverThrown() {
    answer("docker inspect", "not json");
    DockerResult result = ask(DockerOp.INSPECT_CONTAINER, LEASE);
    assertEquals(DockerStatus.FAILED, result.status());
    assertEquals(ID, result.requestId());
  }

  @Test
  void onlySecretNamedValuesOfSixOrMoreCharactersAreRedactedLongestFirst() {
    List<String> secrets =
        DockerOperations.secrets(
            new JsonArray(
                List.of(
                    "API_KEY=abcdef",
                    "apikey=abcdefgh",
                    "PRIVATE_KEY=-----BEGIN",
                    "MY_CREDENTIALS=credvalue",
                    "SECRET=12345",
                    "GIT_AUTHOR_NAME=someone",
                    "QITS_GIT_AUTH_HOST=githost.example:8080",
                    "BASIC_AUTH=basicvalue",
                    "HOME=/home/someone",
                    "NOEQUALS")));
    assertEquals(
        List.of("-----BEGIN", "basicvalue", "credvalue", "abcdefgh", "abcdef"), secrets);
    assertEquals("x *** y", DockerOperations.redact("x abcdefgh y", secrets));
    assertEquals("/home/someone", DockerOperations.redact("/home/someone", secrets));
  }
}
