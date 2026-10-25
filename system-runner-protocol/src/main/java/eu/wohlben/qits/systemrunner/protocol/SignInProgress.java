package eu.wohlben.qits.systemrunner.protocol;

/**
 * Runner → qits-system: a sign-in moved. Sent at every state change; the url and user code once the
 * CLI printed them, and a detail for a person on a FAILED one.
 *
 * @param signInId the {@link SignIn#signInId()} this is about
 * @param harness the sign-in's harness, as the {@link SignIn} named it
 * @param state where the sign-in is now
 * @param url the browser url the CLI printed; null until it did
 * @param userCode Kimi Code's device code; null for Claude Code and until printed
 * @param detail why, on a terminal state; else null
 */
public record SignInProgress(
    String signInId,
    String harness,
    SignInState state,
    String url,
    String userCode,
    String detail)
    implements SystemRunnerMessage {}
