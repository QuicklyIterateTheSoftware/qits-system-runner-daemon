package eu.wohlben.qits.systemrunner;

import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.RunnerSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The build plane's own settings, parsed by {@link Main} beside {@link ExternalVolumes} from the
 * same environment — qits-ci-runner-daemon's {@code RunnerEnv} buildkit handling, with the target's
 * prefix:
 *
 * <ul>
 *   <li>{@code QITS_SYSTEM_RUNNER_BUILDKIT_IMAGE}: the builder's image, {@value #DEFAULT_IMAGE} when
 *       unset — the same pin qits-containers' {@code qits.containers.buildkit.image} and the {@code
 *       ci-slot} image's {@code buildctl} carry;
 *   <li>{@code …_BUILDKIT_MAX_PARALLELISM}: how many build operations the builder runs at once,
 *       {@code null} when unset. There is no fixed default: buildkit v0.33.0's {@code
 *       max-parallelism} is one FIFO semaphore for the whole daemon, held by every {@code
 *       ExecOp}/{@code FileOp}/{@code SourceOp} for its whole duration and shared across every
 *       client session, so a small fixed number turns into cross-lease serialisation rather than a
 *       per-build bound. Unset, {@link BuildPlane} renders the runner's slot total instead — the
 *       most builds the target can have in flight, since each CI lease runs at most one build at a
 *       time ({@link BuildPlane#WORKER_TOML}). The variable stays as the per-target override for a
 *       host that cannot hold that many native builds at once: a {@code RUN} executes in the
 *       builder's own cgroup, not the lease's, so the lease's memory limit never bounds it;
 *   <li>{@code …_BUILDKIT_HTTP_REGISTRIES}: a comma list of {@code host[:port]} buildkitd speaks plain
 *       HTTP to;
 *   <li>{@code …_BUILDKIT_REGISTRY_MIRRORS}: a comma list of {@code from=to}, {@code to} may carry a
 *       path prefix. The {@code Ack}'s {@code registryMirrors} are merged over these, per source host
 *       ({@link BuildPlane#withRegistryMirrors}).
 * </ul>
 *
 * <p>Every registry is checked against its charset here because it is rendered inside a TOML string:
 * a quote or a newline in it would be configuration nobody wrote. An invalid value is a refusal to
 * start ({@code RunnerExit.MISCONFIGURED}) naming the variable.
 *
 * <p>There is no state-volume override, unlike the CI runner's {@code
 * QITS_CI_RUNNER_BUILDKIT_STATE_VOLUME}: the state volume is always the derived {@code
 * qits-system-runner-buildkitd-state-<id8>}, so no setting can point this runner at {@code
 * qits-buildkitd-state}.
 */
record BuildkitSettings(
    String image,
    Integer maxParallelism,
    List<String> httpRegistries,
    List<String> registryMirrors) {

  static final String BUILDKIT_IMAGE = "BUILDKIT_IMAGE";
  static final String BUILDKIT_MAX_PARALLELISM = "BUILDKIT_MAX_PARALLELISM";
  static final String BUILDKIT_HTTP_REGISTRIES = "BUILDKIT_HTTP_REGISTRIES";
  static final String BUILDKIT_REGISTRY_MIRRORS = "BUILDKIT_REGISTRY_MIRRORS";

  /** The builder image when none is named. Bump it with qits-containers' and the ci-slot image's. */
  static final String DEFAULT_IMAGE = "moby/buildkit:v0.33.0";

  /** A registry as buildkitd.toml names it: {@code host[:port]}. */
  static final Pattern REGISTRY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.-]{0,252}(:[0-9]{1,5})?");

  /** A mirror target may carry a path prefix ({@code mirror:8080/hub}). */
  static final Pattern MIRROR_TARGET =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9.-]{0,252}(:[0-9]{1,5})?(/[A-Za-z0-9._-]+)*");

  private static final Pattern IMAGE = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._:/@+-]{0,511}");

  /**
   * Nothing named: the default image, no explicit parallelism — derived from the runner's slot
   * total at render time — and no registry configuration.
   */
  static final BuildkitSettings DEFAULTS =
      new BuildkitSettings(DEFAULT_IMAGE, null, List.of(), List.of());

  BuildkitSettings {
    httpRegistries = List.copyOf(httpRegistries);
    registryMirrors = List.copyOf(registryMirrors);
  }

  /**
   * The four out of {@code env}; {@code null} and blank both mean unset.
   *
   * @throws RunnerSettings.Invalid naming the variable when a value is malformed
   */
  static BuildkitSettings parse(RunnerIdentity identity, Function<String, String> env)
      throws RunnerSettings.Invalid {
    String imageVar = identity.env(BUILDKIT_IMAGE);
    String image = env.apply(imageVar);
    if (blank(image)) {
      image = DEFAULT_IMAGE;
    } else {
      image = image.strip();
      if (!IMAGE.matcher(image).matches()) {
        throw new RunnerSettings.Invalid(imageVar + " is not an image reference: '" + image + "'");
      }
    }
    String parallelismVar = identity.env(BUILDKIT_MAX_PARALLELISM);
    String rawParallelism = env.apply(parallelismVar);
    Integer parallelism = null;
    if (!blank(rawParallelism)) {
      int parsed;
      try {
        parsed = Integer.parseInt(rawParallelism.strip());
      } catch (NumberFormatException e) {
        parsed = 0;
      }
      if (parsed < 1) {
        throw new RunnerSettings.Invalid(
            parallelismVar + " is not a positive integer: '" + rawParallelism.strip() + "'");
      }
      parallelism = parsed;
    }
    return new BuildkitSettings(
        image,
        parallelism,
        httpRegistries(identity.env(BUILDKIT_HTTP_REGISTRIES), env),
        mirrors(identity.env(BUILDKIT_REGISTRY_MIRRORS), env));
  }

  private static List<String> httpRegistries(String variable, Function<String, String> env)
      throws RunnerSettings.Invalid {
    List<String> registries = new ArrayList<>();
    for (String item : items(env.apply(variable))) {
      if (!REGISTRY.matcher(item).matches()) {
        throw new RunnerSettings.Invalid(
            variable + " has an entry that is not host[:port]: '" + item + "'");
      }
      registries.add(item);
    }
    return registries;
  }

  private static List<String> mirrors(String variable, Function<String, String> env)
      throws RunnerSettings.Invalid {
    List<String> mirrors = new ArrayList<>();
    for (String item : items(env.apply(variable))) {
      if (!mirror(item)) {
        throw new RunnerSettings.Invalid(
            variable + " has an entry that is not from=to: '" + item + "'");
      }
      mirrors.add(item);
    }
    return mirrors;
  }

  /** Whether {@code pair} is {@code from=to} with both sides in their charsets. */
  static boolean mirror(String pair) {
    int split = pair.indexOf('=');
    return split > 0
        && REGISTRY.matcher(pair.substring(0, split)).matches()
        && MIRROR_TARGET.matcher(pair.substring(split + 1)).matches();
  }

  private static List<String> items(String value) {
    List<String> items = new ArrayList<>();
    if (blank(value)) {
      return items;
    }
    for (String item : value.split(",")) {
      if (!item.isBlank()) {
        items.add(item.strip());
      }
    }
    return items;
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
