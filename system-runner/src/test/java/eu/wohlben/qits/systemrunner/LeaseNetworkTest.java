package eu.wohlben.qits.systemrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.TeardownItem;
import eu.wohlben.qits.runner.toolkit.testing.FakeDocker;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The lease network against {@link FakeDocker} (qits-2269): its derived name, made only when a
 * read-only look says it is absent — an owner-labelled bridge without inter-container traffic —
 * "already exists" as made, and a failed create or a docker that cannot say failing with docker's
 * words.
 */
@EnabledOnOs(OS.LINUX)
class LeaseNetworkTest {

  private static final RunnerIdentity IDENTITY = SystemRunnerIdentity.IDENTITY;
  private static final String TARGET = "r1";
  private static final String NETWORK = "qits-system-runner-leases-r1";

  @TempDir Path dir;
  private FakeDocker docker;
  private LeaseNetwork network;

  @BeforeEach
  void setUp() throws Exception {
    docker = new FakeDocker(dir);
    network =
        new LeaseNetwork(
            IDENTITY, TARGET, new RunnerDocker(IDENTITY, docker.binary(), Duration.ofSeconds(10)));
  }

  @Test
  void itsNameIsTheTargetsOwnAndItsTeardownANetwork() {
    assertEquals(NETWORK, network.name());
    assertEquals(
        "qits-system-runner-leases-1a2b3c4d",
        LeaseNetwork.name(IDENTITY, "1a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8d"));
    assertEquals(new TeardownItem(TeardownItem.Type.NETWORK, NETWORK), network.teardown());
    for (int i = 0; i < 100; i++) {
      String name = LeaseNetwork.name(IDENTITY, UUID.randomUUID().toString());
      assertTrue(IDENTITY.ownsName(name), name);
      assertFalse(BuildPlane.PLATFORM_NAMES.contains(name), name);
    }
  }

  @Test
  void anAbsentNetworkIsCreatedAsAnOwnerLabelledBridgeWithoutInterContainerTraffic()
      throws Exception {
    docker.answerFor("network-inspect", NETWORK, 1, "", "Error: No such network: " + NETWORK);

    assertEquals(Optional.empty(), network.ensure());

    List<List<String>> creates =
        docker.calls("network").stream().filter(c -> c.contains("create")).toList();
    assertEquals(
        List.of(
            List.of(
                "network",
                "create",
                "--driver",
                "bridge",
                "--label",
                "qits.system.runner=r1",
                "--opt",
                "com.docker.network.bridge.enable_icc=false",
                NETWORK)),
        creates,
        docker.calls()::toString);
    List<List<String>> calls = docker.calls();
    assertEquals(
        List.of("network", "inspect", "--format", "{{.Driver}}", NETWORK),
        calls.getFirst(),
        "a read-only look first");
  }

  @Test
  void aPresentNetworkIsLeftAlone() throws Exception {
    docker.answerFor("network-inspect", NETWORK, 0, "bridge\n", "");

    assertEquals(Optional.empty(), network.ensure());

    assertEquals(
        List.of(List.of("network", "inspect", "--format", "{{.Driver}}", NETWORK)),
        docker.calls());
  }

  @Test
  void alreadyExistsCountsAsCreated() throws Exception {
    docker.answerFor("network-inspect", NETWORK, 1, "", "Error: No such network: " + NETWORK);
    docker.answerFor(
        "network-create",
        NETWORK,
        1,
        "",
        "Error response from daemon: network with name " + NETWORK + " already exists");

    assertEquals(Optional.empty(), network.ensure());
  }

  @Test
  void aFailedCreateFailsWithDockersWords() throws Exception {
    docker.answerFor("network-inspect", NETWORK, 1, "", "Error: No such network: " + NETWORK);
    docker.answerFor(
        "network-create", NETWORK, 1, "", "Error response from daemon: all pools are in use");

    String why = network.ensure().orElseThrow();

    assertTrue(why.contains("could not create the lease network " + NETWORK), why);
    assertTrue(why.contains("all pools are in use"), why);
  }

  @Test
  void aDockerThatCannotSayFailsWithItsWordsAndCreatesNothing() throws Exception {
    docker.answerFor(
        "network-inspect", NETWORK, 1, "", "Cannot connect to the Docker daemon at unix:///x");

    String why = network.ensure().orElseThrow();

    assertTrue(why.contains("docker could not say whether " + NETWORK + " exists"), why);
    assertTrue(why.contains("Cannot connect to the Docker daemon"), why);
    assertTrue(
        docker.calls("network").stream().noneMatch(c -> c.contains("create")),
        docker.calls()::toString);
  }
}
