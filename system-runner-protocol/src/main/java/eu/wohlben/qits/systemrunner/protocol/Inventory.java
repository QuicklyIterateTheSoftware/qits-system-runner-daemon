package eu.wohlben.qits.systemrunner.protocol;

import java.util.List;

/**
 * Runner → qits-system: what of the leases is on this node, read from docker under the runner's own
 * label, and which work volumes exist. Sent on every change. Like {@link Estate}, it is sent only
 * from a listing docker answered, and the codec refuses a frame that carries no lists.
 *
 * @param leases every lease the runner knows of, with its state
 * @param volumes the names of the work volumes on the node: the runner's own {@code
 *     qits-system-runner-work-<workKey>-<id8>} and every adopted legacy volume an estate entry names
 * @param node the node's memory facts (qits-1818), or null when the runner read none — and from a
 *     runner before them
 */
public record Inventory(List<LeaseReport> leases, List<String> volumes, NodeMemory node)
    implements SystemRunnerMessage {

  public Inventory {
    leases = List.copyOf(leases);
    volumes = List.copyOf(volumes);
  }

  /** An inventory without the node's memory facts: the shape before qits-1818. */
  public Inventory(List<LeaseReport> leases, List<String> volumes) {
    this(leases, volumes, null);
  }
}
