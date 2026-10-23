package eu.wohlben.qits.systemrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.systemrunner.protocol.Adopt;
import eu.wohlben.qits.systemrunner.protocol.DesiredState;
import eu.wohlben.qits.systemrunner.protocol.LeaseEntry;
import eu.wohlben.qits.systemrunner.protocol.LeaseSpec;
import eu.wohlben.qits.systemrunner.protocol.SlotType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The two patterns exactly, and what they let a lease take over: a retired workspaces runner's
 * workspace for a WORKSPACE lease, a retired desk runner's desk for a DESK lease, and nothing else
 * — not the other kind's, not this runner's own, not a person's container, not a CI lease's.
 */
class LegacyAdoptionTest {

  private static final String UUID = "dc112edc-4360-4a3b-858b-e75d372e708f";
  private static final String OLD_WS = "qits-workspaces-runner-ws-" + UUID + "-1a2b3c4d";
  private static final String OLD_DESK = "qits-projects-desk-runner-desk-" + UUID + "-1a2b3c4d";

  @Test
  void thePatternsAreTheDossiersExactly() {
    assertEquals(
        "^qits-workspaces-runner-ws-[0-9a-f-]{36}-[0-9a-f]{8}$", LegacyAdoption.WORKSPACE.pattern());
    assertEquals(
        "^qits-projects-desk-runner-desk-[0-9a-f-]{36}-[0-9a-f]{8}$", LegacyAdoption.DESK.pattern());
  }

  @Test
  void aRetiredRunnersOwnNamesAreAllowedForTheirKindOnly() {
    assertTrue(LegacyAdoption.allowed(SlotType.WORKSPACE, OLD_WS));
    assertTrue(LegacyAdoption.allowed(SlotType.DESK, OLD_DESK));
    assertFalse(LegacyAdoption.allowed(SlotType.WORKSPACE, OLD_DESK));
    assertFalse(LegacyAdoption.allowed(SlotType.DESK, OLD_WS));
    assertFalse(LegacyAdoption.allowed(SlotType.CI, OLD_WS));
    assertEquals(Optional.empty(), LegacyAdoption.refusal(entry(SlotType.WORKSPACE, OLD_WS, OLD_WS)));
    assertEquals(Optional.empty(), LegacyAdoption.refusal(entry(SlotType.DESK, OLD_DESK, OLD_DESK)));
    LeaseEntry plain =
        new LeaseEntry(
            "l1",
            SlotType.DESK,
            DesiredState.RUNNING,
            new LeaseSpec("registry.qits.example.eu/qits/project-agent:1", Map.of(), "1000", null),
            "h1",
            "desk-1",
            null);
    assertEquals(Optional.empty(), LegacyAdoption.refusal(plain), "no adopt, nothing to refuse");
  }

  @Test
  void everythingElseIsRefusedAsAdoptNameNotAllowed() {
    List<String> refused =
        List.of(
            "qits_shared_dot_claude",
            "qits-system-runner-work-ws-42-1a2b3c4d",
            "qits-workspaces-runner-dot-claude-1a2b3c4d",
            "qits-workspaces-runner-state-1a2b3c4d",
            "qits-workspaces-runner-ws-42-1a2b3c4d",
            "qits-workspaces-runner-ws-" + UUID + "-1a2b3c4d-x",
            "qits-workspaces-runner-ws-" + UUID + "-1A2B3C4D",
            "x-qits-workspaces-runner-ws-" + UUID + "-1a2b3c4d",
            "qits-projects-desk-runner-dot-claude-1a2b3c4d",
            "qits-projects-desk-runner-desk-" + UUID.toUpperCase() + "-1a2b3c4d",
            "postgres",
            "");
    for (String name : refused) {
      assertFalse(LegacyAdoption.allowed(SlotType.WORKSPACE, name), name);
      assertFalse(LegacyAdoption.allowed(SlotType.DESK, name), name);
      assertEquals(
          Optional.of("adopt name not allowed"),
          LegacyAdoption.refusal(entry(SlotType.WORKSPACE, name, OLD_WS)),
          name);
      assertEquals(
          Optional.of("adopt name not allowed"),
          LegacyAdoption.refusal(entry(SlotType.WORKSPACE, OLD_WS, name)),
          name);
    }
    assertEquals(
        Optional.of("adopt name not allowed"),
        LegacyAdoption.refusal(entry(SlotType.WORKSPACE, null, OLD_WS)));
    assertEquals(
        Optional.of("adopt name not allowed"),
        LegacyAdoption.refusal(entry(SlotType.CI, OLD_WS, OLD_WS)));
  }

  @Test
  void onlyARetiredRunnersVolumeOrOneOfTheseRunnersOwnMayBePurged() {
    assertTrue(LegacyAdoption.purgeable(OLD_WS));
    assertTrue(LegacyAdoption.purgeable(OLD_DESK));
    assertFalse(LegacyAdoption.purgeable("qits_shared_dot_claude"));
    assertFalse(LegacyAdoption.purgeable("qits-workspaces-runner-dot-claude-1a2b3c4d"));
    assertFalse(LegacyAdoption.purgeable(null));
  }

  private static LeaseEntry entry(SlotType type, String container, String volume) {
    return new LeaseEntry(
        "l1",
        type,
        DesiredState.RUNNING,
        new LeaseSpec("registry.qits.example.eu/qits/workspace:1", Map.of(), "1000", null),
        "h1",
        "ws-1",
        new Adopt(container, volume));
  }
}
