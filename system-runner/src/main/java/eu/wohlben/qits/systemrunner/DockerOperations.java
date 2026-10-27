package eu.wohlben.qits.systemrunner;

import eu.wohlben.qits.containers.driver.docker.ContainerProcess;
import eu.wohlben.qits.runner.toolkit.DockerCommand;
import eu.wohlben.qits.runner.toolkit.LifecycleArgv;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.systemrunner.protocol.Docker;
import eu.wohlben.qits.systemrunner.protocol.DockerOp;
import eu.wohlben.qits.systemrunner.protocol.DockerResult;
import eu.wohlben.qits.systemrunner.protocol.DockerStatus;
import eu.wohlben.qits.systemrunner.protocol.SecretKeys;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;

/**
 * The {@code docker} frame: one docker command on one of this target's own containers or volumes,
 * answered as exactly one {@link DockerResult}. What lets a person read a lease's log, look into a
 * work volume or stop a stuck container without a shell on the node (qits-1642).
 *
 * <p><b>Ownership first.</b> A name that fails {@link RunnerIdentity#ownsName} is never handed to
 * docker. One that passes is inspected ({@code --type container}, or {@code docker volume
 * inspect}), and answers {@code NOT_FOUND} unless it carries the owner label {@code
 * qits.system.runner=<targetId>} — or, for a container, is this runner's own process container
 * ({@code qits.system.runner.process=<targetId>}), which may be inspected and read but never exec'd
 * into, stopped or removed ({@code REFUSED}). "Not there" and "not this target's" read the same, so
 * a frame cannot probe another runner's resources by name. The container listing selects by the
 * owner label alone, as every listing of the runner does.
 *
 * <p><b>A volume is also owned by being mounted.</b> A lease adopted from the retired
 * qits-workspaces-runner mounts volumes under that runner's names ({@code
 * qits-workspaces-runner-ws-5053-8d45fd1d}) and without the owner label. Such a volume counts as
 * this target's when a container carrying the owner label mounts it ({@code Mounts[]} of type
 * {@code volume}, read from {@code docker inspect} of the owned containers): {@code LIST_VOLUMES}
 * answers the labelled volumes and these, each row saying why in {@code ownedBy} ({@code label}
 * or {@code mount}), and {@code INSPECT_VOLUME} admits either. Any other volume stays {@code
 * NOT_FOUND}, and one outside the runner's prefix is not handed to docker by name until an owned
 * container is seen mounting it. Nothing else widens: there is no op that removes a volume.
 *
 * <p><b>Limits are the runner's.</b> {@code logs --tail} is {@value #DEFAULT_TAIL} lines unless
 * asked, never more than {@value #MAX_TAIL}; an exec runs {@value #DEFAULT_EXEC_SECONDS}s unless
 * asked, never more than {@value #MAX_EXEC_SECONDS}s, with no TTY and no stdin; every text answer
 * is capped at {@link #OUTPUT_CAP} bytes and says so ({@code truncated}). Stdout comes through
 * {@link RunnerDocker}'s head-kept {@link ContainerProcess#STDOUT_CAP}; stderr is the toolkit's
 * tail of {@link ContainerProcess#STDERR_TAIL_CHARS} characters, so a log's stderr half or an
 * exec's stderr is never longer than that and is marked truncated when it reaches it.
 *
 * <p><b>Secrets in output.</b> A log or an exec prints whatever the container's process prints, and
 * a lease's environment carries its token. Before either is answered, every value of the
 * container's own environment (as its inspect reads it) whose key {@link SecretKeys#isSecret} and
 * which is at least {@value #MIN_SECRET} characters long is replaced with {@code ***}. The inspect
 * itself is answered whole: qits-system-service redacts it before a person sees it.
 *
 * <p>Plain and synchronous: the domain runs {@link #answer} off the socket's thread. Nothing here
 * throws — an exception is a {@code FAILED} answer naming it.
 */
final class DockerOperations {

  private static final Logger LOG = Logger.getLogger(DockerOperations.class);

  static final int DEFAULT_TAIL = 200;
  static final int MAX_TAIL = 5000;
  static final int DEFAULT_EXEC_SECONDS = 30;
  static final int MAX_EXEC_SECONDS = 120;
  static final int STOP_SECONDS = 10;

  /** Every text answer's cap, in UTF-8 bytes: the log, an exec's stdout and its stderr each. */
  static final int OUTPUT_CAP = 1024 * 1024;

  /** A secret shorter than this is not replaced: {@code true} or {@code 1} would blank the log. */
  static final int MIN_SECRET = 6;

  static final String REDACTED = "***";

  /** Docker's {@code --since}: a duration, a timestamp or a unix time — never an option. */
  private static final Pattern SINCE = Pattern.compile("[0-9A-Za-z][0-9A-Za-z:.+-]{0,63}");

  private final RunnerIdentity identity;
  private final String targetId;
  private final DockerCommand docker;

  DockerOperations(RunnerIdentity identity, String targetId, DockerCommand docker) {
    this.identity = identity;
    this.targetId = targetId;
    this.docker = docker;
  }

  /** The one answer to {@code request}; never throws. */
  DockerResult answer(Docker request) {
    try {
      return switch (request.op()) {
        case LIST_CONTAINERS -> listContainers(request.requestId());
        case LIST_VOLUMES -> listVolumes(request.requestId());
        case INSPECT_CONTAINER -> inspectContainer(request);
        case LOGS -> logs(request);
        case EXEC -> exec(request);
        case STOP -> stop(request);
        case REMOVE -> remove(request);
        case INSPECT_VOLUME -> inspectVolume(request);
      };
    } catch (RuntimeException e) {
      LOG.warnf("system-runner's docker %s of %s failed: %s", request.op(), request.name(), e);
      return failed(request.requestId(), "docker " + request.op() + " failed: " + e);
    }
  }

  // --- the listings ------------------------------------------------------------------------------

  private DockerResult listContainers(UUID requestId) {
    return listing(
        requestId,
        List.of(docker.binary(), "ps", "-a", "--filter", ownerFilter(), "--format", "{{json .}}"));
  }

  /**
   * The labelled volumes, then those an owned container mounts that carry no label of this target,
   * each row with {@code ownedBy}. The mounted ones' rows come from an unfiltered {@code docker
   * volume ls}, so every row has docker's own shape; any other volume in it is left out.
   */
  private DockerResult listVolumes(UUID requestId) {
    DockerResult labelled =
        listing(
            requestId,
            List.of(
                docker.binary(),
                "volume",
                "ls",
                "--filter",
                ownerFilter(),
                "--format",
                "{{json .}}"));
    if (labelled.status() != DockerStatus.OK) {
      return labelled;
    }
    Mounted mounted = mountedVolumes();
    if (mounted.failure != null) {
      return failed(requestId, mounted.failure);
    }
    JsonArray rows = new JsonArray(labelled.body());
    Set<String> listed = new LinkedHashSet<>();
    for (int i = 0; i < rows.size(); i++) {
      JsonObject row = rows.getJsonObject(i);
      row.put("ownedBy", "label");
      listed.add(row.getString("Name"));
    }
    Set<String> unlisted = new LinkedHashSet<>(mounted.names);
    unlisted.removeAll(listed);
    boolean truncated = labelled.truncated();
    if (!unlisted.isEmpty()) {
      DockerResult all =
          listing(requestId, List.of(docker.binary(), "volume", "ls", "--format", "{{json .}}"));
      if (all.status() != DockerStatus.OK) {
        return all;
      }
      Map<String, JsonObject> byName = new LinkedHashMap<>();
      JsonArray every = new JsonArray(all.body());
      for (int i = 0; i < every.size(); i++) {
        JsonObject row = every.getJsonObject(i);
        byName.put(row.getString("Name"), row);
      }
      for (String name : unlisted) {
        JsonObject row = byName.get(name);
        if (row != null) {
          rows.add(row.put("ownedBy", "mount"));
        }
      }
      truncated = truncated || all.truncated();
    }
    return new DockerResult(requestId, DockerStatus.OK, rows.encode(), truncated, null, null);
  }

  private String ownerFilter() {
    return "label=" + identity.ownerLabel() + "=" + LifecycleArgv.requireRunnerId(targetId);
  }

  /** One JSON object per line, answered as one array; a line cut by the cap is left out. */
  private DockerResult listing(UUID requestId, List<String> argv) {
    RunnerDocker.Result listed = docker.run(argv);
    if (!listed.ok()) {
      return failed(requestId, listed.detail());
    }
    JsonArray rows = new JsonArray();
    for (String line : listed.stdout().split("\n")) {
      if (line.isBlank()) {
        continue;
      }
      try {
        rows.add(new JsonObject(line.strip()));
      } catch (RuntimeException unreadable) {
        if (!listed.stdoutTruncated()) {
          throw unreadable;
        }
      }
    }
    return new DockerResult(
        requestId, DockerStatus.OK, rows.encode(), listed.stdoutTruncated(), null, null);
  }

  // --- one container -----------------------------------------------------------------------------

  private DockerResult inspectContainer(Docker request) {
    Lookup found = container(request);
    if (found.answer != null) {
      return found.answer;
    }
    return ok(request.requestId(), found.inspected.encode());
  }

  private DockerResult logs(Docker request) {
    String since = request.since();
    if (since != null && !SINCE.matcher(since).matches()) {
      return refused(request.requestId(), "since is not a time docker reads: " + since);
    }
    Lookup found = container(request);
    if (found.answer != null) {
      return found.answer;
    }
    List<String> argv = new ArrayList<>();
    argv.add(docker.binary());
    argv.add("logs");
    argv.add("--tail");
    argv.add(Integer.toString(tail(request.tail())));
    if (since != null) {
      argv.add("--since");
      argv.add(since);
    }
    argv.add(request.name());
    RunnerDocker.Result logs = docker.run(argv);
    if (!logs.ok()) {
      return missingOrFailed(request, logs);
    }
    // docker logs writes the container's stdout to its stdout and the container's stderr to its
    // stderr; both are the log.
    String stderr = logs.stderr() == null ? "" : logs.stderr();
    Capped body = cap(redact(logs.stdout() + stderr, found.secrets));
    boolean truncated =
        body.truncated
            || logs.stdoutTruncated()
            || stderr.length() >= ContainerProcess.STDERR_TAIL_CHARS;
    return new DockerResult(request.requestId(), DockerStatus.OK, body.text, truncated, null, null);
  }

  /**
   * {@code docker exec <name> <argv…>}: no {@code -i}, no {@code -t}. At the deadline the docker
   * CLI is killed and the answer is {@code FAILED}; the process inside the container may outlive
   * it, as with any {@code docker exec} whose client went away.
   */
  private DockerResult exec(Docker request) {
    List<String> command = request.argv();
    if (command == null || command.isEmpty() || command.stream().anyMatch(a -> a == null)) {
      return refused(request.requestId(), "an exec needs a command");
    }
    Lookup found = container(request);
    if (found.answer != null) {
      return found.answer;
    }
    if (found.process) {
      return ownProcess(request);
    }
    int seconds = execSeconds(request.timeoutSeconds());
    List<String> argv = new ArrayList<>();
    argv.add(docker.binary());
    argv.add("exec");
    argv.add(request.name());
    argv.addAll(command);
    RunnerDocker.Result ran = docker.run(argv, Duration.ofSeconds(seconds));
    if (ran.timedOut()) {
      return failed(request.requestId(), "the exec did not finish within " + seconds + "s");
    }
    if (ran.exitCode() == -1) {
      return failed(request.requestId(), ran.detail());
    }
    String rawErr = ran.stderr() == null ? "" : ran.stderr();
    Capped out = cap(redact(ran.stdout(), found.secrets));
    Capped err = cap(redact(rawErr, found.secrets));
    boolean truncated =
        out.truncated
            || err.truncated
            || ran.stdoutTruncated()
            || rawErr.length() >= ContainerProcess.STDERR_TAIL_CHARS;
    return new DockerResult(
        request.requestId(), DockerStatus.OK, out.text, truncated, ran.exitCode(), err.text);
  }

  private DockerResult stop(Docker request) {
    return lifecycle(
        request,
        List.of("stop", "-t", Integer.toString(STOP_SECONDS)),
        docker.deadline().plusSeconds(STOP_SECONDS));
  }

  private DockerResult remove(Docker request) {
    return lifecycle(request, List.of("rm", "-f"), docker.deadline());
  }

  /** {@code docker <verb…> <name>}, once the name is this target's and not the runner's own. */
  private DockerResult lifecycle(Docker request, List<String> verb, Duration deadline) {
    Lookup found = container(request);
    if (found.answer != null) {
      return found.answer;
    }
    if (found.process) {
      return ownProcess(request);
    }
    List<String> argv = new ArrayList<>();
    argv.add(docker.binary());
    argv.addAll(verb);
    argv.add(request.name());
    RunnerDocker.Result done = docker.run(argv, deadline);
    if (!done.ok()) {
      return missingOrFailed(request, done);
    }
    LOG.infof("system-runner's docker %s of %s, asked by its host", request.op(), request.name());
    return ok(request.requestId(), done.stdout().strip());
  }

  /** What a request about one container found: the answer already, or the container. */
  private record Lookup(
      DockerResult answer, JsonObject inspected, boolean process, List<String> secrets) {

    static Lookup answered(DockerResult answer) {
      return new Lookup(answer, null, false, List.of());
    }
  }

  /**
   * The named container, when it is this target's: owned by name, then inspected, then owned by
   * label. Anything else is the answer.
   */
  private Lookup container(Docker request) {
    UUID id = request.requestId();
    String name = request.name();
    if (!ownedName(name)) {
      return Lookup.answered(notFound(id, "container", name));
    }
    RunnerDocker.Result inspected =
        docker.run(List.of(docker.binary(), "inspect", "--type", "container", name));
    if (!inspected.ok()) {
      return Lookup.answered(missingOrFailed(request, inspected));
    }
    JsonArray all = new JsonArray(inspected.stdout().strip());
    if (all.isEmpty()) {
      return Lookup.answered(notFound(id, "container", name));
    }
    JsonObject container = all.getJsonObject(0);
    JsonObject config = object(container, "Config");
    JsonObject labels = object(config, "Labels");
    boolean owned = targetId.equals(labels.getString(identity.ownerLabel()));
    boolean process = targetId.equals(labels.getString(identity.processLabel()));
    if (!owned && !process) {
      return Lookup.answered(notFound(id, "container", name));
    }
    return new Lookup(null, container, process && !owned, secrets(config.getJsonArray("Env")));
  }

  private DockerResult ownProcess(Docker request) {
    return refused(
        request.requestId(),
        request.name() + " is this runner's own container: no " + request.op() + " through it");
  }

  // --- one volume --------------------------------------------------------------------------------

  /**
   * {@code docker volume inspect} alone, answered as docker says it: owned by the runner's name and
   * the owner label, or mounted by a container that carries it. A name outside the runner's prefix
   * is inspected only once an owned container is found mounting it. No size: {@code docker system
   * df -v} walks every volume on the node and outlasted the service's wait on a node with large
   * caches (qits-1621).
   */
  private DockerResult inspectVolume(Docker request) {
    UUID id = request.requestId();
    String name = request.name();
    if (!safeName(name)) {
      return notFound(id, "volume", name);
    }
    Mounted mounted = null;
    if (!identity.ownsName(name)) {
      mounted = mountedVolumes();
      if (mounted.failure != null) {
        return failed(id, mounted.failure);
      }
      if (!mounted.names.contains(name)) {
        return notFound(id, "volume", name);
      }
    }
    RunnerDocker.Result inspected = docker.run(List.of(docker.binary(), "volume", "inspect", name));
    if (!inspected.ok()) {
      return missingOrFailed(request, inspected);
    }
    JsonArray all = new JsonArray(inspected.stdout().strip());
    if (all.isEmpty()) {
      return notFound(id, "volume", name);
    }
    JsonObject volume = all.getJsonObject(0);
    JsonObject labels = object(volume, "Labels");
    if (targetId.equals(labels.getString(identity.ownerLabel()))) {
      return ok(id, volume.encode());
    }
    if (mounted == null) {
      mounted = mountedVolumes();
      if (mounted.failure != null) {
        return failed(id, mounted.failure);
      }
    }
    return mounted.names.contains(name) ? ok(id, volume.encode()) : notFound(id, "volume", name);
  }

  /** The volume names the owned containers mount, or why docker could not say. */
  private record Mounted(Set<String> names, String failure) {}

  /**
   * Every named volume a container carrying the owner label mounts, from {@code docker inspect} of
   * those containers — the runner's own process container is not among them. A name docker could
   * read as an option is left out.
   */
  private Mounted mountedVolumes() {
    RunnerDocker.Result ids =
        docker.run(
            List.of(docker.binary(), "ps", "-a", "-q", "--no-trunc", "--filter", ownerFilter()));
    if (!ids.ok()) {
      return new Mounted(Set.of(), ids.detail());
    }
    List<String> argv = new ArrayList<>(List.of(docker.binary(), "inspect", "--type", "container"));
    for (String line : ids.stdout().split("\n")) {
      String containerId = line.strip();
      if (!containerId.isEmpty() && safeName(containerId)) {
        argv.add(containerId);
      }
    }
    Set<String> names = new LinkedHashSet<>();
    if (argv.size() == 4) {
      return new Mounted(names, null);
    }
    RunnerDocker.Result inspected = docker.run(argv);
    if (!inspected.ok() && inspected.stdout().isBlank()) {
      // A container removed between the two calls fails the inspect but docker still prints the
      // others; only when nothing is printed is it every one of them, or docker not answering.
      return TargetVolumes.absent(inspected.detail()) && !inspected.timedOut()
          ? new Mounted(names, null)
          : new Mounted(Set.of(), inspected.detail());
    }
    JsonArray containers = new JsonArray(inspected.stdout().strip());
    for (int i = 0; i < containers.size(); i++) {
      JsonObject container = containers.getJsonObject(i);
      JsonObject labels = object(object(container, "Config"), "Labels");
      if (!targetId.equals(labels.getString(identity.ownerLabel()))) {
        continue;
      }
      JsonArray mounts = container.getJsonArray("Mounts");
      if (mounts == null) {
        continue;
      }
      for (int m = 0; m < mounts.size(); m++) {
        JsonObject mount = mounts.getJsonObject(m);
        String volume = mount == null ? null : mount.getString("Name");
        if (mount != null && "volume".equals(mount.getString("Type")) && safeName(volume)) {
          names.add(volume);
        }
      }
    }
    return new Mounted(names, null);
  }

  // --- the pieces --------------------------------------------------------------------------------

  /** A name this runner may name: its own prefix, and nothing docker could read as an option. */
  private boolean ownedName(String name) {
    return name != null && identity.ownsName(name) && safeName(name);
  }

  /** A docker name, and nothing docker could read as an option. */
  private static boolean safeName(String name) {
    try {
      LifecycleArgv.requireWorkId(name);
      return true;
    } catch (IllegalArgumentException notAName) {
      return false;
    }
  }

  /** A nested object; absent or JSON null reads as empty. */
  private static JsonObject object(JsonObject parent, String key) {
    JsonObject value = parent == null ? null : parent.getJsonObject(key);
    return value == null ? new JsonObject() : value;
  }

  static int tail(Integer asked) {
    if (asked == null || asked < 1) {
      return DEFAULT_TAIL;
    }
    return Math.min(asked, MAX_TAIL);
  }

  static int execSeconds(Integer asked) {
    if (asked == null || asked < 1) {
      return DEFAULT_EXEC_SECONDS;
    }
    return Math.min(asked, MAX_EXEC_SECONDS);
  }

  /**
   * The values to redact from a container's {@code Config.Env}: each {@code KEY=VALUE} whose key
   * {@link SecretKeys#isSecret} and whose value is at least {@link #MIN_SECRET} long, longest
   * first, so a secret containing another is replaced whole.
   */
  static List<String> secrets(JsonArray env) {
    Set<String> values = new LinkedHashSet<>();
    if (env != null) {
      for (Object entry : env) {
        if (!(entry instanceof String variable)) {
          continue;
        }
        int eq = variable.indexOf('=');
        if (eq <= 0) {
          continue;
        }
        String value = variable.substring(eq + 1);
        if (SecretKeys.isSecret(variable.substring(0, eq)) && value.length() >= MIN_SECRET) {
          values.add(value);
        }
      }
    }
    List<String> sorted = new ArrayList<>(values);
    sorted.sort(Comparator.comparingInt(String::length).reversed());
    return sorted;
  }

  static String redact(String text, List<String> secrets) {
    String out = text == null ? "" : text;
    for (String secret : secrets) {
      out = out.replace(secret, REDACTED);
    }
    return out;
  }

  /** {@code text} cut to at most {@link #OUTPUT_CAP} UTF-8 bytes, never inside a character. */
  record Capped(String text, boolean truncated) {}

  static Capped cap(String text) {
    return cap(text, OUTPUT_CAP);
  }

  static Capped cap(String text, int maxBytes) {
    if (text.length() * 3L <= maxBytes) {
      return new Capped(text, false);
    }
    long bytes = 0;
    int i = 0;
    while (i < text.length()) {
      int cp = text.codePointAt(i);
      int width = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
      if (bytes + width > maxBytes) {
        return new Capped(text.substring(0, i), true);
      }
      bytes += width;
      i += Character.charCount(cp);
    }
    return new Capped(text, false);
  }

  /** A command that failed: the thing went away meanwhile, or docker could not say. */
  private DockerResult missingOrFailed(Docker request, RunnerDocker.Result result) {
    if (!result.timedOut() && TargetVolumes.absent(result.detail())) {
      String what = request.op() == DockerOp.INSPECT_VOLUME ? "volume" : "container";
      return notFound(request.requestId(), what, request.name());
    }
    return failed(request.requestId(), result.detail());
  }

  private static DockerResult ok(UUID id, String body) {
    return new DockerResult(id, DockerStatus.OK, body, false, null, null);
  }

  private DockerResult notFound(UUID id, String what, String name) {
    return DockerResult.of(
        id, DockerStatus.NOT_FOUND, "no " + what + " " + name + " of this target");
  }

  private static DockerResult refused(UUID id, String why) {
    return DockerResult.of(id, DockerStatus.REFUSED, why);
  }

  private static DockerResult failed(UUID id, String why) {
    return DockerResult.of(id, DockerStatus.FAILED, why);
  }
}
