package eu.wohlben.qits.systemrunner;

import eu.wohlben.qits.containers.driver.docker.DockerArgv;
import eu.wohlben.qits.runner.toolkit.HousekeepingContext;
import eu.wohlben.qits.runner.toolkit.HousekeepingPass;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
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
 * belongs to is never looked at, and nothing is current until an {@code estate} has been taken
 * (qits-2513): the toolkit starts housekeeping right after the first {@code Ack}, before the first
 * estate, and until then the probe image alone would be the only "current" image, so a pass run in
 * that gap could remove other tags of its repository — the workspace image — that the coming
 * estate is about to keep warm or run.
 */
final class LeaseImagesPass implements HousekeepingPass {

  private final RunnerDocker docker;
  private final Supplier<Set<String>> currentImages;

  /**
   * @param currentImages the images in use now; empty until an estate has been taken (qits-2513),
   *     so a pass that runs between the {@code Ack} and the first estate removes nothing
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
      RunnerDocker.Result id =
          docker.run(DockerArgv.imageInspectFormat(docker.binary(), image, "{{.Id}}"));
      if (id.ok() && !id.stdout().isBlank()) {
        keepIds.add(id.stdout().strip());
      }
    }
    RunnerDocker.Result listed =
        docker.run(DockerArgv.imageLs(docker.binary(), "{{.Repository}}:{{.Tag}}|{{.ID}}"));
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
      RunnerDocker.Result users = docker.run(DockerArgv.psAncestor(docker.binary(), id));
      if (!users.ok()) {
        context.log().warnf("system-runner left %s: could not ask which containers use it", ref);
        return false;
      }
      if (!users.stdout().isBlank()) {
        return false;
      }
      RunnerDocker.Result gone = docker.run(DockerArgv.imageRmRefs(docker.binary(), List.of(ref)));
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
