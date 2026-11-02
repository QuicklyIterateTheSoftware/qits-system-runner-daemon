package eu.wohlben.qits.systemrunner;

import eu.wohlben.qits.containers.driver.ContainersDriver;
import eu.wohlben.qits.containers.driver.spec.VolumeSpec;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The volumes a lease mounts, named from the runner's {@link RunnerIdentity} and never from a frame
 * (qits-projects-desk-runner-daemon's {@code NodeVolumes}, with the target's names):
 *
 * <ul>
 *   <li>the <b>credential volume</b> ({@code /claude-home}): {@code
 *       QITS_SYSTEM_RUNNER_CREDENTIAL_VOLUME} when set — external, must exist, never created or
 *       removed — else {@code qits-system-runner-dot-claude-<id8>}, created labelled at {@code
 *       onAck}, before an operator's login command could create it unlabelled;
 *   <li>the <b>agent caches</b> ({@code /caches/m2}, {@code /caches/pnpm}): {@code …_M2_VOLUME} /
 *       {@code …_PNPM_VOLUME} when set, else {@code qits-system-runner-m2-<id8>} and {@code
 *       qits-system-runner-pnpm-<id8>};
 *   <li>a lease's <b>work volume</b> ({@code /workspace}): {@code
 *       qits-system-runner-work-<workKey>-<id8>}, labelled {@code qits.system.runner=<id>} and
 *       {@code qits.system.runner.work-key=<workKey>}, kept when its lease ends — it is the work —
 *       and removed only on an {@code estate.purges} entry or with the runner at decommission;
 *   <li>the <b>CI caches</b> ({@code /cache/git}, {@code /caches/m2}, {@code /caches/pnpm}, {@code
 *       /caches/ci-daemon} of a CI lease): {@code qits-system-runner-ci-git-<id8>}, {@code
 *       qits-system-runner-ci-m2-<id8>}, {@code qits-system-runner-ci-pnpm-<id8>}, {@code
 *       qits-system-runner-ci-daemon-<id8>} (the per-version qits-ci-daemon binary qits-ci's lease
 *       bootstrap caches there, qits-1809), always the runner's own, created labelled on the first
 *       CI lease and never mounted into a WORKSPACE or DESK lease.
 * </ul>
 *
 * <p>The node volumes are labelled with the runner's ownership label alone: they belong to no
 * lease, so nothing but the decommission removes them, and an external one carries neither the
 * label nor the runner's name prefix, so even the decommission cannot reach it.
 */
final class TargetVolumes {

  static final String DOT_CLAUDE = "dot-claude";
  static final String M2 = "m2";
  static final String PNPM = "pnpm";
  static final String CI_GIT = "ci-git";
  static final String CI_M2 = "ci-m2";
  static final String CI_PNPM = "ci-pnpm";
  static final String CI_DAEMON = "ci-daemon";

  /** The label a work volume's key rides under: {@code qits.system.runner.work-key}. */
  static final String WORK_KEY_LABEL_SUFFIX = "work-key";

  /** An external node volume and the variable that named it. */
  record External(String name, String variable) {}

  private final RunnerIdentity identity;
  private final String targetId;
  private final TargetNames names;
  private final ExternalVolumes external;

  TargetVolumes(RunnerIdentity identity, String targetId, ExternalVolumes external) {
    this.identity = identity;
    this.targetId = targetId;
    this.names = new TargetNames(identity, targetId);
    this.external = external;
  }

  /** The credential volume: external when named, else {@code qits-system-runner-dot-claude-<id8>}. */
  String credential() {
    return external.credential().orElseGet(() -> identity.nodeVolume(targetId, DOT_CLAUDE));
  }

  /** The maven cache: external, else {@code qits-system-runner-m2-<id8>}. */
  String m2() {
    return external.m2().orElseGet(() -> identity.nodeVolume(targetId, M2));
  }

  /** The pnpm store: external, else {@code qits-system-runner-pnpm-<id8>}. */
  String pnpm() {
    return external.pnpm().orElseGet(() -> identity.nodeVolume(targetId, PNPM));
  }

  /** CI's git cache: {@code qits-system-runner-ci-git-<id8>}. */
  String ciGit() {
    return identity.nodeVolume(targetId, CI_GIT);
  }

  /** CI's maven cache: {@code qits-system-runner-ci-m2-<id8>}. */
  String ciM2() {
    return identity.nodeVolume(targetId, CI_M2);
  }

  /** CI's pnpm store: {@code qits-system-runner-ci-pnpm-<id8>}. */
  String ciPnpm() {
    return identity.nodeVolume(targetId, CI_PNPM);
  }

  /**
   * CI's qits-ci-daemon cache: {@code qits-system-runner-ci-daemon-<id8>} — the per-version daemon
   * binary qits-ci's lease bootstrap caches there (qits-1809).
   */
  String ciDaemon() {
    return identity.nodeVolume(targetId, CI_DAEMON);
  }

  /** The CI caches, made labelled on a CI lease's start. */
  List<String> ciCaches() {
    return List.of(ciGit(), ciM2(), ciPnpm(), ciDaemon());
  }

  /** Every volume a lease of any type may mount, as {@link SlotPolicy.Mounts}. */
  SlotPolicy.Mounts mounts(String workVolume) {
    return new SlotPolicy.Mounts(
        workVolume, credential(), m2(), pnpm(), ciGit(), ciM2(), ciPnpm(), ciDaemon());
  }

  /** {@code qits-system-runner-work-<workKey>-<id8>}. */
  String work(String workKey) {
    return names.workVolume(workKey);
  }

  /** Whether {@code name} is spelled as one of this runner's work volumes. */
  boolean isWork(String name) {
    return names.isWorkVolume(name);
  }

  /** The node volumes that are the runner's own: those no external one replaces. */
  List<String> owned() {
    List<String> own = new ArrayList<>();
    if (external.credential().isEmpty()) {
      own.add(credential());
    }
    if (external.m2().isEmpty()) {
      own.add(m2());
    }
    if (external.pnpm().isEmpty()) {
      own.add(pnpm());
    }
    return List.copyOf(own);
  }

  /** The external node volumes, each with the variable that named it. */
  List<External> external() {
    List<External> named = new ArrayList<>();
    external
        .credential()
        .ifPresent(
            n -> named.add(new External(n, identity.env(ExternalVolumes.CREDENTIAL_VOLUME))));
    external.m2().ifPresent(n -> named.add(new External(n, identity.env(ExternalVolumes.M2_VOLUME))));
    external
        .pnpm()
        .ifPresent(n -> named.add(new External(n, identity.env(ExternalVolumes.PNPM_VOLUME))));
    return List.copyOf(named);
  }

  /** Whether the credential volume is an external one. */
  boolean credentialExternal() {
    return external.credential().isPresent();
  }

  /**
   * {@code qits.system.runner.mounts}' value: a short hash of the volumes a lease container is run
   * with — its work volume and the node's. A container carrying another one (or none) was run with
   * other volumes and is recreated like one of another spec hash.
   */
  String mountsHash(String workVolume) {
    return hash(String.join("\n", workVolume == null ? "-" : workVolume, credential(), m2(), pnpm()));
  }

  /**
   * {@link #mountsHash(String)} for a lease of {@code policy}: a CI lease's is over the CI caches
   * it mounts, an agent type's over its work volume and the node's.
   *
   * <p>{@code ciDaemon()} (qits-1809) is deliberately <b>not</b> in the CI branch. A CI container
   * belongs to one lease, so every new lease mounts the cache anyway; folding it in would only make
   * the upgraded runner read each CI container still running as carrying other volumes, stop it and
   * start it again — killing every run in flight at the upgrade. A lease without the mount fetches
   * its daemon into {@code /tmp}, as before.
   */
  String mountsHash(SlotPolicy policy, String workVolume) {
    if (!policy.agentVolumes()) {
      return hash(String.join("\n", "ci", ciGit(), ciM2(), ciPnpm()));
    }
    return mountsHash(workVolume);
  }

  private static String hash(String mounts) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(mounts.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 6);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("no SHA-256 in this JVM", e);
    }
  }

  /**
   * Why a lease cannot run yet: the first external volume docker does not have (or could not say
   * it has); empty when every one is there. Asked with a {@code docker volume inspect}, never a
   * create.
   */
  Optional<String> missing(RunnerDocker docker) {
    for (External named : external()) {
      Optional<String> why = missing(docker, named.name(), "node volume", named.variable());
      if (why.isPresent()) {
        return why;
      }
    }
    return Optional.empty();
  }

  /** {@link #missing(RunnerDocker)} for the credential volume alone: what the login probe needs. */
  Optional<String> credentialMissing(RunnerDocker docker) {
    if (!credentialExternal()) {
      return Optional.empty();
    }
    return missing(
        docker, credential(), "node volume", identity.env(ExternalVolumes.CREDENTIAL_VOLUME));
  }

  /** Why an adopted volume cannot be mounted: it is not on the node, or docker could not say. */
  Optional<String> adoptedMissing(RunnerDocker docker, String volume) {
    return missing(docker, volume, "adopted volume", null);
  }

  /** Whether docker has a volume {@code name}; false when it has none, throws when it cannot say. */
  static boolean exists(RunnerDocker docker, String name) {
    RunnerDocker.Result answered = inspect(docker, name);
    if (answered.ok() && name.equals(answered.stdout().strip())) {
      return true;
    }
    if (!answered.ok() && !answered.timedOut() && absent(answered.detail())) {
      return false;
    }
    throw new IllegalStateException(
        "docker could not say whether volume " + name + " exists: " + answered.detail());
  }

  private static Optional<String> missing(
      RunnerDocker docker, String name, String what, String variable) {
    RunnerDocker.Result answered = inspect(docker, name);
    String named = variable == null ? "" : " named by " + variable;
    if (answered.ok() && name.equals(answered.stdout().strip())) {
      return Optional.empty();
    }
    if (!answered.ok() && !answered.timedOut() && absent(answered.detail())) {
      return Optional.of(what + " " + name + named + " does not exist");
    }
    return Optional.of(
        "docker could not say whether "
            + what
            + " "
            + name
            + named
            + " exists: "
            + LeaseLauncher.brief(answered.ok() ? answered.stdout() : answered.detail()));
  }

  private static RunnerDocker.Result inspect(RunnerDocker docker, String name) {
    return docker.run(
        List.of(docker.binary(), "volume", "inspect", "--format", "{{.Name}}", name));
  }

  /** A work volume, labelled with the owner label and its work key; idempotent. */
  ContainersDriver.OpResult ensureWork(ContainersDriver driver, String workKey, Duration deadline) {
    return driver.ensureVolume(
        new VolumeSpec(work(workKey)),
        Map.of(
            identity.ownerLabel(),
            targetId,
            identity.driverNamespace() + WORK_KEY_LABEL_SUFFIX,
            workKey),
        deadline);
  }

  /** One of the node's volumes, labelled with the runner's ownership label alone; idempotent. */
  ContainersDriver.OpResult ensureNode(ContainersDriver driver, String name, Duration deadline) {
    return driver.ensureVolume(
        new VolumeSpec(name), Map.of(identity.ownerLabel(), targetId), deadline);
  }

  /** Docker's own words for "there is no such thing". */
  static boolean absent(String detail) {
    String text = detail == null ? "" : detail.toLowerCase(Locale.ROOT);
    return text.contains("no such container")
        || text.contains("no such object")
        || text.contains("no such volume");
  }
}
