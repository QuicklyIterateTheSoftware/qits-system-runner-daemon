package eu.wohlben.qits.systemrunner.protocol;

/** How a {@link Docker} request ended, as its {@link DockerResult} says. */
public enum DockerStatus {
  /** Docker answered; for an {@link DockerOp#EXEC} the command ran, whatever its exit code. */
  OK,
  /** The name is not a container or volume of this target — or not there at all. */
  NOT_FOUND,
  /** The request is not allowed: the runner's own container, or a malformed argument. */
  REFUSED,
  /** Docker could not answer, or the op threw; {@code body} says why. */
  FAILED
}
