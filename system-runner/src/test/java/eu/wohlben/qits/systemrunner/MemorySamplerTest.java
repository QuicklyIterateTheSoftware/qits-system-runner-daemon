package eu.wohlben.qits.systemrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.systemrunner.protocol.NodeMemory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The memory facts over a fake cgroup tree in a temp dir (qits-1818): the node's root counters in
 * an LXC and on a bare host, no tree at all, the kernel's vmstat, a lease's cgroup under either
 * docker cgroup driver and by the fallback search, the coarse peak, and the last sample an exited
 * lease keeps.
 */
class MemorySamplerTest {

  private static final String ID1 = "aaaa1111bbbb2222cccc3333dddd4444eeee5555ffff6666aaaa7777bbbb8888";
  private static final String ID2 = "1234123412341234123412341234123412341234123412341234123412341234";
  private static final long MIB = 1L << 20;

  @TempDir Path tmp;

  private Path root;
  private Path vmstat;

  @BeforeEach
  void tree() throws IOException {
    root = Files.createDirectories(tmp.resolve("host-cgroup"));
    vmstat = tmp.resolve("vmstat");
    Files.writeString(vmstat, "nr_free_pages 12345\noom_kill 297\nnr_foo 1\n");
  }

  // ---- the reader --------------------------------------------------------------------------------

  @Test
  void theReaderReadsTheCountersPeakAndLimitAndAnswersNullForAnythingElse() throws IOException {
    Path dir = Files.createDirectories(tmp.resolve("lease"));
    CgroupMemory memory = new CgroupMemory(dir);
    assertNull(memory.events());
    assertNull(memory.peakBytes());
    assertNull(memory.maxBytes());

    events(dir, 3, 2);
    Files.writeString(dir.resolve("memory.peak"), "4294967296\n");
    Files.writeString(dir.resolve("memory.max"), "max\n");
    assertEquals(new CgroupMemory.Events(3L, 2L), memory.events());
    assertEquals(4L << 30, memory.peakBytes());
    assertNull(memory.maxBytes(), "no limit is no number");

    Files.writeString(dir.resolve("memory.max"), "8589934592\n");
    Files.writeString(dir.resolve("memory.peak"), "garbage\n");
    Files.writeString(dir.resolve("memory.events"), "low 0\nhigh 0\n");
    assertEquals(8L << 30, memory.maxBytes());
    assertNull(memory.peakBytes());
    assertEquals(new CgroupMemory.Events(null, null), memory.events());

    assertEquals(297L, CgroupMemory.vmstatOomKills(vmstat));
    assertNull(CgroupMemory.vmstatOomKills(tmp.resolve("no-vmstat")));
    assertEquals(
        5L, CgroupMemory.counter("oom 1\noom_kill 5\noom_group_kill 0\n", "oom_kill"), "not oom");
  }

  // ---- the node ----------------------------------------------------------------------------------

  @Test
  void anLxcsRootCarriesTheNodeCounterAndItsLimit() throws IOException {
    events(root, 9, 292);
    Files.writeString(root.resolve("memory.max"), "34359738368\n");
    MemorySampler sampler = sampler();
    sampler.sample(List.of());
    assertEquals(new NodeMemory(292L, 297L, 32L << 30), sampler.node());
  }

  @Test
  void aBareHostsRootHasNoMemoryEventsAndOnlyTheKernelsCountIsLeft() {
    MemorySampler sampler = sampler();
    sampler.sample(List.of());
    assertEquals(new NodeMemory(null, 297L, null), sampler.node());
  }

  @Test
  void noTreeAtAllIsNullForEveryCgroupFactNeverAnException() {
    MemorySampler sampler =
        new MemorySampler(new CgroupSettings(tmp.resolve("not-bound"), vmstat));
    sampler.sample(List.of(running("l1", ID1)));
    assertEquals(new NodeMemory(null, 297L, null), sampler.node());
    assertEquals(MemorySampler.LeaseMemory.NONE, sampler.lease("l1"));
    assertNull(sampler.resolve(ID1));

    MemorySampler nothing =
        new MemorySampler(new CgroupSettings(tmp.resolve("not-bound"), tmp.resolve("no-vmstat")));
    nothing.sample(List.of());
    assertNull(nothing.node(), "nothing read is no node, not a node of nulls");
  }

  // ---- finding a lease's cgroup ------------------------------------------------------------------

  @Test
  void theSystemdDriversScopeIsTheLeasesCgroup() throws IOException {
    Path scope = Files.createDirectories(root.resolve("system.slice/docker-" + ID1 + ".scope"));
    events(scope, 1, 1);
    Files.writeString(scope.resolve("memory.peak"), String.valueOf(512 * MIB));
    MemorySampler sampler = sampler();
    sampler.driver("systemd");
    sampler.sample(List.of(running("l1", ID1)));
    assertEquals(scope, sampler.resolve(ID1));
    assertEquals(new MemorySampler.LeaseMemory(1L, 1L, 512 * MIB), sampler.lease("l1"));
  }

  @Test
  void theCgroupfsDriversDirectoryIsTheLeasesCgroup() throws IOException {
    Path dir = Files.createDirectories(root.resolve("docker/" + ID1));
    events(dir, 0, 0);
    MemorySampler sampler = sampler();
    sampler.driver("cgroupfs");
    sampler.sample(List.of(running("l1", ID1)));
    assertEquals(dir, sampler.resolve(ID1));
    assertEquals(new MemorySampler.LeaseMemory(0L, 0L, null), sampler.lease("l1"));
  }

  @Test
  void aCgroupElsewhereIsFoundByTheBoundedSearchAndNothingDeeper() throws IOException {
    Path nested = Files.createDirectories(root.resolve("lxc.payload/docker.slice/docker-" + ID1 + ".scope"));
    Files.createDirectories(root.resolve("a/b/c/docker-" + ID2 + ".scope"));
    MemorySampler sampler = sampler();
    sampler.driver("systemd");
    assertEquals(nested, sampler.resolve(ID1));
    assertNull(sampler.resolve(ID2), "four levels down is past the search");
    assertNull(sampler.resolve("../" + ID1));
    assertNull(sampler.resolve(""));
  }

  // ---- the churn rule ----------------------------------------------------------------------------

  @Test
  void thePeakMovesOnlyBy64MiBButEveryOomCounterChangeIsReported() throws IOException {
    Path scope = Files.createDirectories(root.resolve("system.slice/docker-" + ID1 + ".scope"));
    events(scope, 0, 0);
    peak(scope, 100 * MIB);
    MemorySampler sampler = sampler();
    sampler.sample(List.of(running("l1", ID1)));
    assertEquals(new MemorySampler.LeaseMemory(0L, 0L, 100 * MIB), sampler.lease("l1"));

    peak(scope, 163 * MIB);
    sampler.sample(List.of(running("l1", ID1)));
    assertEquals(100 * MIB, sampler.lease("l1").memoryPeakBytes(), "63 MiB more is no news");

    peak(scope, 164 * MIB);
    sampler.sample(List.of(running("l1", ID1)));
    assertEquals(164 * MIB, sampler.lease("l1").memoryPeakBytes(), "64 MiB more is");

    peak(scope, 200 * MIB);
    events(scope, 0, 1);
    sampler.sample(List.of(running("l1", ID1)));
    assertEquals(new MemorySampler.LeaseMemory(1L, 0L, 164 * MIB), sampler.lease("l1"));

    assertEquals(null, MemorySampler.coarse(null, null));
    assertEquals(5L, MemorySampler.coarse(5L, null));
  }

  // ---- an exited lease ---------------------------------------------------------------------------

  @Test
  void anExitedLeaseKeepsItsLastSampleUntilItIsRecreatedOrGone() throws IOException {
    Path scope = Files.createDirectories(root.resolve("system.slice/docker-" + ID1 + ".scope"));
    events(scope, 1, 2);
    peak(scope, 4096 * MIB);
    MemorySampler sampler = sampler();
    sampler.sample(List.of(running("l1", ID1)));

    // The container exits: its cgroup goes with it, and the last sample stays.
    for (String f : List.of("memory.events", "memory.peak")) {
      Files.delete(scope.resolve(f));
    }
    Files.delete(scope);
    sampler.sample(List.of(exited("l1", ID1)));
    MemorySampler.LeaseMemory last = new MemorySampler.LeaseMemory(2L, 1L, 4096 * MIB);
    assertEquals(last, sampler.lease("l1"));
    sampler.sample(List.of(running("l1", ID1)));
    assertEquals(last, sampler.lease("l1"), "listed running but its cgroup is gone: kept");

    // Recreated under another id: a new container starts from nothing.
    sampler.sample(List.of(exited("l1", ID2)));
    assertEquals(MemorySampler.LeaseMemory.NONE, sampler.lease("l1"));

    // Gone from docker's listing: forgotten.
    Path again = Files.createDirectories(root.resolve("system.slice/docker-" + ID2 + ".scope"));
    events(again, 0, 0);
    sampler.sample(List.of(running("l1", ID2)));
    assertEquals(new MemorySampler.LeaseMemory(0L, 0L, null), sampler.lease("l1"));
    sampler.sample(List.of());
    assertEquals(MemorySampler.LeaseMemory.NONE, sampler.lease("l1"));
  }

  // ---- the inspect -------------------------------------------------------------------------------

  @Test
  void theInspectReadsDockersOomKilledAndNullWhenItSaysNothing() {
    assertEquals(
        Boolean.TRUE,
        LeaseContainers.parse(ID1 + "|exited|137|l1|CI|h|m|true").orElseThrow().oomKilled());
    assertEquals(
        Boolean.FALSE,
        LeaseContainers.parse(ID1 + "|exited|0|l1|CI|h|m|false").orElseThrow().oomKilled());
    assertNull(LeaseContainers.parse(ID1 + "|exited|0|l1|CI|h|m").orElseThrow().oomKilled());
    assertNull(
        LeaseContainers.parse(ID1 + "|exited|0|l1|CI|h|m|<no value>").orElseThrow().oomKilled());
  }

  // ---- the settings ------------------------------------------------------------------------------

  @Test
  void theSettingsDefaultToTheBoundTreeAndTheKernelsVmstat() {
    var identity = SystemRunnerIdentity.IDENTITY;
    assertEquals(CgroupSettings.DEFAULTS, CgroupSettings.parse(identity, k -> null));
    assertEquals(CgroupSettings.DEFAULTS, CgroupSettings.parse(identity, k -> " "));
    assertEquals(Path.of("/qits/host-cgroup"), CgroupSettings.DEFAULTS.hostCgroup());
    assertEquals(
        new CgroupSettings(Path.of("/elsewhere"), Path.of("/tmp/vmstat")),
        CgroupSettings.parse(
            identity,
            k ->
                switch (k) {
                  case "QITS_SYSTEM_RUNNER_HOST_CGROUP" -> "/elsewhere";
                  case "QITS_SYSTEM_RUNNER_VMSTAT" -> "/tmp/vmstat";
                  default -> null;
                }));
  }

  // ---- helpers -----------------------------------------------------------------------------------

  private MemorySampler sampler() {
    return new MemorySampler(new CgroupSettings(root, vmstat));
  }

  private static void events(Path dir, long oom, long oomKill) throws IOException {
    Files.writeString(
        dir.resolve("memory.events"),
        "low 0\nhigh 0\nmax 4\noom " + oom + "\noom_kill " + oomKill + "\noom_group_kill 0\n");
  }

  private static void peak(Path dir, long bytes) throws IOException {
    Files.writeString(dir.resolve("memory.peak"), bytes + "\n");
  }

  private static LeaseContainers.Seen running(String leaseId, String id) {
    return new LeaseContainers.Seen(id, leaseId, "running", 0, "CI", "h", null, null);
  }

  private static LeaseContainers.Seen exited(String leaseId, String id) {
    return new LeaseContainers.Seen(id, leaseId, "exited", 137, "CI", "h", null, true);
  }
}
