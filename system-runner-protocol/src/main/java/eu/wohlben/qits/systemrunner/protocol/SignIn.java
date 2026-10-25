package eu.wohlben.qits.systemrunner.protocol;

/**
 * qits-system → runner: run an agent CLI's own login on this target's credential volume and relay
 * its progress as {@link SignInProgress} frames. At most one sign-in per harness runs at a time; a
 * second is answered {@link SignInState#FAILED}.
 *
 * @param signInId the server's id for this attempt, echoed in every {@link SignInProgress}
 * @param harness {@code claude} or {@code kimi}; any other value is answered FAILED, not dropped
 */
public record SignIn(String signInId, String harness) implements SystemRunnerMessage {}
