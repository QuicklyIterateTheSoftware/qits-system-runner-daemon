package eu.wohlben.qits.systemrunner.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The pin resolves — the guard on this module's resource-filtering block, which is the one thing
 * about {@link SystemRunnerBinary} that can silently break — and the identity is the dossier's. It
 * asserts the version's shape and not a value: the version is whatever stamped this tree, a CalVer
 * after a release and a {@code -SNAPSHOT} before the first one.
 */
class SystemRunnerBinaryTest {

  @Test
  void theVersionIsFilteredInAndIsACalVerOrASnapshot() {
    String version = SystemRunnerBinary.VERSION;
    assertFalse(version.isBlank(), "the version is blank");
    assertFalse(
        version.startsWith("$"),
        () -> "an unfiltered ${project.version} reached the jar: " + version);
    assertTrue(
        version.matches("[0-9][0-9.]*[0-9](-SNAPSHOT)?"),
        () -> "not a CalVer — is resource filtering still on? got: " + version);
  }

  @Test
  void theNamesAreTheOnesThePlatformAddressesTheRunnerBy() {
    // Literals, because each is a cross-repository contract: release.yml publishes the image under
    // this repository, qits-system serves the socket and the register door at these paths, and
    // every label and name on a node is derived from these roots.
    assertEquals("targets", SystemRunnerProtocol.KIND);
    assertEquals("QITS_SYSTEM_RUNNER_", SystemRunnerProtocol.ENV_PREFIX);
    assertEquals("qits.system.runner", SystemRunnerProtocol.LABEL_ROOT);
    assertEquals("qits-system-runner", SystemRunnerProtocol.NAME_PREFIX);
    assertEquals("qits/qits-system-runner", SystemRunnerProtocol.IMAGE_REPOSITORY);
    assertEquals("/var/lib/qits-system-runner", SystemRunnerProtocol.STATE_DIR);
    assertEquals("/system/api/targets/{id}/register", SystemRunnerProtocol.REGISTER_PATH);
    assertEquals("/system/targets/socket", SystemRunnerProtocol.SOCKET_PATH);
    assertEquals("qits-system-runner", SystemRunnerProtocol.SERVICE_NAME);
    assertEquals("lease", SystemRunnerProtocol.WORK_LABEL_SUFFIX);
    assertEquals(1, SystemRunnerProtocol.CAPABILITY_VERSION);
    assertEquals("heldLeases", SystemRunnerProtocol.VOCABULARY.heldField());
    assertEquals("adoptedLeases", SystemRunnerProtocol.VOCABULARY.adoptedField());
    assertEquals(
        "ci slots are not enabled on this runner version", SystemRunnerProtocol.CI_NOT_ENABLED);
    assertEquals("adopt name not allowed", SystemRunnerProtocol.ADOPT_NOT_ALLOWED);
  }
}
