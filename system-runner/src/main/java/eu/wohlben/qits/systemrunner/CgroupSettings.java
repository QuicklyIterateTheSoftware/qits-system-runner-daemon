package eu.wohlben.qits.systemrunner;

import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * Where the runner reads the node's memory facts from (qits-1818), parsed by {@link Main} beside
 * {@link ExternalVolumes} from the same environment:
 *
 * <ul>
 *   <li>{@code QITS_SYSTEM_RUNNER_HOST_CGROUP}: the node's cgroup v2 tree, bound read-only into the
 *       runner's container by its install line, {@value #DEFAULT_HOST_CGROUP} when unset. Its root
 *       {@code memory.events} is the node's OOM counter inside an LXC; the lease containers'
 *       cgroups are below it. Not there — an install line from before the bind — and every
 *       cgroup-derived fact is null;
 *   <li>{@code QITS_SYSTEM_RUNNER_VMSTAT}: the kernel's counters, {@value #DEFAULT_VMSTAT} when
 *       unset. Not namespaced, so any container reads the host kernel's {@code oom_kill}.
 * </ul>
 *
 * <p>Neither is validated: a path that is not there is the same answer as no tree, never a refusal
 * to start.
 */
record CgroupSettings(Path hostCgroup, Path vmstat) {

  static final String HOST_CGROUP = "HOST_CGROUP";
  static final String VMSTAT = "VMSTAT";

  static final String DEFAULT_HOST_CGROUP = "/qits/host-cgroup";
  static final String DEFAULT_VMSTAT = "/proc/vmstat";

  static final CgroupSettings DEFAULTS =
      new CgroupSettings(Path.of(DEFAULT_HOST_CGROUP), Path.of(DEFAULT_VMSTAT));

  /** The two out of {@code env}; {@code null} and blank both mean the default. */
  static CgroupSettings parse(RunnerIdentity identity, Function<String, String> env) {
    return new CgroupSettings(
        path(env.apply(identity.env(HOST_CGROUP)), DEFAULT_HOST_CGROUP),
        path(env.apply(identity.env(VMSTAT)), DEFAULT_VMSTAT));
  }

  private static Path path(String raw, String fallback) {
    return Path.of(raw == null || raw.isBlank() ? fallback : raw.strip());
  }
}
