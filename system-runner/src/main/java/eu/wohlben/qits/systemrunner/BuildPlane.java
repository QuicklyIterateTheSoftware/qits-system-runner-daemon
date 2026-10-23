package eu.wohlben.qits.systemrunner;

import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.TeardownItem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * The target's own buildkitd, which every CI lease dials as {@code BUILDKIT_HOST} — ported from
 * qits-ci-runner-daemon's {@code BuildPlane}, with the target's derived names:
 *
 * <ul>
 *   <li>builder {@code qits-system-runner-buildkitd-<id8>}, also its address on the build network
 *       ({@link Names#address()}: {@code tcp://qits-system-runner-buildkitd-<id8>:1234});
 *   <li>network {@code qits-system-runner-build-<id8>}, a bridge every CI lease joins;
 *   <li>state volume {@code qits-system-runner-buildkitd-state-<id8>}, its content store.
 * </ul>
 *
 * <p><b>Never {@code qits-buildkitd} or {@code qits-buildkitd-state}.</b> Those are qits-containers'
 * platform builder and its volume; the platform host runs a target on the same docker. Every name is
 * derived from the identity ({@link Names#of}, which refuses either), none is configurable, and the
 * three are the domain's {@link TeardownItem}s, which the toolkit refuses unless they start with
 * {@code qits-system-runner-}.
 *
 * <p><b>Started on the first CI lease, not at boot</b> ({@link #ensure}): a target that only runs
 * workspaces and desks never pays for a privileged container. Each call re-inspects rather than
 * caching "done", so a builder somebody removed by hand is recreated by the next CI lease.
 *
 * <p><b>Ownership is the stamp label</b> {@code qits.system.runner.buildkitd}: a container under the
 * builder's name without it is somebody else's and is refused, not touched. The stamp hashes the
 * image, the rendered toml and the bootstrap, so a bumped pin or a changed registry setting (the
 * environment's, or the {@code Ack}'s mirrors) replaces the container, and the state volume rides
 * across. The builder deliberately does not carry the owner label {@code qits.system.runner=<id>}:
 * the toolkit's reconcile removes every owner-labelled container that is not a held lease.
 *
 * <p><b>Through the runner's process seam, not the driver.</b> qits-containers-driver cannot express
 * this container: {@code ContainerSpec} has no {@code --privileged} (on purpose), its {@code
 * runBuildkitd} always names the platform's {@code qits-buildkitd}, and it has no network create or
 * connect. So, as in the CI runner, the builder's argv is one fixed shape here ({@link #runArgv}),
 * run through {@link RunnerDocker} under its deadline; {@code --privileged} is never something a
 * spec can say.
 *
 * <p>Departure from the CI runner: no host CA bundle mount. This runner always runs in a container
 * and makes no host-path mount; the builder trusts the image's own bundle (what the CI runner did in
 * a container too).
 */
final class BuildPlane {

  private static final Logger LOG = Logger.getLogger(BuildPlane.class);

  /** The builder's purpose in {@code <namePrefix>-<purpose>-<id8>}. */
  static final String BUILDER = "buildkitd";

  /** The build network's purpose. */
  static final String NETWORK = "build";

  /** The state volume's purpose. */
  static final String STATE = "buildkitd-state";

  /** qits-containers' platform builder and its volume: never created, used or removed here. */
  static final Set<String> PLATFORM_NAMES = Set.of("qits-buildkitd", "qits-buildkitd-state");

  /** The port buildkitd listens on for the leases. */
  static final int PORT = 1234;

  /** The stamp label's suffix: {@code qits.system.runner.buildkitd}. */
  static final String STAMP_LABEL_SUFFIX = "buildkitd";

  /**
   * qits-containers' {@code BUILDKITD_BOOTSTRAP}, verbatim: the toml arrives as an environment
   * value and the container writes it for itself — no bind-mounted file, so no host path, and the
   * toml is inside the {@code docker run} and so inside the stamp.
   */
  static final String BOOTSTRAP =
      """
      set -e
      mkdir -p /etc/buildkit
      printf '%s' "$BUILDKITD_TOML" > /etc/buildkit/buildkitd.toml
      exec buildkitd --addr unix:///run/buildkit/buildkitd.sock --addr tcp://0.0.0.0:1234
      """;

  /** Docker's embedded DNS, which serves the build network's names to a {@code RUN}. */
  static final String EXEC_NAMESERVER = "127.0.0.11";

  /**
   * The worker table, the CI runner's verbatim (buildkit v0.33.0's keys): {@code networkMode =
   * "host"} puts a {@code RUN} in buildkitd's own network namespace; {@code max-parallelism} bounds
   * concurrently running operations ({@code …_BUILDKIT_MAX_PARALLELISM}); and the GC policies bound
   * the state volume — anything unused for 72h goes, then the least recently used above 20 GiB,
   * then the same cap over buildkit's internal records too.
   */
  static final String WORKER_TOML =
      """
      [worker.oci]
        networkMode = "host"
        gc = true
        max-parallelism = %d
      [[worker.oci.gcpolicy]]
        keepDuration = "72h"
      [[worker.oci.gcpolicy]]
        maxUsedSpace = "20GB"
      [[worker.oci.gcpolicy]]
        all = true
        maxUsedSpace = "20GB"
      """;

  /** The three names of one target's build plane. */
  record Names(String builder, String network, String stateVolume) {

    /**
     * {@code <namePrefix>-buildkitd-<id8>}, {@code <namePrefix>-build-<id8>} and {@code
     * <namePrefix>-buildkitd-state-<id8>}.
     *
     * @throws IllegalStateException if a derivation ever spelled a platform name
     */
    static Names of(RunnerIdentity identity, String targetId) {
      Names names =
          new Names(
              identity.nodeName(targetId, BUILDER),
              identity.nodeName(targetId, NETWORK),
              identity.nodeVolume(targetId, STATE));
      for (String name : List.of(names.builder, names.network, names.stateVolume)) {
        if (PLATFORM_NAMES.contains(name) || !identity.ownsName(name)) {
          throw new IllegalStateException(name + " is not this runner's to create");
        }
      }
      return names;
    }

    /** What a CI lease is handed as {@code BUILDKIT_HOST}. */
    String address() {
      return "tcp://" + builder + ":" + PORT;
    }

    /** Container first, then the network it is on, then the volume it mounts. */
    List<TeardownItem> teardown() {
      return List.of(
          new TeardownItem(TeardownItem.Type.CONTAINER, builder),
          new TeardownItem(TeardownItem.Type.NETWORK, network),
          new TeardownItem(TeardownItem.Type.VOLUME, stateVolume));
    }
  }

  /** Whether docker has the object a read-only look asked about. */
  enum Presence {
    /** docker said there is no such object. */
    ABSENT,
    /** It exists — for the builder: under this runner's stamp label. */
    PRESENT,
    /** The builder's name is taken by a container without the stamp label: somebody else's. */
    FOREIGN,
    /** docker did not answer; {@code detail} says what. */
    UNKNOWN
  }

  /** The builder as docker sees it now. */
  record Builder(Presence presence, String stamp, String status, String detail) {}

  /** The build network as docker sees it now. */
  record Network(Presence presence, String driver, String detail) {}

  private final RunnerIdentity identity;
  private final String targetId;
  private final RunnerDocker docker;
  private final BuildkitSettings settings;
  private final List<String> registryMirrors;
  private final Names names;
  private final String toml;

  /** The plane under the environment's settings alone. */
  BuildPlane(
      RunnerIdentity identity, String targetId, RunnerDocker docker, BuildkitSettings settings) {
    this(identity, targetId, docker, settings, settings.registryMirrors());
  }

  private BuildPlane(
      RunnerIdentity identity,
      String targetId,
      RunnerDocker docker,
      BuildkitSettings settings,
      List<String> registryMirrors) {
    this.identity = identity;
    this.targetId = targetId;
    this.docker = docker;
    this.settings = settings;
    this.registryMirrors = List.copyOf(registryMirrors);
    this.names = Names.of(identity, targetId);
    this.toml = renderToml(settings.httpRegistries(), this.registryMirrors, settings.maxParallelism());
  }

  /**
   * A plane with the environment's mirrors merged with the {@code Ack}'s {@code registryMirrors},
   * the {@code Ack}'s winning per source host. <b>Always called on the environment's plane</b>: the
   * host sends its whole map on every {@code Ack}, so a merge over a previous merge would keep a
   * host the latest map dropped. A pair outside the charsets is dropped, logged: it would be
   * rendered inside a TOML string. {@code null} is "the host sent none".
   */
  BuildPlane withRegistryMirrors(Map<String, String> ackMirrors) {
    if (ackMirrors == null) {
      return this;
    }
    List<String> merged = new ArrayList<>();
    for (String pair : settings.registryMirrors()) {
      if (!ackMirrors.containsKey(pair.substring(0, pair.indexOf('=')))) {
        merged.add(pair);
      }
    }
    ackMirrors.forEach(
        (from, to) -> {
          String pair = from + "=" + to;
          if (BuildkitSettings.mirror(pair)) {
            merged.add(pair);
          } else {
            LOG.warnf("system-runner ignored the Ack's registry mirror %s: not from=to", pair);
          }
        });
    return new BuildPlane(identity, targetId, docker, settings, merged);
  }

  /** buildkitd.toml: the worker table, the DNS override, then one merged table per registry host. */
  static String renderToml(
      List<String> httpRegistries, List<String> registryMirrors, int maxParallelism) {
    Map<String, List<String>> mirrors = new LinkedHashMap<>();
    Set<String> http = new LinkedHashSet<>(httpRegistries);
    Set<String> hosts = new LinkedHashSet<>();
    for (String pair : registryMirrors) {
      int split = pair.indexOf('=');
      String from = pair.substring(0, split);
      mirrors.computeIfAbsent(from, k -> new ArrayList<>()).add(pair.substring(split + 1));
      hosts.add(from);
    }
    hosts.addAll(http);
    StringBuilder out =
        new StringBuilder(WORKER_TOML.formatted(maxParallelism))
            .append("[dns]\n  nameservers = [\"")
            .append(EXEC_NAMESERVER)
            .append("\"]\n");
    for (String host : hosts) {
      out.append("[registry.\"").append(host).append("\"]\n");
      List<String> targets = mirrors.get(host);
      if (targets != null) {
        out.append("  mirrors = [");
        for (int i = 0; i < targets.size(); i++) {
          out.append(i == 0 ? "\"" : ", \"").append(targets.get(i)).append("\"");
        }
        out.append("]\n");
      }
      if (http.contains(host)) {
        out.append("  http = true\n");
      }
    }
    return out.toString();
  }

  Names names() {
    return names;
  }

  String image() {
    return settings.image();
  }

  String toml() {
    return toml;
  }

  /** {@code qits.system.runner.buildkitd}. */
  String stampLabel() {
    return identity.driverNamespace() + STAMP_LABEL_SUFFIX;
  }

  /** The stamp {@link #ensure} starts the builder under now. */
  String configuredStamp() {
    String material =
        settings.image() + "\n" + toml + "\n" + BOOTSTRAP + "\n" + names.stateVolume();
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest).substring(0, 16);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is missing from this JVM", e);
    }
  }

  /**
   * The network, the state volume and the builder up under the configured stamp. Empty when a CI
   * lease may start; else why not. Synchronized: two CI leases started at once must not both run
   * the one name. A stale builder is replaced — the state volume stays.
   */
  synchronized Optional<String> ensure() {
    Network network = network();
    if (network.presence() == Presence.UNKNOWN) {
      return Optional.of(
          "docker could not say whether " + names.network() + " exists: " + network.detail());
    }
    if (network.presence() == Presence.ABSENT) {
      RunnerDocker.Result created =
          docker.run(
              List.of(
                  docker.binary(),
                  "network",
                  "create",
                  "--driver",
                  "bridge",
                  "--label",
                  identity.ownerLabel() + "=" + targetId,
                  names.network()));
      if (!created.ok() && !created.detail().contains("already exists")) {
        return Optional.of(
            "could not create the build network " + names.network() + ": " + created.detail());
      }
    }
    // Creating a volume that exists is docker's own no-op. Not owner-labelled: the decommission
    // removes it as a teardown item, after the builder that mounts it.
    RunnerDocker.Result volume =
        docker.run(
            List.of(
                docker.binary(),
                "volume",
                "create",
                "--label",
                stampLabel() + "=state",
                names.stateVolume()));
    if (!volume.ok()) {
      return Optional.of("could not create " + names.stateVolume() + ": " + volume.detail());
    }
    String stamp = configuredStamp();
    Builder builder = builder();
    switch (builder.presence()) {
      case UNKNOWN -> {
        return Optional.of(
            "docker did not answer an inspect of " + names.builder() + ": " + builder.detail());
      }
      case FOREIGN -> {
        return Optional.of(
            "a container named "
                + names.builder()
                + " exists and is not this runner's builder; it was left alone");
      }
      case PRESENT -> {
        if (stamp.equals(builder.stamp())) {
          if (!"running".equals(builder.status())) {
            RunnerDocker.Result started =
                docker.run(List.of(docker.binary(), "start", names.builder()));
            if (!started.ok()) {
              return Optional.of("could not start " + names.builder() + ": " + started.detail());
            }
          }
          return Optional.empty();
        }
        LOG.infof(
            "system-runner replaces its builder: stamp %s, configured %s", builder.stamp(), stamp);
        RunnerDocker.Result gone =
            docker.run(List.of(docker.binary(), "rm", "-f", names.builder()));
        if (!gone.ok() && !TargetVolumes.absent(gone.detail())) {
          return Optional.of("could not replace " + names.builder() + ": " + gone.detail());
        }
      }
      case ABSENT -> {
        // made below
      }
    }
    if (!docker
        .run(List.of(docker.binary(), "image", "inspect", "--format", "{{.Id}}", image()))
        .ok()) {
      RunnerDocker.Result pulled =
          docker.run(List.of(docker.binary(), "pull", image()), docker.pullDeadline());
      if (!pulled.ok()) {
        return Optional.of("could not pull the builder image " + image() + ": " + pulled.detail());
      }
    }
    RunnerDocker.Result started = docker.run(runArgv(stamp));
    if (!started.ok()) {
      return Optional.of("could not run the builder: " + started.detail());
    }
    LOG.infof("system-runner's builder %s is up on %s", names.builder(), image());
    return Optional.empty();
  }

  /**
   * The one privileged {@code docker run} this runner makes, a fixed shape: buildkitd cannot mount
   * overlayfs for its sandboxes without {@code --privileged}, and no spec may ever say it.
   */
  List<String> runArgv(String stamp) {
    return List.of(
        docker.binary(),
        "run",
        "-d",
        "--name",
        names.builder(),
        "--network",
        names.network(),
        "--label",
        stampLabel() + "=" + stamp,
        "--privileged",
        "--restart",
        "unless-stopped",
        "-v",
        names.stateVolume() + ":/var/lib/buildkit",
        "-e",
        "BUILDKITD_TOML=" + toml,
        "--entrypoint",
        "/bin/sh",
        image(),
        "-c",
        BOOTSTRAP);
  }

  /**
   * Join a lease container to the build network: the driver's {@code ContainerSpec} carries one
   * network, so the second membership is a {@code docker network connect} right after the run. A
   * second connect answers "already exists", which is the state asked for. Empty on success.
   */
  Optional<String> connect(String container) {
    RunnerDocker.Result joined =
        docker.run(List.of(docker.binary(), "network", "connect", names.network(), container));
    if (!joined.ok() && !joined.detail().contains("already exists")) {
      return Optional.of(
          "could not connect " + container + " to " + names.network() + ": " + joined.detail());
    }
    return Optional.empty();
  }

  /**
   * The builder's presence, stamp and state — <b>read-only</b>, for the {@code buildkit} and {@code
   * network} checks: one inspect, never a create, start or pull, and not synchronized, so a health
   * check never queues behind an {@link #ensure} that is pulling.
   */
  Builder builder() {
    RunnerDocker.Result inspected =
        docker.run(
            List.of(
                docker.binary(),
                "inspect",
                "--type",
                "container",
                "--format",
                "{{index .Config.Labels \"" + stampLabel() + "\"}}|{{.State.Status}}",
                names.builder()));
    if (!inspected.ok()) {
      return absent(inspected)
          ? new Builder(Presence.ABSENT, null, null, null)
          : new Builder(Presence.UNKNOWN, null, null, inspected.detail());
    }
    String line = inspected.stdout().strip();
    if (line.isEmpty()) {
      return new Builder(Presence.ABSENT, null, null, null);
    }
    String[] answer = line.split("\\|", 2);
    String stamp = answer[0];
    String status = answer.length > 1 ? answer[1] : "";
    if (stamp.isEmpty() || stamp.equals("<no value>")) {
      return new Builder(Presence.FOREIGN, null, status, null);
    }
    return new Builder(Presence.PRESENT, stamp, status, null);
  }

  /** The build network's presence and driver — read-only, one {@code docker network inspect}. */
  Network network() {
    RunnerDocker.Result inspected =
        docker.run(
            List.of(
                docker.binary(), "network", "inspect", "--format", "{{.Driver}}", names.network()));
    if (!inspected.ok()) {
      return absent(inspected)
          ? new Network(Presence.ABSENT, null, null)
          : new Network(Presence.UNKNOWN, null, inspected.detail());
    }
    String driver = inspected.stdout().strip();
    return new Network(Presence.PRESENT, driver.isEmpty() ? null : driver, null);
  }

  private static boolean absent(RunnerDocker.Result result) {
    String detail = result.detail() == null ? "" : result.detail().toLowerCase(Locale.ROOT);
    return !result.timedOut()
        && (detail.contains("no such object")
            || detail.contains("no such container")
            || detail.contains("no such network")
            || detail.contains("not found"));
  }
}
