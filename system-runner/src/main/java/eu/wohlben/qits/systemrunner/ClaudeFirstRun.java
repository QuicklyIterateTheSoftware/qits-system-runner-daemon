package eu.wohlben.qits.systemrunner;

import eu.wohlben.qits.containers.driver.ContainersDriver;
import eu.wohlben.qits.runner.toolkit.LifecycleArgv;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The keys Claude Code asks a person about on its first interactive start — onboarding, the trust
 * dialog for {@code /workspace}, the upsells, the theme — written into the credential volume so a
 * lease's agent starts straight into work. A login alone is not enough: a CLI that stops at its
 * onboarding screen inside a lease waits for a keypress nobody will give.
 *
 * <p><b>Only an absent key is written</b> (absent or {@code null}); a value somebody set is never
 * overwritten, whatever it is. {@link #KEYS} is the one definition of the set; the {@link #SCRIPT}
 * that checks and merges it is generated from it, and runs in the probe image — which carries
 * {@code bash}, {@code jq} and {@code claude} — as {@code docker run --rm --name
 * qits-system-runner-first-run-<id8> --user 1000 --entrypoint bash -v <credential>:/claude-home …
 * <image> -c <SCRIPT> qits-first-run check|apply}. The mode is the last argument so the command
 * reads as what it does; {@code lastOnboardingVersion} is {@code claude --version}'s first word in
 * that same container, else {@link #FALLBACK_VERSION}.
 *
 * <p>The files are created owned by uid 1000 when missing ({@code .claude.json} mode 600); one that
 * holds something jq cannot read is left alone and the merge fails, rather than replaced.
 *
 * <p><b>The container carries no owner label</b>, unlike the login probe: the toolkit's reconcile
 * removes every owner-labelled container that is not a held lease, and an {@code estate} arriving
 * mid-merge would kill a write into {@code .claude.json}. It is {@code --rm}, under the probe's
 * deadline, and its derived name is removed before each run and at decommission ({@code
 * teardown}).
 */
final class ClaudeFirstRun {

  /** {@code lastOnboardingVersion} when {@code claude --version} answers nothing usable. */
  static final String FALLBACK_VERSION = "2.1.283";

  static final String CONFIG = LoginProbe.CLAUDE_CONFIG_DIR + "/.claude.json";
  static final String SETTINGS = LoginProbe.CLAUDE_CONFIG_DIR + "/settings.json";

  /** The value of {@code lastOnboardingVersion}: the CLI's own version, read in the container. */
  static final Object CLI_VERSION = new Object();

  /**
   * One first-run key.
   *
   * @param file {@link #CONFIG} or {@link #SETTINGS}
   * @param path the key's path in that file's object
   * @param value a {@link Boolean}, an {@link Integer}, a {@link String} or {@link #CLI_VERSION}
   */
  record Key(String file, List<String> path, Object value) {}

  /** The set, and the only place it is spelled. */
  static final List<Key> KEYS =
      List.of(
          new Key(CONFIG, List.of("hasCompletedOnboarding"), true),
          new Key(CONFIG, List.of("lastOnboardingVersion"), CLI_VERSION),
          new Key(CONFIG, List.of("remoteDialogSeen"), true),
          new Key(CONFIG, List.of("fullscreenUpsellSeenCount"), 3),
          new Key(CONFIG, List.of("passesUpsellSeenCount"), 3),
          new Key(CONFIG, List.of("hasSeenAutoModeEntryWarning"), true),
          new Key(CONFIG, List.of("hasSeenTasksHint"), true),
          new Key(CONFIG, List.of("projects", "/workspace", "hasTrustDialogAccepted"), true),
          new Key(SETTINGS, List.of("skipDangerousModePermissionPrompt"), true),
          new Key(SETTINGS, List.of("tui"), "fullscreen"),
          new Key(SETTINGS, List.of("theme"), "dark"));

  /** What the script is called as ({@code $0}): only for its own error lines. */
  static final String SCRIPT_NAME = "qits-first-run";

  static final String CHECK = "check";
  static final String APPLY = "apply";

  /**
   * {@code check} exits 0 exactly when every key is present in both files; {@code apply} merges the
   * missing ones, then checks again. Generated once, from {@link #KEYS}.
   */
  static final String SCRIPT = script();

  private final RunnerDocker docker;
  private final TargetVolumes volumes;
  private final String name;

  /**
   * @param docker the probe's docker: every run is under its deadline
   * @param name the container's derived name, {@code qits-system-runner-first-run-<id8>}
   */
  ClaudeFirstRun(RunnerDocker docker, TargetVolumes volumes, String name) {
    this.docker = docker;
    this.volumes = volumes;
    this.name = name;
  }

  /**
   * Every key there: checked, and merged when one is missing. One run at a time, because the runs
   * share one container name.
   *
   * @return the run that decided: the check when it passed, else the merge (whose own check closes
   *     it) — {@link RunnerDocker.Result#ok()} when every key is now present
   */
  synchronized RunnerDocker.Result ensure(ContainersDriver driver, String image) {
    RunnerDocker.Result checked = run(driver, image, CHECK);
    if (checked.ok()) {
      return checked;
    }
    return run(driver, image, APPLY);
  }

  private RunnerDocker.Result run(ContainersDriver driver, String image, String mode) {
    driver.remove(name, docker.deadline());
    return docker.run(argv(image, mode));
  }

  /** The {@code docker run} for {@code mode} — {@link #CHECK} or {@link #APPLY}. */
  List<String> argv(String image, String mode) {
    return List.of(
        docker.binary(),
        "run",
        "--rm",
        "--name",
        name,
        "--user",
        LoginProbe.USER,
        "--entrypoint",
        "bash",
        "-v",
        volumes.credential() + ":" + LoginProbe.CLAUDE_HOME,
        "-e",
        "HOME=" + LoginProbe.CLAUDE_HOME,
        "-e",
        "CLAUDE_CONFIG_DIR=" + LoginProbe.CLAUDE_CONFIG_DIR,
        LifecycleArgv.requireImage(image),
        "-c",
        SCRIPT,
        SCRIPT_NAME,
        mode);
  }

  // --- the script --------------------------------------------------------------------------------

  private static String script() {
    return String.join(
        "\n",
        "set -u",
        "config=" + CONFIG,
        "settings=" + SETTINGS,
        "present() {",
        "  jq -e '" + checkProgram(CONFIG) + "' \"$config\" >/dev/null 2>&1 &&",
        "  jq -e '" + checkProgram(SETTINGS) + "' \"$settings\" >/dev/null 2>&1",
        "}",
        "merge() {",
        "  file=\"$1\" mode=\"$2\" program=\"$3\"",
        "  if [ -s \"$file\" ]; then",
        "    merged=$(jq --arg v \"$version\" \"$program\" \"$file\") \\",
        "      || { echo \"$0: $file is not JSON jq can read; left as it is\" >&2; return 1; }",
        "  else",
        "    merged=$(printf '{}' | jq --arg v \"$version\" \"$program\") || return 1",
        "  fi",
        "  if [ ! -e \"$file\" ]; then",
        "    (umask 077 && : > \"$file\") && chmod \"$mode\" \"$file\" || return 1",
        "  fi",
        "  printf '%s\\n' \"$merged\" > \"$file\"",
        "}",
        "if [ \"${1:-}\" = " + CHECK + " ]; then present; exit $?; fi",
        "mkdir -p \"$(dirname \"$config\")\" || exit 1",
        "version=$(claude --version 2>/dev/null | awk 'NR == 1 { print $1 }')",
        "case \"$version\" in [0-9]*) ;; *) version=" + FALLBACK_VERSION + " ;; esac",
        "merge \"$config\" 600 '" + mergeProgram(CONFIG) + "' || exit 1",
        "merge \"$settings\" 644 '" + mergeProgram(SETTINGS) + "' || exit 1",
        "present || {",
        "  echo \"$0: the first-run keys are still missing after the merge\" >&2; exit 1",
        "}",
        "");
  }

  /** {@code [getpath(p1), …] | all(. != null)}: true exactly when every key of the file is set. */
  static String checkProgram(String file) {
    return keysOf(file).stream()
            .map(k -> "getpath(" + path(k) + ")")
            .collect(Collectors.joining(", ", "[", "]"))
        + " | all(. != null)";
  }

  /** Each key of {@code file} set where it is absent or null, and nothing else touched. */
  static String mergeProgram(String file) {
    return "reduce "
        + keysOf(file).stream()
            .map(k -> "[" + path(k) + ", " + literal(k.value()) + "]")
            .collect(Collectors.joining(", ", "(", ")"))
        + " as [$p, $x] (.; if getpath($p) == null then setpath($p; $x) else . end)";
  }

  private static List<Key> keysOf(String file) {
    return KEYS.stream().filter(k -> k.file().equals(file)).toList();
  }

  private static String path(Key key) {
    return key.path().stream()
        .map(ClaudeFirstRun::quoted)
        .collect(Collectors.joining(",", "[", "]"));
  }

  private static String literal(Object value) {
    if (value == CLI_VERSION) {
      return "$v";
    }
    return switch (value) {
      case Boolean b -> b.toString();
      case Integer i -> i.toString();
      case String s -> quoted(s);
      default -> throw new IllegalArgumentException("not a first-run value: " + value);
    };
  }

  /** A JSON string; the set holds no quote, backslash or apostrophe, and the script needs none. */
  private static String quoted(String s) {
    if (s.contains("\"") || s.contains("\\") || s.contains("'")) {
      throw new IllegalArgumentException("a first-run key may not spell " + s);
    }
    return "\"" + s + "\"";
  }
}
