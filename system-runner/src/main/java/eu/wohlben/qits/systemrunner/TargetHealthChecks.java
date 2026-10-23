package eu.wohlben.qits.systemrunner;

import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.health.DefaultHealthChecks;
import eu.wohlben.qits.runner.toolkit.health.HealthChecks;
import eu.wohlben.qits.runner.toolkit.health.RunnerHealthCheck;
import eu.wohlben.qits.systemrunner.protocol.LoginState;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerBinary;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The target runner's health checks, all run on every {@code healthCheck}, in this order; the
 * answer is ok only when every one is:
 *
 * <ol>
 *   <li>{@code docker} — {@link DefaultHealthChecks#docker()}: the daemon answers; {@code data:
 *       {serverVersion}}. Read-only.
 *   <li>{@code nodeInventory} — {@link DefaultHealthChecks#nodeInventory}, at {@link
 *       SystemRunnerBinary#VERSION}: the runner's labelled containers and volumes and its own
 *       container. Read-only.
 *   <li>{@code login} — the last login probe's result, with no new probe; {@code data: {claude,
 *       kimi, checkedAt, volume}}. Read-only, and <b>always ok</b>: it reports, and the server
 *       decides — presence is the operator's to change, so an absent login must never hold a target
 *       in quarantine.
 *   <li>{@code session} — {@link DefaultHealthChecks#session()}: {@code data: {connected,
 *       connectedSince, slots, held}}. Read-only.
 *   <li>{@code buildkit} — the target's builder runs under the stamp the next CI lease would start
 *       it with; {@code data: {container, presence, state, stamp, configuredStamp, address, image,
 *       stateVolume}}. Read-only ({@link BuildPlane#builder()}); never started from here. A builder
 *       never created is <b>ok</b>: it comes up on the first CI lease. A stale stamp, a stopped
 *       builder or a foreign container under its name fails.
 *   <li>{@code network} — the build network exists; {@code data: {network, presence, driver,
 *       builder}}. Missing is ok while no builder was ever created, and fails once one exists: a
 *       builder whose network went is one no CI lease can reach.
 *   <li>{@code idRange} — this process's user namespace maps the full uid/gid space, so buildkit can
 *       unpack any image layer (qits-556); {@code data: {idRange, fullIdRange, narrow}}. Unknown is
 *       ok.
 * </ol>
 *
 * <p>The last three are qits-ci-runner-daemon's {@code BuildkitCheck}, {@code NetworkCheck} and
 * {@code IdRangeCheck}, as closures over what they read rather than beans looked up in the context:
 * nothing here is CDI.
 */
final class TargetHealthChecks {

  /** How many uids and gids a host outside a user namespace maps: the whole 32-bit space. */
  static final long FULL_ID_RANGE = 4294967295L;

  private TargetHealthChecks() {}

  static void registerDefaults(
      HealthChecks checks,
      RunnerIdentity identity,
      String targetId,
      Supplier<LoginState> lastLogin,
      Supplier<BuildPlane> buildPlane,
      Supplier<Long> idRange) {
    checks
        .register(DefaultHealthChecks.docker())
        .register(
            DefaultHealthChecks.nodeInventory(identity, targetId, SystemRunnerBinary.VERSION, null))
        .register(RunnerHealthCheck.named("login", null, ctx -> login(lastLogin.get())))
        .register(DefaultHealthChecks.session())
        .register(RunnerHealthCheck.named("buildkit", null, ctx -> buildkit(buildPlane.get())))
        .register(RunnerHealthCheck.named("network", null, ctx -> network(buildPlane.get())))
        .register(RunnerHealthCheck.named("idRange", null, ctx -> idRange(idRange.get())));
  }

  static RunnerHealthCheck.Result buildkit(BuildPlane plane) {
    BuildPlane.Builder builder = plane.builder();
    String container = plane.names().builder();
    String configured = plane.configuredStamp();
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("container", container);
    data.put("presence", builder.presence().name());
    data.put("state", builder.status());
    data.put("stamp", builder.stamp());
    data.put("configuredStamp", configured);
    data.put("address", plane.names().address());
    data.put("image", plane.image());
    data.put("stateVolume", plane.names().stateVolume());
    return switch (builder.presence()) {
      case ABSENT ->
          RunnerHealthCheck.Result.ok(
              "not started yet: no CI lease on this target has needed " + container, data);
      case UNKNOWN ->
          RunnerHealthCheck.Result.failed(
              "docker did not answer an inspect of " + container + ": " + builder.detail(), data);
      case FOREIGN ->
          RunnerHealthCheck.Result.failed(
              "a container named "
                  + container
                  + " exists and is not this runner's builder (it has no "
                  + plane.stampLabel()
                  + " label); a CI lease here will refuse it",
              data);
      case PRESENT -> {
        if (!"running".equals(builder.status())) {
          yield RunnerHealthCheck.Result.failed(
              container + " is " + builder.status() + "; the next CI lease starts it", data);
        }
        if (!configured.equals(builder.stamp())) {
          yield RunnerHealthCheck.Result.failed(
              container
                  + " runs stamp "
                  + builder.stamp()
                  + " but the configuration is "
                  + configured
                  + "; the next CI lease replaces it",
              data);
        }
        yield RunnerHealthCheck.Result.ok(container + " is running, stamp " + configured, data);
      }
    };
  }

  static RunnerHealthCheck.Result network(BuildPlane plane) {
    BuildPlane.Network network = plane.network();
    String name = plane.names().network();
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("network", name);
    data.put("presence", network.presence().name());
    data.put("driver", network.driver());
    data.put("builder", null);
    return switch (network.presence()) {
      case PRESENT -> RunnerHealthCheck.Result.ok(name + " exists (" + network.driver() + ")", data);
      case UNKNOWN ->
          RunnerHealthCheck.Result.failed(
              "docker did not answer an inspect of " + name + ": " + network.detail(), data);
      case ABSENT, FOREIGN -> {
        BuildPlane.Builder builder = plane.builder();
        data.put("builder", builder.presence().name());
        yield builder.presence() == BuildPlane.Presence.ABSENT
            ? RunnerHealthCheck.Result.ok(
                "not needed yet: " + name + " is created with the builder", data)
            : RunnerHealthCheck.Result.failed(
                name
                    + " is missing while "
                    + plane.names().builder()
                    + " is "
                    + builder.presence().name().toLowerCase(Locale.ROOT)
                    + "; the next CI lease recreates it",
                data);
      }
    };
  }

  static RunnerHealthCheck.Result idRange(Long range) {
    boolean narrow = range != null && range < FULL_ID_RANGE;
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("idRange", range);
    data.put("fullIdRange", FULL_ID_RANGE);
    data.put("narrow", narrow);
    if (range == null) {
      return RunnerHealthCheck.Result.ok(
          "unknown: this process's uid/gid maps could not be read", data);
    }
    if (narrow) {
      return RunnerHealthCheck.Result.failed(narrowWarning(range), data);
    }
    return RunnerHealthCheck.Result.ok("maps " + range + " uids/gids", data);
  }

  /** qits-ci-runner-daemon's {@code IdRange.narrowWarning}, with this runner's name and its CI. */
  static String narrowWarning(long range) {
    return "system-runner's user namespace maps only "
        + range
        + " uids/gids (a full host maps "
        + FULL_ID_RANGE
        + "), so buildkit cannot unpack an image layer that owns a file above that id. Widen the"
        + " range on this host: for rootless docker, the rootless user's entries in /etc/subuid and"
        + " /etc/subgid (then restart its dockerd); for an unprivileged LXC, the container's"
        + " lxc.idmap on the LXC host.";
  }

  static RunnerHealthCheck.Result login(LoginState last) {
    Map<String, Object> data = new LinkedHashMap<>();
    if (last == null) {
      data.put("claude", null);
      data.put("kimi", null);
      data.put("checkedAt", null);
      data.put("volume", null);
      return RunnerHealthCheck.Result.ok("the login has not been probed yet", data);
    }
    data.put("claude", last.claude().name());
    data.put("kimi", last.kimi().name());
    data.put("checkedAt", last.checkedAt());
    data.put("volume", last.volume());
    return RunnerHealthCheck.Result.ok(
        "claude "
            + last.claude()
            + ", kimi "
            + last.kimi()
            + " on "
            + last.volume()
            + " at "
            + last.checkedAt(),
        data);
  }
}
