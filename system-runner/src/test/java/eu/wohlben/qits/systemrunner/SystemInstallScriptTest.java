package eu.wohlben.qits.systemrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.toolkit.LifecycleArgv;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.SelfSpec;
import eu.wohlben.qits.runner.toolkit.install.InstallScript;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerBinary;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The install script, as qits-system will render it for THIS runner: qits-runner-toolkit's
 * template ({@code InstallScript.script}) filled from {@link SystemRunnerIdentity#IDENTITY}.
 * There is no copy of the template here, and its contract — the {@code docker run} equals the
 * toolkit's successor argv — is the toolkit's {@code InstallScriptContractTest}. What this adds is
 * the target spelling, and the one property a target node depends on: <b>a re-run never removes a
 * lease container or volume</b>, because the only thing the script selects is the
 * runner's own process label.
 */
@EnabledOnOs(OS.LINUX)
class SystemInstallScriptTest {

  private static final RunnerIdentity IDENTITY = SystemRunnerIdentity.IDENTITY;
  private static final String REGISTRY = "registry.qits.example.eu";
  private static final String ID = "3f2a9c1e-0000-4000-8000-000000000001";
  private static final String ID8 = "3f2a9c1e";
  private static final String VERSION = SystemRunnerBinary.VERSION;
  private static final String IMAGE = REGISTRY + "/qits/qits-system-runner:" + VERSION;
  private static final InstallScript.InstallPage PAGE =
      new InstallScript.InstallPage("the system targets page", List.of());

  /**
   * A docker that records every argv, one line each, and answers {@code ps} with a container per
   * label it was asked for: the runner's own for the process label, a LEASE container for the
   * ownership label — so a script that selected by the latter would be seen removing it.
   */
  private static final String STUB =
      """
      printf 'docker %s\\n' "$*" >> "$W/calls.log"
      if [ "$1" = "--config" ]; then shift 2; fi
      case "$1" in
        login) cat > /dev/null ;;
        ps)
          for a in "$@"; do
            case "$a" in
              "label=qits.system.runner.process=$ID") echo runnercontainer1 ;;
              "label=qits.system.runner=$ID") echo leasecontainer1 ;;
            esac
          done
          ;;
        run)
          if [ "$2" = "-d" ]; then
            : > "$W/run-argv"
            for a in "$@"; do printf '%s\\n' "$a" >> "$W/run-argv"; done
            echo 4f6c1e0b2a9d
          fi
          ;;
      esac
      exit 0
      """;

  @TempDir Path work;

  @Test
  void itRendersWithTheTargetNamesAndParses() throws Exception {
    String script = InstallScript.script(IDENTITY, PAGE, REGISTRY);

    assertFalse(script.contains("{{"), script);
    assertTrue(script.contains("  image='" + IMAGE + "'\n"), script);
    for (String name :
        List.of(
            "QITS_SYSTEM_RUNNER_URL",
            "QITS_SYSTEM_RUNNER_ID",
            "QITS_SYSTEM_RUNNER_REGISTRATION_TOKEN",
            "QITS_SYSTEM_RUNNER_SLOTS")) {
      assertTrue(script.contains("require " + name + " \"${" + name + ":-}\""), name);
    }
    assertTrue(script.contains("name=\"qits-system-runner-$id8-$version\""), script);
    assertTrue(script.contains("volume=\"qits-system-runner-state-$id8\""), script);
    assertTrue(
        script.contains(
            "--filter \"label=qits.system.runner.process=$QITS_SYSTEM_RUNNER_ID\""),
        script);
    assertTrue(script.contains("paste the whole line from the system targets page."));
    for (String ci : List.of("QITS_CI_RUNNER_", "qits-ci-runner", "qits.ci.runner", "qits_tok_")) {
      assertFalse(script.contains(ci), ci);
    }

    Path file = work.resolve("runner-install.sh");
    Files.writeString(file, script);
    assertEquals(0, sh(List.of("sh", "-n", file.toString()), Map.of("PATH", "/usr/bin:/bin"), null));
  }

  @Test
  void aReRunRemovesOnlyTheRunnersOwnContainerNeverALeaseOrAVolume() throws Exception {
    Path stubs = Files.createDirectories(work.resolve("stubs"));
    Path docker = stubs.resolve("docker");
    Files.writeString(
        docker, "#!/bin/sh\nW='" + work + "'\nID='" + ID + "'\n" + STUB, StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(docker, PosixFilePermissions.fromString("rwxr-xr-x"));
    Path script = work.resolve("runner-install.sh");
    Files.writeString(script, InstallScript.script(IDENTITY, PAGE, REGISTRY));

    int exit =
        sh(
            List.of("sh"),
            Map.of(
                "PATH", stubs + ":/usr/bin:/bin",
                "QITS_SYSTEM_RUNNER_URL", "https://system.qits.example.eu",
                "QITS_SYSTEM_RUNNER_ID", ID,
                "QITS_SYSTEM_RUNNER_REGISTRATION_TOKEN", "qits_tok_FIXTURE",
                "QITS_SYSTEM_RUNNER_SLOTS", "2"),
            script);
    List<String> calls = Files.readAllLines(work.resolve("calls.log"));
    assertEquals(0, exit, String.join("\n", calls));

    List<String> listings = calls.stream().filter(c -> c.startsWith("docker ps")).toList();
    assertEquals(
        List.of("docker ps -aq --filter label=qits.system.runner.process=" + ID),
        listings,
        "the only selection is the runner's own process label");
    List<String> removals =
        calls.stream()
            .filter(c -> c.startsWith("docker rm") || c.startsWith("docker volume"))
            .toList();
    assertEquals(List.of("docker rm -f runnercontainer1"), removals);
    assertTrue(
        calls.contains(
            "docker run --rm --entrypoint rm -v qits-system-runner-state-"
                + ID8
                + ":/var/lib/qits-system-runner "
                + IMAGE
                + " -f /var/lib/qits-system-runner/client.json"),
        "the state volume is kept, its client.json removed: " + calls);
    assertTrue(
        calls.stream()
            .anyMatch(
                c ->
                    c.startsWith("docker run -d --name qits-system-runner-" + ID8 + "-" + VERSION)
                        && c.contains("--label qits.system.runner.process=" + ID)
                        && c.endsWith(IMAGE)),
        "the runner starts under its own name, label and image: " + calls);
  }

  /**
   * The toolkit contract's point 5 with the target identity: the install starts exactly the container
   * {@link LifecycleArgv#runSuccessor} would start in its place — read back from a {@code docker
   * inspect} of the container the script started — plus the token as {@code -e
   * QITS_SYSTEM_RUNNER_REGISTRATION_TOKEN}, the one element a successor never carries.
   */
  @Test
  void aFirstInstallStartsExactlyTheContainerASuccessorWouldBe() throws Exception {
    assertEquals(0, install());

    List<String> run = Files.readAllLines(work.resolve("run-argv"));
    String name = "qits-system-runner-" + ID8 + "-" + VERSION;
    String volume = "qits-system-runner-state-" + ID8;
    String inspect =
        """
        [{"Id": "4f6c1e0b2a9d0000000000000000000000000000000000000000000000000000",
          "Name": "/%1$s", "Image": "sha256:fixture",
          "Config": {"Image": "%2$s",
            "Env": ["QITS_SYSTEM_RUNNER_URL=https://system.qits.example.eu",
                    "QITS_SYSTEM_RUNNER_ID=%3$s",
                    "QITS_SYSTEM_RUNNER_SLOTS=2",
                    "QITS_SYSTEM_RUNNER_REGISTRATION_TOKEN=qits_tok_SPENT",
                    "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"],
            "Labels": {"qits.system.runner.process": "%3$s",
                       "qits.system.runner.version": "%4$s"}},
          "HostConfig": {"Binds": ["/var/run/docker.sock:/var/run/docker.sock",
                                   "%5$s:/var/lib/qits-system-runner"],
                         "NetworkMode": "bridge",
                         "RestartPolicy": {"Name": "unless-stopped", "MaximumRetryCount": 0}}}]
        """
            .formatted(name, IMAGE, ID, VERSION, volume);
    String image =
        """
        [{"Id": "sha256:fixture", "Config": {"Env":
          ["PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"]}}]
        """;
    List<String> successor =
        LifecycleArgv.runSuccessor(
            IDENTITY, "docker", ID, VERSION, IMAGE, SelfSpec.parse(inspect, image));
    List<String> expected = new ArrayList<>(successor.subList(1, successor.size() - 1));
    expected.add("-e");
    expected.add("QITS_SYSTEM_RUNNER_REGISTRATION_TOKEN");
    expected.add(successor.getLast());
    assertEquals(expected, run, "the install and a successor differ");
    assertEquals(
        List.of("run", "-d", "--name", name, "--restart", "unless-stopped"), run.subList(0, 6));
  }

  /**
   * The platform host's line: the page lists the three external-volume names as tuning, and a line
   * that sets them hands each to the runner's container as {@code -e} — whence a successor's
   * {@code SelfSpec} carries them on, as it carries every variable the container was given.
   */
  @Test
  void theExternalVolumeTuningReachesTheRunnersContainer() throws Exception {
    Path stubs = Files.createDirectories(work.resolve("stubs"));
    Path docker = stubs.resolve("docker");
    Files.writeString(
        docker, "#!/bin/sh\nW='" + work + "'\nID='" + ID + "'\n" + STUB, StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(docker, PosixFilePermissions.fromString("rwxr-xr-x"));
    Path script = work.resolve("runner-install.sh");
    InstallScript.InstallPage page =
        new InstallScript.InstallPage(
            "the system targets page",
            List.of(
                ExternalVolumes.CREDENTIAL_VOLUME,
                ExternalVolumes.M2_VOLUME,
                ExternalVolumes.PNPM_VOLUME));
    Files.writeString(script, InstallScript.script(IDENTITY, page, REGISTRY));
    Map<String, String> env = new java.util.HashMap<>();
    env.put("PATH", stubs + ":/usr/bin:/bin");
    env.put("QITS_SYSTEM_RUNNER_URL", "https://system.qits.example.eu");
    env.put("QITS_SYSTEM_RUNNER_ID", ID);
    env.put("QITS_SYSTEM_RUNNER_REGISTRATION_TOKEN", "qits_tok_FIXTURE");
    env.put("QITS_SYSTEM_RUNNER_SLOTS", "2");
    env.put("QITS_SYSTEM_RUNNER_CREDENTIAL_VOLUME", "qits_shared_dot_claude");
    env.put("QITS_SYSTEM_RUNNER_M2_VOLUME", "qits_shared_m2");
    env.put("QITS_SYSTEM_RUNNER_PNPM_VOLUME", "qits_shared_pnpm");
    assertEquals(0, sh(List.of("sh"), env, script));

    List<String> run = Files.readAllLines(work.resolve("run-argv"));
    for (String variable :
        List.of(
            "QITS_SYSTEM_RUNNER_CREDENTIAL_VOLUME=qits_shared_dot_claude",
            "QITS_SYSTEM_RUNNER_M2_VOLUME=qits_shared_m2",
            "QITS_SYSTEM_RUNNER_PNPM_VOLUME=qits_shared_pnpm")) {
      int at = run.indexOf(variable);
      assertTrue(at > 0 && run.get(at - 1).equals("-e"), variable + ": " + run);
    }
  }

  /** The install line, run against the stub docker: the script on stdin, the four values in env. */
  private int install() throws Exception {
    Path stubs = Files.createDirectories(work.resolve("stubs"));
    Path docker = stubs.resolve("docker");
    Files.writeString(
        docker, "#!/bin/sh\nW='" + work + "'\nID='" + ID + "'\n" + STUB, StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(docker, PosixFilePermissions.fromString("rwxr-xr-x"));
    Path script = work.resolve("runner-install.sh");
    Files.writeString(script, InstallScript.script(IDENTITY, PAGE, REGISTRY));
    return sh(
        List.of("sh"),
        Map.of(
            "PATH", stubs + ":/usr/bin:/bin",
            "QITS_SYSTEM_RUNNER_URL", "https://system.qits.example.eu",
            "QITS_SYSTEM_RUNNER_ID", ID,
            "QITS_SYSTEM_RUNNER_REGISTRATION_TOKEN", "qits_tok_FIXTURE",
            "QITS_SYSTEM_RUNNER_SLOTS", "2"),
        script);
  }

  private static int sh(List<String> command, Map<String, String> env, Path stdin)
      throws IOException, InterruptedException {
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    builder.environment().clear();
    builder.environment().putAll(env);
    if (stdin != null) {
      builder.redirectInput(stdin.toFile());
    }
    Process process = builder.start();
    try (InputStream out = process.getInputStream()) {
      out.readAllBytes();
    }
    if (!process.waitFor(30, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new AssertionError("the script did not finish: " + command);
    }
    return process.exitValue();
  }
}
