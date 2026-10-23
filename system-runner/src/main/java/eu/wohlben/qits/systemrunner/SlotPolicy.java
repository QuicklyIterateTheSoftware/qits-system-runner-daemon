package eu.wohlben.qits.systemrunner;

import eu.wohlben.qits.containers.driver.spec.ContainerSpec;
import eu.wohlben.qits.containers.driver.spec.LifecyclePolicy;
import eu.wohlben.qits.systemrunner.protocol.LeaseEntry;
import eu.wohlben.qits.systemrunner.protocol.LeaseSpec;
import eu.wohlben.qits.systemrunner.protocol.SlotImages;
import eu.wohlben.qits.systemrunner.protocol.SlotType;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerProtocol;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * <b>What a lease's container is allowed to be, per slot type</b> — the part of a container no
 * frame can say. A {@link LeaseSpec} carries an image, an environment, a user and limits; every
 * mount, network, capability, {@code --init} and restart flag is this table's, fixed in the runner,
 * so a spec relayed by qits-system can never ask for a docker socket, a host path or another
 * tenant's volume. {@code SlotPolicyTest} asserts every cell.
 *
 * <pre>
 *                     CI                              WORKSPACE                DESK
 *   image prefix      Ack.slotImages.ci               Ack.slotImages.workspace Ack.slotImages.desk
 *   user              spec, never 0                   spec, never 0            spec, never 0
 *   /claude-home      never                           credential volume, rw    credential volume, rw
 *   /workspace        container filesystem            work volume              work volume
 *   caches            ci-git, ci-m2, ci-pnpm          m2, pnpm                 m2, pnpm
 *   networks          bridge + the build network      bridge                   bridge
 *   BUILDKIT_HOST     injected                        never                    never
 *   cap-drop / nnp    yes / yes                       no / no                  no / no
 *   --init            yes                             no                       yes
 *   restart           no                              no                       unless-stopped
 *   on ENDED          container removed               container removed, work volume kept
 * </pre>
 *
 * <p>A spec env key the policy owns ({@link #OWNED_ENV}) is refused, naming the key: {@code
 * CLAUDE_CONFIG_DIR} is the policy's ({@code /claude-home/.claude} for every type that mounts the
 * credential volume), {@code BUILDKIT_HOST} is CI's build plane, and {@code HOME} is the image's —
 * a spec that moved it would move where every tool looks for its configuration.
 */
enum SlotPolicy {
  CI(
      SlotType.CI,
      true,
      false,
      false,
      List.of(Cache.CI_GIT, Cache.CI_M2, Cache.CI_PNPM),
      true,
      true,
      true,
      true,
      true,
      false,
      null),
  WORKSPACE(
      SlotType.WORKSPACE,
      true,
      true,
      true,
      List.of(Cache.M2, Cache.PNPM),
      false,
      false,
      false,
      false,
      false,
      false,
      "ws-"),
  DESK(
      SlotType.DESK,
      true,
      true,
      true,
      List.of(Cache.M2, Cache.PNPM),
      false,
      false,
      false,
      false,
      true,
      true,
      "desk-");

  /** Where the credential volume lands. */
  static final String CLAUDE_HOME = "/claude-home";

  /** Claude Code's config directory inside it, as every workspace and desk has always had it. */
  static final String CLAUDE_CONFIG_DIR_VALUE = CLAUDE_HOME + "/.claude";

  /** Where the work volume lands. */
  static final String WORKSPACE_TARGET = "/workspace";

  /** The one network every lease joins first. */
  static final String NETWORK = "bridge";

  static final String BUILDKIT_HOST = "BUILDKIT_HOST";
  static final String HOME = "HOME";
  static final String CLAUDE_CONFIG_DIR = "CLAUDE_CONFIG_DIR";

  /** The environment keys the policy owns: a spec naming one is refused. */
  static final Set<String> OWNED_ENV = Set.of(BUILDKIT_HOST, HOME, CLAUDE_CONFIG_DIR);

  /** A cache volume a type mounts, and where. */
  enum Cache {
    /** The agent maven cache: {@code …_M2_VOLUME} or {@code qits-system-runner-m2-<id8>}. */
    M2("/caches/m2"),
    /** The agent pnpm store: {@code …_PNPM_VOLUME} or {@code qits-system-runner-pnpm-<id8>}. */
    PNPM("/caches/pnpm"),
    /** CI's git cache: {@code qits-system-runner-ci-git-<id8>}. */
    CI_GIT("/cache/git"),
    /** CI's maven cache: {@code qits-system-runner-ci-m2-<id8>}. */
    CI_M2("/caches/m2"),
    /** CI's pnpm store: {@code qits-system-runner-ci-pnpm-<id8>}. */
    CI_PNPM("/caches/pnpm");

    final String target;

    Cache(String target) {
      this.target = target;
    }
  }

  /**
   * The volumes a lease of a type that has them mounts, as the runner named them: the work volume,
   * the credential volume and the agent caches for WORKSPACE and DESK, the CI caches for CI. A type
   * never mounts the others' — CI never the credential volume, the agent caches or a work volume,
   * and WORKSPACE and DESK never a CI cache — whatever this record carries.
   */
  record Mounts(
      String workVolume,
      String credential,
      String m2,
      String pnpm,
      String ciGit,
      String ciM2,
      String ciPnpm) {

    /** The agent types' volumes alone. */
    Mounts(String workVolume, String credential, String m2, String pnpm) {
      this(workVolume, credential, m2, pnpm, null, null, null);
    }
  }

  /** The build plane a CI lease joins: its network and the {@code BUILDKIT_HOST} it dials. */
  record Build(String network, String buildkitHost) {}

  final SlotType type;
  final boolean enabled;
  final boolean credentialHome;
  final boolean workVolume;
  final List<Cache> caches;
  final boolean buildNetwork;
  final boolean injectsBuildkitHost;
  final boolean capDropAll;
  final boolean noNewPrivileges;
  final boolean init;
  final boolean restartUnlessStopped;
  final String workKeyPrefix;

  SlotPolicy(
      SlotType type,
      boolean enabled,
      boolean credentialHome,
      boolean workVolume,
      List<Cache> caches,
      boolean buildNetwork,
      boolean injectsBuildkitHost,
      boolean capDropAll,
      boolean noNewPrivileges,
      boolean init,
      boolean restartUnlessStopped,
      String workKeyPrefix) {
    this.type = type;
    this.enabled = enabled;
    this.credentialHome = credentialHome;
    this.workVolume = workVolume;
    this.caches = caches;
    this.buildNetwork = buildNetwork;
    this.injectsBuildkitHost = injectsBuildkitHost;
    this.capDropAll = capDropAll;
    this.noNewPrivileges = noNewPrivileges;
    this.init = init;
    this.restartUnlessStopped = restartUnlessStopped;
    this.workKeyPrefix = workKeyPrefix;
  }

  /** The policy of {@code type}. */
  static SlotPolicy of(SlotType type) {
    return switch (type) {
      case CI -> CI;
      case WORKSPACE -> WORKSPACE;
      case DESK -> DESK;
    };
  }

  /** {@code --restart unless-stopped} or none: the driver's lifecycle for this type. */
  LifecyclePolicy lifecycle() {
    return restartUnlessStopped ? LifecyclePolicy.explicitLifetime() : LifecyclePolicy.ephemeral(null);
  }

  /**
   * Why a RUNNING {@code entry} may not run under this policy, as the {@code FAILED} detail; empty
   * when it may. In this order: a type this runner version does not run, an image outside the
   * type's prefix, no user or root, a policy-owned environment key, a missing or misshapen work
   * key. Legacy adoption's names are {@link LegacyAdoption}'s.
   */
  Optional<String> refusal(LeaseEntry entry, SlotImages images) {
    if (!enabled) {
      return Optional.of(SystemRunnerProtocol.CI_NOT_ENABLED);
    }
    LeaseSpec spec = entry.spec();
    String prefix = images.of(type);
    if (prefix == null || prefix.isBlank()) {
      return Optional.of("no " + type + " image is pinned for this target yet");
    }
    if (!spec.image().startsWith(prefix)) {
      return Optional.of(
          "image " + spec.image() + " is not a " + type + " image (" + prefix + "…)");
    }
    String user = spec.user();
    if (user == null || user.isBlank()) {
      return Optional.of("a " + type + " lease must name its user");
    }
    if (root(user)) {
      return Optional.of("a " + type + " lease may not run as user " + user);
    }
    for (String key : spec.env().keySet()) {
      if (OWNED_ENV.contains(key)) {
        return Optional.of("spec env " + key + " is set by the " + type + " slot policy");
      }
    }
    if (workVolume) {
      String key = entry.workVolume();
      if (key == null
          || !key.startsWith(workKeyPrefix)
          || !TargetNames.ID.matcher(key).matches()) {
        return Optional.of(
            "a " + type + " lease needs a work key " + workKeyPrefix + "<id>, not " + key);
      }
    }
    return Optional.empty();
  }

  /** {@link #containerSpec(String, LeaseSpec, Mounts, Build)} for a type without a build plane. */
  ContainerSpec containerSpec(String name, LeaseSpec spec, Mounts mounts) {
    return containerSpec(name, spec, mounts, null);
  }

  /**
   * The driver's spec of a lease container under this policy. Only for an entry {@link #refusal}
   * passed. The spec's network is {@code bridge} for every type; a CI lease's second network, the
   * build network, is {@link #networks}' and joined after the run ({@link BuildPlane#connect}),
   * because the driver's {@code ContainerSpec} carries one network.
   *
   * @throws IllegalStateException for a policy this runner version does not run, or a CI lease
   *     without its build plane
   */
  ContainerSpec containerSpec(String name, LeaseSpec spec, Mounts mounts, Build build) {
    if (!enabled) {
      throw new IllegalStateException(SystemRunnerProtocol.CI_NOT_ENABLED);
    }
    if (injectsBuildkitHost && (build == null || build.buildkitHost() == null)) {
      throw new IllegalStateException("a " + type + " lease needs its build plane");
    }
    ContainerSpec.Builder b =
        ContainerSpec.builder(spec.image())
            .name(name)
            .network(NETWORK)
            .user(spec.user())
            .init(init)
            .pullPolicy(ContainerSpec.PullPolicy.MISSING)
            .security(
                new ContainerSpec.SecurityPosture(
                    capDropAll,
                    noNewPrivileges,
                    spec.limits().memory(),
                    spec.limits().memorySwap(),
                    spec.limits().pidsLimit(),
                    spec.limits().cpus(),
                    spec.limits().oomScoreAdj()));
    spec.env().forEach(b::env);
    if (credentialHome) {
      b.env(CLAUDE_CONFIG_DIR, CLAUDE_CONFIG_DIR_VALUE);
    }
    if (injectsBuildkitHost) {
      b.env(BUILDKIT_HOST, build.buildkitHost());
    }
    if (workVolume) {
      b.mount(mounts.workVolume(), WORKSPACE_TARGET);
    }
    if (credentialHome) {
      // The node's, not this lease's: the driver keeps the two apart for what a removal reads.
      b.shared(mounts.credential(), CLAUDE_HOME);
    }
    for (Cache cache : caches) {
      b.shared(
          switch (cache) {
            case M2 -> mounts.m2();
            case PNPM -> mounts.pnpm();
            case CI_GIT -> mounts.ciGit();
            case CI_M2 -> mounts.ciM2();
            case CI_PNPM -> mounts.ciPnpm();
          },
          cache.target);
    }
    return b.build();
  }

  /**
   * Whether a lease of this type mounts the credential volume or an agent cache — the node volumes
   * an operator may name externally. CI mounts none of them.
   */
  boolean agentVolumes() {
    return credentialHome || caches.contains(Cache.M2) || caches.contains(Cache.PNPM);
  }

  /**
   * The networks a lease of this type is on: {@code bridge} — the spec's, at {@code docker run} —
   * and for CI the build network after it.
   */
  List<String> networks(Build build) {
    if (!buildNetwork) {
      return List.of(NETWORK);
    }
    if (build == null || build.network() == null) {
      throw new IllegalStateException("a " + type + " lease needs its build plane");
    }
    return List.of(NETWORK, build.network());
  }

  /** {@code 0}, {@code root}, or either with a group: the one user no lease may be. */
  static boolean root(String user) {
    String uid = user.strip();
    int colon = uid.indexOf(':');
    if (colon >= 0) {
      uid = uid.substring(0, colon);
    }
    uid = uid.toLowerCase(Locale.ROOT);
    return uid.equals("root") || uid.matches("0+");
  }
}
