package eu.wohlben.qits.systemrunner.protocol;

/**
 * The node's memory facts in an {@link Inventory} (qits-1818), each null when the runner cannot
 * read it.
 *
 * @param nodeOomKills processes the OOM killer took in the node's cgroup tree: its root {@code
 *     memory.events}' {@code oom_kill}. Inside an LXC that is the container's own counter; on a bare
 *     host the root cgroup has no {@code memory.events}, and this is null
 * @param kernelOomKills the kernel's own count ({@code /proc/vmstat}'s {@code oom_kill}): every OOM
 *     kill on the physical host, not only this node's
 * @param memoryMaxBytes the node's memory limit (its root {@code memory.max}), null when there is
 *     none ({@code max}) or it is unreadable
 */
public record NodeMemory(Long nodeOomKills, Long kernelOomKills, Long memoryMaxBytes) {}
