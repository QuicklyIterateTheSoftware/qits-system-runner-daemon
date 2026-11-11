package eu.wohlben.qits.systemrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.RunnerSettings;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link NodeVolumeRecord}, one case per row of its table — a named variable, {@code -}, unset or
 * blank against a record, nothing recorded — and the refusals: a bad name in the record, a record
 * that is not one, and a bad variable, which leaves the record as it was (qits-2088).
 */
class NodeVolumeRecordTest {

  private static final RunnerIdentity IDENTITY = SystemRunnerIdentity.IDENTITY;
  private static final String CREDENTIAL = "QITS_SYSTEM_RUNNER_CREDENTIAL_VOLUME";
  private static final String M2 = "QITS_SYSTEM_RUNNER_M2_VOLUME";
  private static final String PNPM = "QITS_SYSTEM_RUNNER_PNPM_VOLUME";

  @TempDir Path state;
  private Path file;
  private final Map<String, String> env = new HashMap<>();

  @BeforeEach
  void setUp() {
    file = state.resolve(NodeVolumeRecord.FILE);
    env.put("QITS_SYSTEM_RUNNER_URL", "https://system.qits.example.eu");
    env.put("QITS_SYSTEM_RUNNER_ID", "r1");
    env.put("QITS_SYSTEM_RUNNER_STATE_DIR", state.toString());
  }

  @Test
  void aNamedVariableIsUsedAndRecorded() throws Exception {
    env.put(CREDENTIAL, " qits_shared_dot_claude ");
    env.put(M2, "qits_shared_m2");

    assertEquals(
        new ExternalVolumes(
            Optional.of("qits_shared_dot_claude"), Optional.of("qits_shared_m2"), Optional.empty()),
        resolve());
    assertEquals(
        record("\"qits_shared_dot_claude\"", "\"qits_shared_m2\"", "null"), Files.readString(file));
  }

  @Test
  void anUnsetOrBlankVariableMeansWhatTheRecordHolds() throws Exception {
    Files.writeString(file, record("\"ext-dot-claude\"", "\"ext-m2\"", "null"));
    env.put(M2, "  ");

    assertEquals(
        new ExternalVolumes(Optional.of("ext-dot-claude"), Optional.of("ext-m2"), Optional.empty()),
        resolve(),
        "a rotate-token line names none: the record does");
    assertEquals(record("\"ext-dot-claude\"", "\"ext-m2\"", "null"), Files.readString(file));
  }

  @Test
  void aDashResetsToTheRunnersOwnAndRecordsNull() throws Exception {
    Files.writeString(file, record("\"ext-dot-claude\"", "\"ext-m2\"", "\"ext-pnpm\""));
    env.put(CREDENTIAL, "-");
    env.put(PNPM, " - ");

    assertEquals(
        new ExternalVolumes(Optional.empty(), Optional.of("ext-m2"), Optional.empty()), resolve());
    assertEquals(record("null", "\"ext-m2\"", "null"), Files.readString(file));
  }

  @Test
  void aVariableOverridesTheRecord() throws Exception {
    Files.writeString(file, record("\"ext-dot-claude\"", "null", "null"));
    env.put(CREDENTIAL, "other-dot-claude");

    assertEquals(
        new ExternalVolumes(Optional.of("other-dot-claude"), Optional.empty(), Optional.empty()),
        resolve());
    assertEquals(record("\"other-dot-claude\"", "null", "null"), Files.readString(file));
  }

  @Test
  void nothingRecordedIsTheRunnersOwnAndARecordWithoutTheKeyLikewise() throws Exception {
    assertFalse(Files.exists(file));
    assertEquals(ExternalVolumes.NONE, resolve());
    assertEquals(record("null", "null", "null"), Files.readString(file), "the choice is recorded");

    Files.writeString(file, "{\"m2\": \"ext-m2\"}");
    assertEquals(
        new ExternalVolumes(Optional.empty(), Optional.of("ext-m2"), Optional.empty()), resolve());
  }

  @Test
  void aBadNameInTheRecordRefusesTheStartNamingTheFile() throws Exception {
    Files.writeString(file, record("\"bad/name\"", "null", "null"));

    RunnerSettings.Invalid invalid = assertThrows(RunnerSettings.Invalid.class, this::resolve);
    assertTrue(invalid.getMessage().startsWith(file + "'s credential "), invalid.getMessage());

    Files.writeString(file, record("42", "null", "null"));
    invalid = assertThrows(RunnerSettings.Invalid.class, this::resolve);
    assertTrue(invalid.getMessage().startsWith(file + "'s credential "), invalid.getMessage());
  }

  @Test
  void aRecordThatIsNotOneRefusesTheStartRatherThanFallingBackToTheOwnVolumes() throws Exception {
    for (String corrupt : new String[] {"{\"credential\": \"ext-dot", "", "[]", "not json"}) {
      Files.writeString(file, corrupt);
      RunnerSettings.Invalid invalid = assertThrows(RunnerSettings.Invalid.class, this::resolve);
      assertTrue(invalid.getMessage().startsWith(file + " is not a usable"), invalid.getMessage());
      assertEquals(corrupt, Files.readString(file), "left for the operator to look at");
    }
  }

  @Test
  void aBadVariableRefusesTheStartNamingItAndLeavesTheRecordAlone() throws Exception {
    Files.writeString(file, record("\"ext-dot-claude\"", "null", "null"));
    env.put(PNPM, "_nope");

    RunnerSettings.Invalid invalid = assertThrows(RunnerSettings.Invalid.class, this::resolve);
    assertTrue(invalid.getMessage().startsWith(PNPM + " "), invalid.getMessage());
    assertEquals(record("\"ext-dot-claude\"", "null", "null"), Files.readString(file));
  }

  @Test
  void mainRefusesToStartOnACorruptRecord() throws Exception {
    env.put("QITS_SYSTEM_RUNNER_REGISTRATION_TOKEN", "qits_tok_X");
    Files.writeString(file, "{");
    // No Vert.x: a run that got past the refusal would fail on it, not exit 2.
    int code =
        Main.runner(
            null,
            new String[0],
            env::get,
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    assertEquals(2, code);
  }

  private ExternalVolumes resolve() throws RunnerSettings.Invalid {
    return NodeVolumeRecord.resolve(IDENTITY, RunnerSettings.parse(IDENTITY, env::get), env::get);
  }

  /** The record as {@link NodeVolumeRecord} writes it. */
  private static String record(String credential, String m2, String pnpm) {
    return "{\n  \"credential\" : "
        + credential
        + ",\n  \"m2\" : "
        + m2
        + ",\n  \"pnpm\" : "
        + pnpm
        + "\n}\n";
  }
}
