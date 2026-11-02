package eu.wohlben.qits.systemrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * The CI cache added by qits-1809 is mounted into every new CI lease, and leaves the CI mounts hash
 * as it was: a CI container still running when the runner is upgraded must read as current, not be
 * stopped and recreated mid-run.
 */
class TargetVolumesTest {

  private final TargetVolumes volumes =
      new TargetVolumes(SystemRunnerIdentity.IDENTITY, "r1", ExternalVolumes.NONE);

  @Test
  void theCiDaemonCacheIsACiCache() {
    assertEquals("qits-system-runner-ci-daemon-r1", volumes.ciDaemon());
    assertTrue(volumes.ciCaches().contains(volumes.ciDaemon()));
    assertEquals(volumes.ciDaemon(), volumes.mounts(null).ciDaemon());
  }

  @Test
  void theCiMountsHashIsTheOneBeforeTheCiDaemonCache() throws Exception {
    String before =
        String.join("\n", "ci", volumes.ciGit(), volumes.ciM2(), volumes.ciPnpm());
    byte[] digest =
        MessageDigest.getInstance("SHA-256").digest(before.getBytes(StandardCharsets.UTF_8));
    assertEquals(
        HexFormat.of().formatHex(digest, 0, 6), volumes.mountsHash(SlotPolicy.CI, null));
  }
}
