package eu.wohlben.qits.systemrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.TeardownItem;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The build plane's derived names, as the dossier spells them — and never qits-containers'
 * platform builder {@code qits-buildkitd} or its volume {@code qits-buildkitd-state}, in a name, a
 * teardown item or an argv element.
 */
class BuildPlaneNamesTest {

  private static final RunnerIdentity IDENTITY = SystemRunnerIdentity.IDENTITY;
  private static final String TARGET = "1a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8d";

  @Test
  void theBuilderNetworkAndStateVolumeAreTheTargetsOwn() {
    BuildPlane.Names names = BuildPlane.Names.of(IDENTITY, TARGET);
    assertEquals("qits-system-runner-buildkitd-1a2b3c4d", names.builder());
    assertEquals("qits-system-runner-build-1a2b3c4d", names.network());
    assertEquals("qits-system-runner-buildkitd-state-1a2b3c4d", names.stateVolume());
    assertEquals("tcp://qits-system-runner-buildkitd-1a2b3c4d:1234", names.address());
  }

  @Test
  void theTeardownIsTheBuilderThenItsNetworkThenItsVolume() {
    assertEquals(
        List.of(
            new TeardownItem(TeardownItem.Type.CONTAINER, "qits-system-runner-buildkitd-1a2b3c4d"),
            new TeardownItem(TeardownItem.Type.NETWORK, "qits-system-runner-build-1a2b3c4d"),
            new TeardownItem(
                TeardownItem.Type.VOLUME, "qits-system-runner-buildkitd-state-1a2b3c4d")),
        BuildPlane.Names.of(IDENTITY, TARGET).teardown());
  }

  @Test
  void noTargetEverNamesThePlatformsBuilderOrItsVolume() {
    for (int i = 0; i < 200; i++) {
      String id = UUID.randomUUID().toString();
      BuildPlane.Names names = BuildPlane.Names.of(IDENTITY, id);
      for (String name : List.of(names.builder(), names.network(), names.stateVolume())) {
        assertFalse(name.startsWith("qits-buildkitd"), name);
        assertTrue(name.startsWith("qits-system-runner-"), name);
        assertTrue(IDENTITY.ownsName(name), name);
      }
      for (TeardownItem item : names.teardown()) {
        assertFalse(item.name().startsWith("qits-buildkitd"), item.toString());
      }
    }
  }

  @Test
  void theBuildersArgvNeverMentionsThePlatformsBuilder() {
    BuildPlane plane =
        new BuildPlane(
            IDENTITY,
            TARGET,
            new RunnerDocker(IDENTITY, "docker", Duration.ofSeconds(5)),
            BuildkitSettings.DEFAULTS);
    List<String> argv = plane.runArgv(plane.configuredStamp());
    for (String element : argv) {
      assertFalse(element.equals("qits-buildkitd"), argv::toString);
      assertFalse(element.startsWith("qits-buildkitd-state"), argv::toString);
      assertFalse(element.startsWith("qits-buildkitd:"), argv::toString);
    }
    assertEquals(
        List.of(
            "docker",
            "run",
            "-d",
            "--name",
            "qits-system-runner-buildkitd-1a2b3c4d",
            "--network",
            "qits-system-runner-build-1a2b3c4d",
            "--label",
            "qits.system.runner.buildkitd=" + plane.configuredStamp(),
            "--privileged",
            "--restart",
            "unless-stopped",
            "-v",
            "qits-system-runner-buildkitd-state-1a2b3c4d:/var/lib/buildkit",
            "-e",
            "BUILDKITD_TOML=" + plane.toml(),
            "--entrypoint",
            "/bin/sh",
            "moby/buildkit:v0.33.0",
            "-c",
            BuildPlane.BOOTSTRAP),
        argv);
    assertFalse(
        argv.contains("qits.system.runner=" + TARGET),
        "never the owner label: the toolkit's reconcile would remove the builder");
  }
}
