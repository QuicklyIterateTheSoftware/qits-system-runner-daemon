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
 *
 * <p>The same inspect reads the container's {@code --dns} servers ({@code {{json
 * .HostConfig.Dns}}}, qits-2091) — docker prints {@code null} or a JSON array of address literals,
 * read by hand since the native image carries no JSON parser — and its {@code generation} label,
 * so reporting a lease's resolvers costs no docker call of its own.
 */
final class LeaseContainers {

  /** The suffix of the label a lease container's slot type rides under. */
  static final String SLOT_TYPE_LABEL_SUFFIX = "slot-type";

  /** The suffix of the label a lease container's spec hash rides under. */
  static final String SPEC_HASH_LABEL_SUFFIX = "spec-hash";

  /** The suffix of the label a lease container's volumes ({@link TargetVolumes#mountsHash}) ride. */
  static final String MOUNTS_LABEL_SUFFIX = "mounts";

  /** The suffix of the label a lease container's {@code LeaseEntry.generation} rides under. */
  static final String GENERATION_LABEL_SUFFIX = "generation";

  /**
   * One lease container as docker reported it; {@code oomKilled} is docker's {@code
   * State.OOMKilled}, null when docker did not say (qits-1818). {@code dns} is its {@code --dns}
   * servers in order, empty when it has none or docker did not say; {@code generation} its
   * generation label, {@code 0} when it has none or the label is no number (qits-2091).
   */
  record Seen(
      String id,
      String leaseId,
      String status,
      int exitCode,
      String slotType,
      String specHash,
      String mounts,
      Boolean oomKilled,
      List<String> dns,
      long generation) {

    Seen {
      dns = dns == null ? List.of() : List.copyOf(dns);
    }

    /** A container read without its resolvers and generation: none, and {@code 0}. */
    Seen(
        String id,
        String leaseId,
        String status,
        int exitCode,
        String slotType,
        String specHash,
        String mounts,
        Boolean oomKilled) {
      this(id, leaseId, status, exitCode, slotType, specHash, mounts, oomKilled, List.of(), 0);
    }

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
            + "\"}}|{{.State.OOMKilled}}|{{json .HostConfig.Dns}}|{{index .Config.Labels \""
            + generationLabel(identity)
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

  /**
   * {@code qits.system.runner.generation}: the owner's {@code LeaseEntry.generation} the container
   * was run under, written only when it is above {@code 0} (qits-2091).
   */
  static String generationLabel(RunnerIdentity identity) {
    return identity.driverNamespace() + GENERATION_LABEL_SUFFIX;
  }

  /** The inspect's {@code --format} template, one {@code |}-separated line per container. */
  String format() {
    return format;
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
   * {@code
   * <id>|<status>|<exitCode>|<leaseId>|<slotType>|<specHash>|<mounts>|<oomKilled>|<dns>|<generation>};
   * empty when it is not a lease container. A label the container lacks reads as null, and so does
   * an {@code oomKilled} that is neither {@code true} nor {@code false}; a {@code dns} that is not
   * a JSON array of strings reads as none, a generation that is no non-negative number as {@code
   * 0}. Fields missing at the end — a line of a shorter format — read the same way.
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
            nullable(fields, 6),
            bool(nullable(fields, 7)),
            dns(nullable(fields, 8)),
            generation(nullable(fields, 9))));
  }

  /**
   * {@code {{json .HostConfig.Dns}}} read by hand: {@code null}, {@code []} or {@code ["a","b"]}.
   * Anything else — a template docker did not fill, an element that is not a plain string — is
   * none: the report then says the lease has no resolvers of its own, never ones it does not have.
   */
  static List<String> dns(String json) {
    if (json == null) {
      return List.of();
    }
    String s = json.strip();
    if (!s.startsWith("[") || !s.endsWith("]")) {
      return List.of();
    }
    String inner = s.substring(1, s.length() - 1).strip();
    if (inner.isEmpty()) {
      return List.of();
    }
    List<String> servers = new ArrayList<>();
    for (String element : inner.split(",", -1)) {
      String e = element.strip();
      if (e.length() < 2 || !e.startsWith("\"") || !e.endsWith("\"")) {
        return List.of();
      }
      String server = e.substring(1, e.length() - 1);
      if (server.isEmpty() || server.indexOf('"') >= 0 || server.indexOf('\\') >= 0) {
        return List.of();
      }
      servers.add(server);
    }
    return List.copyOf(servers);
  }

  /** The generation label: absent, not a number or negative is {@code 0}. */
  static long generation(String value) {
    if (value == null) {
      return 0;
    }
    try {
      return Math.max(0, Long.parseLong(value.strip()));
    } catch (NumberFormatException notANumber) {
      return 0;
    }
  }

  private static Boolean bool(String value) {
    if ("true".equalsIgnoreCase(value)) {
      return Boolean.TRUE;
    }
    if ("false".equalsIgnoreCase(value)) {
      return Boolean.FALSE;
    }
    return null;
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
