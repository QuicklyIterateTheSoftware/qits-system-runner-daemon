package eu.wohlben.qits.systemrunner.protocol;

/**
 * What kind of work a lease is, and so which fixed policy the runner applies to its container
 * (mounts, user, networks, init, restart, capabilities, removal). Spelled on the wire as the
 * constant's name. A value this version does not know is a refusal, never a guess: the policy is
 * the boundary, and an unknown type has none.
 *
 * <p>{@link #CI} is declared so the server can already speak of it; this runner version refuses
 * every CI lease ({@code FAILED}, {@link SystemRunnerProtocol#CI_NOT_ENABLED}) until the CI
 * feature lands.
 */
public enum SlotType {
  /** A CI step: qits-ci's run. Not enabled on this runner version. */
  CI,
  /** A coding workspace: qits-workspaces' row, its work volume {@code ws-<rowId>}. */
  WORKSPACE,
  /** A project front desk: qits-projects' desk, its work volume {@code desk-<deskId>}. */
  DESK
}
