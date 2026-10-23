package eu.wohlben.qits.systemrunner.protocol;

/** qits-system → runner: re-check the agent logins now; answered {@link LoginState}. */
public record ProbeLogin() implements SystemRunnerMessage {}
