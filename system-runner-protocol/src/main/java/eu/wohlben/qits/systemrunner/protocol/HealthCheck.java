package eu.wohlben.qits.systemrunner.protocol;

/**
 * qits-system → runner: run every registered health check now and answer {@link HealthChecked}. A
 * new target is quarantined until one passes.
 *
 * @param requestId the asker's id, echoed in the answer; null when the asker sent none
 */
public record HealthCheck(String requestId) implements SystemRunnerMessage {}
