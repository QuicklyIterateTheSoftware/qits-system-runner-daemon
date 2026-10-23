package eu.wohlben.qits.systemrunner.protocol;

/**
 * Whether an agent CLI is logged in on a target's credential volume, as its probe found it.
 * Spelled on the wire as the constant's name; a value this version does not know, or none, reads
 * as {@link #UNKNOWN} — a report the reader cannot place is not evidence of either answer.
 */
public enum LoginPresence {
  /** The probe ran and found a login. */
  PRESENT,
  /** The probe ran and found none: the targets page shows the login command. */
  ABSENT,
  /** The probe could not run (no image, a docker error) or has not run. */
  UNKNOWN;

  /** The wire value, read tolerantly. */
  public static LoginPresence fromWire(String value) {
    if (value != null) {
      for (LoginPresence presence : values()) {
        if (presence.name().equals(value)) {
          return presence;
        }
      }
    }
    return UNKNOWN;
  }
}
