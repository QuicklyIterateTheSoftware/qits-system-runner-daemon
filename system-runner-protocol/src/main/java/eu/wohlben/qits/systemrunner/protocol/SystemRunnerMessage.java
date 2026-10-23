package eu.wohlben.qits.systemrunner.protocol;

import eu.wohlben.qits.runner.protocol.RunnerMessage;

/**
 * Every target-runner frame on the control socket. Sealed here and extending the base's non-sealed
 * {@link RunnerMessage}, because a {@code permits} list cannot span artifacts: the lifecycle frames
 * are qits-runner-protocol's, and {@code RunnerCodec} hands everything else to {@link
 * SystemRunnerCodec}. Each side switches exhaustively over this set for its own frames.
 *
 * <p>There is no pull frame: {@code reserve}, {@code take} and {@code backlog} are never used by
 * this runner, because qits-system allocates — the {@link Estate} is the whole desired state.
 */
public sealed interface SystemRunnerMessage extends RunnerMessage
    permits Estate, ProbeLogin, HealthCheck, Inventory, LoginState, HealthChecked {}
