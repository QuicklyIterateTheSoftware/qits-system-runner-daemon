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
 * @param purges volume names to remove: a work volume of this runner, or an adopted legacy volume.
 *     A volume is removed only on such an entry
 */
public record Estate(List<LeaseEntry> leases, List<String> purges) implements SystemRunnerMessage {

  public Estate {
    leases = List.copyOf(leases);
    purges = purges == null ? List.of() : List.copyOf(purges);
  }
}
