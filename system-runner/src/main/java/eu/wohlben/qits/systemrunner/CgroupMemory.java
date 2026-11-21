package eu.wohlben.qits.systemrunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The memory facts one cgroup v2 directory tells (qits-1818): {@code memory.events}' {@code oom}
 * (this cgroup's own limit was hit) and {@code oom_kill} (processes the OOM killer took in it or
 * below it), {@code memory.peak} (kernel 5.19 and newer) and {@code memory.max} — and the kernel's
 * own {@code oom_kill} out of {@code /proc/vmstat} and {@code MemTotal} out of {@code
 * /proc/meminfo} (qits-2362). qits-workspace-daemon's {@code CgroupMemory}, pointed at any
 * directory.
 *
 * <p><b>Every read is tolerant of the file not being there.</b> A cgroup v1 host, a bare host's root
 * cgroup (no {@code memory.events}), a lease whose container stopped (its cgroup is gone), an
 * unreadable file and a malformed value all answer null, never an exception.
 */
final class CgroupMemory {

  /** The two {@code memory.events} counters the inventory reports; either may be null. */
  record Events(Long oom, Long oomKill) {}

  private final Path dir;

  CgroupMemory(Path dir) {
    this.dir = dir;
  }

  /** {@code memory.events}, or null when it cannot be read. */
  Events events() {
    String events = read(dir.resolve("memory.events"));
    if (events == null) {
      return null;
    }
    return new Events(counter(events, "oom"), counter(events, "oom_kill"));
  }

  /** {@code memory.peak} in bytes, or null. */
  Long peakBytes() {
    String peak = read(dir.resolve("memory.peak"));
    return peak == null ? null : parse(peak.strip());
  }

  /** {@code memory.max} in bytes, or null when there is none ({@code max}) or it is unreadable. */
  Long maxBytes() {
    String max = read(dir.resolve("memory.max"));
    if (max == null || "max".equals(max.strip())) {
      return null;
    }
    return parse(max.strip());
  }

  /** {@code oom_kill} out of a {@code /proc/vmstat}-shaped file, or null. */
  static Long vmstatOomKills(Path vmstat) {
    String text = read(vmstat);
    return text == null ? null : counter(text, "oom_kill");
  }

  /**
   * {@code MemTotal} out of a {@code /proc/meminfo}-shaped file, in bytes (its kB value × 1024), or
   * null when it cannot be read (qits-2362): a root cgroup that is {@code max} — localhost, a
   * workstation — leaves {@link #maxBytes} null too, and without MemTotal placement has nothing
   * left to count the node's memory against at all.
   */
  static Long memTotalBytes(Path meminfo) {
    String text = read(meminfo);
    if (text == null) {
      return null;
    }
    for (String line : text.split("\n")) {
      String[] parts = line.strip().split("\\s+");
      if (parts.length >= 2 && "MemTotal:".equals(parts[0])) {
        Long kb = parse(parts[1]);
        return kb == null ? null : kb * 1024;
      }
    }
    return null;
  }

  /** The value of the {@code <key> <n>} line, or null when there is none. */
  static Long counter(String text, String key) {
    for (String line : text.split("\n")) {
      String[] parts = line.strip().split("\\s+");
      if (parts.length == 2 && key.equals(parts[0])) {
        return parse(parts[1]);
      }
    }
    return null;
  }

  private static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  private static Long parse(String value) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
