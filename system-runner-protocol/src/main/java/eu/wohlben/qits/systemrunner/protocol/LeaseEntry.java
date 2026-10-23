package eu.wohlben.qits.systemrunner.protocol;

/**
 * One lease qits-system has placed on this target: an {@link Estate} entry.
 *
 * @param leaseId the lease's id; the container's name and work label are keyed on it
 * @param slotType which policy its container runs under
 * @param desired whether its container should run, or the lease is over
 * @param spec what the container is; may be null on an {@code ENDED} entry
 * @param specHash the server's hash of the canonical spec. The runner labels the container with it
 *     and recreates the container when an entry carries a different one; may be null on an {@code
 *     ENDED} entry
 * @param workVolume the lease's <b>work key</b> — {@code ws-<rowId>} or {@code desk-<deskId>} —
 *     from which the runner derives the work volume {@code
 *     qits-system-runner-work-<workKey>-<id8>}; never a volume name, so no frame can point the
 *     runner at a volume it does not own. Null for a type without one (CI)
 * @param adopt the retired runner's container and volume to take over, or null
 */
public record LeaseEntry(
    String leaseId,
    SlotType slotType,
    DesiredState desired,
    LeaseSpec spec,
    String specHash,
    String workVolume,
    Adopt adopt) {

  public LeaseEntry {
    if (leaseId == null || leaseId.isBlank()) {
      throw new IllegalArgumentException("a lease entry names no lease");
    }
    if (slotType == null) {
      throw new IllegalArgumentException("a lease entry has no slot type");
    }
    if (desired == null) {
      throw new IllegalArgumentException("a lease entry has no desired state");
    }
    if (desired == DesiredState.RUNNING) {
      if (spec == null) {
        throw new IllegalArgumentException("a running lease entry has no spec");
      }
      if (specHash == null || specHash.isBlank()) {
        throw new IllegalArgumentException("a running lease entry has no spec hash");
      }
    }
  }
}
