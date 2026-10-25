package eu.wohlben.qits.systemrunner.protocol;

/**
 * qits-system → runner: stop a sign-in — the person gave up, or the server's own deadline passed.
 * Answered {@link SignInState#CANCELLED} when the runner still held it.
 *
 * @param signInId the sign-in to stop
 */
public record SignInCancel(String signInId) implements SystemRunnerMessage {}
