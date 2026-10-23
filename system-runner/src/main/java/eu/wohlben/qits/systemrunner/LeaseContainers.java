package eu.wohlben.qits.systemrunner;

import eu.wohlben.qits.runner.toolkit.LifecycleArgv;
import eu.wohlben.qits.runner.toolkit.OwnResources;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * What docker says about this runner's lease containers: one by name, or all of them by the
 * runner's own labels. The reads behind the convergence, the {@code inventory}, the session start's
 * held list and the periodic look — one argv for all of them, so they can never disagree. A copy
 * of qits-projects-desk-runner-daemon's {@code DeskContainers}, keyed on the lease id.
 *
 * <p><b>Its own inspect, not the driver's</b>, for that class's three reasons: the driver's carries
 * no labels, and the convergence needs the spec-hash and mounts labels; a docker that did not answer
 * throws here, so "could not read" never reads as "not there" (the reason {@link OwnResources}
 * lists through the process seam too); and it says {@code --type container}, so a volume of the
 * same name is never answered for a container that is gone.
 */
final class LeaseContainers {

  /** The suffix of the label a lease container's slot type rides under. */
  static final String SLOT_TYPE_LABEL_SUFFIX = "slot-type";

  /** The suffix of the label a lease container's spec hash rides under. */
  static final String SPEC_HASH_LABEL_SUFFIX = "spec-hash";

  /** The suffix of the label a lease container's volumes ({@link TargetVolumes#mountsHash}) ride. */
  static final String MOUNTS_LABEL_SUFFIX = "mounts";

  /** One lease container as docker reported it. */
  record Seen(
      String id,
      String leaseId,
      String status,
      int exitCode,
      String slotType,
      String specHash,
      String mounts) {

    boolean running() {
      return "running".equals(status);
    }

    /** Created and not started yet, or docker restarting it: neither running nor done. */
    boolean starting() {
      return "created".equals(status) || "restarting".equals(status);
    }
  }

  private final RunnerIdentity identity;
  private final String targetId;
  private final RunnerDocker docker;
  private final String format;

  LeaseContainers(RunnerIdentity identity, String targetId, RunnerDocker docker) {
    this.identity = identity;
    this.targetId = targetId;
    this.docker = docker;
    this.format =
        "{{.Id}}|{{.State.Status}}|{{.State.ExitCode}}|{{index .Config.Labels \""
            + identity.workLabel()
            + "\"}}|{{index .Config.Labels \""
            + slotTypeLabel(identity)
            + "\"}}|{{index .Config.Labels \""
            + specHashLabel(identity)
            + "\"}}|{{index .Config.Labels \""
            + mountsLabel(identity)
            + "\"}}";
  }

  /** {@code qits.system.runner.slot-type}. */
  static String slotTypeLabel(RunnerIdentity identity) {
    return identity.driverNamespace() + SLOT_TYPE_LABEL_SUFFIX;
  }

  /** {@code qits.system.runner.spec-hash}: own-namespace, so only the runner writes it. */
  static String specHashLabel(RunnerIdentity identity) {
    return identity.driverNamespace() + SPEC_HASH_LABEL_SUFFIX;
  }

  /** {@code qits.system.runner.mounts}: the volumes the container was run with, hashed. */
  static String mountsLabel(RunnerIdentity identity) {
    return identity.driverNamespace() + MOUNTS_LABEL_SUFFIX;
  }

  /** The inspect every read here makes, of a name this runner derived or an id docker listed. */
  List<String> inspectArgv(String nameOrId) {
    return List.of(
        docker.binary(),
        "inspect",
        "--type",
        "container",
        "--format",
        format,
        LifecycleArgv.requireWorkId(nameOrId));
  }

  /**
   * The container under {@code name}, or empty when docker has none.
   *
   * @throws IllegalStateException when docker did not answer, or answered something unreadable
   */
  Optional<Seen> inspect(String name) {
    RunnerDocker.Result answered = docker.run(inspectArgv(name));
    if (!answered.ok()) {
      if (!answered.timedOut() && TargetVolumes.absent(answered.detail())) {
        return Optional.empty();
      }
      throw new IllegalStateException(
          "docker did not answer an inspect of " + name + ": " + answered.detail());
    }
    String line = answered.stdout().strip();
    if (line.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        parse(line)
            .orElseThrow(
                () -> new IllegalStateException("docker answered an unreadable inspect of " + name)));
  }

  /**
   * Whether docker has any container under {@code name} at all — a retired runner's, which carries
   * none of this runner's labels. Throws when docker could not say.
   */
  boolean exists(String name) {
    RunnerDocker.Result answered =
        docker.run(
            List.of(
                docker.binary(),
                "inspect",
                "--type",
                "container",
                "--format",
                "{{.Id}}",
                LifecycleArgv.requireWorkId(name)));
    if (answered.ok()) {
      return !answered.stdout().isBlank();
    }
    if (!answered.timedOut() && TargetVolumes.absent(answered.detail())) {
      return false;
    }
    throw new IllegalStateException(
        "docker did not answer an inspect of " + name + ": " + answered.detail());
  }

  /**
   * Every lease container of this runner, running or not: listed by {@code
   * label=qits.system.runner=<id>} and the work label, each inspected. A container that went
   * between the listing and its inspect is simply not in the answer.
   *
   * @throws IllegalStateException when docker did not answer the listing or an inspect — never
   *     read as an empty node
   */
  List<Seen> list() {
    RunnerDocker.Result listed =
        docker.run(
            List.of(
                docker.binary(),
                "ps",
                "-aq",
                "--filter",
                "label=" + identity.ownerLabel() + "=" + LifecycleArgv.requireRunnerId(targetId),
                "--filter",
                "label=" + identity.workLabel()));
    if (!listed.ok()) {
      throw new IllegalStateException("could not list the lease containers: " + listed.detail());
    }
    List<Seen> seen = new ArrayList<>();
    for (String id : listed.stdout().split("\\s+")) {
      if (id.isBlank()) {
        continue;
      }
      RunnerDocker.Result answered = docker.run(inspectArgv(id.strip()));
      if (!answered.ok()) {
        if (!answered.timedOut() && TargetVolumes.absent(answered.detail())) {
          continue;
        }
        throw new IllegalStateException(
            "docker did not answer an inspect of " + id.strip() + ": " + answered.detail());
      }
      parse(answered.stdout().strip()).ifPresent(seen::add);
    }
    return seen;
  }

  /**
   * {@code <id>|<status>|<exitCode>|<leaseId>|<slotType>|<specHash>|<mounts>}; empty when it is not
   * a lease container. A label the container lacks reads as null.
   */
  static Optional<Seen> parse(String line) {
    String[] fields = line.split("\\|", -1);
    if (fields.length < 4) {
      return Optional.empty();
    }
    String leaseId = value(fields[3]);
    if (leaseId.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new Seen(
            value(fields[0]),
            leaseId,
            value(fields[1]).toLowerCase(Locale.ROOT),
            exitCode(value(fields[2])),
            nullable(fields, 4),
            nullable(fields, 5),
            nullable(fields, 6)));
  }

  private static String nullable(String[] fields, int at) {
    if (fields.length <= at) {
      return null;
    }
    String v = value(fields[at]);
    return v.isEmpty() ? null : v;
  }

  private static String value(String field) {
    String v = field.strip();
    return "<no value>".equals(v) ? "" : v;
  }

  /** The map path prints a JSON number, which may come back as {@code 137} or {@code 137.0}. */
  private static int exitCode(String value) {
    if (value.isEmpty()) {
      return -1;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException notAnInt) {
      try {
        return (int) Double.parseDouble(value);
      } catch (NumberFormatException notANumber) {
        return -1;
      }
    }
  }
}
