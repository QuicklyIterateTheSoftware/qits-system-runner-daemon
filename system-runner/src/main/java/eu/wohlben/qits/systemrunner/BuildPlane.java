package eu.wohlben.qits.systemrunner;

import eu.wohlben.qits.containers.driver.docker.DockerArgv;
import eu.wohlben.qits.containers.driver.docker.HostRunArgv;
import eu.wohlben.qits.containers.driver.docker.SwarmArgv;
import eu.wohlben.qits.containers.driver.spec.ContainersIdentifiers;
import eu.wohlben.qits.containers.driver.spec.HostRunSpec;
import eu.wohlben.qits.containers.driver.spec.LabelNamespace;
import eu.wohlben.qits.containers.driver.spec.VolumeSpec;
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
 * image, the rendered toml, the bootstrap, the resolvers when there are any and the memory cap, so a
 * bumped pin, a changed registry setting (the environment's, or the {@code Ack}'s mirrors), a
 * changed resolver list or a changed {@code …_BUILDER_CONCURRENT_BUILDS} replaces the container,
 * and the state volume rides across. The builder deliberately does not carry the owner label
 * {@code qits.system.runner=<id>}: the toolkit's reconcile removes every owner-labelled container
 * that is not a held lease.
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
 * <p><b>One {@code docker run}, no join after it</b> (qits-2270). The builder's only network is the
 * build network, a user-defined one, so docker gives it its embedded DNS from the start: the {@code
 * --dns} servers never reach its {@code /etc/resolv.conf}, and it has no moment where a public
 * resolver answers next to the tunnel's. A lease is different: it starts on {@code bridge}, so it is
 * made with {@code docker create} and started only after its joins ({@link LeaseLauncher}). A second
 * network on the builder would need the same order.
 *
 * <p><b>Under a memory cap</b> (qits-2503): {@code --memory} {@link
 * BuildkitSettings#memoryCapBytes()}, {@code …_BUILDER_CONCURRENT_BUILDS} × 8 GiB, and no {@code
 * --memory-swap}. The builds a CI lease sends here — a native-image {@code RUN} takes up to 8 GiB —
 * run in the builder's cgroup, outside the lease's own cap, so without one they were memory no
 * placement could count; the runner reports the cap as the node's {@code builderMemoryBytes}. The
 * cap joined the stamp, so the release carrying it changed every existing builder's stamp once, and
 * each was replaced through the path below on the first {@link #ensure} that found no CI lease on
 * its network — never under a running build. <b>A raised cap does not wait for that gap</b>
 * (qits-2503): a kept stale builder whose {@code HostConfig.Memory} is below the configured cap gets
 * the cap in place ({@link #raiseInPlace}), because on a busy target CI leases overlap without end
 * and a build that needs the new cap kept failing on the old one ({@code cannot allocate memory}).
 * A lower cap is never applied in place — it could OOM a running build — and waits for the
 * replacement.
 *
 * <p><b>Never replaced under a running build</b> (qits-2260): a stale builder that is running and
 * has a CI lease on the build network ({@link #users}) is kept, and the lease starts on it. The
 * first {@link #ensure} that finds no CI lease on the network replaces it. When docker cannot say
 * who is on the network, the builder is kept too. Each replacement, and each first deferral, logs
 * which input changed ({@link #changes}). The one change made to a kept builder is the raised cap
 * above, a {@code docker update} that touches neither the container nor its builds; the stamp label
 * cannot be updated, so the builder stays stale and is still replaced at the next gap.
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
   * The last stale stamp pair whose kept builder is known to run at or above the configured cap —
   * read so, or raised in place (qits-2503) — so a later deferral of the same pair skips the
   * inspect. A failed raise leaves it unset, and the next {@link #ensure} tries again.
   */
  private String capInPlace;

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
   * The stamp over the image, the toml, the bootstrap, the state volume, the resolvers — only when
   * there are any — and the memory cap. An empty resolver list adds nothing, as it did since
   * qits-2090; the cap is always there, so qits-2503 changed every builder's stamp once, the
   * empty-resolver one included, and each builder was replaced the way a bumped image is.
   */
  String configuredStamp(List<String> dns) {
    return stamp(settings.image(), toml, dns, settings.memoryCapBytes());
  }

  /**
   * The stamp over these inputs, with this plane's bootstrap and state volume. A {@code memory} of
   * {@code 0} — docker's "no limit", what a builder from before qits-2503 inspects back as — adds
   * nothing, so {@link #changes} can restate such a builder's stamp from what it reads.
   */
  private String stamp(String image, String toml, List<String> dns, long memory) {
    String material = image + "\n" + toml + "\n" + BOOTSTRAP + "\n" + names.stateVolume();
    if (!dns.isEmpty()) {
      material += "\n" + String.join(",", dns);
    }
    if (memory > 0) {
      material += "\nmemory=" + memory;
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
              DockerArgv.bridgeNetworkCreate(
                  docker.binary(),
                  new LinkedHashMap<>(Map.of(identity.ownerLabel(), targetId)),
                  null,
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
            DockerArgv.volumeCreate(
                docker.binary(),
                new LabelNamespace(identity.driverNamespace()),
                new VolumeSpec(names.stateVolume()),
                Map.of(stampLabel(), "state")));
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
                docker.run(DockerArgv.startByReference(docker.binary(), names.builder()));
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
            if (pair.equals(deferred) && pair.equals(capInPlace)) {
              return Optional.empty();
            }
            // One inspect serves both the log and the in-place cap (qits-2503).
            Running running = running();
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
                  changes(builder.stamp(), dns, running),
                  names.network());
            }
            if (raiseInPlace(running)) {
              capInPlace = pair;
            }
            return Optional.empty();
          }
        }
        deferred = null;
        capInPlace = null;
        LOG.infof(
            "system-runner replaces its builder: stamp %s, configured %s, changed %s",
            builder.stamp(),
            stamp,
            changes(builder.stamp(), dns));
        RunnerDocker.Result gone =
            docker.run(DockerArgv.rm(docker.binary(), List.of(names.builder())));
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
    List<String> run;
    try {
      run = runArgv(stamp, dns);
    } catch (IllegalArgumentException refused) {
      return Optional.of("could not run the builder: " + refused.getMessage());
    }
    RunnerDocker.Result started = docker.run(run);
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
            DockerArgv.psFormatFirst(
                docker.binary(), false, "{{.Names}}", List.of("network=" + names.network())));
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
   * The running builder's stamp inputs as one {@code docker container inspect} reads them back:
   * the image, {@code HostConfig.Memory} ({@code 0} for none, {@code -1} when unreadable), {@code
   * HostConfig.MemorySwap} (null when unreadable), the resolvers and the toml. {@code detail} is set
   * instead when docker did not answer.
   */
  record Running(
      String image, long memory, Long memorySwap, List<String> dns, String toml, String detail) {}

  /** The one inspect behind {@link #changes} and {@link #raiseInPlace}; never fails the caller. */
  Running running() {
    RunnerDocker.Result inspected =
        docker.run(
            DockerArgv.containerInspect(
                docker.binary(),
                names.builder(),
                "{{.Config.Image}}{{\"\\n\"}}"
                    + "{{.HostConfig.Memory}} {{.HostConfig.MemorySwap}}{{\"\\n\"}}"
                    + "{{range .HostConfig.Dns}}{{.}} {{end}}{{\"\\n\"}}"
                    + "{{range .Config.Env}}{{json .}}{{\"\\n\"}}{{end}}"));
    if (!inspected.ok()) {
      return new Running(null, -1, null, List.of(), null, inspected.detail());
    }
    String[] lines = inspected.stdout().split("\n", -1);
    String oldImage = lines.length > 0 ? lines[0].strip() : "";
    long oldMemory = 0;
    Long oldSwap = null;
    if (lines.length > 1) {
      String[] limits = lines[1].strip().split(" ");
      try {
        oldMemory = Long.parseLong(limits[0]);
      } catch (NumberFormatException unreadable) {
        oldMemory = -1;
      }
      if (limits.length > 1) {
        try {
          oldSwap = Long.parseLong(limits[1]);
        } catch (NumberFormatException unreadable) {
          oldSwap = null;
        }
      }
    }
    List<String> oldDns = new ArrayList<>();
    if (lines.length > 2) {
      for (String server : lines[2].strip().split(" ")) {
        if (!server.isEmpty()) {
          oldDns.add(server);
        }
      }
    }
    String oldToml = null;
    for (int i = 3; i < lines.length; i++) {
      String env = unquote(lines[i].strip());
      if (env != null && env.startsWith("BUILDKITD_TOML=")) {
        oldToml = env.substring("BUILDKITD_TOML=".length());
      }
    }
    return new Running(oldImage, oldMemory, oldSwap, oldDns, oldToml, null);
  }

  /** {@link #changes(String, List, Running)} over a fresh {@link #running()}. */
  String changes(String runningStamp, List<String> dns) {
    return changes(runningStamp, dns, running());
  }

  /**
   * Which of the stamp's inputs differ between the running builder and the configured one, for the
   * log: the image, the memory cap (qits-2503, {@code 0} for none) and the resolvers with old and
   * new values, the toml by name, and the bootstrap or state volume when the old inputs read back do
   * not give the running stamp. A cap raised in place reads back as the configured one, so such a
   * builder's stamp cannot be restated, and that is named too. Never fails the caller.
   */
  String changes(String runningStamp, List<String> dns, Running running) {
    if (running.detail() != null) {
      return "unknown: " + running.detail();
    }
    List<String> changed = new ArrayList<>();
    if (!running.image().equals(image())) {
      changed.add("image " + running.image() + " -> " + image());
    }
    long memory = settings.memoryCapBytes();
    if (running.memory() >= 0 && running.memory() != memory) {
      changed.add("memory " + running.memory() + " -> " + memory);
    }
    if (!running.dns().equals(dns)) {
      changed.add("resolvers " + running.dns() + " -> " + dns);
    }
    if (!toml.equals(running.toml())) {
      changed.add("toml");
    }
    if (running.toml() != null
        && running.memory() >= 0
        && !stamp(running.image(), running.toml(), running.dns(), running.memory())
            .equals(runningStamp)) {
      changed.add(
          running.memory() == memory
              ? "bootstrap, state volume or a memory cap raised in place"
              : "bootstrap or state volume");
    }
    return changed.isEmpty() ? "nothing readable" : String.join(", ", changed);
  }

  /**
   * Raise a kept stale builder's memory cap to the configured one in place (qits-2503), so a raised
   * {@code …_BUILDER_CONCURRENT_BUILDS} or {@link BuildkitSettings#MEMORY_PER_BUILD} takes effect
   * under running builds instead of at the next gap. Only ever up: a {@code HostConfig.Memory} at or
   * above the cap — {@code 0}, docker's "no limit", included — or an unreadable one is left alone,
   * and a lower cap waits for the replacement, since it could OOM a running build. One {@code docker
   * update} under {@link RunnerDocker}'s deadline ({@link #memoryUpdateArgv}); a failure is logged
   * and the lease starts regardless. True when the builder now runs at or above the cap.
   */
  boolean raiseInPlace(Running running) {
    long cap = settings.memoryCapBytes();
    if (running.detail() != null || running.memory() < 0) {
      return false;
    }
    if (running.memory() == 0 || running.memory() >= cap) {
      return true;
    }
    long swap = memorySwapFor(running.memory(), running.memorySwap(), cap);
    RunnerDocker.Result updated = docker.run(memoryUpdateArgv(cap, swap));
    if (!updated.ok()) {
      LOG.warnf(
          "system-runner could not raise its stale builder's memory cap in place %d -> %d: %s",
          running.memory(),
          cap,
          updated.detail());
      return false;
    }
    LOG.infof(
        "system-runner raised its stale builder's memory cap in place: %d -> %d (memory-swap %s ->"
            + " %d); the builder is still replaced once no CI lease is on %s",
        running.memory(),
        cap,
        running.memorySwap() == null ? "unknown" : running.memorySwap(),
        swap,
        names.network());
    return true;
  }

  /**
   * The {@code --memory-swap} that goes with a raised {@code cap}: docker refuses a {@code --memory}
   * above the current memory+swap limit unless both are given. Docker's own ratio is kept — {@code
   * -1} (unlimited) stays {@code -1}, twice the memory (what {@code docker run --memory} alone sets,
   * how the builder is run) becomes twice the cap, and so does an unreadable one — and any other
   * value becomes the larger of it and the cap.
   */
  static long memorySwapFor(long memory, Long memorySwap, long cap) {
    if (memorySwap == null || memorySwap == 2 * memory) {
      return 2 * cap;
    }
    if (memorySwap == -1) {
      return -1;
    }
    return Math.max(memorySwap, cap);
  }

  /**
   * {@code docker update --memory <cap> --memory-swap <swap> <builder>} (qits-2503). <b>Spelled
   * here, not by a {@code qits-containers-driver} builder</b>: the driver has only {@code
   * updateRestartNo}, and the raise could not wait for a library release while builds failed. The
   * builder's name goes through the driver's own belt; the spelling moves into {@code DockerArgv}
   * once the driver gains one.
   */
  List<String> memoryUpdateArgv(long cap, long swap) {
    return List.of(
        docker.binary(),
        "update",
        "--memory",
        String.valueOf(cap),
        "--memory-swap",
        String.valueOf(swap),
        ContainersIdentifiers.requireContainerName(names.builder()));
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
        .run(DockerArgv.imageInspectFormat(docker.binary(), image(), "{{.Id}}"))
        .ok();
  }

  /**
   * Pull the builder image under the host's docker config and {@link RunnerDocker#pullDeadline()}:
   * empty on success, else why not. Slow on a cold node, so never under the converge lock.
   */
  Optional<String> pullImage() {
    RunnerDocker.Result pulled =
        docker.run(DockerArgv.pull(docker.binary(), image()), docker.pullDeadline());
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

  /**
   * The same, with one {@code --dns} per resolver right after {@code --network} — rendered by the
   * driver's {@link HostRunArgv}, whose options keep the order they are added in. Throws when a
   * value fails the driver's belts (a resolver that is not an address literal). The builder runs
   * under {@code --memory} {@link BuildkitSettings#memoryCapBytes()} (qits-2503), with no {@code
   * --memory-swap}.
   */
  List<String> runArgv(String stamp, List<String> dns) {
    HostRunSpec.Builder run =
        HostRunSpec.detached(names.builder(), image()).network(names.network());
    for (String server : dns) {
      run.dns(server);
    }
    return HostRunArgv.render(
        docker.binary(),
        run.label(stampLabel(), stamp)
            .privileged()
            .restart("unless-stopped")
            .memory(String.valueOf(settings.memoryCapBytes()))
            .volume(names.stateVolume() + ":/var/lib/buildkit")
            .env("BUILDKITD_TOML", toml)
            .entrypoint("/bin/sh")
            .args("-c", BOOTSTRAP)
            .build());
  }

  /**
   * Join a lease container to the build network: the driver's {@code ContainerSpec} carries one
   * network, so the second membership is a {@code docker network connect}. {@link LeaseLauncher}
   * calls it after the {@code docker create} and before the {@code docker start}, so the lease never
   * runs off docker's embedded DNS (qits-2270). A second connect answers "already exists", which is
   * the state asked for. Empty on success.
   */
  Optional<String> connect(String container) {
    RunnerDocker.Result joined =
        docker.run(DockerArgv.networkConnect(docker.binary(), names.network(), container));
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
            DockerArgv.inspectTypeContainerFormat(
                docker.binary(),
                names.builder(),
                "{{index .Config.Labels \"" + stampLabel() + "\"}}|{{.State.Status}}"));
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
        docker.run(SwarmArgv.networkInspect(docker.binary(), names.network(), "{{.Driver}}"));
    if (!inspected.ok()) {
      return absent(inspected)
          ? new Network(Presence.ABSENT, null, null)
          : new Network(Presence.UNKNOWN, null, inspected.detail());
    }
    String driver = inspected.stdout().strip();
    return new Network(Presence.PRESENT, driver.isEmpty() ? null : driver, null);
  }

  /**
   * Whether a failed read-only look said "no such object" — never a timeout, which is UNKNOWN.
   * {@link LeaseNetwork}'s presence check too.
   */
  static boolean absent(RunnerDocker.Result result) {
    String detail = result.detail() == null ? "" : result.detail().toLowerCase(Locale.ROOT);
    return !result.timedOut()
        && (detail.contains("no such object")
            || detail.contains("no such container")
            || detail.contains("no such network")
            || detail.contains("not found"));
  }
}
