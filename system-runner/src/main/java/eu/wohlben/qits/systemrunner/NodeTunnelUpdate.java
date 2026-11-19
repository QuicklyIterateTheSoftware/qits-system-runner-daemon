package eu.wohlben.qits.systemrunner;

import eu.wohlben.qits.containers.driver.docker.DockerArgv;
import eu.wohlben.qits.containers.driver.docker.HostRunArgv;
import eu.wohlben.qits.containers.driver.spec.HostRunSpec;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.SelfContainer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * <b>The node's {@code qits-node-tunnel} follows the released one</b> (qits-2385). The install
 * line downloads the helper once, at the join; this keeps it current afterwards, with no person on
 * the node.
 *
 * <p>Every {@link #EVERY}, from {@link #FIRST} after the first {@code Ack}, on a thread of its own:
 *
 * <ol>
 *   <li>the host's binary's SHA-256, read once per process by a helper container ({@link
 *       #helperArgv}, mode {@code digest}). A host without {@code /usr/local/bin/qits-node-tunnel}
 *       and its unit has not joined: one INFO line, and the schedule ends;
 *   <li>{@code GET <network-access>/api/nodes/qits-node-tunnel} with the runner's own bearer and
 *       {@code If-None-Match} naming that digest (and one the node refused before). 304 is
 *       "current". The network-access base is derived from the runner's server URL the way the
 *       edge names every application ({@link #networkAccessUrl});
 *   <li>200: the body's SHA-256 must be the {@code ETag}. It is staged in the state volume, and
 *       the helper (mode {@code install}) copies it next to the host's binary, checks the digest,
 *       keeps the old one as {@code .old}, swaps them by rename — the running process keeps its
 *       inode — and restarts the unit. The new one counts as started when the unit is active with
 *       the same main PID 3 s and 10 s after the restart. Otherwise the old one is renamed back and
 *       the unit restarted again, and the digest is not tried again in this process.
 * </ol>
 *
 * <p>The log names both digests and the served release ({@code X-Qits-Version}). The helper is the
 * runner's own image, privileged with the host's PID namespace: it reaches the host's files through
 * {@code /proc/1/root} and its systemd through {@code nsenter -t 1}. Its fixed name makes a second
 * runner on the node (a rollover's successor) fail its run rather than swap at the same time.
 * Restarting the unit drops the tunnel for a few seconds. Never throws.
 */
final class NodeTunnelUpdate {

  private static final Logger LOG = Logger.getLogger(NodeTunnelUpdate.class);

  static final Duration FIRST = Duration.ofMinutes(1);
  static final Duration EVERY = Duration.ofMinutes(5);

  /** The helper's name purpose: {@code qits-system-runner-node-tunnel-<id8>}. */
  static final String PURPOSE = "node-tunnel";

  /** Where the staged binary sits below the state directory, and so below {@code /state}. */
  static final String STAGED = "node-tunnel/qits-node-tunnel";

  static final String VERSION_HEADER = "X-Qits-Version";

  /**
   * The helper's script; {@code $1} is the mode, {@code $2} the staged binary's digest. {@code
   * QITS_HOST_ROOT} and {@code QITS_STAGE} are unset in production and point the suite at a
   * directory.
   */
  static final String SCRIPT =
      """
      set -u
      H=${QITS_HOST_ROOT:-/proc/1/root}
      S=${QITS_STAGE:-/state}
      B=$H/usr/local/bin/qits-node-tunnel
      U=qits-node-tunnel
      if [ ! -f "$H/etc/systemd/system/$U.service" ] || [ ! -f "$B" ]; then
        echo absent
        [ "$1" = digest ] && exit 0
        exit 10
      fi
      if [ "$1" = digest ]; then sha256sum "$B" | cut -d' ' -f1; exit 0; fi
      host() { nsenter -t 1 -m -u -i -n -p -- "$@"; }
      up() {
        host systemctl restart "$U" || return 1
        sleep 3; a=$(host systemctl show -p MainPID --value "$U")
        sleep 7; b=$(host systemctl show -p MainPID --value "$U")
        [ "$(host systemctl is-active "$U")" = active ] && [ -n "$a" ] && [ "$a" != 0 ] && [ "$a" = "$b" ]
      }
      cp "$S/node-tunnel/qits-node-tunnel" "$B.new" && chmod 0755 "$B.new" \\
        || { rm -f "$B.new"; echo "could not stage $B.new"; exit 11; }
      if [ "$(sha256sum "$B.new" | cut -d' ' -f1)" != "$2" ]; then
        rm -f "$B.new"; echo "the staged binary is not $2"; exit 11
      fi
      cp -p "$B" "$B.old" || { rm -f "$B.new"; echo "could not keep $B.old"; exit 11; }
      mv -f "$B.new" "$B" || { rm -f "$B.new" "$B.old"; echo "could not swap $B"; exit 11; }
      if up; then rm -f "$B.old"; echo updated; exit 0; fi
      mv -f "$B.old" "$B"
      if up; then echo "rolled back"; exit 20; fi
      echo "rolled back, and the old binary does not stay up either"; exit 21
      """;

  /** The helper's exit when the new binary did not stay up and the old one is back. */
  static final int ROLLED_BACK = 20;

  /** What the server answered. {@code body} is empty on a 304. */
  record Answer(int status, byte[] body, String etag, String version) {}

  /** The download; the seam a suite scripts. */
  @FunctionalInterface
  interface Fetch {
    Answer get(String url, String bearer, List<String> ifNoneMatch) throws Exception;
  }

  /** The node's side; the seam a suite scripts. */
  interface Host {
    /** The host binary's digest, {@code "absent"} when the node has not joined, else empty. */
    Optional<String> digest();

    /** Install the staged binary of {@code sha256}: the helper's result. */
    RunnerDocker.Result install(String sha256);
  }

  private final String url;
  private final Path stateDir;
  private final Host host;
  private final Fetch fetch;
  private final Supplier<Supplier<CompletionStage<String>>> bearer;
  private final ScheduledThreadPoolExecutor executor;

  // Only the update thread touches these.
  private String hostDigest;
  private final List<String> refused = new ArrayList<>();
  private boolean started;

  NodeTunnelUpdate(
      String url,
      Path stateDir,
      Host host,
      Fetch fetch,
      Supplier<Supplier<CompletionStage<String>>> bearer) {
    this.url = url;
    this.stateDir = stateDir;
    this.host = host;
    this.fetch = fetch;
    this.bearer = bearer;
    this.executor =
        new ScheduledThreadPoolExecutor(
            1,
            r -> {
              Thread t = new Thread(r, "system-runner-node-tunnel");
              t.setDaemon(true);
              return t;
            });
    executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
  }

  /** Begin the schedule; once per process, a later call changes nothing. */
  synchronized void start() {
    if (started) {
      return;
    }
    started = true;
    if (url == null) {
      LOG.debugf("system-runner does not follow qits-node-tunnel: no network-access URL derives");
      return;
    }
    try {
      executor.scheduleWithFixedDelay(
          this::checkSafely, FIRST.toMillis(), EVERY.toMillis(), TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException closed) {
      // On the way out.
    }
  }

  void close() {
    executor.shutdown();
  }

  ScheduledThreadPoolExecutor executor() {
    return executor;
  }

  private void checkSafely() {
    try {
      check();
    } catch (RuntimeException e) {
      LOG.warnf("system-runner could not check qits-node-tunnel: %s", e.getMessage());
    }
  }

  /** One round; see the class javadoc. Answers whether to go on. */
  boolean check() {
    if (hostDigest == null) {
      Optional<String> read = host.digest();
      if (read.isEmpty()) {
        return true;
      }
      if (read.get().equals("absent")) {
        LOG.infof("system-runner's node has not joined the private network; it does not follow"
            + " qits-node-tunnel");
        executor.shutdown();
        return false;
      }
      hostDigest = read.get();
    }
    String token;
    try {
      token = bearer.get().get().toCompletableFuture().get(1, TimeUnit.MINUTES);
    } catch (Exception e) {
      LOG.warnf("system-runner cannot ask for qits-node-tunnel: no bearer (%s)", e.getMessage());
      return true;
    }
    List<String> known = new ArrayList<>();
    known.add(hostDigest);
    known.addAll(refused);
    Answer answer;
    try {
      answer = fetch.get(url + "/api/nodes/qits-node-tunnel", token, known);
    } catch (Exception e) {
      LOG.warnf("system-runner could not ask %s for qits-node-tunnel: %s", url, e.getMessage());
      return true;
    }
    if (answer.status() == 304) {
      LOG.debugf("system-runner's qits-node-tunnel %s is current", brief(hostDigest));
      return true;
    }
    if (answer.status() != 200) {
      LOG.warnf("system-runner asked for qits-node-tunnel and got %d", answer.status());
      return true;
    }
    String served = sha256(answer.body());
    String etag = unquote(answer.etag());
    if (etag != null && !etag.equals(served)) {
      LOG.warnf(
          "system-runner refuses a qits-node-tunnel download whose digest %s is not its ETag %s",
          brief(served), brief(etag));
      return true;
    }
    if (served.equals(hostDigest) || refused.contains(served)) {
      return true;
    }
    String release = answer.version() == null ? "unknown" : answer.version();
    Path staged = stateDir.resolve(STAGED);
    try {
      Files.createDirectories(staged.getParent());
      Files.write(staged, answer.body());
    } catch (IOException e) {
      LOG.warnf("system-runner could not stage qits-node-tunnel in %s: %s", staged, e.getMessage());
      return true;
    }
    LOG.infof(
        "system-runner updates qits-node-tunnel from %s to %s (qits-network-access %s)",
        brief(hostDigest), brief(served), release);
    try {
      RunnerDocker.Result result = host.install(served);
      if (result.ok()) {
        LOG.infof(
            "system-runner updated qits-node-tunnel from %s to %s (qits-network-access %s)",
            brief(hostDigest), brief(served), release);
        hostDigest = served;
      } else if (result.exitCode() == ROLLED_BACK) {
        refused.add(served);
        LOG.warnf(
            "system-runner put qits-node-tunnel %s back: %s (qits-network-access %s) did not stay"
                + " up",
            brief(hostDigest), brief(served), release);
      } else {
        LOG.warnf(
            "system-runner could not update qits-node-tunnel from %s to %s: exit %d%s, %s",
            brief(hostDigest),
            brief(served),
            result.exitCode(),
            result.timedOut() ? " (timed out)" : "",
            result.detail());
        // Read the host again next round: the swap may have happened.
        hostDigest = null;
      }
    } finally {
      try {
        Files.deleteIfExists(staged);
      } catch (IOException e) {
        LOG.debugf("system-runner could not remove %s: %s", staged, e.getMessage());
      }
    }
    return true;
  }

  /** {@code sha256:} and the first 12 hex digits: what the log names a binary by. */
  static String brief(String digest) {
    return "sha256:" + (digest.length() > 12 ? digest.substring(0, 12) : digest);
  }

  static String unquote(String etag) {
    if (etag == null) {
      return null;
    }
    String tag = etag.strip();
    if (tag.startsWith("W/")) {
      tag = tag.substring(2);
    }
    if (tag.length() >= 2 && tag.startsWith("\"") && tag.endsWith("\"")) {
      tag = tag.substring(1, tag.length() - 1);
    }
    return tag.toLowerCase(Locale.ROOT);
  }

  static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * qits-network-access's base, derived from the runner's server URL the way the edge names every
   * application: its first host label replaced by {@code network-access}, and the service's root
   * path — {@code https://system.qits.example.eu} → {@code
   * https://network-access.qits.example.eu/network-access}. Null when the host has one label or is
   * an address, as {@code RunnerSettings.telemetryUrl} derives nothing then.
   */
  static String networkAccessUrl(String hostUrl) {
    if (hostUrl == null) {
      return null;
    }
    URI uri;
    try {
      uri = URI.create(hostUrl.trim());
    } catch (IllegalArgumentException e) {
      return null;
    }
    String host = uri.getHost();
    if (uri.getScheme() == null || host == null || host.matches("[0-9.]+|.*:.*|\\[.*\\]")) {
      return null;
    }
    int dot = host.indexOf('.');
    if (dot <= 0 || dot == host.length() - 1) {
      return null;
    }
    return uri.getScheme().toLowerCase(Locale.ROOT)
        + "://network-access"
        + host.substring(dot).toLowerCase(Locale.ROOT)
        + (uri.getPort() == -1 ? "" : ":" + uri.getPort())
        + "/network-access";
  }

  /**
   * {@code docker run --rm --name <helper> --label <owner> --privileged --pid=host --network none
   * -v <state volume>:/state:ro --entrypoint /bin/sh <image> -c <SCRIPT> sh <mode> [<digest>]}.
   */
  static List<String> helperArgv(
      String binary,
      RunnerIdentity identity,
      String runnerId,
      String image,
      String mode,
      String digest) {
    List<String> args = new ArrayList<>(List.of("-c", SCRIPT, "sh", mode));
    if (digest != null) {
      args.add(digest);
    }
    return HostRunArgv.render(
        binary,
        HostRunSpec.removed(image)
            .name(identity.nodeName(runnerId, PURPOSE))
            .label(identity.ownerLabel(), runnerId)
            .privileged()
            .pidHost()
            .network("none")
            .volume(identity.stateVolume(runnerId) + ":/state:ro")
            .entrypoint("/bin/sh")
            .args(args)
            .build());
  }

  /** The production node: helpers from the runner's own image, through {@code docker}. */
  static Host dockerHost(RunnerIdentity identity, String runnerId, RunnerDocker docker) {
    return new Host() {
      private String image;

      private Optional<String> image() {
        if (image != null) {
          return Optional.of(image);
        }
        Optional<String> self = SelfContainer.detect();
        if (self.isEmpty()) {
          LOG.debugf("system-runner is not in a container; it does not follow qits-node-tunnel");
          return Optional.empty();
        }
        RunnerDocker.Result inspected =
            docker.run(
                DockerArgv.inspectTypeContainerFormat(
                    docker.binary(), self.get(), "{{.Config.Image}}"));
        String found = inspected.ok() ? inspected.stdout().strip() : "";
        if (found.isEmpty()) {
          LOG.warnf("system-runner could not read its own image: %s", inspected.detail());
          return Optional.empty();
        }
        image = found;
        return Optional.of(image);
      }

      @Override
      public Optional<String> digest() {
        Optional<String> own = image();
        if (own.isEmpty()) {
          return Optional.empty();
        }
        RunnerDocker.Result read =
            docker.run(helperArgv(docker.binary(), identity, runnerId, own.get(), "digest", null));
        String out = read.stdout() == null ? "" : read.stdout().strip();
        if (!read.ok() || !(out.equals("absent") || out.matches("[0-9a-f]{64}"))) {
          LOG.warnf("system-runner could not read the node's qits-node-tunnel: %s", read.detail());
          return Optional.empty();
        }
        return Optional.of(out);
      }

      @Override
      public RunnerDocker.Result install(String sha256) {
        Optional<String> own = image();
        if (own.isEmpty()) {
          return new RunnerDocker.Result(1, "", "no image of its own", false, false);
        }
        return docker.run(
            helperArgv(docker.binary(), identity, runnerId, own.get(), "install", sha256));
      }
    };
  }

  /** The production download: {@code java.net.http}, the bearer and the known digests. */
  static Fetch http() {
    HttpClient client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    return (target, token, known) -> {
      HttpRequest.Builder request =
          HttpRequest.newBuilder(URI.create(target))
              .timeout(Duration.ofMinutes(2))
              .header("Authorization", "Bearer " + token)
              .header("Accept", "application/octet-stream");
      if (!known.isEmpty()) {
        request.header(
            "If-None-Match", String.join(", ", known.stream().map(d -> "\"" + d + "\"").toList()));
      }
      HttpResponse<byte[]> response =
          client.send(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
      return new Answer(
          response.statusCode(),
          response.body() == null ? new byte[0] : response.body(),
          response.headers().firstValue("ETag").orElse(null),
          response.headers().firstValue(VERSION_HEADER).orElse(null));
    };
  }
}
