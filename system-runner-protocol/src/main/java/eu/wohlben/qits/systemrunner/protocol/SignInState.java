package eu.wohlben.qits.systemrunner.protocol;

/**
 * Where one sign-in is, as the runner reports it in a {@link SignInProgress}. Spelled on the wire as
 * the constant's name; {@link #SUCCEEDED}, {@link #FAILED}, {@link #EXPIRED} and {@link #CANCELLED}
 * are terminal — nothing follows them for the same sign-in.
 */
public enum SignInState {
  /** The login is being started: the image pulled, the CLI run. */
  STARTING,
  /** Claude Code printed its url and waits for the code the browser shows ({@code signInCode}). */
  AWAITING_CODE,
  /** Kimi Code printed its url and user code and waits for the browser to approve the device. */
  AWAITING_BROWSER,
  /** The CLI exited 0: the volume holds a login. A {@code loginState} follows. */
  SUCCEEDED,
  /** The login could not run or the CLI exited non-zero; {@code detail} says why. */
  FAILED,
  /** Nobody finished it in time: the runner stopped it. */
  EXPIRED,
  /** A {@code signInCancel} stopped it. */
  CANCELLED;

  /** Whether nothing follows this state for the same sign-in. */
  public boolean terminal() {
    return this == SUCCEEDED || this == FAILED || this == EXPIRED || this == CANCELLED;
  }
}
