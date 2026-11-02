package eu.wohlben.qits.systemrunner;

import eu.wohlben.qits.systemrunner.protocol.NodeMemory;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The node's and every lease container's memory facts, sampled before each {@code inventory} is
 * built (qits-1818) — so on the 15 s look as well as at the {@code Ack} and around a convergence —
 * out of the node's cgroup tree the runner container sees read-only ({@link CgroupSettings}).
 *
 * <p><b>Where a lease's cgroup is.</b> Docker's cgroup driver says it: {@code
 * system.slice/docker-<fullId>.scope} under {@code systemd}, {@code docker/<fullId>} under {@code
 * cgroupfs}. When the driver is unknown, or the directory is not where it says, the tree is searched
 * at most {@value #SEARCH_DEPTH} levels deep for a directory whose name holds the full container
 * id. The answer is kept per lease while it is still there.
 *
 * <p><b>A stopped lease keeps its last sample.</b> Its cgroup goes with its container's processes,
 * so the {@code EXITED} report carries what was read while it ran — the OOM kill that ended it
 * included, when the look caught it. A lease whose container was recreated (another id) starts
 * again from nothing; a lease docker no longer lists is forgotten.
 *
 * <p><b>The peak is coarse on purpose.</b> {@code memory.peak} grows by the page, and an inventory
 * is sent on every change: the reported peak moves only when the read one exceeds it by {@link
 * #PEAK_STEP} or more, so a growing lease costs one frame per 64 MiB, not one per look. The OOM
 * counters are reported exactly — a change in one is the point.
 *
 * <p>Nothing here throws: no tree, no file, no permission all read as null.
 */
final class MemorySampler {

  /** The reported peak moves only by this much or more. */
  static final long PEAK_STEP = 64L << 20;

  /** How deep below the root the fallback search looks for a lease's cgroup. */
  static final int SEARCH_DEPTH = 3;

  /** What an inventory reports of one lease; every field may be null. */
  record LeaseMemory(Long oomKills, Long oomEvents, Long memoryPeakBytes) {

    static final LeaseMemory NONE = new LeaseMemory(null, null, null);
  }

  /** The last sample of one lease's container: its id, where its cgroup was, and what it read. */
  private record Sample(String containerId, Path dir, LeaseMemory memory) {}

  private final Path root;
  private final Path vmstat;
  private final Map<String, Sample> samples = new HashMap<>();
  private String driver;
  private NodeMemory node;

  MemorySampler(CgroupSettings settings) {
    this.root = settings.hostCgroup();
    this.vmstat = settings.vmstat();
  }

  /** Docker's cgroup driver ({@code systemd}, {@code cgroupfs}), or null when docker did not say. */
  synchronized void driver(String driver) {
    this.driver = driver == null || driver.isBlank() ? null : driver.strip();
  }

  /**
   * Read the node's facts and every running lease container's; keep the last sample of a stopped
   * one, forget a lease that is no longer listed.
   */
  synchronized void sample(List<LeaseContainers.Seen> now) {
    node = readNode();
    Set<String> listed = new HashSet<>();
    for (LeaseContainers.Seen seen : now) {
      listed.add(seen.leaseId());
      Sample last = samples.get(seen.leaseId());
      if (last != null && !last.containerId().equals(seen.id())) {
        samples.remove(seen.leaseId());
        last = null;
      }
      if (!seen.running()) {
        continue;
      }
      Path dir = last != null && isDirectory(last.dir()) ? last.dir() : resolve(seen.id());
      if (dir == null) {
        continue;
      }
      CgroupMemory memory = new CgroupMemory(dir);
      CgroupMemory.Events events = memory.events();
      Long peak = memory.peakBytes();
      if (events == null && peak == null) {
        // The container stopped between the listing and the read: keep what the last look saw.
        continue;
      }
      LeaseMemory before = last == null ? LeaseMemory.NONE : last.memory();
      samples.put(
          seen.leaseId(),
          new Sample(
              seen.id(),
              dir,
              new LeaseMemory(
                  events == null || events.oomKill() == null ? before.oomKills() : events.oomKill(),
                  events == null || events.oom() == null ? before.oomEvents() : events.oom(),
                  coarse(before.memoryPeakBytes(), peak))));
    }
    samples.keySet().retainAll(listed);
  }

  /** The last sample of {@code leaseId}, or {@link LeaseMemory#NONE}. */
  synchronized LeaseMemory lease(String leaseId) {
    Sample sample = samples.get(leaseId);
    return sample == null ? LeaseMemory.NONE : sample.memory();
  }

  /** The node's facts at the last sample, or null when none could be read. */
  synchronized NodeMemory node() {
    return node;
  }

  /** The peak to report: the read one only when it is the first or grew by {@link #PEAK_STEP}. */
  static Long coarse(Long reported, Long read) {
    if (read == null) {
      return reported;
    }
    if (reported == null || read - reported >= PEAK_STEP) {
      return read;
    }
    return reported;
  }

  private NodeMemory readNode() {
    Long nodeOomKills = null;
    Long memoryMax = null;
    if (isDirectory(root)) {
      CgroupMemory rootMemory = new CgroupMemory(root);
      CgroupMemory.Events events = rootMemory.events();
      nodeOomKills = events == null ? null : events.oomKill();
      memoryMax = rootMemory.maxBytes();
    }
    Long kernel = CgroupMemory.vmstatOomKills(vmstat);
    if (nodeOomKills == null && kernel == null && memoryMax == null) {
      return null;
    }
    return new NodeMemory(nodeOomKills, kernel, memoryMax);
  }

  /** Where the container's cgroup is: the driver's path, else a bounded search; null when absent. */
  Path resolve(String containerId) {
    if (containerId == null
        || containerId.isBlank()
        || containerId.contains("/")
        || containerId.contains("..")
        || !isDirectory(root)) {
      return null;
    }
    List<Path> candidates = new ArrayList<>();
    if (driver == null || "systemd".equals(driver)) {
      candidates.add(root.resolve("system.slice").resolve("docker-" + containerId + ".scope"));
    }
    if (driver == null || "cgroupfs".equals(driver)) {
      candidates.add(root.resolve("docker").resolve(containerId));
    }
    for (Path candidate : candidates) {
      if (isDirectory(candidate)) {
        return candidate;
      }
    }
    return search(root, containerId, SEARCH_DEPTH);
  }

  /** Breadth first, at most {@code depth} levels below {@code from}; an unreadable level is skipped. */
  private static Path search(Path from, String containerId, int depth) {
    List<Path> level = List.of(from);
    for (int d = 0; d < depth && !level.isEmpty(); d++) {
      List<Path> next = new ArrayList<>();
      for (Path dir : level) {
        try (DirectoryStream<Path> children = Files.newDirectoryStream(dir)) {
          for (Path child : children) {
            if (!isDirectory(child)) {
              continue;
            }
            if (child.getFileName().toString().contains(containerId)) {
              return child;
            }
            next.add(child);
          }
        } catch (IOException | RuntimeException unreadable) {
          // A directory the runner may not list is no answer, never a failure.
        }
      }
      level = next;
    }
    return null;
  }

  private static boolean isDirectory(Path path) {
    try {
      return path != null && Files.isDirectory(path);
    } catch (RuntimeException e) {
      return false;
    }
  }
}
