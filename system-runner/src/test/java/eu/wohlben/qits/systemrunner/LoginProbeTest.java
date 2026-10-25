package eu.wohlben.qits.systemrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.testing.FakeDocker;
import eu.wohlben.qits.systemrunner.protocol.LoginPresence;
import eu.wohlben.qits.systemrunner.protocol.LoginState;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link LoginProbe} against a node: the two argvs exactly, PRESENT, ABSENT and UNKNOWN, the
 * mounted volume named in every answer, and the probe image pulled when it is not on the node — the
 * desk runner's {@code login: null}, fixed.
 */
@EnabledOnOs(OS.LINUX)
class LoginProbeTest {

  private static final RunnerIdentity IDENTITY = SystemRunnerIdentity.IDENTITY;
  private static final String IMAGE = "registry.qits.example.eu/qits/workspace:2026.1023.1";
  private static final Instant NOW = Instant.parse("2026-10-23T07:38:19Z");
  private static final String DOT_CLAUDE = "qits-system-runner-dot-claude-r1";
  private static final String PROBE = "qits-system-runner-login-probe-r1";
  private static final String FIRST_RUN = "qits-system-runner-first-run-r1";

  @TempDir Path dir;
  private FakeDocker docker;
  private RunnerDocker runnerDocker;
  private LoginProbe probe;
  private final List<String> pulled = Collections.synchronizedList(new ArrayList<>());
  private Function<String, Optional<String>> pull =
      image -> {
        pulled.add(image);
        return Optional.empty();
      };

  @BeforeEach
  void setUp() throws Exception {
    docker = new FakeDocker(dir);
    runnerDocker = new RunnerDocker(IDENTITY, docker.binary(), Duration.ofSeconds(10));
    probe = probe(ExternalVolumes.NONE);
  }

  private LoginProbe probe(ExternalVolumes external) {
    return new LoginProbe(
        IDENTITY,
        "r1",
        runnerDocker,
        new TargetVolumes(IDENTITY, "r1", external),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void theProbesRunTheProbeImageAgainstTheCredentialVolumeExactly() throws Exception {
    claudeSays(0, "{\"loggedIn\": true}");
    kimiExits(0);

    probe.probe(runnerDocker.driver(), IMAGE, pull);

    List<List<String>> runs = docker.calls("run");
    assertEquals(
        List.of(
            List.of(
                "run", "--rm", "--name", PROBE, "--label", "qits.system.runner=r1", "--user", "1000",
                "--entrypoint", "claude", "-v", DOT_CLAUDE + ":/claude-home", "-e",
                "HOME=/claude-home", "-e", "CLAUDE_CONFIG_DIR=/claude-home/.claude", IMAGE, "auth",
                "status"),
            List.of(
                "run", "--rm", "--name", FIRST_RUN, "--user", "1000", "--entrypoint", "bash", "-v",
                DOT_CLAUDE + ":/claude-home", "-e", "HOME=/claude-home", "-e",
                "CLAUDE_CONFIG_DIR=/claude-home/.claude", IMAGE, "-c", ClaudeFirstRun.SCRIPT,
                "qits-first-run", "check"),
            List.of(
                "run", "--rm", "--name", PROBE, "--label", "qits.system.runner=r1", "--user", "1000",
                "--entrypoint", "bash", "-v", DOT_CLAUDE + ":/claude-home", "-e",
                "KIMI_CODE_HOME=/claude-home/.kimi-code", IMAGE, "-c",
                "test -f \"$KIMI_CODE_HOME/credentials\""
                    + " || test -f \"$KIMI_CODE_HOME/credentials/kimi-code.json\"")),
        runs);
    assertEquals(
        Map.of("qits.system.runner", "r1"),
        docker.volumes().get(DOT_CLAUDE),
        "the credential volume is made labelled before a probe mounts it");
    assertTrue(pulled.isEmpty(), "the image is here: no pull");
  }

  @Test
  void loggedInIsPresentAndCarriesTheVolume() throws Exception {
    claudeSays(0, "{\"loggedIn\": true, \"authMethod\": \"claude.ai\"}");
    kimiExits(0);

    assertEquals(
        new LoginState(LoginPresence.PRESENT, LoginPresence.PRESENT, NOW.toString(), DOT_CLAUDE),
        probe.probe(runnerDocker.driver(), IMAGE, pull));
  }

  @Test
  void loggedInFalseIsAbsentWhateverTheExitCodeAndNoKimiCredentialIsAbsent() throws Exception {
    claudeSays(1, "{\n  \"loggedIn\": false\n}");
    kimiExits(1);

    assertEquals(
        new LoginState(LoginPresence.ABSENT, LoginPresence.ABSENT, NOW.toString(), DOT_CLAUDE),
        probe.probe(runnerDocker.driver(), IMAGE, pull));
  }

  @Test
  void aRunThatNeverReachedTheCliIsUnknownNotAbsent() throws Exception {
    docker.answerFor("run", "status", 125, "", "docker: Error response from daemon: no space left");
    docker.answerFor("run", LoginProbe.KIMI_CHECK, 127, "", "exec: \"bash\": not found");

    assertEquals(
        new LoginState(LoginPresence.UNKNOWN, LoginPresence.UNKNOWN, NOW.toString(), DOT_CLAUDE),
        probe.probe(runnerDocker.driver(), IMAGE, pull));
  }

  @Test
  void anAbsentProbeImageIsPulledFirstAndThenProbed() throws Exception {
    docker.answerFor("image-inspect", IMAGE, 1, "", "Error: No such image: " + IMAGE);
    claudeSays(0, "{\"loggedIn\": true}");
    kimiExits(1);

    assertEquals(
        new LoginState(LoginPresence.PRESENT, LoginPresence.ABSENT, NOW.toString(), DOT_CLAUDE),
        probe.probe(runnerDocker.driver(), IMAGE, pull));
    assertEquals(List.of(IMAGE), pulled);
    assertEquals(3, docker.calls("run").size(), "claude, its first-run keys, kimi");
  }

  @Test
  void aPullThatFailsOrNoProbeImageIsUnknownAndRunsNothing() throws Exception {
    docker.answerFor("image-inspect", IMAGE, 1, "", "Error: No such image: " + IMAGE);
    Function<String, Optional<String>> refused = image -> Optional.of("unauthorized");

    assertEquals(
        new LoginState(LoginPresence.UNKNOWN, LoginPresence.UNKNOWN, NOW.toString(), DOT_CLAUDE),
        probe.probe(runnerDocker.driver(), IMAGE, refused));
    assertEquals(
        new LoginState(LoginPresence.UNKNOWN, LoginPresence.UNKNOWN, NOW.toString(), DOT_CLAUDE),
        probe.probe(runnerDocker.driver(), null, pull),
        "the host named no probe image");
    assertTrue(docker.calls("run").isEmpty(), docker.calls()::toString);
  }

  @Test
  void anExternalCredentialVolumeIsMountedAndNamedAndNeverCreated() throws Exception {
    LoginProbe shared =
        probe(new ExternalVolumes(Optional.of("qits_shared_dot_claude"), Optional.empty(), Optional.empty()));
    docker.answerFor("volume-inspect", "qits_shared_dot_claude", 0, "qits_shared_dot_claude\n", "");
    claudeSays(0, "{\"loggedIn\": true}");
    kimiExits(0);

    LoginState state = shared.probe(runnerDocker.driver(), IMAGE, pull);
    assertEquals("qits_shared_dot_claude", state.volume());
    assertEquals(LoginPresence.PRESENT, state.claude());
    assertTrue(docker.calls("run").getFirst().contains("qits_shared_dot_claude:/claude-home"));
    assertFalse(docker.volumes().containsKey("qits_shared_dot_claude"), "never created");

    docker.answerFor("volume-inspect", "qits_shared_dot_claude", 1, "", "Error: No such volume");
    LoginState missing = shared.probe(runnerDocker.driver(), IMAGE, pull);
    assertEquals(
        new LoginState(
            LoginPresence.UNKNOWN, LoginPresence.UNKNOWN, NOW.toString(), "qits_shared_dot_claude"),
        missing,
        "a missing external volume is not read as an empty login");
  }

  @Test
  void aLoginWithoutItsFirstRunKeysHasThemMergedInAndIsPresentOnceTheyAre() throws Exception {
    claudeSays(0, "{\"loggedIn\": true}");
    kimiExits(0);
    docker.answerFor("run", "check", 1, "", "");
    docker.answerFor("run", "apply", 0, "", "");

    assertEquals(
        new LoginState(LoginPresence.PRESENT, LoginPresence.PRESENT, NOW.toString(), DOT_CLAUDE),
        probe.probe(runnerDocker.driver(), IMAGE, pull));
    assertEquals(
        List.of("status", "check", "apply", LoginProbe.KIMI_CHECK),
        docker.calls("run").stream().map(List::getLast).toList());
  }

  @Test
  void aLoginWhoseKeysCannotBeWrittenIsAbsentAndADockerFaultThereIsUnknown() throws Exception {
    claudeSays(0, "{\"loggedIn\": true}");
    kimiExits(0);
    docker.answerFor("run", "check", 1, "", "");
    docker.answerFor("run", "apply", 1, "", "qits-first-run: the first-run keys are still missing");

    assertEquals(LoginPresence.ABSENT, probe.probe(runnerDocker.driver(), IMAGE, pull).claude());

    docker.answerFor("run", "apply", 125, "", "docker: Error response from daemon: no space left");
    assertEquals(LoginPresence.UNKNOWN, probe.probe(runnerDocker.driver(), IMAGE, pull).claude());
  }

  @Test
  void aSignedOutCliNeverHasItsKeysWritten() throws Exception {
    claudeSays(1, "{\"loggedIn\": false}");
    kimiExits(1);

    probe.probe(runnerDocker.driver(), IMAGE, pull);

    assertEquals(
        List.of("status", LoginProbe.KIMI_CHECK),
        docker.calls("run").stream().map(List::getLast).toList());
  }

  @Test
  void theReadingsAreTheWorkspacesRunners() {
    assertEquals(LoginPresence.PRESENT, LoginProbe.claude(result(0, "{\"loggedIn\":true}", false)));
    assertEquals(LoginPresence.ABSENT, LoginProbe.claude(result(1, "{\"loggedIn\": false}", false)));
    assertEquals(LoginPresence.PRESENT, LoginProbe.claude(result(0, "", false)));
    assertEquals(LoginPresence.ABSENT, LoginProbe.claude(result(1, "", false)));
    assertEquals(LoginPresence.UNKNOWN, LoginProbe.claude(result(125, "", false)));
    assertEquals(LoginPresence.UNKNOWN, LoginProbe.claude(result(0, "{\"loggedIn\":true}", true)));
    assertEquals(LoginPresence.UNKNOWN, LoginProbe.kimi(result(126, "", false)));
    assertEquals(LoginPresence.UNKNOWN, LoginProbe.kimi(result(-1, "", false)));
  }

  private static RunnerDocker.Result result(int code, String out, boolean timedOut) {
    return new RunnerDocker.Result(code, out, "", timedOut, false);
  }

  private void claudeSays(int code, String out) throws Exception {
    docker.answerFor("run", "status", code, out + "\n", "");
  }

  private void kimiExits(int code) throws Exception {
    docker.answerFor("run", LoginProbe.KIMI_CHECK, code, "", "");
  }
}
