package eu.wohlben.qits.systemrunner.protocol;

import java.util.UUID;

/**
 * Runner → qits-system: the one answer to a {@link Docker} request.
 *
 * <p>{@code body} is what the op answers: a JSON array for the two listings, the inspected JSON
 * object for the two inspects, the log text,
 * an exec's stdout, docker's own line for a stop or a removal — and on any status but {@code OK}, a
 * line for a person saying why. Never null.
 *
 * @param requestId the {@link Docker#requestId()} this answers
 * @param status how it ended
 * @param body the answer, see above; empty rather than null
 * @param truncated the body (or an exec's stderr) was cut at the runner's cap
 * @param exitCode an exec's exit code; else null
 * @param stderr an exec's stderr; else null
 */
public record DockerResult(
    UUID requestId,
    DockerStatus status,
    String body,
    boolean truncated,
    Integer exitCode,
    String stderr)
    implements SystemRunnerMessage {

  public DockerResult {
    if (requestId == null) {
      throw new IllegalArgumentException("a docker result has no requestId");
    }
    if (status == null) {
      throw new IllegalArgumentException("a docker result has no status");
    }
    body = body == null ? "" : body;
  }

  /** A non-OK answer: {@code detail} as the body, nothing else. */
  public static DockerResult of(UUID requestId, DockerStatus status, String detail) {
    return new DockerResult(requestId, status, detail, false, null, null);
  }
}
