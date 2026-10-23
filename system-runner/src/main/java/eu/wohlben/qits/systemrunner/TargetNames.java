package eu.wohlben.qits.systemrunner;

import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import java.util.regex.Pattern;

/**
 * Every name the target runner gives a thing on its node, derived from its {@link RunnerIdentity}
 * and the target id — never taken from a frame whole — so no frame can point the runner at a
 * container or a volume it does not own. {@code <id8>} is the target id's first eight characters.
 *
 * <ul>
 *   <li>a lease's container: {@code qits-system-runner-lease-<leaseId>-<id8>};
 *   <li>a work volume: {@code qits-system-runner-work-<workKey>-<id8>}, {@code workKey} being
 *       {@code ws-<rowId>} or {@code desk-<deskId>};
 *   <li>the login probe's container: {@code qits-system-runner-login-probe-<id8>}.
 * </ul>
 *
 * <p><b>Spelled here, not through {@code RunnerIdentity.nodeName}</b>, which caps its purpose at
 * 41 characters: {@code lease-<uuid>} is 42 and {@code work-desk-<uuid>} 46. The spelling is
 * {@code nodeName}'s exactly — {@code <namePrefix>-<purpose>-<id8>} — and every id that goes into a
 * name passes {@link #ID} first, so each name is a DNS label docker and the driver accept and starts
 * with the runner's own prefix, which every removal is held to.
 */
final class TargetNames {

  /** What a lease id or a work key may be: lowercase, digits and dashes, as a DNS label is. */
  static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,99}");

  static final String LEASE = "lease";
  static final String WORK = "work";
  static final String LOGIN_PROBE = "login-probe";

  private final RunnerIdentity identity;
  private final String targetId;

  TargetNames(RunnerIdentity identity, String targetId) {
    this.identity = identity;
    this.targetId = targetId;
  }

  /**
   * {@code qits-system-runner-lease-<leaseId>-<id8>}.
   *
   * @throws IllegalArgumentException when the lease id cannot be part of a name
   */
  String container(String leaseId) {
    return named(LEASE, require("lease id", leaseId));
  }

  /**
   * {@code qits-system-runner-work-<workKey>-<id8>}.
   *
   * @throws IllegalArgumentException when the work key cannot be part of a name
   */
  String workVolume(String workKey) {
    return named(WORK, require("work key", workKey));
  }

  /** {@code qits-system-runner-login-probe-<id8>}. */
  String loginProbe() {
    return identity.nodeName(targetId, LOGIN_PROBE);
  }

  /** Whether {@code name} is one of this runner's work volumes, by its spelling. */
  boolean isWorkVolume(String name) {
    String prefix = identity.namePrefix() + "-" + WORK + "-";
    String suffix = "-" + id8();
    return name != null
        && name.startsWith(prefix)
        && name.endsWith(suffix)
        && name.length() > prefix.length() + suffix.length()
        && ID.matcher(name.substring(prefix.length(), name.length() - suffix.length())).matches();
  }

  String id8() {
    return targetId.substring(0, Math.min(8, targetId.length()));
  }

  private String named(String purpose, String id) {
    return identity.namePrefix() + "-" + purpose + "-" + id + "-" + id8();
  }

  private static String require(String what, String value) {
    if (value == null || !ID.matcher(value).matches()) {
      throw new IllegalArgumentException(
          what + " '" + value + "' cannot be part of a name (" + ID.pattern() + ")");
    }
    return value;
  }
}
