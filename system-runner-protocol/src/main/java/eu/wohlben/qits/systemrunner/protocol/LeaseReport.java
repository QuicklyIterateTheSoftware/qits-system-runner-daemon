package eu.wohlben.qits.systemrunner.protocol;

/**
 * One lease in an {@link Inventory}.
 *
 * @param leaseId the lease
 * @param state what it is on the node
 * @param specHash the spec-hash label of its container, or null when there is none
 * @param exitCode the container's exit code when it {@code EXITED}, else null
 * @param detail one line for a person — why it FAILED, for instance — or null
 */
public record LeaseReport(
    String leaseId, LeaseState state, String specHash, Integer exitCode, String detail) {

  public LeaseReport {
    if (leaseId == null || leaseId.isBlank()) {
      throw new IllegalArgumentException("a lease report names no lease");
    }
    if (state == null) {
      throw new IllegalArgumentException("a lease report has no state");
    }
  }
}
