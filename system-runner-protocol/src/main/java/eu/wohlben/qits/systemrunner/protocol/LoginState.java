package eu.wohlben.qits.systemrunner.protocol;

/**
 * Runner → qits-system: whether each agent CLI is logged in on this target's credential volume.
 * Presence only — the probe never reads, copies or sends a credential.
 *
 * @param claude Claude Code's login
 * @param kimi Kimi Code's login
 * @param checkedAt an ISO-8601 instant: when the probe ran
 * @param volume the credential volume the probe mounted — the one every WORKSPACE and DESK lease
 *     mounts at {@code /claude-home}, and the only one the server's login command may name
 */
public record LoginState(LoginPresence claude, LoginPresence kimi, String checkedAt, String volume)
    implements SystemRunnerMessage {

  public LoginState {
    claude = claude == null ? LoginPresence.UNKNOWN : claude;
    kimi = kimi == null ? LoginPresence.UNKNOWN : kimi;
  }
}
