package eu.wohlben.qits.systemrunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * How many uids and gids this process's user namespace maps — the {@code idRange} capability,
 * qits-ci-runner-daemon's {@code IdRange} reading. Read from this process's own {@code uid_map}
 * and {@code gid_map}, because the runner runs in its docker daemon's user namespace: each line is
 * {@code inside outside count}, the range is the sum of the counts, and the smaller of the two
 * maps. {@code null} when a map cannot be read or parsed — not a guess: absence tells qits-system
 * "unknown", and a wrong number would mislead placement.
 */
final class IdRange {

  private IdRange() {}

  static Long read(Path procSelf) {
    if (procSelf == null) {
      return null;
    }
    Long uids = total(procSelf.resolve("uid_map"));
    Long gids = total(procSelf.resolve("gid_map"));
    return uids == null || gids == null ? null : Math.min(uids, gids);
  }

  private static Long total(Path map) {
    String text;
    try {
      text = Files.readString(map);
    } catch (IOException | RuntimeException e) {
      return null;
    }
    long total = 0;
    boolean any = false;
    for (String line : text.split("\n")) {
      String trimmed = line.strip();
      if (trimmed.isEmpty()) {
        continue;
      }
      String[] fields = trimmed.split("\\s+");
      if (fields.length != 3) {
        return null;
      }
      try {
        total += Long.parseLong(fields[2]);
      } catch (NumberFormatException e) {
        return null;
      }
      any = true;
    }
    return any ? total : null;
  }
}
