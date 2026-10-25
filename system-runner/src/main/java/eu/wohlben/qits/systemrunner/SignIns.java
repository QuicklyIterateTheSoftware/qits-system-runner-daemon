package eu.wohlben.qits.systemrunner;

import eu.wohlben.qits.containers.driver.ContainersDriver;
import eu.wohlben.qits.runner.toolkit.LifecycleArgv;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.systemrunner.protocol.LoginPresence;
import eu.wohlben.qits.systemrunner.protocol.SignIn;
import eu.wohlben.qits.systemrunner.protocol.SignInCancel;
import eu.wohlben.qits.systemrunner.protocol.SignInCode;
import eu.wohlben.qits.systemrunner.protocol.SignInProgress;
import eu.wohlben.qits.systemrunner.protocol.SignInState;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jboss.logging.Logger;

/**
 * <b>The agent logins, run on the target itself</b> (qits-1593): a {@code signIn} starts the
 * harness's own login CLI in the probe image against the credential volume — the same volume,
 * user and environment the login probe reads — and relays what a person needs to finish it in a
 * browser as {@link SignInProgress} frames, so {@code qits targets sign-in} works from anywhere the
 * API answers, with no shell on the node.
 *
 * <ul>
 *   <li><b>Claude Code</b>: {@code claude auth status} first, as the probe asks it — already logged
 *       in is SUCCEEDED at once, with the first-run keys merged ({@link ClaudeFirstRun}), and no
 *       browser. Else STARTING, then {@code docker run --rm -i --name
 *       qits-system-runner-sign-in-claude-<id8> --user 1000 --entrypoint claude -v
 *       <credential>:/claude-home -e HOME=/claude-home -e CLAUDE_CONFIG_DIR=/claude-home/.claude
 *       <image> auth login}; the url on its {@code visit:} line is AWAITING_CODE, and the {@code
 *       signInCode}'s code goes to its stdin.
 *   <li><b>Kimi Code</b>: STARTING, then the same with {@code --entrypoint kimi}, {@code -e
 *       KIMI_CODE_HOME=/claude-home/.kimi-code} as the probe sets it, and {@code login}; its url
 *       and user code are AWAITING_BROWSER — the person approves the device, nothing comes back.
 * </ul>
 *
 * <p>Exit 0 is SUCCEEDED — after Claude Code's first-run keys are merged — and then the login probe
 * runs and sends its {@code loginState}, as at every other moment the login may have changed. Any
 * other exit is FAILED with the last line the CLI printed. A {@code signInCancel} is CANCELLED and
 * the {@link #DEADLINE} passing EXPIRED; either kills the process and removes its container.
 *
 * <p><b>One sign-in per harness</b>, and its container name is the harness's, so a leftover is
 * removed before the next one runs. A second {@code signIn} for a harness that has one running is
 * FAILED, naming the running one. Everything slow — the pull, the status run, the merge, the kill —
 * runs on {@code work}, never on the socket's thread.
 *
 * <p><b>The code is never logged</b>, and of what the CLI prints only a failure's last line is —
 * the url and the rest of the transcript are relayed to the host and nowhere else.
 *
 * <p>The sign-in containers carry no owner label, for {@link ClaudeFirstRun}'s reason: the
 * reconcile removes every owner-labelled container that is not a held lease, and an {@code estate}
 * arriving while a person reads the browser would end the login under them.
 */
final class SignIns {

  private static final Logger LOG = Logger.getLogger(SignIns.class);

  /** How long a sign-in may wait for its person: the server's own deadline too. */
  static final Duration DEADLINE = Duration.ofMinutes(10);

  static final String CLAUDE = "claude";
  static final String KIMI = "kimi";

  /** The harnesses a sign-in can run, in the order their containers are torn down. */
  static final List<String> HARNESSES = List.of(CLAUDE, KIMI);

  private final LoginProbe probe;
  private final TargetNames names;
  private final TargetVolumes volumes;
  private final RunnerDocker docker;
  private final SignInProcesses processes;
  private final Executor work;
  private final ScheduledExecutorService timer;
  private final Duration deadline;
  private final Consumer<SignInProgress> sink;
  private final Runnable signedIn;

  /** The running sign-ins by harness; guarded by {@code this}. */
  private final Map<String, Attempt> running = new HashMap<>();

  /**
   * @param probe the login probe: its preparation, its status run and its first-run keys
   * @param docker the probe's docker, for the argv's binary and the removals' deadline
   * @param work where the slow steps run
   * @param timer where each sign-in's deadline is kept
   * @param sink sends a progress frame to the host
   * @param signedIn runs the login probe and sends its {@code loginState}
   */
  SignIns(
      LoginProbe probe,
      TargetNames names,
      TargetVolumes volumes,
      RunnerDocker docker,
      SignInProcesses processes,
      Executor work,
      ScheduledExecutorService timer,
      Duration deadline,
      Consumer<SignInProgress> sink,
      Runnable signedIn) {
    this.probe = probe;
    this.names = names;
    this.volumes = volumes;
    this.docker = docker;
    this.processes = processes;
    this.work = work;
    this.timer = timer;
    this.deadline = deadline;
    this.sink = sink;
    this.signedIn = signedIn;
  }

  /**
   * A {@code signIn}: refused at once when its harness is unknown or busy or no probe image is
   * named, else started on {@code work}.
   *
   * @param image {@code Ack.probeImage}, or null
   * @param pull the runner's pull, as the probe takes it
   */
  void start(
      SignIn frame,
      String image,
      ContainersDriver driver,
      Function<String, Optional<String>> pull) {
    String harness = frame.harness();
    if (!HARNESSES.contains(harness)) {
      refuse(frame, "unknown harness " + harness + " (" + String.join(", ", HARNESSES) + ")");
      return;
    }
    Attempt attempt;
    synchronized (this) {
      Attempt busy = running.get(harness);
      if (busy != null) {
        refuse(
            frame, "a sign-in for " + harness + " is already running (" + busy.signInId + ")");
        return;
      }
      if (image == null || image.isBlank()) {
        refuse(frame, LoginProbe.NO_IMAGE);
        return;
      }
      attempt = new Attempt(frame.signInId(), harness, image, driver);
      running.put(harness, attempt);
    }
    LOG.infof("system-runner starts sign-in %s for %s", attempt.signInId, harness);
    try {
      attempt.expiry =
          timer.schedule(
              () -> attempt.stop(SignInState.EXPIRED, "nobody finished the sign-in in time"),
              deadline.toMillis(),
              TimeUnit.MILLISECONDS);
      work.execute(() -> attempt.run(pull));
    } catch (RejectedExecutionException shutDown) {
      attempt.finish(SignInState.FAILED, "the runner is shutting down");
    }
  }

  /** A {@code signInCode}: to the stdin of the sign-in it names, when that one awaits a code. */
  void code(SignInCode frame) {
    Attempt attempt = find(frame.signInId());
    if (attempt == null) {
      LOG.infof("system-runner has no sign-in %s for a code", frame.signInId());
      return;
    }
    attempt.code(frame.code());
  }

  /** A {@code signInCancel}: the sign-in it names is CANCELLED, when it still runs. */
  void cancel(SignInCancel frame) {
    Attempt attempt = find(frame.signInId());
    if (attempt == null) {
      LOG.debugf("system-runner has no sign-in %s to cancel", frame.signInId());
      return;
    }
    attempt.stop(SignInState.CANCELLED, null);
  }

  /** Stop every running sign-in as CANCELLED — at decommission. */
  void stopAll(String why) {
    List<Attempt> all;
    synchronized (this) {
      all = new ArrayList<>(running.values());
    }
    for (Attempt attempt : all) {
      attempt.stop(SignInState.CANCELLED, why);
    }
  }

  /** The sign-in running for {@code harness}, for a suite. */
  synchronized Optional<String> runningFor(String harness) {
    Attempt attempt = running.get(harness);
    return attempt == null ? Optional.empty() : Optional.of(attempt.signInId);
  }

  /**
   * {@code docker run --rm -i --name <sign-in-<harness>> --user 1000 --entrypoint <harness> -v
   * <credential>:/claude-home <env> <image> <args>}.
   */
  List<String> argv(String harness, String image) {
    List<String> argv = new ArrayList<>();
    argv.addAll(
        List.of(
            docker.binary(),
            "run",
            "--rm",
            "-i",
            "--name",
            names.signIn(harness),
            "--user",
            LoginProbe.USER,
            "--entrypoint",
            harness,
            "-v",
            volumes.credential() + ":" + LoginProbe.CLAUDE_HOME));
    if (harness.equals(CLAUDE)) {
      argv.addAll(
          List.of(
              "-e",
              "HOME=" + LoginProbe.CLAUDE_HOME,
              "-e",
              "CLAUDE_CONFIG_DIR=" + LoginProbe.CLAUDE_CONFIG_DIR,
              LifecycleArgv.requireImage(image),
              "auth",
              "login"));
    } else {
      argv.addAll(
          List.of(
              "-e",
              "KIMI_CODE_HOME=" + LoginProbe.KIMI_HOME,
              LifecycleArgv.requireImage(image),
              "login"));
    }
    return List.copyOf(argv);
  }

  private synchronized Attempt find(String signInId) {
    for (Attempt attempt : running.values()) {
      if (attempt.signInId.equals(signInId)) {
        return attempt;
      }
    }
    return null;
  }

  private synchronized void forget(Attempt attempt) {
    running.remove(attempt.harness, attempt);
  }

  private void refuse(SignIn frame, String detail) {
    LOG.infof("system-runner refused sign-in %s: %s", frame.signInId(), detail);
    send(
        new SignInProgress(
            frame.signInId(), frame.harness(), SignInState.FAILED, null, null, detail));
  }

  private void send(SignInProgress progress) {
    try {
      sink.accept(progress);
    } catch (RuntimeException e) {
      LOG.debugf("system-runner could not send sign-in progress: %s", e.getMessage());
    }
  }

  /** One sign-in, from its {@code signIn} to its terminal state. */
  private final class Attempt {

    final String signInId;
    final String harness;
    final String image;
    final ContainersDriver driver;
    final SignInTranscript transcript = new SignInTranscript();

    /** Guarded by {@code this}, as everything below is. */
    private SignInState state = SignInState.STARTING;

    private String url;
    private String userCode;
    private SignInProcesses.Running process;
    volatile ScheduledFuture<?> expiry;

    Attempt(String signInId, String harness, String image, ContainersDriver driver) {
      this.signInId = signInId;
      this.harness = harness;
      this.image = image;
      this.driver = driver;
    }

    /** On {@code work}: prepare, ask claude's status, then run the login. */
    void run(Function<String, Optional<String>> pull) {
      try {
        Optional<String> cannot = probe.prepare(driver, image, pull);
        if (cannot.isPresent()) {
          finish(SignInState.FAILED, cannot.get());
          return;
        }
        if (harness.equals(CLAUDE)
            && probe.claudeLogin(driver, image) == LoginPresence.PRESENT) {
          succeed("already signed in");
          return;
        }
        if (!announce(SignInState.STARTING, null)) {
          return;
        }
        driver.remove(names.signIn(harness), docker.deadline());
        synchronized (this) {
          if (state.terminal()) {
            return;
          }
          process = processes.start(argv(harness, image), this::output);
        }
        process.exit().whenComplete((code, failed) -> exited(code == null ? -1 : code));
      } catch (IOException | RuntimeException e) {
        finish(SignInState.FAILED, "could not start the " + harness + " login: " + e.getMessage());
      }
    }

    /** A chunk of the CLI's output: the url (and code) relayed once it is whole. */
    private void output(String chunk) {
      SignInProgress awaiting = null;
      synchronized (this) {
        transcript.append(chunk);
        if (state != SignInState.STARTING) {
          return;
        }
        if (harness.equals(CLAUDE)) {
          Optional<String> found = transcript.claudeUrl();
          if (found.isPresent()) {
            url = found.get();
            state = SignInState.AWAITING_CODE;
            awaiting = progress(null);
          }
        } else {
          Optional<String> found = transcript.kimiUrl();
          Optional<String> code = transcript.kimiUserCode();
          if (found.isPresent() && code.isPresent()) {
            url = found.get();
            userCode = code.get();
            state = SignInState.AWAITING_BROWSER;
            awaiting = progress(null);
          }
        }
      }
      if (awaiting != null) {
        LOG.infof("system-runner's sign-in %s for %s is %s", signInId, harness, awaiting.state());
        send(awaiting);
      }
    }

    /** The person's code, to stdin — only while the CLI waits for one. Never logged. */
    void code(String code) {
      SignInProcesses.Running to;
      synchronized (this) {
        if (state != SignInState.AWAITING_CODE || process == null) {
          LOG.infof(
              "system-runner ignored a code for sign-in %s: it is %s, not AWAITING_CODE",
              signInId, state);
          return;
        }
        to = process;
      }
      try {
        to.write(code + "\n");
        LOG.infof("system-runner handed sign-in %s its code", signInId);
      } catch (IOException e) {
        stop(SignInState.FAILED, "could not hand the code to the " + harness + " login");
      }
    }

    /** The CLI ended: 0 is a login, anything else a failure in its own words. */
    private void exited(int code) {
      synchronized (this) {
        if (state.terminal()) {
          return;
        }
      }
      if (code == 0) {
        try {
          work.execute(() -> succeed(null));
        } catch (RejectedExecutionException shutDown) {
          succeed(null);
        }
        return;
      }
      String last;
      synchronized (this) {
        last = transcript.lastLine();
      }
      finish(
          SignInState.FAILED,
          last == null ? "the " + harness + " login exited " + code : last);
    }

    /** SUCCEEDED, with Claude Code's first-run keys merged first; then the probe. */
    private void succeed(String detail) {
      if (harness.equals(CLAUDE)) {
        try {
          RunnerDocker.Result keys = probe.firstRun().ensure(driver, image);
          if (!keys.ok()) {
            LOG.warnf(
                "system-runner's sign-in %s could not write claude's first-run keys (exit %d): %s",
                signInId, keys.exitCode(), LeaseLauncher.brief(keys.detail()));
          }
        } catch (RuntimeException e) {
          LOG.warnf(
              "system-runner's sign-in %s could not write claude's first-run keys: %s",
              signInId, e.getMessage());
        }
      }
      if (finish(SignInState.SUCCEEDED, detail)) {
        signedIn.run();
      }
    }

    /** CANCELLED, EXPIRED or FAILED from outside: the process killed and its container gone. */
    void stop(SignInState terminal, String detail) {
      SignInProcesses.Running killed;
      synchronized (this) {
        if (state.terminal()) {
          return;
        }
        killed = process;
      }
      finish(terminal, detail);
      Runnable kill =
          () -> {
            if (killed != null) {
              killed.kill();
            }
            ContainersDriver.OpResult gone =
                driver.remove(names.signIn(harness), docker.deadline());
            if (!gone.ok() && !TargetVolumes.absent(gone.detail())) {
              LOG.warnf(
                  "system-runner could not remove sign-in %s's container: %s",
                  signInId, gone.detail());
            }
          };
      try {
        work.execute(kill);
      } catch (RejectedExecutionException shutDown) {
        kill.run();
      }
    }

    /** A non-terminal state change, sent; false when the sign-in already ended. */
    private boolean announce(SignInState next, String detail) {
      SignInProgress progress;
      synchronized (this) {
        if (state.terminal()) {
          return false;
        }
        state = next;
        progress = progress(detail);
      }
      send(progress);
      return true;
    }

    /** The terminal state, sent once; false when another one came first. */
    boolean finish(SignInState terminal, String detail) {
      SignInProgress progress;
      synchronized (this) {
        if (state.terminal()) {
          return false;
        }
        state = terminal;
        progress = progress(detail);
      }
      ScheduledFuture<?> timer = expiry;
      if (timer != null) {
        timer.cancel(false);
      }
      forget(this);
      LOG.infof(
          "system-runner's sign-in %s for %s is %s%s",
          signInId, harness, terminal, detail == null ? "" : ": " + detail);
      send(progress);
      return true;
    }

    private SignInProgress progress(String detail) {
      return new SignInProgress(signInId, harness, state, url, userCode, detail);
    }
  }
}
