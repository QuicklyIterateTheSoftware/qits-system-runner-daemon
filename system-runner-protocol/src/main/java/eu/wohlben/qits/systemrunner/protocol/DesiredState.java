package eu.wohlben.qits.systemrunner.protocol;

/**
 * What qits-system wants of one lease on this runner's node. Spelled on the wire as the constant's
 * name. It is an instruction, so a value this version does not know is refused by {@link
 * SystemRunnerCodec} rather than guessed at.
 */
public enum DesiredState {
  /** The lease's container runs: created when absent, recreated on a new spec hash. */
  RUNNING,
  /** The lease is over: its container is stopped and removed; a work volume is kept. */
  ENDED
}
