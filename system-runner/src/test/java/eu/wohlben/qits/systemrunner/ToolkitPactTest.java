package eu.wohlben.qits.systemrunner;

import eu.wohlben.qits.pact.consumer.GoldenMasters;
import eu.wohlben.qits.runner.toolkit.contracts.RunnerToolkitPact;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * The REST calls qits-runner-toolkit makes on this daemon's behalf, pacted as this daemon's own
 * consumer pacts (qits-1163): the token mint at qits-idp, the log export to qits-observability and
 * the register door at qits-system-service, each through the real toolkit classes. Writes {@code
 * pacts/qits-system-runner-daemon_qits-runner-javalib_<provider>.json} under {@code
 * -Dgolden.update=true}; a provider whose states are not recorded yet is skipped, naming them —
 * qits-system-service's register door ({@link #SYSTEM}, {@code registerTarget}) until its golden
 * masters are published and pinned here.
 */
class ToolkitPactTest {

  /** The register host: qits-system-service, application qits-system. */
  static final GoldenMasters SYSTEM = GoldenMasters.of("qits-system-service", "qits-system");

  @TestFactory
  Stream<DynamicTest> theToolkitsCalls() {
    return RunnerToolkitPact.forConsumer("qits-system-runner-daemon")
        .register(SYSTEM, "registerTarget", SystemRunnerIdentity.IDENTITY)
        .token()
        .logExport()
        .tests();
  }
}
