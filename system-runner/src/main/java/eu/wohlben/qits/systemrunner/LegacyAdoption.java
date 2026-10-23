package eu.wohlben.qits.systemrunner;

import eu.wohlben.qits.systemrunner.protocol.Adopt;
import eu.wohlben.qits.systemrunner.protocol.LeaseEntry;
import eu.wohlben.qits.systemrunner.protocol.SlotType;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerProtocol;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * <b>Which retired runner's container and volume a lease may take over</b>: only names matching
 * the retired runners' own patterns, so an {@code adopt} can never make this runner stop, remove or
 * mount anything else on the node.
 *
 * <ul>
 *   <li>WORKSPACE: qits-workspaces-runner-daemon's {@code
 *       qits-workspaces-runner-ws-<36>-<id8>}, container and volume alike;
 *   <li>DESK: qits-projects-desk-runner-daemon's {@code qits-projects-desk-runner-desk-<36>-<id8>}.
 * </ul>
 *
 * <p>Before the lease starts the runner stops and removes that container (if present) and mounts
 * that volume as the work volume. It never creates such a volume, and it removes one only on an
 * explicit {@code purges} entry ({@link #purgeable}). A name outside the patterns — or an adopt on a
 * type that has none — fails the lease with {@link SystemRunnerProtocol#ADOPT_NOT_ALLOWED}.
 */
final class LegacyAdoption {

  /** qits-workspaces-runner-daemon's workspace container and volume. */
  static final Pattern WORKSPACE =
      Pattern.compile("^qits-workspaces-runner-ws-[0-9a-f-]{36}-[0-9a-f]{8}$");

  /** qits-projects-desk-runner-daemon's desk container and volume. */
  static final Pattern DESK =
      Pattern.compile("^qits-projects-desk-runner-desk-[0-9a-f-]{36}-[0-9a-f]{8}$");

  private LegacyAdoption() {}

  /** The pattern a {@code type}'s adopted names must match; empty for a type with none. */
  static Optional<Pattern> pattern(SlotType type) {
    return switch (type) {
      case WORKSPACE -> Optional.of(WORKSPACE);
      case DESK -> Optional.of(DESK);
      case CI -> Optional.empty();
    };
  }

  /** Whether {@code name} may be adopted by a lease of {@code type}. */
  static boolean allowed(SlotType type, String name) {
    return name != null && pattern(type).map(p -> p.matcher(name).matches()).orElse(false);
  }

  /** {@link SystemRunnerProtocol#ADOPT_NOT_ALLOWED} when {@code entry}'s adopt names anything else. */
  static Optional<String> refusal(LeaseEntry entry) {
    Adopt adopt = entry.adopt();
    if (adopt == null) {
      return Optional.empty();
    }
    if (allowed(entry.slotType(), adopt.container()) && allowed(entry.slotType(), adopt.volume())) {
      return Optional.empty();
    }
    return Optional.of(SystemRunnerProtocol.ADOPT_NOT_ALLOWED);
  }

  /** Whether a {@code purges} entry may name {@code volume} as a retired runner's. */
  static boolean purgeable(String volume) {
    return volume != null && (WORKSPACE.matcher(volume).matches() || DESK.matcher(volume).matches());
  }
}
