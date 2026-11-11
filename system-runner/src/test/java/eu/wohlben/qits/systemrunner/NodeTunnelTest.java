package eu.wohlben.qits.systemrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.driver.spec.ContainerSpec;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link NodeTunnel} against a scripted {@link NodeTunnel.Probe} and a temporary resolv.conf: the
 * tunnel's address first and the runner's upstreams after it only when the probe hears an answer
 * and an upstream is there, the spec unchanged in every other case — and {@link NodeTunnel#UDP}
 * against an in-process DNS server on {@code 127.0.0.1}.
 */
class NodeTunnelTest {

  private static final String LEASE = "qits-system-runner-lease-l1-r1";
  private static final String HOST = "system.qits.example";

  /** Every question the scripted probe was asked. */
  private record Asked(InetSocketAddress server, String name, Duration timeout) {}

  private final List<Asked> asked = new ArrayList<>();
  private boolean answers;

  @TempDir Path dir;
  private Path resolvConf;
  private NodeTunnel tunnel;

  private DatagramSocket server;

  @BeforeEach
  void setUp() throws Exception {
    resolvConf = dir.resolve("resolv.conf");
    Files.writeString(resolvConf, "nameserver 192.168.188.1\nsearch fritz.box\n");
    tunnel =
        new NodeTunnel(
            (server, name, timeout) -> {
              asked.add(new Asked(server, name, timeout));
              return answers;
            },
            HOST,
            resolvConf);
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.close();
    }
  }

  private static ContainerSpec spec() {
    return ContainerSpec.builder("registry.example:5000/qits/workspace:1")
        .name(LEASE)
        .network("bridge")
        .user("1000")
        .env("QITS_TOKEN", "qits_tok_LEASE")
        .mount("qits-system-runner-work-ws-42-r1", "/workspace")
        .build();
  }

  // ---- the scripted probe -----------------------------------------------------------------------

  @Test
  void anAnsweringTunnelPutsItsAddressFirstThenTheUpstreamOnBridgeAlone() {
    answers = true;
    ContainerSpec spec = spec();

    ContainerSpec resolved = tunnel.apply(spec, LEASE);

    assertEquals(List.of("100.96.0.1", "192.168.188.1"), resolved.dns());
    assertEquals("bridge", resolved.network());
    assertEquals(List.of(), resolved.extraNetworks(), "no network joined");
    assertEquals(
        spec,
        PrivateEdge.with(resolved, List.of(), resolved.extraNetworks()),
        "every other component copied as it was");
    assertTrue(NodeTunnel.on(resolved));
  }

  @Test
  void theProbeAsksTheServerUrlsHostAtTheTunnelsDnsWithinOneSecond() {
    answers = true;

    tunnel.apply(spec(), LEASE);

    assertEquals(
        List.of(new Asked(new InetSocketAddress("100.96.0.1", 53), HOST, Duration.ofSeconds(1))),
        asked);
  }

  @Test
  void theChoiceIsTheTunnelsAddressOnlyWhereItAnswersAndAnUpstreamIsThere() throws Exception {
    answers = false;
    assertEquals(java.util.Optional.empty(), tunnel.choice());
    answers = true;
    assertEquals(java.util.Optional.of("100.96.0.1"), tunnel.choice());
    assertEquals(
        List.of(new Asked(new InetSocketAddress("100.96.0.1", 53), HOST, Duration.ofSeconds(1))),
        asked.subList(1, 2),
        "the same question apply asks");
    Files.writeString(resolvConf, "nameserver 100.96.0.1\n");
    assertEquals(java.util.Optional.empty(), tunnel.choice(), "only the tunnel: no choice");
  }

  @Test
  void aSilentTunnelLeavesTheSpecUnchanged() {
    answers = false;
    ContainerSpec spec = spec();

    assertSame(spec, tunnel.apply(spec, LEASE));
    assertEquals(1, asked.size());
  }

  @Test
  void anAnsweringTunnelWithoutAnUpstreamLeavesTheSpecUnchanged() throws Exception {
    answers = true;
    ContainerSpec spec = spec();

    Files.writeString(resolvConf, "search fritz.box\n");
    assertSame(spec, tunnel.apply(spec, LEASE), "no nameserver at all");

    Files.writeString(resolvConf, "nameserver 127.0.0.53\nnameserver ::1\n");
    assertSame(spec, tunnel.apply(spec, LEASE), "loopback only");

    Files.writeString(resolvConf, "nameserver 100.96.0.1\n");
    assertSame(spec, tunnel.apply(spec, LEASE), "only the tunnel itself");

    Files.delete(resolvConf);
    assertSame(spec, tunnel.apply(spec, LEASE), "no file to read");
  }

  @Test
  void theTunnelsAddressIsNotRepeatedWhenTheResolvConfListsIt() throws Exception {
    answers = true;
    Files.writeString(
        resolvConf, "nameserver 100.96.0.1\nnameserver 192.168.188.1\nnameserver 1.1.1.1\n");

    assertEquals(
        List.of("100.96.0.1", "192.168.188.1", "1.1.1.1"), tunnel.apply(spec(), LEASE).dns());
  }

  @Test
  void theExtServersLineIsReadAsThePrivateEdgeReadsIt() throws Exception {
    answers = true;
    Files.writeString(
        resolvConf,
        "nameserver 127.0.0.11\n# ExtServers: [host(192.168.188.1) 1.1.1.1]\n# Overrides: []\n");

    assertEquals(
        List.of("100.96.0.1", "192.168.188.1", "1.1.1.1"), tunnel.apply(spec(), LEASE).dns());
  }

  @Test
  void aSpecWithResolversAlreadyIsTheOverlaysAndIsNeverProbed() {
    answers = true;
    ContainerSpec onEdge =
        PrivateEdge.with(spec(), List.of("10.231.0.2", "192.168.188.1"), List.of("qits-private"));

    assertSame(onEdge, tunnel.apply(onEdge, LEASE));
    assertTrue(asked.isEmpty(), "never probed: " + asked);
    assertFalse(NodeTunnel.on(onEdge));
  }

  @Test
  void aThrowingProbeLeavesTheSpecUnchanged() {
    NodeTunnel throwing =
        new NodeTunnel(
            (server, name, timeout) -> {
              throw new IllegalStateException("boom");
            },
            HOST,
            resolvConf);
    ContainerSpec spec = spec();

    assertSame(spec, throwing.apply(spec, LEASE));
  }

  // ---- resolvers(): the same decision without a spec (qits-2090) ------------------------------

  @Test
  void resolversAreTheTunnelThenTheUpstreamsWhenTheProbeHearsAnAnswer() {
    answers = true;

    assertEquals(List.of("100.96.0.1", "192.168.188.1"), tunnel.resolvers());
    assertEquals(
        List.of(new Asked(new InetSocketAddress("100.96.0.1", 53), HOST, Duration.ofSeconds(1))),
        asked,
        "one probe per question, never cached");
    tunnel.resolvers();
    assertEquals(2, asked.size(), "asked afresh");
  }

  @Test
  void resolversAreNoneWhenTheProbeHearsNothing() {
    answers = false;

    assertEquals(List.of(), tunnel.resolvers());
    assertEquals(1, asked.size());
  }

  @Test
  void resolversAreNoneWithoutAnUpstream() throws Exception {
    answers = true;

    Files.writeString(resolvConf, "nameserver 127.0.0.53\n");
    assertEquals(List.of(), tunnel.resolvers(), "loopback only");

    Files.writeString(resolvConf, "nameserver 100.96.0.1\n");
    assertEquals(List.of(), tunnel.resolvers(), "only the tunnel itself");

    Files.delete(resolvConf);
    assertEquals(List.of(), tunnel.resolvers(), "no file to read");
  }

  @Test
  void resolversAreNoneWhenTheProbeThrows() {
    NodeTunnel throwing =
        new NodeTunnel(
            (server, name, timeout) -> {
              throw new IllegalStateException("boom");
            },
            HOST,
            resolvConf);

    assertEquals(List.of(), throwing.resolvers());
  }

  @Test
  void resolversStayTheSameOnceTheRunnersOwnResolvConfListsTheTunnelFirst() throws Exception {
    answers = true;
    List<String> before = tunnel.resolvers();
    // The runner's successor started with --dns 100.96.0.1 --dns 192.168.188.1, on bridge.
    Files.writeString(
        resolvConf, "nameserver 100.96.0.1\nnameserver 192.168.188.1\nsearch fritz.box\n");

    assertEquals(List.of("100.96.0.1", "192.168.188.1"), tunnel.resolvers());
    assertEquals(before, tunnel.resolvers(), "no second 100.96.0.1, so no new builder stamp");
  }

  @Test
  void theProbeNameIsTheServerUrlsHost() {
    assertEquals("system.qits.wohlben.eu", NodeTunnel.hostOf("https://system.qits.wohlben.eu"));
    assertEquals("127.0.0.1", NodeTunnel.hostOf("http://127.0.0.1:8080/"));
    assertEquals(null, NodeTunnel.hostOf("not a url"));
    assertEquals(null, NodeTunnel.hostOf(null));
  }

  // ---- the UDP probe ----------------------------------------------------------------------------

  /**
   * A one-shot DNS server on {@code 127.0.0.1}: it answers the first query with what {@code reply}
   * makes of it, or not at all when that is null.
   */
  private InetSocketAddress serve(UnaryOperator<byte[]> reply) throws Exception {
    server = new DatagramSocket(0, InetAddress.getLoopbackAddress());
    DatagramSocket socket = server;
    Thread thread =
        new Thread(
            () -> {
              try {
                byte[] buffer = new byte[512];
                DatagramPacket query = new DatagramPacket(buffer, buffer.length);
                socket.receive(query);
                byte[] q = Arrays.copyOf(buffer, query.getLength());
                byte[] r = reply.apply(q);
                if (r != null) {
                  socket.send(new DatagramPacket(r, r.length, query.getSocketAddress()));
                }
              } catch (Exception closed) {
                // the test is over
              }
            });
    thread.setDaemon(true);
    thread.start();
    return new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort());
  }

  /** The query turned into a response: QR set, RD kept, RA set, this RCODE, no answer. */
  private static byte[] response(byte[] query, int rcode) {
    byte[] r = query.clone();
    r[2] = (byte) (query[2] | 0x80);
    r[3] = (byte) (0x80 | rcode);
    return r;
  }

  @Test
  void theUdpProbeAsksOneAQueryAndCountsANoErrorReplyWithItsId() throws Exception {
    List<byte[]> queries = new ArrayList<>();
    InetSocketAddress at =
        serve(
            q -> {
              queries.add(q);
              return response(q, 0);
            });

    assertTrue(NodeTunnel.udpAnswers(at, HOST, Duration.ofSeconds(1)));
    byte[] q = queries.getFirst();
    assertEquals(0x01, q[2], "a query, recursion desired");
    assertEquals(0x00, q[3]);
    assertEquals(1, ((q[4] & 0xff) << 8) | (q[5] & 0xff), "one question");
    byte[] name = {
      6, 's', 'y', 's', 't', 'e', 'm', //
      4, 'q', 'i', 't', 's', //
      7, 'e', 'x', 'a', 'm', 'p', 'l', 'e', //
      0
    };
    assertEquals(
        Arrays.toString(name),
        Arrays.toString(Arrays.copyOfRange(q, 12, 12 + name.length)));
    assertEquals(
        Arrays.toString(new byte[] {0, 1, 0, 1}),
        Arrays.toString(Arrays.copyOfRange(q, 12 + name.length, q.length)),
        "A, IN");
  }

  @Test
  void theUdpProbeCountsARefusedReplyAsNoAnswer() throws Exception {
    InetSocketAddress at = serve(q -> response(q, 5));

    assertFalse(NodeTunnel.udpAnswers(at, HOST, Duration.ofSeconds(1)));
  }

  @Test
  void theUdpProbeIgnoresAReplyOfAnotherId() throws Exception {
    InetSocketAddress at =
        serve(
            q -> {
              byte[] r = response(q, 0);
              r[0] = (byte) ~r[0];
              return r;
            });

    assertFalse(NodeTunnel.udpAnswers(at, HOST, Duration.ofSeconds(1)));
  }

  @Test
  void theUdpProbeGivesUpOnSilenceWithinItsTimeout() throws Exception {
    InetSocketAddress at = serve(q -> null);

    long started = System.nanoTime();
    assertFalse(NodeTunnel.udpAnswers(at, HOST, Duration.ofSeconds(1)));
    long took = Duration.ofNanos(System.nanoTime() - started).toMillis();
    assertTrue(took >= 900 && took < 2_000, "took " + took + " ms");
  }

  @Test
  void theUdpProbeAsksNothingForANameItCannotSpell() {
    assertFalse(NodeTunnel.udpAnswers(new InetSocketAddress("127.0.0.1", 9), null, Duration.ZERO));
    assertFalse(
        NodeTunnel.udpAnswers(new InetSocketAddress("127.0.0.1", 9), "a..b", Duration.ZERO));
  }
}
