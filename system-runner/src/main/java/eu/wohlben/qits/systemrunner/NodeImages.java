package eu.wohlben.qits.systemrunner;

import eu.wohlben.qits.runner.toolkit.LifecycleArgv;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import java.util.Optional;
import java.util.function.Function;

/**
 * An image on this node, pulled under the runner's own registry login ({@code
 * RunnerSession#registry()}: a throwaway {@code --config} holding the runner's client, never the
 * host's docker config) when it is not here yet — the step before a lease run and a login probe.
 * Never a pull of an image that is already here: a pinned image does not move under its tag, and a
 * registry blip must not fail a start the node could make from what it has. A copy of
 * qits-projects-desk-runner-daemon's {@code NodeImages}, with the pull handed in.
 */
final class NodeImages {

  private NodeImages() {}

  /**
   * Empty when {@code image} is here (already, or pulled now); else why not.
   *
   * @param pull the runner's pull: empty on success, else docker's words
   */
  static Optional<String> ensure(
      RunnerDocker docker, Function<String, Optional<String>> pull, String image) {
    if (present(docker, image)) {
      return Optional.empty();
    }
    return pull.apply(image);
  }

  /** Whether {@code image} is on this node; a reference the belt refuses is not. */
  static boolean present(RunnerDocker docker, String image) {
    try {
      return docker.run(LifecycleArgv.imageInspect(docker.binary(), image)).ok();
    } catch (IllegalArgumentException refused) {
      return false;
    }
  }
}
