package eu.wohlben.qits.systemrunner.protocol;

import java.util.List;

/**
 * qits-system → runner: the <b>whole</b> desired state of this target. Sent after the {@code Ack},
 * on every change and every estate interval.
 *
 * <p><b>{@code leases} is the server's list, and it wins.</b> A lease it lists that the runner does
 * not hold is started; a held lease that is missing or {@code ENDED} is stopped and removed; a
 * {@code specHash} change recreates the container. So it is sent only from a successful read, never
 * as an empty list on error — and the codec refuses a frame that carries no list at all rather than
 * reading the absence as "holds nothing".
 *
 * @param leases every lease placed on this target
 * <p><b>{@code warmImages} is advice, never desired state</b> (qits-1692): full image references the
 * runner pulls in the background when they are not on the node, so the first lease of a newly
 * released slot image does not wait for a cold pull. A warm pull never blocks a convergence and
 * never fails a lease, and image cleanup keeps every one of them. Absent — every server before it —
 * reads as empty, so it is not a capability bump.
 *
 * @param leases every lease placed on this target
 * @param purges volume names to remove: a work volume of this runner, or an adopted legacy volume.
 *     A volume is removed only on such an entry
 * @param warmImages image references to keep pulled on this node; empty when the server names none
 */
public record Estate(List<LeaseEntry> leases, List<String> purges, List<String> warmImages)
    implements SystemRunnerMessage {

  public Estate {
    leases = List.copyOf(leases);
    purges = purges == null ? List.of() : List.copyOf(purges);
    warmImages = warmImages == null ? List.of() : List.copyOf(warmImages);
  }

  /** An estate that names no image to keep warm: what every server before qits-1692 sends. */
  public Estate(List<LeaseEntry> leases, List<String> purges) {
    this(leases, purges, List.of());
  }
}
