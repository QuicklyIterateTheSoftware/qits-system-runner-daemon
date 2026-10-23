package eu.wohlben.qits.systemrunner.protocol;

/**
 * A lease container's resource limits, each rendered as its {@code docker run} flag and each
 * nullable: null renders nothing, so "unset" never silently acquires a limit.
 *
 * @param memory {@code --memory}
 * @param memorySwap {@code --memory-swap}
 * @param pidsLimit {@code --pids-limit}
 * @param cpus {@code --cpus}
 * @param oomScoreAdj {@code --oom-score-adj}
 */
public record Limits(
    String memory, String memorySwap, Long pidsLimit, String cpus, Integer oomScoreAdj) {

  /** No limits at all. */
  public static final Limits NONE = new Limits(null, null, null, null, null);
}
