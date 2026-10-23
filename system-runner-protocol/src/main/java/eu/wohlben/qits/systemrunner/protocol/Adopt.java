package eu.wohlben.qits.systemrunner.protocol;

/**
 * A retired runner's container and volume a lease takes over (legacy adoption): before the lease
 * starts, the runner stops and removes {@code container} when present and mounts {@code volume} as
 * the work volume. Only names matching the retired runners' own patterns are accepted; anything
 * else fails the lease ({@link SystemRunnerProtocol#ADOPT_NOT_ALLOWED}).
 *
 * @param container the old container's name
 * @param volume the old work volume's name
 */
public record Adopt(String container, String volume) {}
