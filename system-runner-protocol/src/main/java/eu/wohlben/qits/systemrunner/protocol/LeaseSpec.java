package eu.wohlben.qits.systemrunner.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a lease's container is, as its owning service composes it and qits-system relays it.
 *
 * <p><b>The shape is the boundary.</b> This arrives over a socket and becomes an argv to a
 * root-equivalent daemon, so it can express only an image, an environment, a user and limits.
 * Mounts, networks, capabilities, init and restart are <b>not</b> here: they are the slot type's
 * policy, fixed in the runner. A capability this record cannot express is a change to it, reviewed
 * as one.
 *
 * <p>The environment keeps its arrival order (never {@code Map.copyOf}, whose iteration order is
 * salted per JVM), so a hash over the canonical spec is the same in every process. It carries the
 * lease's own token ({@code QITS_TOKEN}) and is never logged.
 *
 * @param image the image reference; must start with the slot type's {@code Ack.slotImages} entry
 * @param env the container's environment
 * @param user {@code --user}; the runner refuses none, blank and {@code 0}
 * @param limits the resource limits; never null on a decoded spec
 */
public record LeaseSpec(String image, Map<String, String> env, String user, Limits limits) {

  public LeaseSpec {
    if (image == null || image.isBlank()) {
      throw new IllegalArgumentException("a lease spec names no image");
    }
    env =
        env == null || env.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(env));
    limits = limits == null ? Limits.NONE : limits;
  }
}
