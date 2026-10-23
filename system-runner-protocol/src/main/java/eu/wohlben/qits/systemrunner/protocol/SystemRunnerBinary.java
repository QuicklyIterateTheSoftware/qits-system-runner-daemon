package eu.wohlben.qits.systemrunner.protocol;

import eu.wohlben.qits.runner.protocol.RunnerBinaryVersion;

/**
 * <b>What this jar was released with</b>: the {@code qits-system-runner} version of the release
 * that published it — {@code DeskRunnerBinary}'s twin, read through qits-runner-protocol's {@link
 * RunnerBinaryVersion}.
 *
 * <p>A target runner is a container on its node, started from the image {@code <registry
 * host>/}{@value SystemRunnerProtocol#IMAGE_REPOSITORY}{@code :<version>} that this repository's
 * release pushes beside this jar. qits-system renders the install script from that reference and
 * names it in every {@code Upgrade} it sends a runner of another version, so the pin is a line in
 * its pom that its own release request gated — and the same release as the wire contract it
 * compiles against.
 *
 * <p>The value is this module's {@code ${project.version}}, filtered at build time into {@code
 * system-runner-binary.properties} beside this class; a missing, blank or unfiltered value is
 * refused loudly rather than defaulted.
 */
public final class SystemRunnerBinary {

  /** The released version: the tag of the image published beside this jar. */
  public static final String VERSION =
      RunnerBinaryVersion.read(SystemRunnerBinary.class, "system-runner-binary.properties");

  private SystemRunnerBinary() {}
}
