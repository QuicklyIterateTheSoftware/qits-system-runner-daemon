package eu.wohlben.qits.systemrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.testing.FakeDocker;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ClaudeFirstRun}: the argv and the generated jq programs exactly, the check-then-merge
 * order — and the script itself, run verbatim by {@code bash} with {@code jq}, {@code
 * CLAUDE_CONFIG_DIR} pointed at a temp directory standing in for {@code /claude-home/.claude}:
 * missing keys are written, every existing value survives, the files it creates are mode 600 /
 * 644, and a run with {@code CLAUDE_CONFIG_DIR} unset refuses rather than touching anything.
 */
@EnabledOnOs(OS.LINUX)
class ClaudeFirstRunTest {

  private static final RunnerIdentity IDENTITY = SystemRunnerIdentity.IDENTITY;
  private static final String IMAGE = "registry.qits.example.eu/qits/workspace:2026.1023.1";
  private static final String NAME = "qits-system-runner-first-run-r1";

  @TempDir Path dir;

  @Test
  void theCheckProgramAsksForEveryKeyOfItsFile() {
    assertEquals(
        "[(getpath([\"hasCompletedOnboarding\"]) == true),"
            + " (getpath([\"lastOnboardingVersion\"]) != null),"
            + " (getpath([\"remoteDialogSeen\"]) == true),"
            + " (getpath([\"fullscreenUpsellSeenCount\"]) != null),"
            + " (getpath([\"passesUpsellSeenCount\"]) != null),"
            + " (getpath([\"hasSeenAutoModeEntryWarning\"]) == true),"
            + " (getpath([\"hasSeenTasksHint\"]) == true),"
            + " (getpath([\"projects\",\"/workspace\",\"hasTrustDialogAccepted\"]) == true)]"
            + " | all",
        ClaudeFirstRun.checkProgram(ClaudeFirstRun.CONFIG));
    assertEquals(
        "reduce ([[\"skipDangerousModePermissionPrompt\"], true], [[\"tui\"], \"fullscreen\"],"
            + " [[\"theme\"], \"dark\"]) as [$p, $x]"
            + " (.; if getpath($p) == null or ($x == true and getpath($p) == false)"
            + " then setpath($p; $x) else . end)",
        ClaudeFirstRun.mergeProgram(ClaudeFirstRun.SETTINGS));
    assertTrue(
        ClaudeFirstRun.mergeProgram(ClaudeFirstRun.CONFIG)
            .contains("[[\"lastOnboardingVersion\"], $v]"),
        "the version is the CLI's, read in the container");
    assertEquals("/claude-home/.claude/.claude.json", ClaudeFirstRun.CONFIG);
    assertEquals("/claude-home/.claude/settings.json", ClaudeFirstRun.SETTINGS);
  }

  @Test
  void itRunsTheProbeImageAsUid1000WithoutTheOwnerLabelAndChecksBeforeItMerges()
      throws Exception {
    FakeDocker docker = new FakeDocker(dir.resolve("docker"));
    RunnerDocker runner = new RunnerDocker(IDENTITY, docker.binary(), Duration.ofSeconds(10));
    ClaudeFirstRun firstRun =
        new ClaudeFirstRun(runner, new TargetVolumes(IDENTITY, "r1", ExternalVolumes.NONE), NAME);

    assertEquals(
        List.of(
            runner.binary(), "run", "--rm", "--name", NAME, "--user", "1000", "--entrypoint",
            "bash", "-v", "qits-system-runner-dot-claude-r1:/claude-home", "-e",
            "HOME=/claude-home", "-e", "CLAUDE_CONFIG_DIR=/claude-home/.claude", IMAGE, "-c",
            ClaudeFirstRun.SCRIPT, "qits-first-run", "apply"),
        firstRun.argv(IMAGE, ClaudeFirstRun.APPLY));

    assertTrue(firstRun.ensure(runner.driver(), IMAGE).ok(), "present: nothing to merge");
    assertEquals(List.of("check"), docker.calls("run").stream().map(List::getLast).toList());

    docker.answerFor("run", "check", 1, "", "");
    docker.answerFor("run", "apply", 0, "", "");
    assertTrue(firstRun.ensure(runner.driver(), IMAGE).ok());
    assertEquals(
        List.of("check", "check", "apply"),
        docker.calls("run").stream().map(List::getLast).toList());
    assertTrue(
        docker.calls("rm").stream().allMatch(c -> c.contains(NAME)),
        "its name is removed before every run");
    assertFalse(
        docker.calls("run").stream().anyMatch(c -> c.contains("--label")),
        "the reconcile removes owner-labelled containers");
  }

  // --- the script, run --------------------------------------------------------------------------

  @Test
  void theScriptWritesOnlyMissingKeysAndOpenFlagsAndKeepsEveryOtherValue() throws Exception {
    assumeTrue(onPath("jq") && onPath("bash"), "needs bash and jq");
    Path home = dir.resolve("claude-home");
    Path config = home.resolve(".claude").resolve(".claude.json");
    Files.createDirectories(config.getParent());
    Files.writeString(
        config,
        "{\"hasCompletedOnboarding\": false, \"theme\": \"keep\", \"oauthAccount\": {\"a\": 1},"
            + " \"projects\": {\"/workspace\": {\"allowedTools\": [\"x\"]}, \"/other\": {}},"
            + " \"fullscreenUpsellSeenCount\": null}");
    Path bin = fakeClaude("2.1.300 (Claude Code)");

    assertEquals(1, script(home, bin, "check").exit(), "keys are missing");
    Run applied = script(home, bin, "apply");
    assertEquals(0, applied.exit(), applied.output());
    assertEquals(0, script(home, bin, "check").exit());

    assertEquals(
        "true",
        jq(config, ".hasCompletedOnboarding"),
        "a flag left false is an open question: the launch still asks it");
    assertEquals("\"keep\"", jq(config, ".theme"), "a key of the other file is not touched");
    assertEquals("{\"a\":1}", jq(config, ".oauthAccount"));
    assertEquals("[\"x\"]", jq(config, ".projects[\"/workspace\"].allowedTools"));
    assertEquals("true", jq(config, ".projects[\"/workspace\"].hasTrustDialogAccepted"));
    assertEquals("{}", jq(config, ".projects[\"/other\"]"));
    assertEquals("\"2.1.300\"", jq(config, ".lastOnboardingVersion"));
    assertEquals("3", jq(config, ".fullscreenUpsellSeenCount"), "null counts as absent");
    assertEquals("3", jq(config, ".passesUpsellSeenCount"));
    for (String key :
        List.of("remoteDialogSeen", "hasSeenAutoModeEntryWarning", "hasSeenTasksHint")) {
      assertEquals("true", jq(config, "." + key), key);
    }
    Path settings = home.resolve(".claude").resolve("settings.json");
    assertEquals("true", jq(settings, ".skipDangerousModePermissionPrompt"));
    assertEquals("\"fullscreen\"", jq(settings, ".tui"));
    assertEquals("\"dark\"", jq(settings, ".theme"));
    assertEquals(
        "rw-r--r--",
        PosixFilePermissions.toString(Files.getPosixFilePermissions(settings)),
        "created");

    String before = Files.readString(config);
    assertEquals(0, script(home, bin, "apply").exit());
    assertEquals(before, Files.readString(config), "a second merge changes nothing");
  }

  @Test
  void theScriptCreatesTheFilesWhenMissingAndFallsBackToTheKnownVersion() throws Exception {
    assumeTrue(onPath("jq") && onPath("bash"), "needs bash and jq");
    Path home = dir.resolve("claude-home");
    Files.createDirectories(home);
    Path bin = fakeClaude(null);

    Run applied = script(home, bin, "apply");
    assertEquals(0, applied.exit(), applied.output());
    Path config = home.resolve(".claude").resolve(".claude.json");
    assertEquals(
        "rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(config)));
    assertEquals(
        "\"" + ClaudeFirstRun.FALLBACK_VERSION + "\"", jq(config, ".lastOnboardingVersion"));
    assertEquals("true", jq(config, ".hasCompletedOnboarding"));
  }

  @Test
  void theScriptNeverSpellsTheCredentialVolumePath() {
    assertFalse(ClaudeFirstRun.SCRIPT.contains("/claude-home"), ClaudeFirstRun.SCRIPT);
  }

  @Test
  void theScriptRefusesWithoutConfigDirAndTouchesNothing() throws Exception {
    assumeTrue(onPath("jq") && onPath("bash"), "needs bash and jq");
    Path home = dir.resolve("claude-home");
    Files.createDirectories(home);
    Path bin = fakeClaude("2.1.300 (Claude Code)");

    Run applied = script(home, bin, "apply", false);
    assertTrue(applied.exit() != 0, applied.output());
    assertTrue(applied.output().contains("CLAUDE_CONFIG_DIR is not set"), applied.output());
    try (var children = Files.list(home)) {
      assertTrue(children.findAny().isEmpty(), "nothing was created under home");
    }
  }

  @Test
  void aFileJqCannotReadIsLeftAsItIsAndTheMergeFails() throws Exception {
    assumeTrue(onPath("jq") && onPath("bash"), "needs bash and jq");
    Path home = dir.resolve("claude-home");
    Path config = home.resolve(".claude").resolve(".claude.json");
    Files.createDirectories(config.getParent());
    Files.writeString(config, "{not json");

    Run applied = script(home, fakeClaude("2.1.283 (Claude Code)"), "apply");
    assertEquals(1, applied.exit());
    assertTrue(applied.output().contains("is not JSON jq can read"), applied.output());
    assertEquals("{not json", Files.readString(config));
  }

  private record Run(int exit, String output) {}

  /**
   * {@link ClaudeFirstRun#SCRIPT} verbatim, as {@code bash -c} runs it in the container, with
   * {@code CLAUDE_CONFIG_DIR} pointed at {@code home}'s {@code .claude} and {@code HOME} at {@code
   * home}, so nothing outside the temp directory is ever touched.
   */
  private Run script(Path home, Path bin, String mode) throws Exception {
    return script(home, bin, mode, true);
  }

  /**
   * As {@link #script(Path, Path, String)}, but with {@code withConfigDir} false omitting {@code
   * CLAUDE_CONFIG_DIR} from the environment entirely, to prove the script refuses rather than
   * defaulting onto {@code HOME}.
   */
  private Run script(Path home, Path bin, String mode, boolean withConfigDir) throws Exception {
    List<String> argv =
        new ArrayList<>(List.of("bash", "-c", ClaudeFirstRun.SCRIPT, "qits-first-run", mode));
    ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
    pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
    pb.environment().put("HOME", home.toString());
    if (withConfigDir) {
      pb.environment().put("CLAUDE_CONFIG_DIR", home.resolve(".claude").toString());
    } else {
      pb.environment().remove("CLAUDE_CONFIG_DIR");
    }
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(p.waitFor(30, TimeUnit.SECONDS));
    return new Run(p.exitValue(), out);
  }

  /** A {@code claude} on PATH printing {@code version}, or failing when null. */
  private Path fakeClaude(String version) throws IOException {
    Path bin = dir.resolve("bin");
    Files.createDirectories(bin);
    Path claude = bin.resolve("claude");
    Files.writeString(
        claude,
        version == null ? "#!/bin/sh\nexit 127\n" : "#!/bin/sh\necho '" + version + "'\n");
    Files.setPosixFilePermissions(claude, PosixFilePermissions.fromString("rwxr-xr-x"));
    return bin;
  }

  private static String jq(Path file, String filter) throws Exception {
    Process p = new ProcessBuilder("jq", "-c", filter, file.toString()).start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
    assertEquals(0, p.waitFor(), "jq " + filter);
    return out;
  }

  private static boolean onPath(String tool) {
    try {
      return new ProcessBuilder("sh", "-c", "command -v " + tool).start().waitFor() == 0;
    } catch (IOException | InterruptedException e) {
      return false;
    }
  }
}
