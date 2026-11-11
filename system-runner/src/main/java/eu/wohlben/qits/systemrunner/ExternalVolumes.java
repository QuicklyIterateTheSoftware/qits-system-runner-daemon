package eu.wohlben.qits.systemrunner;

import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.RunnerSettings;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The node volumes an operator named instead of the runner's own: {@code
 * QITS_SYSTEM_RUNNER_CREDENTIAL_VOLUME}, {@code …_M2_VOLUME} and {@code …_PNPM_VOLUME}, install page
 * tuning the line passes through to the runner's container. On the platform host they name the
 * volumes every other container there mounts — {@code qits_shared_dot_claude}, {@code
 * qits_shared_m2}, {@code qits_shared_pnpm} — so the target's one credential volume is the one the
 * admin workspaces and qits-workspaces' transcript archive already use.
 *
 * <p><b>An external volume is never the runner's.</b> It is mounted where the runner's own node
 * volume would be — by every WORKSPACE and DESK lease and by the login probe — and nothing else:
 * never created, never labelled, never removed. A lease started while it is missing fails rather
 * than letting {@code docker run -v} make an empty one. Exactly {@value #OWN} means the runner's own
 * volume.
 *
 * <p><b>The choice outlives a reinstall</b> (qits-2088): a rotate-token install line carries only
 * the url, the id, the registration token and the slots, so a reinstalled runner that read these
 * three from its environment alone would fall back to its own volumes — an empty {@code
 * /claude-home}, and every running WORKSPACE and DESK lease recreated onto it, because the mounts
 * hash covers the names. {@link Main} therefore resolves them through {@link NodeVolumeRecord}:
 * a set variable wins and is recorded in the state volume, {@value #OWN} resets to the runner's
 * own, and an unset or blank one means what the record says. Removing a volume stays the
 * decommission's job; neither the record nor a reset ever removes one.
 *
 * <p>An invalid name — set or recorded — is a refusal to start ({@code RunnerExit.MISCONFIGURED}),
 * as any other bad setting is. qits-projects-desk-runner-daemon's {@code ExternalVolumes}, with the
 * credential volume's name.
 */
record ExternalVolumes(Optional<String> credential, Optional<String> m2, Optional<String> pnpm) {

  /** {@code <envPrefix>CREDENTIAL_VOLUME}: the agent home and login ({@code /claude-home}). */
  static final String CREDENTIAL_VOLUME = "CREDENTIAL_VOLUME";

  /** {@code <envPrefix>M2_VOLUME}: the maven cache ({@code /caches/m2}). */
  static final String M2_VOLUME = "M2_VOLUME";

  /** {@code <envPrefix>PNPM_VOLUME}: the pnpm store ({@code /caches/pnpm}). */
  static final String PNPM_VOLUME = "PNPM_VOLUME";

  /** A variable's value that means the runner's own volume, whatever the record says. */
  static final String OWN = "-";

  /** Docker's own volume name charset. */
  static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]*");

  /** None named: the runner's own node volumes, created labelled. */
  static final ExternalVolumes NONE =
      new ExternalVolumes(Optional.empty(), Optional.empty(), Optional.empty());

  /**
   * The three out of {@code env} alone, no record consulted; {@code null}, blank and {@value #OWN}
   * all mean the runner's own.
   *
   * @throws RunnerSettings.Invalid naming the variable when a value is no docker volume name
   */
  static ExternalVolumes parse(RunnerIdentity identity, Function<String, String> env)
      throws RunnerSettings.Invalid {
    return new ExternalVolumes(
        one(identity.env(CREDENTIAL_VOLUME), env),
        one(identity.env(M2_VOLUME), env),
        one(identity.env(PNPM_VOLUME), env));
  }

  private static Optional<String> one(String variable, Function<String, String> env)
      throws RunnerSettings.Invalid {
    String raw = env.apply(variable);
    if (raw == null || raw.isBlank() || OWN.equals(raw.strip())) {
      return Optional.empty();
    }
    return Optional.of(name(variable, raw));
  }

  /**
   * {@code raw}, stripped, when it is a docker volume name.
   *
   * @param source what named it, for the refusal: the variable, or the record and its key
   * @throws RunnerSettings.Invalid naming {@code source} when it is not
   */
  static String name(String source, String raw) throws RunnerSettings.Invalid {
    String name = raw.strip();
    if (name.length() > 128 || !NAME.matcher(name).matches()) {
      throw new RunnerSettings.Invalid(
          source + " is not a docker volume name ([A-Za-z0-9][A-Za-z0-9_.-]*): '" + name + "'");
    }
    return name;
  }
}
