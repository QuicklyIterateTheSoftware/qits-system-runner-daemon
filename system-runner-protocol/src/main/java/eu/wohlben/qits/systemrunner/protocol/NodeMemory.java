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
 * @param memoryTotalBytes the node's physical memory ({@code /proc/meminfo}'s {@code MemTotal}),
 *     null when it is unreadable (qits-2362): a root cgroup that is {@code max} — localhost, a
 *     workstation — leaves {@code memoryMaxBytes} null too, and without this placement has nothing
 *     left to size the node by at all. Appended last so the wire stays additive: an old reader
 *     ignores it, and a message encoded before it existed decodes it as null.
 * @param builderMemoryBytes the memory cap of the target's shared buildkitd (qits-2503), which the
 *     runner derives as {@code …_BUILDER_CONCURRENT_BUILDS} × 6 GiB and starts the builder under
 *     ({@code --memory}): the image builds a CI lease sends there run in the builder's cgroup, not
 *     the lease's, so placement has to count this beside the leases. Null from a runner without a
 *     build plane or older than qits-2503. Appended last, additive on the wire like {@code
 *     memoryTotalBytes}.
 */
public record NodeMemory(
    Long nodeOomKills,
    Long kernelOomKills,
    Long memoryMaxBytes,
    Long memoryTotalBytes,
    Long builderMemoryBytes) {}
