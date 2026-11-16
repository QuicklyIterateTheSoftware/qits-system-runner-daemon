package eu.wohlben.qits.systemrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import eu.wohlben.qits.containers.driver.docker.DockerArgv;
import eu.wohlben.qits.containers.driver.spec.ContainerSpec;
import eu.wohlben.qits.containers.driver.spec.LabelNamespace;
import eu.wohlben.qits.containers.driver.spec.LifecyclePolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * qits-2270: a lease is made with {@code docker create}, from the driver's own run argv, so it can
 * join every network before it starts. Since qits-2289 that create is the driver's own builder
 * ({@link DockerArgv#create}, which {@link LeaseLauncher} calls), held here to the list the
 * runner's rewrite of {@link DockerArgv#run} produced.
 */
class LeaseLauncherCreateArgvTest {

  private static final String NAMESPACE = SystemRunnerIdentity.IDENTITY.driverNamespace();
  private static final String NAME = "qits-system-runner-lease-l1-r1";

  @Test
  void theCreateArgvIsTheDriversRunArgvWithCreateForRunMinusDetach() {
    ContainerSpec spec =
        ContainerSpec.builder("registry.example/ci-slot:1")
            .network("bridge")
            .dns("100.96.0.1", "10.255.255.254")
            .init(true)
            .env("TZ", "UTC")
            .build();
    Map<String, String> labels = Map.of(NAMESPACE + "spec-hash", "h1");
    LifecyclePolicy lifecycle = LifecyclePolicy.explicitLifetime();

    List<String> create =
        DockerArgv.create(
            "docker", new LabelNamespace(NAMESPACE), NAME, spec, labels, lifecycle);

    List<String> run =
        new ArrayList<>(
            DockerArgv.run("docker", new LabelNamespace(NAMESPACE), NAME, spec, labels, lifecycle));
    assertEquals(List.of("docker", "run", "-d"), run.subList(0, 3));
    run.set(1, "create");
    run.remove(2);
    assertEquals(run, create);
    assertFalse(create.contains("-d"), create::toString);
    assertEquals(List.of("docker", "create", "--init", "--name", NAME), create.subList(0, 5));
  }
}
