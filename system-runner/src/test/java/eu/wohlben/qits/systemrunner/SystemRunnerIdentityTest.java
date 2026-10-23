package eu.wohlben.qits.systemrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.toolkit.KnownRunnerNamespaces;
import eu.wohlben.qits.runner.toolkit.OwnResources;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerBinary;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The node namespace the dossier promises, spelled out: every name and label the target runner
 * uses, derived from its identity — and the identity is the one the toolkit lists for this kind, so
 * no other runner on the node can reach into it.
 */
class SystemRunnerIdentityTest {

  private static final RunnerIdentity IDENTITY = SystemRunnerIdentity.IDENTITY;
  private static final String ID = "1a2b3c4d-0000-4000-8000-000000000001";
  private static final String LEASE = "8273c743-e0bb-4d93-90d4-391aa10d9151";
  private static final String DESK = "5ca0e7e9-7dd8-4128-bbdd-58aa40d9b397";

  @Test
  void theIdentityHasTheRootsTheToolkitKnowsForThisKind() {
    assertTrue(KnownRunnerNamespaces.SYSTEM.matches(IDENTITY));
    assertEquals("targets", IDENTITY.kind());
    assertEquals(SystemRunnerBinary.VERSION, IDENTITY.version());
    assertEquals("qits/qits-system-runner", IDENTITY.imageRepository());
    assertEquals("/var/lib/qits-system-runner", IDENTITY.defaultStateDir());
    assertEquals("/system/api/targets/" + ID + "/register", IDENTITY.registerPath(ID));
    assertEquals("qits-system-runner", IDENTITY.serviceName());
    assertEquals(1, IDENTITY.capabilityVersion());
    assertEquals("heldLeases", IDENTITY.vocabulary().heldField());
    assertEquals("adoptedLeases", IDENTITY.vocabulary().adoptedField());
  }

  @Test
  void everyNameOnTheNodeIsTheDossiersSpelling() {
    assertEquals("qits-system-runner-1a2b3c4d-2026.1023.1", IDENTITY.containerName(ID, "2026.1023.1"));
    assertEquals("qits-system-runner-state-1a2b3c4d", IDENTITY.stateVolume(ID));
    assertEquals("qits.system.runner", IDENTITY.ownerLabel());
    assertEquals("qits.system.runner.lease", IDENTITY.workLabel());
    assertEquals("qits.system.runner.process", IDENTITY.processLabel());
    assertEquals("qits.system.runner.version", IDENTITY.versionLabel());
    assertEquals("qits.system.runner.", IDENTITY.driverNamespace());
    assertEquals("QITS_SYSTEM_RUNNER_URL", IDENTITY.env("URL"));
    assertEquals("qits.system.runner.slot-type", LeaseContainers.slotTypeLabel(IDENTITY));
    assertEquals("qits.system.runner.spec-hash", LeaseContainers.specHashLabel(IDENTITY));

    TargetNames names = new TargetNames(IDENTITY, ID);
    assertEquals("qits-system-runner-lease-" + LEASE + "-1a2b3c4d", names.container(LEASE));
    assertEquals("qits-system-runner-work-ws-42-1a2b3c4d", names.workVolume("ws-42"));
    assertEquals(
        "qits-system-runner-work-desk-" + DESK + "-1a2b3c4d", names.workVolume("desk-" + DESK));
    assertEquals("qits-system-runner-login-probe-1a2b3c4d", names.loginProbe());
    assertTrue(names.isWorkVolume("qits-system-runner-work-ws-42-1a2b3c4d"));
    assertFalse(names.isWorkVolume("qits-system-runner-work-ws-42-99999999"), "another target's");
    assertFalse(names.isWorkVolume("qits-system-runner-dot-claude-1a2b3c4d"));
    assertFalse(names.isWorkVolume("qits_shared_dot_claude"));
    for (String bad : new String[] {"../x", "WS-1", "", "a b", "x;rm"}) {
      assertThrows(IllegalArgumentException.class, () -> names.container(bad), bad);
      assertThrows(IllegalArgumentException.class, () -> names.workVolume(bad), bad);
    }

    TargetVolumes own = new TargetVolumes(IDENTITY, ID, ExternalVolumes.NONE);
    assertEquals("qits-system-runner-dot-claude-1a2b3c4d", own.credential());
    assertEquals("qits-system-runner-m2-1a2b3c4d", own.m2());
    assertEquals("qits-system-runner-pnpm-1a2b3c4d", own.pnpm());
    TargetVolumes shared =
        new TargetVolumes(
            IDENTITY,
            ID,
            new ExternalVolumes(
                Optional.of("qits_shared_dot_claude"),
                Optional.of("qits_shared_m2"),
                Optional.of("qits_shared_pnpm")));
    assertEquals("qits_shared_dot_claude", shared.credential());
    assertEquals("qits_shared_m2", shared.m2());
    assertEquals("qits_shared_pnpm", shared.pnpm());
    assertTrue(shared.owned().isEmpty(), "an external volume is never the runner's to create");

    OwnResources resources =
        new OwnResources(IDENTITY, ID, new RunnerDocker(IDENTITY, "docker", Duration.ofSeconds(1)));
    assertEquals(
        Map.of("qits.system.runner", ID, "qits.system.runner.lease", LEASE),
        resources.labels(LEASE));
  }
}
