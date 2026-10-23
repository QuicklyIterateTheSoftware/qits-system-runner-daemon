package eu.wohlben.qits.systemrunner;

import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerBinary;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerProtocol;

/**
 * Who the target runner is: the one value it hands qits-runner-toolkit besides its domain. Every
 * label, container, volume and variable name the runner uses is derived from it — {@code
 * qits.system.runner=<id>}, {@code qits-system-runner-<id8>-<version>}, {@code
 * QITS_SYSTEM_RUNNER_URL}, … — and none is spelled anywhere else.
 *
 * <p>Built only from the protocol jar's constants, in {@link RunnerIdentity}'s order, so
 * qits-system-service, which builds the same value for the install script it renders, cannot name a
 * different runner than this one is. Its roots are the toolkit's {@code KnownRunnerNamespaces}
 * entry for {@code qits.system.runner}, which {@code SystemRunnerIdentityTest} holds.
 */
public final class SystemRunnerIdentity {

  /** The target runner's identity, at the version this tree was built as. */
  public static final RunnerIdentity IDENTITY =
      new RunnerIdentity(
          SystemRunnerProtocol.KIND,
          SystemRunnerProtocol.ENV_PREFIX,
          SystemRunnerProtocol.LABEL_ROOT,
          SystemRunnerProtocol.NAME_PREFIX,
          SystemRunnerProtocol.IMAGE_REPOSITORY,
          SystemRunnerProtocol.STATE_DIR,
          SystemRunnerProtocol.REGISTER_PATH,
          SystemRunnerProtocol.SERVICE_NAME,
          SystemRunnerBinary.VERSION,
          SystemRunnerProtocol.CAPABILITY_VERSION,
          SystemRunnerProtocol.VOCABULARY,
          SystemRunnerProtocol.WORK_LABEL_SUFFIX);

  private SystemRunnerIdentity() {}
}
