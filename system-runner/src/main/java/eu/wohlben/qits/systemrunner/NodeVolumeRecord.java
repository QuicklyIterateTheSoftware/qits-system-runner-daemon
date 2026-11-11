package eu.wohlben.qits.systemrunner;

import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.RunnerSettings;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.jboss.logging.Logger;

/**
 * The node-volume choice, kept in the state volume as {@code <stateDir>/node-volumes.json} — {@code
 * {"credential": <name or null>, "m2": …, "pnpm": …}}, null being the runner's own derived volume —
 * so it survives a reinstall (qits-2088). A rotate-token install line carries none of {@code
 * …_CREDENTIAL_VOLUME}, {@code …_M2_VOLUME} and {@code …_PNPM_VOLUME}; the state volume is the one
 * thing of the runner's a reinstall keeps (the install script removes only {@code client.json}, and
 * only the decommission removes the volume). Without the record a reinstalled platform-host runner
 * mounted its own empty credential volume instead of {@code qits_shared_dot_claude}, and since the
 * mounts hash covers the names, recreated every running WORKSPACE and DESK lease onto it.
 *
 * <p>Each of the three resolves, at every start, as:
 *
 * <ul>
 *   <li>the variable names a docker volume → that one ({@code env}), recorded;
 *   <li>the variable is exactly {@value ExternalVolumes#OWN} → the runner's own ({@code own}),
 *       recorded as null;
 *   <li>the variable is unset or blank → what the record holds for it ({@code recorded}, or {@code
 *       own} for a recorded null);
 *   <li>nothing recorded either → the runner's own ({@code own}).
 * </ul>
 *
 * <p><b>A record that cannot be read refuses the start</b>, naming the file, as an invalid name in
 * it does: falling back to the derived names is exactly the fault this record exists to prevent.
 * The file is rewritten only when the choice changed, atomically (a temp file moved over it, as the
 * toolkit's {@code Registration} writes {@code client.json}), so a crash leaves the old record or
 * the new. Nothing here removes a volume: switching away from one leaves it where it is.
 */
final class NodeVolumeRecord {

  private static final Logger LOG = Logger.getLogger(NodeVolumeRecord.class);

  /** The record's name in the state directory. */
  static final String FILE = "node-volumes.json";

  /** The record's keys: {@code …_CREDENTIAL_VOLUME}, {@code …_M2_VOLUME}, {@code …_PNPM_VOLUME}. */
  static final String CREDENTIAL = "credential";

  static final String M2 = "m2";
  static final String PNPM = "pnpm";

  /** Where a volume came from, as the start's log line spells it. */
  enum Source {
    ENV,
    RECORDED,
    OWN;

    String spelled() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** One volume's resolution: the external name, or empty for the runner's own. */
  record Choice(Optional<String> name, Source source) {}

  private NodeVolumeRecord() {}

  /**
   * The three resolved against {@code <settings.stateDir()>/node-volumes.json}, the record brought
   * up to date, and one INFO line naming each volume and its source.
   *
   * @throws RunnerSettings.Invalid naming the variable for a bad value, or the file for a record
   *     that cannot be read, holds a bad name, or cannot be written
   */
  static ExternalVolumes resolve(
      RunnerIdentity identity, RunnerSettings settings, Function<String, String> env)
      throws RunnerSettings.Invalid {
    Path file = settings.stateDir().resolve(FILE);
    // Every variable first: a bad value refuses the start before the record is even read.
    Optional<Choice> credentialSet = set(identity.env(ExternalVolumes.CREDENTIAL_VOLUME), env);
    Optional<Choice> m2Set = set(identity.env(ExternalVolumes.M2_VOLUME), env);
    Optional<Choice> pnpmSet = set(identity.env(ExternalVolumes.PNPM_VOLUME), env);
    Map<String, Optional<String>> recorded = read(file);
    Choice credential = credentialSet.orElseGet(() -> recorded(recorded, CREDENTIAL));
    Choice m2 = m2Set.orElseGet(() -> recorded(recorded, M2));
    Choice pnpm = pnpmSet.orElseGet(() -> recorded(recorded, PNPM));
    ExternalVolumes chosen = new ExternalVolumes(credential.name(), m2.name(), pnpm.name());
    write(file, chosen);
    TargetVolumes volumes = new TargetVolumes(identity, settings.runnerId(), chosen);
    LOG.infof(
        "%s's node volumes: credential %s (%s), m2 %s (%s), pnpm %s (%s)",
        identity.logName(),
        volumes.credential(),
        credential.source().spelled(),
        volumes.m2(),
        m2.source().spelled(),
        volumes.pnpm(),
        pnpm.source().spelled());
    return chosen;
  }

  /** The variable's say, when it has one: a name, or {@value ExternalVolumes#OWN}. */
  private static Optional<Choice> set(String variable, Function<String, String> env)
      throws RunnerSettings.Invalid {
    String raw = env.apply(variable);
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    if (ExternalVolumes.OWN.equals(raw.strip())) {
      return Optional.of(new Choice(Optional.empty(), Source.OWN));
    }
    return Optional.of(new Choice(Optional.of(ExternalVolumes.name(variable, raw)), Source.ENV));
  }

  private static Choice recorded(Map<String, Optional<String>> recorded, String key) {
    Optional<String> name = recorded.getOrDefault(key, Optional.empty());
    return new Choice(name, name.isPresent() ? Source.RECORDED : Source.OWN);
  }

  /**
   * The record's keys, each a name or empty (null); none when there is no file yet.
   *
   * @throws RunnerSettings.Invalid naming the file when it cannot be read, is not a JSON object, or
   *     holds anything but a docker volume name or null under one of the keys
   */
  static Map<String, Optional<String>> read(Path file) throws RunnerSettings.Invalid {
    Map<String, Optional<String>> recorded = new HashMap<>();
    if (!Files.exists(file)) {
      return recorded;
    }
    JsonObject json;
    try {
      json = new JsonObject(Files.readString(file, StandardCharsets.UTF_8));
    } catch (IOException | RuntimeException e) {
      throw new RunnerSettings.Invalid(
          file
              + " is not a usable node-volume record ("
              + e.getMessage()
              + "); fix it, or delete it to fall back to the variables and the runner's own"
              + " volumes");
    }
    for (String key : new String[] {CREDENTIAL, M2, PNPM}) {
      if (!json.containsKey(key)) {
        continue;
      }
      Object value = json.getValue(key);
      if (value == null) {
        recorded.put(key, Optional.empty());
      } else if (value instanceof String name) {
        recorded.put(key, Optional.of(ExternalVolumes.name(file + "'s " + key, name)));
      } else {
        throw new RunnerSettings.Invalid(
            file + "'s " + key + " is neither a docker volume name nor null: " + value);
      }
    }
    return recorded;
  }

  /** {@code chosen} as the record, written only when it differs from the file there. */
  private static void write(Path file, ExternalVolumes chosen) throws RunnerSettings.Invalid {
    String text =
        new JsonObject()
                .put(CREDENTIAL, chosen.credential().orElse(null))
                .put(M2, chosen.m2().orElse(null))
                .put(PNPM, chosen.pnpm().orElse(null))
                .encodePrettily()
            + "\n";
    Path temp = file.resolveSibling(FILE + ".tmp");
    try {
      if (Files.exists(file) && text.equals(Files.readString(file, StandardCharsets.UTF_8))) {
        return;
      }
      Files.createDirectories(file.getParent());
      Files.writeString(temp, text, StandardCharsets.UTF_8);
      try {
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException e) {
      throw new RunnerSettings.Invalid("cannot write " + file + ": " + e.getMessage());
    }
  }
}
