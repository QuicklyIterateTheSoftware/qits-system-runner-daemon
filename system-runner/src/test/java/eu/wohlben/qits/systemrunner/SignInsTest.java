package eu.wohlben.qits.systemrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.testing.FakeDocker;
import eu.wohlben.qits.systemrunner.protocol.SignIn;
import eu.wohlben.qits.systemrunner.protocol.SignInCancel;
import eu.wohlben.qits.systemrunner.protocol.SignInCode;
import eu.wohlben.qits.systemrunner.protocol.SignInProgress;
import eu.wohlben.qits.systemrunner.protocol.SignInState;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link SignIns}' state machine against a fake process seam — the login CLI is a {@link
 * FakeProcesses} the suite prints into and exits — and {@link FakeDocker} for every docker call
 * around it (the pull check, {@code auth status}, the first-run keys, the removals). The slow work
 * runs on the calling thread, so every transition is in order by the time a call returns.
 */
@EnabledOnOs(OS.LINUX)
class SignInsTest {

  private static final RunnerIdentity IDENTITY = SystemRunnerIdentity.IDENTITY;
  private static final String IMAGE = "registry.qits.example.eu/qits/workspace:2026.1023.1";
  private static final String DOT_CLAUDE = "qits-system-runner-dot-claude-r1";
  private static final String CLAUDE_BOX = "qits-system-runner-sign-in-claude-r1";
  private static final String KIMI_BOX = "qits-system-runner-sign-in-kimi-r1";
  private static final String S1 = "0b9c7a3e-5d1f-4e2a-9b8c-7d6e5f4a3b2c";
  private static final String S2 = "6a1f0e2d-3c4b-4a59-8e7f-6d5c4b3a2918";
  private static final String CLAUDE_URL =
      "https://claude.com/cai/oauth/authorize?code=true&client_id=9d1c&response_type=code"
          + "&state=AbC";
  private static final String KIMI_URL =
      "https://www.kimi.com/code/authorize_device?user_code=ABCD-1234";

  @TempDir Path dir;
  private FakeDocker docker;
  private RunnerDocker runnerDocker;
  private TargetVolumes volumes;
  private LoginProbe probe;
  private final FakeProcesses processes = new FakeProcesses();
  private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);
  private final List<SignInProgress> sent = Collections.synchronizedList(new ArrayList<>());
  private final AtomicInteger probed = new AtomicInteger();
  private final Function<String, Optional<String>> pull = image -> Optional.empty();

  @BeforeEach
  void setUp() throws Exception {
    docker = new FakeDocker(dir);
    runnerDocker = new RunnerDocker(IDENTITY, docker.binary(), Duration.ofSeconds(10));
    volumes = new TargetVolumes(IDENTITY, "r1", ExternalVolumes.NONE);
    probe = probe(volumes);
    docker.answerFor("run", "status", 1, "{\"loggedIn\": false}\n", "");
  }

  @AfterEach
  void tearDown() {
    timer.shutdownNow();
  }

  private LoginProbe probe(TargetVolumes volumes) {
    return new LoginProbe(
        IDENTITY, "r1", runnerDocker, volumes, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
  }

  private SignIns signIns(Duration deadline) {
    return signIns(probe, volumes, deadline);
  }

  private SignIns signIns(LoginProbe probe, TargetVolumes volumes, Duration deadline) {
    return new SignIns(
        probe,
        new TargetNames(IDENTITY, "r1"),
        volumes,
        runnerDocker,
        processes,
        Runnable::run,
        timer,
        deadline,
        sent::add,
        probed::incrementAndGet);
  }

  // --- claude ------------------------------------------------------------------------------------

  @Test
  void claudeRelaysItsUrlTakesTheCodeOnStdinAndSucceedsWithItsKeysThenTheProbe() throws Exception {
    SignIns signIns = signIns(SignIns.DEADLINE);
    signIns.start(new SignIn(S1, "claude"), IMAGE, runnerDocker.driver(), pull);

    assertEquals(List.of(state(S1, "claude", SignInState.STARTING)), sent);
    assertEquals(
        List.of(
            runnerDocker.binary(), "run", "--rm", "-i", "--name", CLAUDE_BOX, "--user", "1000",
            "--entrypoint", "claude", "-v", DOT_CLAUDE + ":/claude-home", "-e",
            "HOME=/claude-home", "-e", "CLAUDE_CONFIG_DIR=/claude-home/.claude", IMAGE, "auth",
            "login"),
        processes.argv());
    List<List<String>> calls = docker.calls();
    assertTrue(
        calls.indexOf(List.of("run", "--rm", "--name", "qits-system-runner-login-probe-r1",
                "--label", "qits.system.runner=r1", "--user", "1000", "--entrypoint", "claude",
                "-v", DOT_CLAUDE + ":/claude-home", "-e", "HOME=/claude-home", "-e",
                "CLAUDE_CONFIG_DIR=/claude-home/.claude", IMAGE, "auth", "status"))
            >= 0,
        "auth status first, as the probe asks it: " + calls);
    assertTrue(
        docker.calls("rm").stream().anyMatch(c -> c.contains(CLAUDE_BOX)),
        "a leftover under the harness's name is removed first");

    FakeRunning login = processes.last();
    login.print("\u001B[2mOpening browser to sign in…\u001B[0m\n");
    login.print("If the browser didn't open, visit: " + CLAUDE_URL.substring(0, 40));
    assertEquals(1, sent.size(), "half a url is not relayed");
    login.print(CLAUDE_URL.substring(40) + "\n");
    login.print("Paste code here if prompted > ");

    assertEquals(
        new SignInProgress(S1, "claude", SignInState.AWAITING_CODE, CLAUDE_URL, null, null),
        sent.get(1));
    assertEquals(2, sent.size(), "the prompt changes nothing more");

    signIns.code(new SignInCode(S1, "c0de#st4te"));
    assertEquals("c0de#st4te\n", login.stdin());
    assertEquals(0, probed.get());

    login.exit(0);
    assertEquals(
        new SignInProgress(S1, "claude", SignInState.SUCCEEDED, CLAUDE_URL, null, null),
        sent.get(2));
    assertEquals(1, probed.get(), "the probe runs and sends its loginState");
    assertTrue(
        docker.calls("run").stream().anyMatch(c -> c.getLast().equals("check")),
        "the first-run keys are made sure of");
    assertEquals(Optional.empty(), signIns.runningFor("claude"));
  }

  @Test
  void anAlreadyLoggedInClaudeSucceedsAtOnceWithItsKeysAndNoBrowser() throws Exception {
    docker.answerFor("run", "status", 0, "{\"loggedIn\": true}\n", "");
    docker.answerFor("run", "check", 1, "", "");
    docker.answerFor("run", "apply", 0, "", "");

    signIns(SignIns.DEADLINE).start(new SignIn(S1, "claude"), IMAGE, runnerDocker.driver(), pull);

    assertEquals(
        List.of(
            new SignInProgress(
                S1, "claude", SignInState.SUCCEEDED, null, null, "already signed in")),
        sent);
    assertTrue(processes.started.isEmpty(), "no login runs");
    assertEquals(
        List.of("status", "check", "apply"),
        docker.calls("run").stream().map(List::getLast).toList());
    assertEquals(1, probed.get());
  }

  @Test
  void aNonZeroExitFailsWithTheLastLineTheCliPrinted() throws Exception {
    SignIns signIns = signIns(SignIns.DEADLINE);
    signIns.start(new SignIn(S1, "claude"), IMAGE, runnerDocker.driver(), pull);
    FakeRunning login = processes.last();
    login.print(
        "If the browser didn't open, visit: " + CLAUDE_URL + "\nPaste code here if prompted > ");
    signIns.code(new SignInCode(S1, "wrong"));
    login.print("\u001B[31mLogin failed: Request failed with status code 400\u001B[39m\n");
    login.exit(1);

    assertEquals(
        new SignInProgress(
            S1,
            "claude",
            SignInState.FAILED,
            CLAUDE_URL,
            null,
            "Login failed: Request failed with status code 400"),
        sent.getLast());
    assertEquals(0, probed.get());

    signIns.start(new SignIn(S2, "claude"), IMAGE, runnerDocker.driver(), pull);
    login = processes.last();
    login.exit(2);
    assertEquals("the claude login exited 2", sent.getLast().detail(), "silent: the exit code");
  }

  // --- kimi --------------------------------------------------------------------------------------

  @Test
  void kimiRelaysItsUrlAndUserCodeAndSucceedsWithoutFirstRunKeys() throws Exception {
    SignIns signIns = signIns(SignIns.DEADLINE);
    signIns.start(new SignIn(S1, "kimi"), IMAGE, runnerDocker.driver(), pull);

    assertEquals(
        List.of(
            runnerDocker.binary(), "run", "--rm", "-i", "--name", KIMI_BOX, "--user", "1000",
            "--entrypoint", "kimi", "-v", DOT_CLAUDE + ":/claude-home", "-e",
            "KIMI_CODE_HOME=/claude-home/.kimi-code", IMAGE, "login"),
        processes.argv());
    FakeRunning login = processes.last();
    login.print("Opening browser for Kimi device login: " + KIMI_URL + "\n");
    login.print("If the browser did not open, paste the URL above and enter code: ABCD-1234\n");
    login.print("Code expires in 1800s.\nWaiting for authorization to complete...\n");

    assertEquals(
        List.of(
            state(S1, "kimi", SignInState.STARTING),
            new SignInProgress(
                S1, "kimi", SignInState.AWAITING_BROWSER, KIMI_URL, "ABCD-1234", null)),
        sent);

    signIns.code(new SignInCode(S1, "nothing-asks-for-this"));
    assertEquals("", login.stdin(), "a code goes only to a login awaiting one");

    login.exit(0);
    assertEquals(SignInState.SUCCEEDED, sent.getLast().state());
    assertEquals(1, probed.get());
    assertTrue(docker.calls("run").isEmpty(), "no auth status, no first-run keys for kimi");
  }

  // --- refusals ----------------------------------------------------------------------------------

  @Test
  void aSecondSignInForARunningHarnessFailsNamingTheRunningOneAndTheOtherHarnessMayRun()
      throws Exception {
    SignIns signIns = signIns(SignIns.DEADLINE);
    signIns.start(new SignIn(S1, "claude"), IMAGE, runnerDocker.driver(), pull);
    signIns.start(new SignIn(S2, "claude"), IMAGE, runnerDocker.driver(), pull);

    assertEquals(
        new SignInProgress(
            S2,
            "claude",
            SignInState.FAILED,
            null,
            null,
            "a sign-in for claude is already running (" + S1 + ")"),
        sent.getLast());
    assertEquals(Optional.of(S1), signIns.runningFor("claude"));
    assertEquals(1, processes.started.size());

    signIns.start(new SignIn(S2, "kimi"), IMAGE, runnerDocker.driver(), pull);
    assertEquals(2, processes.started.size());
    assertEquals(Optional.of(S2), signIns.runningFor("kimi"));
  }

  @Test
  void noProbeImageAnUnknownHarnessAndAMissingExternalVolumeFailBeforeAnythingRuns()
      throws Exception {
    SignIns signIns = signIns(SignIns.DEADLINE);
    signIns.start(new SignIn(S1, "claude"), null, runnerDocker.driver(), pull);
    assertEquals(
        new SignInProgress(
            S1, "claude", SignInState.FAILED, null, null, "the host named no probe image"),
        sent.getLast());

    signIns.start(new SignIn(S1, "gemini"), IMAGE, runnerDocker.driver(), pull);
    assertEquals(SignInState.FAILED, sent.getLast().state());
    assertTrue(sent.getLast().detail().startsWith("unknown harness gemini"));

    TargetVolumes shared =
        new TargetVolumes(
            IDENTITY,
            "r1",
            new ExternalVolumes(
                Optional.of("qits_shared_dot_claude"), Optional.empty(), Optional.empty()));
    docker.answerFor("volume-inspect", "qits_shared_dot_claude", 1, "", "Error: No such volume");
    signIns(probe(shared), shared, SignIns.DEADLINE)
        .start(new SignIn(S2, "kimi"), IMAGE, runnerDocker.driver(), pull);
    assertEquals(SignInState.FAILED, sent.getLast().state());
    assertTrue(
        sent.getLast().detail().contains("qits_shared_dot_claude")
            && sent.getLast().detail().contains("does not exist"),
        sent.getLast().detail());
    assertTrue(processes.started.isEmpty());
    assertFalse(docker.volumes().containsKey("qits_shared_dot_claude"), "never created");
  }

  // --- cancel and expiry -------------------------------------------------------------------------

  @Test
  void aCancelKillsTheLoginRemovesItsContainerAndFreesTheHarness() throws Exception {
    SignIns signIns = signIns(SignIns.DEADLINE);
    signIns.start(new SignIn(S1, "kimi"), IMAGE, runnerDocker.driver(), pull);
    FakeRunning login = processes.last();
    int removalsBefore = removalsOf(KIMI_BOX);

    signIns.cancel(new SignInCancel(S1));

    assertEquals(state(S1, "kimi", SignInState.CANCELLED), sent.getLast());
    assertTrue(login.killed);
    assertEquals(removalsBefore + 1, removalsOf(KIMI_BOX), "docker rm -f of its container");
    int frames = sent.size();
    login.exit(0);
    assertEquals(frames, sent.size(), "nothing follows a terminal state");
    assertEquals(0, probed.get());

    signIns.cancel(new SignInCancel(S1));
    assertEquals(frames, sent.size(), "a second cancel is nobody's");
    signIns.start(new SignIn(S2, "kimi"), IMAGE, runnerDocker.driver(), pull);
    assertEquals(state(S2, "kimi", SignInState.STARTING), sent.getLast());
  }

  @Test
  void aSignInNobodyFinishesExpiresAndIsKilled() throws Exception {
    SignIns signIns = signIns(Duration.ofMillis(200));
    signIns.start(new SignIn(S1, "claude"), IMAGE, runnerDocker.driver(), pull);
    FakeRunning login = processes.last();
    login.print("visit: " + CLAUDE_URL + "\n");

    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (sent.getLast().state() != SignInState.EXPIRED && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertEquals(SignInState.EXPIRED, sent.getLast().state());
    assertTrue(login.killed);
    assertEquals(Optional.empty(), signIns.runningFor("claude"));

    signIns.code(new SignInCode(S1, "too-late"));
    assertEquals("", login.stdin());
  }

  @Test
  void stopAllCancelsEveryRunningSignIn() throws Exception {
    SignIns signIns = signIns(SignIns.DEADLINE);
    signIns.start(new SignIn(S1, "claude"), IMAGE, runnerDocker.driver(), pull);
    signIns.start(new SignIn(S2, "kimi"), IMAGE, runnerDocker.driver(), pull);

    signIns.stopAll("the target is being decommissioned");

    assertEquals(
        List.of(SignInState.CANCELLED, SignInState.CANCELLED),
        sent.stream().filter(p -> p.state().terminal()).map(SignInProgress::state).toList());
    assertTrue(processes.started.stream().allMatch(r -> r.killed));
  }

  @Test
  void aLoginThatCannotBeStartedFails() throws Exception {
    processes.refuse = new IOException("Cannot run program \"docker\"");
    signIns(SignIns.DEADLINE).start(new SignIn(S1, "kimi"), IMAGE, runnerDocker.driver(), pull);

    SignInProgress last = sent.getLast();
    assertEquals(SignInState.FAILED, last.state());
    assertEquals("could not start the kimi login: Cannot run program \"docker\"", last.detail());
    assertNull(last.url());
  }

  // --- fixtures ----------------------------------------------------------------------------------

  private static SignInProgress state(String id, String harness, SignInState state) {
    return new SignInProgress(id, harness, state, null, null, null);
  }

  private int removalsOf(String name) {
    return (int) docker.calls("rm").stream().filter(c -> c.contains(name)).count();
  }

  /** The login CLI, as the suite plays it. */
  static final class FakeProcesses implements SignInProcesses {

    final List<FakeRunning> started = Collections.synchronizedList(new ArrayList<>());
    volatile IOException refuse;

    @Override
    public Running start(List<String> argv, Consumer<String> output) throws IOException {
      if (refuse != null) {
        throw refuse;
      }
      FakeRunning running = new FakeRunning(argv, output);
      started.add(running);
      return running;
    }

    FakeRunning last() {
      return started.getLast();
    }

    List<String> argv() {
      return last().argv;
    }
  }

  static final class FakeRunning implements SignInProcesses.Running {

    final List<String> argv;
    private final Consumer<String> output;
    private final StringBuilder stdin = new StringBuilder();
    private final CompletableFuture<Integer> exit = new CompletableFuture<>();
    volatile boolean killed;

    FakeRunning(List<String> argv, Consumer<String> output) {
      this.argv = argv;
      this.output = output;
    }

    void print(String chunk) {
      output.accept(chunk);
    }

    void exit(int code) {
      exit.complete(code);
    }

    synchronized String stdin() {
      return stdin.toString();
    }

    @Override
    public synchronized void write(String text) {
      stdin.append(text);
    }

    @Override
    public CompletableFuture<Integer> exit() {
      return exit;
    }

    @Override
    public void kill() {
      killed = true;
      exit.complete(137);
    }
  }
}
