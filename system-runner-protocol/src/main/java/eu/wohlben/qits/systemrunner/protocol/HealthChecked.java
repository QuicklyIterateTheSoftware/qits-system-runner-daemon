package eu.wohlben.qits.systemrunner.protocol;

import eu.wohlben.qits.runner.protocol.health.CheckResult;
import eu.wohlben.qits.runner.protocol.health.HealthReport;
import java.util.List;

/**
 * Runner → qits-system: the answer to {@link HealthCheck} — qits-runner-protocol's {@link
 * HealthReport}, every runner kind's shape, on {@code HealthWire}'s wire: {@code ok} only when every
 * check passed, {@code detail} a line for a person, {@code requestId} echoed, {@code checks} each
 * named check's outcome in the runner's order.
 *
 * @param ok every check passed
 * @param detail a line for a person
 * @param requestId the {@link HealthCheck#requestId()} this answers, or null
 * @param checks each check's outcome
 */
public record HealthChecked(boolean ok, String detail, String requestId, List<CheckResult> checks)
    implements SystemRunnerMessage {

  public HealthChecked {
    checks = checks == null ? List.of() : List.copyOf(checks);
  }

  /** The frame carrying {@code report}. */
  public static HealthChecked of(HealthReport report) {
    return new HealthChecked(report.ok(), report.detail(), report.requestId(), report.checks());
  }

  /** This frame's payload, as every runner kind reports it. */
  public HealthReport report() {
    return new HealthReport(ok, detail, requestId, checks);
  }
}
