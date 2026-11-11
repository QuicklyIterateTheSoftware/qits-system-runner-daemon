package eu.wohlben.qits.systemrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link LeaseContainers}' reading of one inspect line: the container's {@code --dns} servers from
 * {@code {{json .HostConfig.Dns}}} and its generation label (qits-2091), read by hand, and a line
 * of the older, shorter format read with the defaults.
 */
class LeaseContainersTest {

  private static final RunnerIdentity IDENTITY = SystemRunnerIdentity.IDENTITY;
  private static final String HEAD = "c1|running|0|l1|WORKSPACE|h|m|false";

  @Test
  void theDnsIsReadFromDockersJsonNullEmptyOrAList() {
    assertEquals(List.of(), parse(HEAD + "|null|<no value>").dns());
    assertEquals(List.of(), parse(HEAD + "|[]|<no value>").dns());
    assertEquals(
        List.of("100.96.0.1", "192.168.188.1"),
        parse(HEAD + "|[\"100.96.0.1\",\"192.168.188.1\"]|<no value>").dns());
    assertEquals(List.of("10.231.0.2"), parse(HEAD + "| [ \"10.231.0.2\" ] |").dns());
  }

  @Test
  void anUnreadableDnsIsNoneNeverAGuess() {
    assertEquals(List.of(), parse(HEAD + "|<no value>|").dns());
    assertEquals(List.of(), parse(HEAD + "|[\"100.96.0.1\",]|").dns());
    assertEquals(List.of(), parse(HEAD + "|[100.96.0.1]|").dns());
    assertEquals(List.of(), parse(HEAD + "|\"100.96.0.1\"|").dns());
    assertEquals(List.of(), parse(HEAD + "|[\"a\\\"b\"]|").dns());
  }

  @Test
  void theGenerationLabelIsANonNegativeNumberElseZero() {
    assertEquals(3, parse(HEAD + "|null|3").generation());
    assertEquals(0, parse(HEAD + "|null|<no value>").generation());
    assertEquals(0, parse(HEAD + "|null|").generation());
    assertEquals(0, parse(HEAD + "|null|three").generation());
    assertEquals(0, parse(HEAD + "|null|-2").generation());
  }

  @Test
  void aLineOfTheOlderFormatReadsAsNoDnsAndGenerationZero() {
    LeaseContainers.Seen old = parse("c1|running|0|l1|WORKSPACE|h|m");
    assertEquals(List.of(), old.dns());
    assertEquals(0, old.generation());
    LeaseContainers.Seen before = parse(HEAD);
    assertEquals(List.of(), before.dns());
    assertEquals(0, before.generation());
  }

  @Test
  void theInspectAsksForTheDnsAndTheGenerationLabelInTheSameFormat() {
    assertEquals("qits.system.runner.generation", LeaseContainers.generationLabel(IDENTITY));
    LeaseContainers containers = new LeaseContainers(IDENTITY, "r1", null);
    String format = containers.format();
    assertTrue(
        format.endsWith(
            "|{{.State.OOMKilled}}|{{json .HostConfig.Dns}}"
                + "|{{index .Config.Labels \"qits.system.runner.generation\"}}"),
        format);
  }

  private static LeaseContainers.Seen parse(String line) {
    return LeaseContainers.parse(line).orElseThrow();
  }
}
