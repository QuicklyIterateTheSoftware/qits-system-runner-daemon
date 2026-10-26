package eu.wohlben.qits.systemrunner.protocol;

/**
 * What one {@link Docker} frame asks of the target's docker, spelled on the wire as the constant's
 * name. Every op is about the runner's own resources only — a container or volume carrying {@code
 * qits.system.runner=<targetId>} — and one docker command each; an op this version does not know is
 * a refused frame, never a guess.
 */
public enum DockerOp {
  /** {@code docker ps -a}, filtered by the owner label. */
  LIST_CONTAINERS,
  /** {@code docker inspect} of one container. */
  INSPECT_CONTAINER,
  /** {@code docker logs --tail <n> [--since <s>]} of one container, its secrets redacted. */
  LOGS,
  /** {@code docker exec} in one container, no TTY and no stdin, its secrets redacted. */
  EXEC,
  /** {@code docker stop -t 10} of one container. */
  STOP,
  /** {@code docker rm -f} of one container. */
  REMOVE,
  /** {@code docker volume ls}, filtered by the owner label. */
  LIST_VOLUMES,
  /** {@code docker volume inspect} of one volume, with its size when docker says it. */
  INSPECT_VOLUME;

  /** Whether the op names one container or volume, so {@link Docker#name()} is required. */
  public boolean named() {
    return this != LIST_CONTAINERS && this != LIST_VOLUMES;
  }
}
