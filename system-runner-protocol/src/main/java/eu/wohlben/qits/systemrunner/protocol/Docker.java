package eu.wohlben.qits.systemrunner.protocol;

import java.util.List;
import java.util.UUID;

/**
 * qits-system → runner: run one docker operation on the target's own resources and answer exactly
 * one {@link DockerResult} with the same {@code requestId}. The runner checks ownership first and
 * applies its own limits; the arguments here are requests, never trusted.
 *
 * @param requestId the asker's id, echoed in the answer
 * @param op what to do
 * @param name the container or volume, for every op but the two listings; else null
 * @param tail {@link DockerOp#LOGS}: how many lines; null for the runner's default
 * @param since {@link DockerOp#LOGS}: docker's {@code --since}; null for all
 * @param argv {@link DockerOp#EXEC}: the command; else null
 * @param timeoutSeconds {@link DockerOp#EXEC}: how long it may run; null for the runner's default
 */
public record Docker(
    UUID requestId,
    DockerOp op,
    String name,
    Integer tail,
    String since,
    List<String> argv,
    Integer timeoutSeconds)
    implements SystemRunnerMessage {

  public Docker {
    if (requestId == null) {
      throw new IllegalArgumentException("a docker request has no requestId");
    }
    if (op == null) {
      throw new IllegalArgumentException("a docker request has no op");
    }
    argv = argv == null ? null : List.copyOf(argv);
  }
}
