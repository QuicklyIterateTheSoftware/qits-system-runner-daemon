package eu.wohlben.qits.systemrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link NodeTunnelUpdate}: the derived URL, the helper's argv, one round against a scripted node
 * and server, and the helper's script itself against a directory standing in for the host, with
 * {@code nsenter}, {@code systemctl} and {@code sleep} stubbed on {@code PATH}.
 */
class NodeTunnelUpdateTest {

  private static final byte[] OLD = "old binary\n".getBytes(StandardCharsets.UTF_8);
  private static final byte[] NEW = "good binary\n".getBytes(StandardCharsets.UTF_8);
  private static final String OLD_SHA = NodeTunnelUpdate.sha256(OLD);
  private static final String NEW_SHA = NodeTunnelUpdate.sha256(NEW);

  @TempDir Path dir;

  private final List<String> installs = new ArrayList<>();
  private final List<List<String>> asked = new ArrayList<>();
  private NodeTunnelUpdate update;

  @AfterEach
  void close() {
    if (update != null) {
      update.close();
    }
  }

  @Test
  void theNetworkAccessBaseIsTheServerUrlsSibling() {
    assertEquals(
        "https://network-access.qits.example.eu/network-access",
        NodeTunnelUpdate.networkAccessUrl("https://system.qits.example.eu"));
    assertEquals(
        "https://network-access.qits.example.eu:8443/network-access",
        NodeTunnelUpdate.networkAccessUrl("https://System.qits.example.eu:8443/"));
    assertNull(NodeTunnelUpdate.networkAccessUrl("http://system.qits.example.eu"), "http");
    assertNull(NodeTunnelUpdate.networkAccessUrl("https://qits.example.eu"), "two-label parent");
    assertNull(NodeTunnelUpdate.networkAccessUrl("https://system.example.eu"), "two-label parent");
    assertNull(NodeTunnelUpdate.networkAccessUrl("https://ci.qits.example.eu"), "not system");
    assertNull(NodeTunnelUpdate.networkAccessUrl("https://system.1.2.3"), "an address");
    assertNull(NodeTunnelUpdate.networkAccessUrl("https://127.0.0.1:8080"));
    assertNull(NodeTunnelUpdate.networkAccessUrl("http://localhost:8080"));
    assertNull(NodeTunnelUpdate.networkAccessUrl(null));
  }

  @Test
  void theHelperIsOneFixedPrivilegedShape() {
    List<String> argv =
        NodeTunnelUpdate.helperArgv(
            "docker",
            SystemRunnerIdentity.IDENTITY,
            "28272007-0418-45c1-97f3-6505a89c6502",
            "registry.qits.example.eu/qits/qits-system-runner:2026.1119.1",
            "install",
            NEW_SHA);
    assertEquals(
        List.of(
            "docker",
            "run",
            "--rm",
            "--name",
            "qits-system-runner-node-tunnel-28272007",
            "--label",
            SystemRunnerIdentity.IDENTITY.ownerLabel() + "=28272007-0418-45c1-97f3-6505a89c6502",
            "--privileged",
            "--pid=host",
            "--network",
            "none",
            "-v",
            "qits-system-runner-state-28272007:/state:ro",
            "--entrypoint",
            "/bin/sh",
            "registry.qits.example.eu/qits/qits-system-runner:2026.1119.1",
            "-c",
            NodeTunnelUpdate.SCRIPT,
            "sh",
            "install",
            NEW_SHA),
        argv);
  }

  private NodeTunnelUpdate update(Optional<String> digest, int installExit, NodeTunnelUpdate.Answer answer) {
    NodeTunnelUpdate.Host host =
        new NodeTunnelUpdate.Host() {
          @Override
          public Optional<String> digest() {
            return digest;
          }

          @Override
          public RunnerDocker.Result install(String sha256) {
            installs.add(sha256);
            assertTrue(Files.exists(dir.resolve(NodeTunnelUpdate.STAGED)), "staged before");
            return new RunnerDocker.Result(installExit, "", "", false, false);
          }
        };
    NodeTunnelUpdate.Fetch fetch =
        (url, bearer, known) -> {
          assertEquals("https://network-access.qits.example.eu/network-access/api/nodes/qits-node-tunnel", url);
          assertEquals("the-bearer", bearer);
          asked.add(List.copyOf(known));
          return answer;
        };
    update =
        new NodeTunnelUpdate(
            "https://network-access.qits.example.eu/network-access",
            dir,
            host,
            fetch,
            () -> () -> CompletableFuture.completedFuture("the-bearer"));
    return update;
  }

  private static NodeTunnelUpdate.Answer served(byte[] body) {
    return new NodeTunnelUpdate.Answer(
        200, body, "\"" + NodeTunnelUpdate.sha256(body) + "\"", "2026.1119.150507");
  }

  @Test
  void aCurrentNodeAsksByItsDigestAndInstallsNothing() {
    NodeTunnelUpdate u =
        update(Optional.of(OLD_SHA), 0, new NodeTunnelUpdate.Answer(304, new byte[0], null, null));
    assertTrue(u.check());
    assertEquals(List.of(List.of(OLD_SHA)), asked);
    assertEquals(List.of(), installs);
  }

  @Test
  void aNewReleaseIsInstalledOnceAndTheStagedFileRemoved() {
    NodeTunnelUpdate u = update(Optional.of(OLD_SHA), 0, served(NEW));
    assertTrue(u.check());
    assertEquals(List.of(NEW_SHA), installs);
    assertFalse(Files.exists(dir.resolve(NodeTunnelUpdate.STAGED)));
    u.check();
    assertEquals(List.of(NEW_SHA), asked.get(1), "the next round asks by the new digest");
  }

  @Test
  void aRolledBackDigestIsNotTriedAgain() {
    NodeTunnelUpdate u = update(Optional.of(OLD_SHA), NodeTunnelUpdate.ROLLED_BACK, served(NEW));
    u.check();
    u.check();
    assertEquals(List.of(NEW_SHA), installs);
    assertEquals(List.of(OLD_SHA, NEW_SHA), asked.get(1));
  }

  @Test
  void aBodyThatIsNotItsEtagIsRefused() {
    NodeTunnelUpdate u =
        update(
            Optional.of(OLD_SHA),
            0,
            new NodeTunnelUpdate.Answer(200, NEW, "\"" + OLD_SHA.replace('a', 'b') + "\"", null));
    u.check();
    assertEquals(List.of(), installs);
  }

  @Test
  void aBodyWithoutAnEtagIsRefused() {
    NodeTunnelUpdate u =
        update(Optional.of(OLD_SHA), 0, new NodeTunnelUpdate.Answer(200, NEW, null, null));
    u.check();
    assertEquals(List.of(), installs);
    assertFalse(Files.exists(dir.resolve(NodeTunnelUpdate.STAGED)));
  }

  @Test
  void aServerUrlThatDerivesNoHostSendsNoRequest() throws Exception {
    List<String> sent = new ArrayList<>();
    update =
        new NodeTunnelUpdate(
            NodeTunnelUpdate.networkAccessUrl("https://qits.example.eu"),
            dir,
            new NodeTunnelUpdate.Host() {
              @Override
              public Optional<String> digest() {
                sent.add("digest");
                return Optional.of(OLD_SHA);
              }

              @Override
              public RunnerDocker.Result install(String sha256) {
                sent.add("install");
                return new RunnerDocker.Result(0, "", "", false, false);
              }
            },
            (url, bearer, known) -> {
              sent.add(url);
              return served(NEW);
            },
            () -> () -> CompletableFuture.completedFuture("the-bearer"));
    update.start();
    assertTrue(update.executor().getQueue().isEmpty(), "nothing scheduled");
    assertEquals(List.of(), sent);
  }

  @Test
  void theDownloadRefusesAnHttpUrl() {
    var thrown =
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () ->
                NodeTunnelUpdate.http()
                    .get("http://network-access.qits.example.eu/x", "the-bearer", List.of()));
    assertTrue(thrown.getMessage().contains("non-https"));
  }

  @Test
  void aNodeThatNeverJoinedEndsTheSchedule() {
    NodeTunnelUpdate u = update(Optional.of("absent"), 0, served(NEW));
    assertFalse(u.check());
    assertEquals(List.of(), asked);
    assertTrue(u.executor().isShutdown());
  }

  @Test
  void anUnreadableHostIsAskedAgainNextRound() {
    NodeTunnelUpdate u = update(Optional.empty(), 0, served(NEW));
    assertTrue(u.check());
    assertEquals(List.of(), asked);
  }

  // --- the helper's script, against a directory -----------------------------------------------

  /** Runs the script in {@code mode}; the stub systemctl keeps a unit up only for "good" bytes. */
  private Process script(String mode, String digest) throws Exception {
    Path bin = dir.resolve("bin");
    Files.createDirectories(bin);
    stub(bin.resolve("sleep"), "#!/bin/sh\nexit 0\n");
    stub(bin.resolve("nsenter"), "#!/bin/sh\nwhile [ \"$1\" != -- ]; do shift; done\nshift\nexec \"$@\"\n");
    // A good binary keeps its PID; any other one is a new PID at every look (a crash loop).
    stub(
        bin.resolve("systemctl"),
        "#!/bin/sh\n"
            + "B=\"$QITS_HOST_ROOT/usr/local/bin/qits-node-tunnel\"\n"
            + "echo \"$*\" >> \"$QITS_HOST_ROOT/systemctl.log\"\n"
            + "case \"$1\" in\n"
            + "  restart) exit 0 ;;\n"
            + "  show) if grep -q good \"$B\" || grep -q old \"$B\"; then echo 42; else date +%N; fi ;;\n"
            + "  is-active) if grep -q good \"$B\" || grep -q old \"$B\"; then echo active; else echo activating; fi ;;\n"
            + "esac\n");
    ProcessBuilder run =
        new ProcessBuilder(
            digest == null
                ? List.of("/bin/sh", "-c", NodeTunnelUpdate.SCRIPT, "sh", mode)
                : List.of("/bin/sh", "-c", NodeTunnelUpdate.SCRIPT, "sh", mode, digest));
    run.environment().put("QITS_HOST_ROOT", dir.resolve("host").toString());
    run.environment().put("QITS_STAGE", dir.resolve("state").toString());
    run.environment().put("PATH", bin + ":" + System.getenv("PATH"));
    run.redirectErrorStream(true);
    Process p = run.start();
    assertTrue(p.waitFor(30, TimeUnit.SECONDS));
    return p;
  }

  private static void stub(Path file, String text) throws Exception {
    Files.writeString(file, text);
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
  }

  private Path joinedHost() throws Exception {
    Path host = dir.resolve("host");
    Files.createDirectories(host.resolve("usr/local/bin"));
    Files.createDirectories(host.resolve("etc/systemd/system"));
    Files.writeString(host.resolve("etc/systemd/system/qits-node-tunnel.service"), "[Unit]\n");
    Files.write(host.resolve("usr/local/bin/qits-node-tunnel"), OLD);
    return host.resolve("usr/local/bin/qits-node-tunnel");
  }

  private void stage(byte[] body) throws Exception {
    Path staged = dir.resolve("state").resolve(NodeTunnelUpdate.STAGED);
    Files.createDirectories(staged.getParent());
    Files.write(staged, body);
  }

  private static String out(Process p) throws Exception {
    return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void theScriptReadsTheHostsDigestOrSaysAbsent() throws Exception {
    Process absent = script("digest", null);
    assertEquals(0, absent.exitValue());
    assertEquals("absent", out(absent));
    joinedHost();
    Process read = script("digest", null);
    assertEquals(0, read.exitValue());
    assertEquals(OLD_SHA, out(read));
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void theScriptSwapsAndRestarts() throws Exception {
    Path binary = joinedHost();
    stage(NEW);
    Process p = script("install", NEW_SHA);
    assertEquals(0, p.exitValue(), out(p));
    assertEquals(NEW_SHA, NodeTunnelUpdate.sha256(Files.readAllBytes(binary)));
    assertTrue(Files.isExecutable(binary));
    assertFalse(Files.exists(binary.resolveSibling("qits-node-tunnel.old")));
    assertFalse(Files.exists(binary.resolveSibling("qits-node-tunnel.new")));
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void aBinaryThatDoesNotStayUpIsPutBack() throws Exception {
    Path binary = joinedHost();
    byte[] bad = "crashing binary\n".getBytes(StandardCharsets.UTF_8);
    stage(bad);
    Process p = script("install", NodeTunnelUpdate.sha256(bad));
    assertEquals(NodeTunnelUpdate.ROLLED_BACK, p.exitValue(), out(p));
    assertEquals(OLD_SHA, NodeTunnelUpdate.sha256(Files.readAllBytes(binary)));
    String log = Files.readString(dir.resolve("host/systemctl.log"));
    assertEquals(2, log.lines().filter(l -> l.startsWith("restart")).count(), log);
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void aStagedBinaryOfAnotherDigestTouchesNothing() throws Exception {
    Path binary = joinedHost();
    stage(NEW);
    Process p = script("install", OLD_SHA);
    assertEquals(11, p.exitValue(), out(p));
    assertEquals(OLD_SHA, NodeTunnelUpdate.sha256(Files.readAllBytes(binary)));
    assertFalse(Files.exists(dir.resolve("host/systemctl.log")));
  }
}
