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
import java.util.function.Supplier;
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
 * image, the rendered toml, the bootstrap and the resolvers when there are any, so a bumped pin, a
 * changed registry setting (the environment's, or the {@code Ack}'s mirrors) or a changed resolver
 * list replaces the container, and the state volume rides
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
 * <p><b>Through the node tunnel where the node has one</b> (qits-2090): the {@code resolvers}
 * seam — {@link NodeTunnel#resolvers()} in production — is asked once per {@link #ensure}, and a
 * non-empty answer is one {@code --dns} each right after {@code --network} and part of the stamp.
 * On the build network docker's embedded DNS ({@value #EXEC_NAMESERVER}, the toml's {@code [dns]})
 * forwards to those servers, so buildkitd and every {@code RUN} resolve private names through the
 * tunnel. An empty answer — the platform host, a node without the tunnel — adds nothing to either,
 * so such a builder keeps the stamp it had before this seam. The tunnel coming or going changes the
 * stamp, and the next CI lease replaces the builder as a bumped image does; while it is down with
 * the builder still on its resolvers, a lookup first waits out about 4 s in docker's embedded DNS
 * before the public answer. In production the seam is {@link SteadyResolvers}: a probe that fails
 * for a short while does not drop the resolvers, so it does not move the stamp (qits-2260).
 *
 * <p><b>Never replaced under a running build</b> (qits-2260): a stale builder that is running and
 * has a CI lease on the build network ({@link #users}) is kept, and the lease starts on it. The
 * first {@link #ensure} that finds no CI lease on the network replaces it. When docker cannot say
 * who is on the network, the builder is kept too. Each replacement, and each first deferral, logs
 * which input changed ({@link #changes}).
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
   * "host"} puts a {@code RUN} in buildkitd's own network namespace; {@code max-parallelism}
   * bounds concurrently running operations — {@code …_BUILDKIT_MAX_PARALLELISM} when set, else
   * the runner's slot total (see {@link BuildkitSettings}); and the GC policies bound the state
   * volume — anything unused for 72h goes, then the least recently used above 20 GiB, then the
   * same cap over buildkit's internal records too.
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
  private final int slots;
  private final List<String> registryMirrors;
  private final Names names;
  private final String toml;
  private final Supplier<List<String>> resolvers;

  /** The last stale stamp pair {@link #ensure} kept the builder for, so it is logged once. */
  private String deferred;

  /**
   * The plane under the environment's settings alone. {@code slots} is the runner's own ({@code
   * RunnerSettings.slots()}, {@code QITS_SYSTEM_RUNNER_SLOTS}): what {@code max-parallelism} falls
   * back to when {@link BuildkitSettings#maxParallelism()} is unset, clamped to at least one.
   */
  BuildPlane(
      RunnerIdentity identity,
      String targetId,
      RunnerDocker docker,
      BuildkitSettings settings,
      int slots) {
    this(identity, targetId, docker, settings, slots, List::of);
  }

  /**
   * The same, with the builder's resolvers: {@link NodeTunnel#resolvers()} in production, asked
   * once per {@link #ensure} (and by {@link #configuredStamp()}); {@code List::of} is no {@code
   * --dns}, the builder as before qits-2090.
   */
  BuildPlane(
      RunnerIdentity identity,
      String targetId,
      RunnerDocker docker,
      BuildkitSettings settings,
      int slots,
      Supplier<List<String>> resolvers) {
    this(identity, targetId, docker, settings, slots, settings.registryMirrors(), resolvers);
  }

  private BuildPlane(
      RunnerIdentity identity,
      String targetId,
      RunnerDocker docker,
      BuildkitSettings settings,
      int slots,
      List<String> registryMirrors,
      Supplier<List<String>> resolvers) {
    this.resolvers = resolvers;
    this.identity = identity;
    this.targetId = targetId;
    this.docker = docker;
    this.settings = settings;
    this.slots = Math.max(1, slots);
    this.registryMirrors = List.copyOf(registryMirrors);
    this.names = Names.of(identity, targetId);
    this.toml = renderToml(settings.httpRegistries(), this.registryMirrors, maxParallelism());
  }

  /** {@code …_BUILDKIT_MAX_PARALLELISM} when set, else the runner's slot total. */
  private int maxParallelism() {
    return settings.maxParallelism() != null ? settings.maxParallelism() : slots;
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
    return new BuildPlane(identity, targetId, docker, settings, slots, merged, resolvers);
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

  /**
   * {@code …_BUILDER_CONCURRENT_BUILDS}: the width of qits-ci-daemon's target-wide build gate a CI
   * lease is handed as {@code QITS_CI_BUILDER_SLOTS} ({@link SlotPolicy}).
   */
  int concurrentBuilds() {
    return settings.concurrentBuilds();
  }

  String toml() {
    return toml;
  }

  /** {@code qits.system.runner.buildkitd}. */
  String stampLabel() {
    return identity.driverNamespace() + STAMP_LABEL_SUFFIX;
  }

  /**
   * The resolvers the builder is started with now: the seam's answer, empty when it throws or
   * answers null. Asked once per {@link #ensure}.
   */
  List<String> resolvers() {
    try {
      List<String> answer = resolvers.get();
      return answer == null ? List.of() : List.copyOf(answer);
    } catch (RuntimeException e) {
      LOG.warnf("system-runner starts its builder off the node tunnel: %s", e.getMessage());
      return List.of();
    }
  }

  /**
   * The stamp {@link #ensure} starts the builder under now — the resolvers asked afresh, so the
   * {@code buildkit} health check compares against what the next CI lease would start.
   */
  String configuredStamp() {
    return configuredStamp(resolvers());
  }

  /**
   * The stamp over the image, the toml, the bootstrap, the state volume and — only when there are
   * any — the resolvers: an empty list is the stamp from before qits-2090, byte for byte, so a
   * builder without resolvers is not replaced by this change.
   */
  String configuredStamp(List<String> dns) {
    return stamp(settings.image(), toml, dns);
  }

  /** The stamp over these inputs, with this plane's bootstrap and state volume. */
  private String stamp(String image, String toml, List<String> dns) {
    String material = image + "\n" + toml + "\n" + BOOTSTRAP + "\n" + names.stateVolume();
    if (!dns.isEmpty()) {
      material += "\n" + String.join(",", dns);
    }
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
   * the one name. A stale builder is replaced — the state volume stays — but only when no CI lease
   * is on the build network; else it is kept, and the lease starts on it (qits-2260).
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
    // Decided once: the stamp and the run must name the same resolvers.
    List<String> dns = resolvers();
    String stamp = configuredStamp(dns);
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
        if ("running".equals(builder.status())) {
          List<String> users = users();
          if (users == null || !users.isEmpty()) {
            String pair = builder.stamp() + ">" + stamp;
            if (!pair.equals(deferred)) {
              deferred = pair;
              LOG.infof(
                  "system-runner keeps its stale builder while %s: stamp %s, configured %s,"
                      + " changed %s; it is replaced once no CI lease is on %s",
                  users == null
                      ? "docker cannot say which CI leases use it"
                      : "CI leases use it " + users,
                  builder.stamp(),
                  stamp,
                  changes(builder.stamp(), dns),
                  names.network());
            }
            return Optional.empty();
          }
        }
        deferred = null;
        LOG.infof(
            "system-runner replaces its builder: stamp %s, configured %s, changed %s",
            builder.stamp(),
            stamp,
            changes(builder.stamp(), dns));
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
    // The pull is the domain's to start off the converge lock (imagePresent, pullImage); this one
    // is the belt for an image removed since, and is not reached on a cold node.
    if (!imagePresent()) {
      Optional<String> pulled = pullImage();
      if (pulled.isPresent()) {
        return pulled;
      }
    }
    RunnerDocker.Result started = docker.run(runArgv(stamp, dns));
    if (!started.ok()) {
      return Optional.of("could not run the builder: " + started.detail());
    }
    if (!dns.isEmpty()) {
      LOG.infof("system-runner starts its builder via the node tunnel, resolvers %s", dns);
    }
    LOG.infof("system-runner's builder %s is up on %s", names.builder(), image());
    return Optional.empty();
  }

  /**
   * The running containers on the build network besides the builder — the CI leases that may have
   * a build on it. One {@code docker ps}. Null when docker does not answer: the caller then
   * assumes the builder is in use.
   */
  List<String> users() {
    RunnerDocker.Result listed =
        docker.run(
            List.of(
                docker.binary(),
                "ps",
                "--format",
                "{{.Names}}",
                "--filter",
                "network=" + names.network()));
    if (!listed.ok()) {
      LOG.warnf(
          "system-runner cannot list the containers on %s: %s", names.network(), listed.detail());
      return null;
    }
    List<String> users = new ArrayList<>();
    for (String line : listed.stdout().split("\n")) {
      String name = line.strip();
      if (!name.isEmpty() && !name.equals(names.builder())) {
        users.add(name);
      }
    }
    return users;
  }

  /**
   * Which of the stamp's inputs differ between the running builder and the configured one, for the
   * log: the image and the resolvers with old and new values, the toml by name, and the bootstrap or
   * state volume when the old inputs read back do not give the running stamp. One {@code docker
   * container inspect}; never fails the caller.
   */
  String changes(String runningStamp, List<String> dns) {
    RunnerDocker.Result inspected =
        docker.run(
            List.of(
                docker.binary(),
                "container",
                "inspect",
                "--format",
                "{{.Config.Image}}{{\"\\n\"}}{{range .HostConfig.Dns}}{{.}} {{end}}{{\"\\n\"}}"
                    + "{{range .Config.Env}}{{json .}}{{\"\\n\"}}{{end}}",
                names.builder()));
    if (!inspected.ok()) {
      return "unknown: " + inspected.detail();
    }
    String[] lines = inspected.stdout().split("\n", -1);
    String oldImage = lines.length > 0 ? lines[0].strip() : "";
    List<String> oldDns = new ArrayList<>();
    if (lines.length > 1) {
      for (String server : lines[1].strip().split(" ")) {
        if (!server.isEmpty()) {
          oldDns.add(server);
        }
      }
    }
    String oldToml = null;
    for (int i = 2; i < lines.length; i++) {
      String env = unquote(lines[i].strip());
      if (env != null && env.startsWith("BUILDKITD_TOML=")) {
        oldToml = env.substring("BUILDKITD_TOML=".length());
      }
    }
    List<String> changed = new ArrayList<>();
    if (!oldImage.equals(image())) {
      changed.add("image " + oldImage + " -> " + image());
    }
    if (!oldDns.equals(dns)) {
      changed.add("resolvers " + oldDns + " -> " + dns);
    }
    if (!toml.equals(oldToml)) {
      changed.add("toml");
    }
    if (oldToml != null && !stamp(oldImage, oldToml, oldDns).equals(runningStamp)) {
      changed.add("bootstrap or state volume");
    }
    return changed.isEmpty() ? "nothing readable" : String.join(", ", changed);
  }

  /** A JSON string literal's value, or null when {@code json} is not one. */
  static String unquote(String json) {
    if (json.length() < 2 || json.charAt(0) != '"' || json.charAt(json.length() - 1) != '"') {
      return null;
    }
    StringBuilder out = new StringBuilder();
    for (int i = 1; i < json.length() - 1; i++) {
      char c = json.charAt(i);
      if (c != '\\') {
        out.append(c);
        continue;
      }
      if (++i >= json.length() - 1) {
        return null;
      }
      char e = json.charAt(i);
      switch (e) {
        case 'n' -> out.append('\n');
        case 't' -> out.append('\t');
        case 'r' -> out.append('\r');
        case 'b' -> out.append('\b');
        case 'f' -> out.append('\f');
        case 'u' -> {
          if (i + 4 >= json.length() - 1) {
            return null;
          }
          try {
            out.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
          } catch (NumberFormatException notHex) {
            return null;
          }
          i += 4;
        }
        default -> out.append(e);
      }
    }
    return out.toString();
  }

  /** Whether the builder image is on this node: one {@code docker image inspect}. */
  boolean imagePresent() {
    return docker
        .run(List.of(docker.binary(), "image", "inspect", "--format", "{{.Id}}", image()))
        .ok();
  }

  /**
   * Pull the builder image under the host's docker config and {@link RunnerDocker#pullDeadline()}:
   * empty on success, else why not. Slow on a cold node, so never under the converge lock.
   */
  Optional<String> pullImage() {
    RunnerDocker.Result pulled =
        docker.run(List.of(docker.binary(), "pull", image()), docker.pullDeadline());
    if (!pulled.ok()) {
      return Optional.of("could not pull the builder image " + image() + ": " + pulled.detail());
    }
    return Optional.empty();
  }

  /**
   * The one privileged {@code docker run} this runner makes, a fixed shape: buildkitd cannot mount
   * overlayfs for its sandboxes without {@code --privileged}, and no spec may ever say it.
   */
  List<String> runArgv(String stamp) {
    return runArgv(stamp, List.of());
  }

  /** The same, with one {@code --dns} per resolver right after {@code --network}. */
  List<String> runArgv(String stamp, List<String> dns) {
    List<String> argv = new ArrayList<>();
    argv.addAll(
        List.of(
            docker.binary(), "run", "-d", "--name", names.builder(), "--network", names.network()));
    for (String server : dns) {
      argv.add("--dns");
      argv.add(server);
    }
    argv.addAll(
        List.of(
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
            BOOTSTRAP));
    return List.copyOf(argv);
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
