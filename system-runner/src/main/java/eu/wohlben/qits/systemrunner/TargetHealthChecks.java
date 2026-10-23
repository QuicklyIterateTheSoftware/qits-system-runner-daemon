package eu.wohlben.qits.systemrunner;

import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.health.DefaultHealthChecks;
import eu.wohlben.qits.runner.toolkit.health.HealthChecks;
import eu.wohlben.qits.runner.toolkit.health.RunnerHealthCheck;
import eu.wohlben.qits.systemrunner.protocol.LoginState;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerBinary;
import java.util.LinkedHashMap;
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
 * </ol>
 */
final class TargetHealthChecks {

  private TargetHealthChecks() {}

  static void registerDefaults(
      HealthChecks checks,
      RunnerIdentity identity,
      String targetId,
      Supplier<LoginState> lastLogin) {
    checks
        .register(DefaultHealthChecks.docker())
        .register(
            DefaultHealthChecks.nodeInventory(identity, targetId, SystemRunnerBinary.VERSION, null))
        .register(RunnerHealthCheck.named("login", null, ctx -> login(lastLogin.get())))
        .register(DefaultHealthChecks.session());
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
