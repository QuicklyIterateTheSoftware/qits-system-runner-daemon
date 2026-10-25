package eu.wohlben.qits.systemrunner;

import eu.wohlben.qits.containers.driver.ContainersDriver;
import eu.wohlben.qits.runner.toolkit.LifecycleArgv;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.systemrunner.protocol.LoginPresence;
import eu.wohlben.qits.systemrunner.protocol.LoginState;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.jboss.logging.Logger;

/**
 * Whether the agent CLIs are logged in on this target's credential volume — presence only.
 * qits-workspaces-runner-daemon's {@code LoginProbe}, three-valued and naming the volume, with one
 * change: it runs {@code Ack.probeImage} (the pinned workspace image) and <b>pulls it when it is
 * absent</b>, under the runner's own login — the desk runner's probe waited for an image a desk had
 * pulled, and a node that had none reported nothing ({@code login: null}).
 *
 * <ul>
 *   <li>Claude Code: {@code claude auth status} with {@code HOME=/claude-home} and {@code
 *       CLAUDE_CONFIG_DIR=/claude-home/.claude}, read by {@code loggedIn} in the JSON it prints —
 *       and PRESENT only once {@link ClaudeFirstRun}'s keys are there too: a logged-in CLI still
 *       stops a lease at its onboarding screen, so a login without them has them merged in here
 *       and is ABSENT when that fails (UNKNOWN when docker could not run the merge);
 *   <li>Kimi Code: the credential file's presence under {@code /claude-home/.kimi-code} — the flat
 *       {@code credentials} file or {@code credentials/kimi-code.json}.
 * </ul>
 *
 * <p><b>Nothing a probe prints is logged, kept or sent</b> beyond the one presence each: {@code auth
 * status} names the account. A probe that could not run — no probe image, a pull that failed, a
 * missing external credential volume, docker refusing, the {@link #DEADLINE} passing — is {@link
 * LoginPresence#UNKNOWN}, never ABSENT: an operator told "not logged in" would log in again to fix
 * a docker fault.
 *
 * <p>Each probe container is {@code qits-system-runner-login-probe-<id8>}, {@code --rm} and carries
 * the runner's ownership label, so one that outlives its deadline still goes by itself — a leftover
 * is removed before the next probe takes its name, and at the latest with the runner at
 * decommission. The user is {@code 1000}, the workspace image's own: a login a probe wrote as root
 * would read PRESENT here and stay unreadable by the leases that use it.
 */
final class LoginProbe {

  private static final Logger LOG = Logger.getLogger(LoginProbe.class);

  /** Why a probe or a sign-in cannot run when {@code Ack.probeImage} is not set. */
  static final String NO_IMAGE = "the host named no probe image";

  /** Each probe's: a {@code claude auth status} that has not answered by then is not answering. */
  static final Duration DEADLINE = Duration.ofSeconds(30);

  /** The user every probe runs as — the login command's {@code --user 1000}. */
  static final String USER = "1000";

  static final String CLAUDE_HOME = SlotPolicy.CLAUDE_HOME;
  static final String CLAUDE_CONFIG_DIR = SlotPolicy.CLAUDE_CONFIG_DIR_VALUE;

  /** Kimi's home inside the agent home ({@code KIMI_CODE_HOME}), as every lease image reads it. */
  static final String KIMI_HOME = CLAUDE_HOME + "/.kimi-code";

  /** {@code AgentAuthStatus}'s Kimi check, word for word: either credential layout. */
  static final String KIMI_CHECK =
      "test -f \"$KIMI_CODE_HOME/credentials\" "
          + "|| test -f \"$KIMI_CODE_HOME/credentials/kimi-code.json\"";

  private final RunnerIdentity identity;
  private final String targetId;
  private final RunnerDocker docker;
  private final TargetVolumes volumes;
  private final TargetNames names;
  private final ClaudeFirstRun firstRun;
  private final Clock clock;

  /**
   * @param docker the runner's docker under the probe's own {@link #DEADLINE}
   */
  LoginProbe(
      RunnerIdentity identity,
      String targetId,
      RunnerDocker docker,
      TargetVolumes volumes,
      Clock clock) {
    this.identity = identity;
    this.targetId = targetId;
    this.docker = docker;
    this.volumes = volumes;
    this.names = new TargetNames(identity, targetId);
    this.firstRun = new ClaudeFirstRun(docker, volumes, names.firstRun());
    this.clock = clock;
  }

  /** The first-run keys, against the same volume under the same deadline. */
  ClaudeFirstRun firstRun() {
    return firstRun;
  }

  /**
   * Both probes against {@code image} ({@code Ack.probeImage}; null when the host named none),
   * pulled with {@code pull} when it is not here. Never throws.
   *
   * @param pull the runner's pull: empty on success, else docker's words
   */
  synchronized LoginState probe(
      ContainersDriver driver, String image, Function<String, Optional<String>> pull) {
    String checkedAt = clock.instant().toString();
    String volume = volumes.credential();
    try {
      Optional<String> cannot = prepare(driver, image, pull);
      if (cannot.isPresent()) {
        if (NO_IMAGE.equals(cannot.get())) {
          LOG.infof("system-runner's login probe cannot run: %s", cannot.get());
        } else {
          LOG.warnf("system-runner's login probe cannot run: %s", cannot.get());
        }
        return unknown(checkedAt, volume);
      }
      LoginPresence claude = claudeLogin(driver, image);
      if (claude == LoginPresence.PRESENT) {
        claude = firstRunKeys(driver, image);
      }
      driver.remove(names.loginProbe(), docker.deadline());
      LoginPresence kimi = kimi(docker.run(kimiArgv(image)));
      LOG.infof("system-runner's login probe on %s: claude %s, kimi %s", volume, claude, kimi);
      return new LoginState(claude, kimi, checkedAt, volume);
    } catch (RuntimeException e) {
      LOG.warnf("system-runner's login probe could not run: %s", e.getMessage());
      return unknown(checkedAt, volume);
    }
  }

  /**
   * Why nothing can run against the credential volume with {@code image}: no image named, a pull
   * that failed, a missing external volume, an own volume docker would not make. Empty when the
   * image is here and the volume is there — what both the probe and a sign-in need first.
   */
  Optional<String> prepare(
      ContainersDriver driver, String image, Function<String, Optional<String>> pull) {
    if (image == null || image.isBlank()) {
      return Optional.of(NO_IMAGE);
    }
    Optional<String> notHere = NodeImages.ensure(docker, pull, image);
    if (notHere.isPresent()) {
      return Optional.of("could not pull " + image + ": " + LeaseLauncher.brief(notHere.get()));
    }
    if (volumes.credentialExternal()) {
      // The operator's credential volume: asked after, never made, so a missing one stays
      // missing rather than reading ABSENT out of an empty volume docker would create.
      return volumes.credentialMissing(docker);
    }
    ContainersDriver.OpResult home =
        volumes.ensureNode(driver, volumes.credential(), docker.deadline());
    return home.ok() ? Optional.empty() : Optional.of("no credential volume: " + home.detail());
  }

  /** {@code claude auth status} alone, read as {@link #claude}: what a sign-in asks first. */
  synchronized LoginPresence claudeLogin(ContainersDriver driver, String image) {
    driver.remove(names.loginProbe(), docker.deadline());
    return claude(docker.run(claudeArgv(image)));
  }

  /**
   * A logged-in Claude Code's first-run keys: PRESENT when they are there or merged in now, ABSENT
   * when the merge ran and they are still missing, UNKNOWN when docker could not run it.
   */
  private LoginPresence firstRunKeys(ContainersDriver driver, String image) {
    RunnerDocker.Result keys = firstRun.ensure(driver, image);
    if (keys.ok()) {
      return LoginPresence.PRESENT;
    }
    LoginPresence presence = byExit(keys);
    LOG.warnf(
        "system-runner's login probe: claude is logged in but its first-run keys are missing and"
            + " could not be written (exit %d), so it reads %s: %s",
        keys.exitCode(), presence, LeaseLauncher.brief(keys.detail()));
    return presence;
  }

  /**
   * {@code docker run --rm --name <login-probe> --label <owner> --user 1000 --entrypoint claude -v
   * <credential>:/claude-home -e HOME=/claude-home -e CLAUDE_CONFIG_DIR=/claude-home/.claude
   * <image> auth status}.
   */
  List<String> claudeArgv(String image) {
    return List.of(
        docker.binary(),
        "run",
        "--rm",
        "--name",
        names.loginProbe(),
        "--label",
        ownership(),
        "--user",
        USER,
        "--entrypoint",
        "claude",
        "-v",
        volumes.credential() + ":" + CLAUDE_HOME,
        "-e",
        "HOME=" + CLAUDE_HOME,
        "-e",
        "CLAUDE_CONFIG_DIR=" + CLAUDE_CONFIG_DIR,
        LifecycleArgv.requireImage(image),
        "auth",
        "status");
  }

  /**
   * {@code docker run --rm --name <login-probe> --label <owner> --user 1000 --entrypoint bash -v
   * <credential>:/claude-home -e KIMI_CODE_HOME=/claude-home/.kimi-code <image> -c <KIMI_CHECK>}.
   */
  List<String> kimiArgv(String image) {
    return List.of(
        docker.binary(),
        "run",
        "--rm",
        "--name",
        names.loginProbe(),
        "--label",
        ownership(),
        "--user",
        USER,
        "--entrypoint",
        "bash",
        "-v",
        volumes.credential() + ":" + CLAUDE_HOME,
        "-e",
        "KIMI_CODE_HOME=" + KIMI_HOME,
        LifecycleArgv.requireImage(image),
        "-c",
        KIMI_CHECK);
  }

  /**
   * The workspaces runner's reading: the JSON's {@code loggedIn} wins whatever the exit code (a
   * signed-out CLI exits non-zero and still prints it); without it, exit 0 is a login, a run docker
   * could not make (125), whose command could not start (126, 127) or that timed out is UNKNOWN, and
   * any other non-zero exit is the CLI saying no.
   */
  static LoginPresence claude(RunnerDocker.Result result) {
    String out = result.stdout() == null ? "" : result.stdout();
    if (!result.timedOut() && out.contains("\"loggedIn\"")) {
      return out.replace(" ", "").contains("\"loggedIn\":true")
          ? LoginPresence.PRESENT
          : LoginPresence.ABSENT;
    }
    return byExit(result);
  }

  /** The Kimi check: exit 0 present, 1 absent, anything that was not the check running UNKNOWN. */
  static LoginPresence kimi(RunnerDocker.Result result) {
    return byExit(result);
  }

  private static LoginPresence byExit(RunnerDocker.Result result) {
    if (result.timedOut()) {
      return LoginPresence.UNKNOWN;
    }
    return switch (result.exitCode()) {
      case 0 -> LoginPresence.PRESENT;
      case -1, 125, 126, 127 -> LoginPresence.UNKNOWN;
      default -> LoginPresence.ABSENT;
    };
  }

  private static LoginState unknown(String checkedAt, String volume) {
    return new LoginState(LoginPresence.UNKNOWN, LoginPresence.UNKNOWN, checkedAt, volume);
  }

  private String ownership() {
    return identity.ownerLabel() + "=" + LifecycleArgv.requireRunnerId(targetId);
  }
}
