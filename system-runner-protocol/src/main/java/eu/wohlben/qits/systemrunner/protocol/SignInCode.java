package eu.wohlben.qits.systemrunner.protocol;

/**
 * qits-system → runner: the code a person pasted from the browser, for a sign-in that is {@link
 * SignInState#AWAITING_CODE}. The runner writes it to the login's stdin and never logs it.
 *
 * @param signInId the sign-in it is for
 * @param code the code, as the person pasted it
 */
public record SignInCode(String signInId, String code) implements SystemRunnerMessage {

  /** Without the code: a frame that reaches a log line must not carry it there. */
  @Override
  public String toString() {
    return "SignInCode[signInId=" + signInId + ", code=<redacted>]";
  }
}
