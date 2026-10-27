package eu.wohlben.qits.systemrunner;

import eu.wohlben.qits.runner.toolkit.HousekeepingContext;
import eu.wohlben.qits.runner.toolkit.HousekeepingPass;
import eu.wohlben.qits.runner.toolkit.LifecycleArgv;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Lease images this node no longer needs: every tag of a current image's repository other than
 * the current images themselves — the latest {@code estate}'s spec images and {@code warmImages},
 * and the {@code Ack}'s {@code probeImage} — that no container, running or stopped, this runner's
 * or anybody's, was created from. Every workspace and project-agent release leaves one behind on
 * every node, and nothing else removes it. qits-projects-desk-runner-daemon's {@code DeskImagesPass}, keeping a
 * set rather than one image.
 *
 * <p>The three belts of the toolkit's {@code RunnerImages}, for the same reasons: a current image is
 * skipped by reference and by id; an image some container uses is skipped by {@code docker ps -a
 * --filter ancestor=}; and {@code docker image rm} without {@code -f} refuses one a container uses
 * anyway. Never {@code system prune}, never {@code image prune -a}. A repository no current image
 * belongs to is never looked at, so before the first {@code estate} and {@code Ack} the pass removes
 * nothing.
 */
final class LeaseImagesPass implements HousekeepingPass {

  private final RunnerDocker docker;
  private final Supplier<Set<String>> currentImages;

  /**
   * @param currentImages the images in use now; empty before an estate and an Ack named any
   */
  LeaseImagesPass(RunnerDocker docker, Supplier<Set<String>> currentImages) {
    this.docker = docker;
    this.currentImages = currentImages;
  }

  @Override
  public String name() {
    return "lease-images";
  }

  @Override
  public void run(HousekeepingContext context) {
    Set<String> current = new LinkedHashSet<>();
    currentImages.get().stream().filter(i -> i != null && !i.isBlank()).forEach(current::add);
    if (current.isEmpty()) {
      return;
    }
    Set<String> repositories = new HashSet<>();
    Set<String> keepIds = new HashSet<>();
    for (String image : current) {
      repositories.add(repository(image));
      RunnerDocker.Result id = docker.run(LifecycleArgv.imageInspect(docker.binary(), image));
      if (id.ok() && !id.stdout().isBlank()) {
        keepIds.add(id.stdout().strip());
      }
    }
    RunnerDocker.Result listed = docker.run(LifecycleArgv.imageList(docker.binary()));
    if (!listed.ok() || listed.stdoutTruncated()) {
      context.log().warnf("system-runner could not list the node's images: %s", listed.detail());
      return;
    }
    int removed = 0;
    for (String line : listed.stdout().split("\\R")) {
      String[] fields = line.strip().split("\\|", -1);
      if (fields.length < 2) {
        continue;
      }
      String ref = fields[0].strip();
      String id = fields[1].strip();
      int colon = ref.lastIndexOf(':');
      if (colon <= 0
          || !repositories.contains(ref.substring(0, colon))
          || ref.endsWith(":<none>")) {
        continue;
      }
      if (current.contains(ref) || keepIds.stream().anyMatch(keep -> sameImage(id, keep))) {
        continue;
      }
      if (remove(context, ref, id)) {
        removed++;
      }
    }
    if (removed > 0) {
      context.log().infof("system-runner removed %d old lease image(s)", removed);
    }
  }

  private boolean remove(HousekeepingContext context, String ref, String id) {
    try {
      RunnerDocker.Result users = docker.run(LifecycleArgv.psAncestor(docker.binary(), id));
      if (!users.ok()) {
        context.log().warnf("system-runner left %s: could not ask which containers use it", ref);
        return false;
      }
      if (!users.stdout().isBlank()) {
        return false;
      }
      RunnerDocker.Result gone = docker.run(LifecycleArgv.imageRm(docker.binary(), ref));
      if (!gone.ok()) {
        context.log().warnf("system-runner could not remove %s: %s", ref, gone.detail());
        return false;
      }
      context.log().infof("system-runner removed %s (an old lease image)", ref);
      return true;
    } catch (IllegalArgumentException notAnImage) {
      context.log().warnf("system-runner ignored an image reference: %s", notAnImage.getMessage());
      return false;
    }
  }

  /** {@code registry.qits.<d>/qits/workspace} of {@code registry.qits.<d>/qits/workspace:<tag>}. */
  static String repository(String image) {
    int at = image.indexOf('@');
    String named = at >= 0 ? image.substring(0, at) : image;
    int colon = named.lastIndexOf(':');
    int slash = named.lastIndexOf('/');
    return colon > slash ? named.substring(0, colon) : named;
  }

  /** Two image ids, either possibly without its {@code sha256:} prefix or truncated. */
  static boolean sameImage(String a, String b) {
    String x = a.startsWith("sha256:") ? a.substring(7) : a;
    String y = b.startsWith("sha256:") ? b.substring(7) : b;
    return !x.isEmpty() && !y.isEmpty() && (x.startsWith(y) || y.startsWith(x));
  }
}
