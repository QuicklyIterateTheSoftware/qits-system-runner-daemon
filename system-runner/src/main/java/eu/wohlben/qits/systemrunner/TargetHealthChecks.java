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
 * </ol>
 *
 * <p>The last two are qits-ci-runner-daemon's {@code BuildkitCheck} and {@code NetworkCheck}, as
 * closures over what they read rather than beans looked up in the context: nothing here is CDI.
 */
final class TargetHealthChecks {

  private TargetHealthChecks() {}

  static void registerDefaults(
      HealthChecks checks,
      RunnerIdentity identity,
      String targetId,
      Supplier<LoginState> lastLogin,
      Supplier<BuildPlane> buildPlane) {
    checks
        .register(DefaultHealthChecks.docker())
        .register(
            DefaultHealthChecks.nodeInventory(identity, targetId, SystemRunnerBinary.VERSION, null))
        .register(RunnerHealthCheck.named("login", null, ctx -> login(lastLogin.get())))
        .register(DefaultHealthChecks.session())
        .register(RunnerHealthCheck.named("buildkit", null, ctx -> buildkit(buildPlane.get())))
        .register(RunnerHealthCheck.named("network", null, ctx -> network(buildPlane.get())));
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
