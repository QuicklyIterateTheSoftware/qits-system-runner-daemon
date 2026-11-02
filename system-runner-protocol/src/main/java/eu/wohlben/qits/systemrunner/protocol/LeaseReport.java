package eu.wohlben.qits.systemrunner.protocol;

/**
 * One lease in an {@link Inventory}.
 *
 * <p>The four memory facts (qits-1818) are read from the lease container's cgroup v2 directory on
 * the node, through the node's cgroup tree the runner container sees read-only; each is null when
 * the runner cannot read it — no tree bound, a cgroup v1 host, a lease with no container yet. An
 * {@code EXITED} lease carries the last values read while its container ran, since its cgroup is
 * gone once it stops.
 *
 * @param leaseId the lease
 * @param state what it is on the node
 * @param specHash the spec-hash label of its container, or null when there is none
 * @param exitCode the container's exit code when it {@code EXITED}, else null
 * @param detail one line for a person — why it FAILED, for instance — or null
 * @param oomKills processes the OOM killer took in the lease's cgroup ({@code memory.events}'
 *     {@code oom_kill}), or null
 * @param oomEvents times the lease's own memory limit was hit ({@code memory.events}' {@code oom}),
 *     or null
 * @param memoryPeakBytes the lease's peak memory ({@code memory.peak}, kernel 5.19 and newer),
 *     reported coarsely — it moves only when the peak grew by {@code 64 MiB} or more — or null
 * @param oomKilled docker's {@code State.OOMKilled} of an {@code EXITED} lease's container, else
 *     null
 */
public record LeaseReport(
    String leaseId,
    LeaseState state,
    String specHash,
    Integer exitCode,
    String detail,
    Long oomKills,
    Long oomEvents,
    Long memoryPeakBytes,
    Boolean oomKilled) {

  public LeaseReport {
    if (leaseId == null || leaseId.isBlank()) {
      throw new IllegalArgumentException("a lease report names no lease");
    }
    if (state == null) {
      throw new IllegalArgumentException("a lease report has no state");
    }
  }

  /** A report without the memory facts: the shape before qits-1818. */
  public LeaseReport(
      String leaseId, LeaseState state, String specHash, Integer exitCode, String detail) {
    this(leaseId, state, specHash, exitCode, detail, null, null, null, null);
  }
}
