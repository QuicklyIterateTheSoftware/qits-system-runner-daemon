package eu.wohlben.qits.systemrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.toolkit.RunnerLauncher;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerBinary;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import eu.wohlben.qits.runner.toolkit.RunnerSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link Main}'s one decision: which invocations {@code RunnerLauncher} answers before Quarkus
 * starts, handed no Vert.x. Pinned here because it leans on the toolkit's order — health, the
 * version probe, helper mode — never touching the Vert.x it is passed; a toolkit that started to
 * would fail these with a {@code NullPointerException} long before an image ever shipped.
 */
class MainTest {

  @TempDir Path state;

  @Test
  void healthTheVersionProbeAndHelperModeAnswerBeforeQuarkusAndNothingElseDoes() {
    assertTrue(Main.answersBeforeQuarkus(new String[] {"health"}, name -> null));
    assertTrue(
        Main.answersBeforeQuarkus(
            new String[0], Map.of("QITS_SYSTEM_RUNNER_PRINT_VERSION", "1")::get));
    assertTrue(
        Main.answersBeforeQuarkus(
            new String[0], Map.of("QITS_SYSTEM_RUNNER_DECOMMISSION", "r1")::get));

    assertFalse(Main.answersBeforeQuarkus(new String[0], name -> null));
    assertFalse(
        Main.answersBeforeQuarkus(
            new String[0], Map.of("QITS_SYSTEM_RUNNER_PRINT_VERSION", "0")::get));
    assertFalse(
        Main.answersBeforeQuarkus(
            new String[0], Map.of("QITS_SYSTEM_RUNNER_URL", "https://x")::get));
  }

  @Test
  void theVersionProbeAndHealthNeedNoVertx() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int code =
        RunnerLauncher.main(
            SystemRunnerIdentity.IDENTITY,
            SystemRunnerDomain::new,
            null,
            new String[0],
            Map.of("QITS_SYSTEM_RUNNER_PRINT_VERSION", "1")::get,
            new PrintStream(out, true, StandardCharsets.UTF_8));
    assertEquals(0, code);
    assertEquals(SystemRunnerBinary.VERSION + "\n", out.toString(StandardCharsets.UTF_8));

    int health =
        RunnerLauncher.main(
            SystemRunnerIdentity.IDENTITY,
            SystemRunnerDomain::new,
            null,
            new String[] {"health"},
            Map.of("QITS_SYSTEM_RUNNER_STATE_DIR", state.toString())::get,
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    assertEquals(1, health, "no heartbeat yet");
  }

  @Test
  void anInvalidExternalVolumeNameRefusesToStartBeforeTheToolkitRuns() {
    for (String bad : List.of("bad/name", "-leading", "two words", "a:b", "x".repeat(129))) {
      Map<String, String> env = new HashMap<>();
      env.put("QITS_SYSTEM_RUNNER_URL", "https://system.qits.example.eu");
      env.put("QITS_SYSTEM_RUNNER_ID", "r1");
      env.put("QITS_SYSTEM_RUNNER_REGISTRATION_TOKEN", "qits_tok_X");
      env.put("QITS_SYSTEM_RUNNER_STATE_DIR", state.toString());
      env.put("QITS_SYSTEM_RUNNER_M2_VOLUME", bad);
      // No Vert.x: a run that got past the refusal would fail on it, not exit 2.
      int code =
          Main.runner(
              null,
              new String[0],
              env::get,
              new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
      assertEquals(2, code, bad);
    }
  }

  @Test
  void theExternalVolumesParseUnsetBlankAndValidNamesAndNameTheVariableOfABadOne()
      throws Exception {
    assertEquals(
        ExternalVolumes.NONE, ExternalVolumes.parse(SystemRunnerIdentity.IDENTITY, name -> null));
    assertEquals(
        ExternalVolumes.NONE,
        ExternalVolumes.parse(
            SystemRunnerIdentity.IDENTITY,
            Map.of(
                    "QITS_SYSTEM_RUNNER_CREDENTIAL_VOLUME", "  ",
                    "QITS_SYSTEM_RUNNER_M2_VOLUME", "")
                ::get));
    assertEquals(
        new ExternalVolumes(
            Optional.of("qits_shared_dot_claude"),
            Optional.of("qits_shared_m2"),
            Optional.of("Shared.pnpm-1")),
        ExternalVolumes.parse(
            SystemRunnerIdentity.IDENTITY,
            Map.of(
                    "QITS_SYSTEM_RUNNER_CREDENTIAL_VOLUME", " qits_shared_dot_claude ",
                    "QITS_SYSTEM_RUNNER_M2_VOLUME", "qits_shared_m2",
                    "QITS_SYSTEM_RUNNER_PNPM_VOLUME", "Shared.pnpm-1")
                ::get));
    RunnerSettings.Invalid invalid =
        assertThrows(
            RunnerSettings.Invalid.class,
            () ->
                ExternalVolumes.parse(
                    SystemRunnerIdentity.IDENTITY,
                    Map.of("QITS_SYSTEM_RUNNER_PNPM_VOLUME", "_nope")::get));
    assertTrue(
        invalid.getMessage().startsWith("QITS_SYSTEM_RUNNER_PNPM_VOLUME "),
        invalid.getMessage());
  }
}
