package eu.wohlben.qits.systemrunner.protocol;

/**
 * What one lease is on the node, as the runner reports it in an {@link Inventory}. Spelled on the
 * wire as the constant's name.
 */
public enum LeaseState {
  /** Being converged: the image is pulled, the volumes made, the container created. */
  STARTING,
  /** The lease's container exists and docker reports it running. */
  RUNNING,
  /** The container exited by itself; {@code exitCode} says how. The runner does not restart it. */
  EXITED,
  /** The runner could not, or would not, run the lease; {@code detail} says why. */
  FAILED,
  /** Desired {@code ENDED} and done: the container is gone. */
  ENDED
}
