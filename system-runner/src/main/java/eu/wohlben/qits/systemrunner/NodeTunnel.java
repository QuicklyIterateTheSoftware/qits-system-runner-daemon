package eu.wohlben.qits.systemrunner;

import eu.wohlben.qits.containers.driver.spec.ContainerSpec;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.jboss.logging.Logger;

/**
 * <b>A lease resolving through the node tunnel where the node has one</b> (qits-2068): a target off
 * the platform host whose host runs the node tunnel reaches the private edge's tunnel address
 * {@value #DNS_ADDRESS} from {@code bridge}, the runner's own container exactly like a lease. Such
 * a lease keeps {@code bridge} and no other network, and gets {@code --dns 100.96.0.1} followed by
 * the runner's own upstreams, so private names resolve to the private edge through the tunnel and
 * every other name still resolves — the edge's DNS answers a public name REFUSED, and glibc then
 * asks the next server.
 *
 * <p>Asked at every create, after {@link PrivateEdge} and never cached:
 *
 * <ol>
 *   <li>a spec that already has resolvers — {@link PrivateEdge} put it on {@code qits-private} —
 *       is returned unchanged, without a probe: the overlay wins;
 *   <li>one UDP {@code A} query for the host of the runner's own server URL ({@code
 *       QITS_SYSTEM_RUNNER_URL}) to {@value #DNS_ADDRESS}:{@value #DNS_PORT}, within {@link
 *       #PROBE_TIMEOUT}. Only a NOERROR answer with the query's id counts — an empty answer
 *       section included; silence, REFUSED or anything else is "no tunnel", and the lease runs as
 *       before with a DEBUG line;
 *   <li>the upstreams from the runner's {@code /etc/resolv.conf}, read as {@link PrivateEdge}
 *       reads them, without {@value #DNS_ADDRESS}. None is the spec unchanged with a WARN: a list
 *       holding only the tunnel would answer every public name REFUSED.
 * </ol>
 *
 * <p>Like {@link PrivateEdge}, the result is never hashed into the spec-hash or mounts label, and
 * a lease keeps the resolvers it was created with: when the tunnel goes down later, its public
 * lookups first wait out glibc's timeout on {@value #DNS_ADDRESS} until the tunnel is back or the
 * lease is recreated. Never throws; neither the environment nor anything else of the spec is
 * logged.
 */
final class NodeTunnel {

  private static final Logger LOG = Logger.getLogger(NodeTunnel.class);

  /**
   * The private edge's tunnel and DNS address: qits-edge-service's {@code TunnelDns} binds {@code
   * qits.edge.dns.bind=100.96.0.1:53}, and {@code PrivateEdgeAnnounced.dnsAddress} carries it
   * (qits-1976, qits-1977). Restated rather than configured: the owner rules out settings for
   * values that are derivable.
   */
  static final String DNS_ADDRESS = "100.96.0.1";

  /** The port of {@code TunnelDns}'s bind, {@code qits.edge.dns.bind}. */
  static final int DNS_PORT = 53;

  /** How long the probe waits for the answer: an answering tunnel replies in milliseconds. */
  static final Duration PROBE_TIMEOUT = Duration.ofSeconds(1);

  /** Whether a DNS server answers a name; the seam a suite scripts. Never throws. */
  @FunctionalInterface
  interface Probe {
    boolean answers(InetSocketAddress server, String name, Duration timeout);
  }

  /** The production probe: one UDP {@code A} query on a {@link DatagramSocket}. */
  static final Probe UDP = NodeTunnel::udpAnswers;

  /** A probe that never hears an answer: a node without the tunnel. */
  static final Probe NEVER = (server, name, timeout) -> false;

  private final Probe probe;
  private final String probeHost;
  private final Path resolvConf;

  /**
   * @param probeHost the name asked: the host of the runner's own server URL
   * @param resolvConf where the runner's own resolvers are read from
   */
  NodeTunnel(Probe probe, String probeHost, Path resolvConf) {
    this.probe = probe;
    this.probeHost = probeHost;
    this.resolvConf = resolvConf;
  }

  /**
   * {@code spec} with {@value #DNS_ADDRESS} first among its resolvers, or {@code spec} itself — see
   * the class javadoc. Never throws.
   */
  ContainerSpec apply(ContainerSpec spec, String containerName) {
    try {
      return resolve(spec, containerName);
    } catch (RuntimeException e) {
      LOG.warnf("system-runner runs %s off the node tunnel: %s", containerName, e.getMessage());
      return spec;
    }
  }

  private ContainerSpec resolve(ContainerSpec spec, String containerName) {
    if (!spec.dns().isEmpty()) {
      return spec;
    }
    if (!probe.answers(
        new InetSocketAddress(DNS_ADDRESS, DNS_PORT), probeHost, PROBE_TIMEOUT)) {
      LOG.debugf(
          "system-runner runs %s off the node tunnel: %s does not answer",
          containerName, DNS_ADDRESS);
      return spec;
    }
    List<String> upstreams = PrivateEdge.upstreamsBesides(resolvConf, DNS_ADDRESS);
    if (upstreams.isEmpty()) {
      LOG.warnf(
          "system-runner runs %s off the node tunnel although %s answers: no upstream resolver in"
              + " %s",
          containerName, DNS_ADDRESS, resolvConf);
      return spec;
    }
    List<String> dns = new ArrayList<>();
    dns.add(DNS_ADDRESS);
    dns.addAll(upstreams);
    ContainerSpec resolved = PrivateEdge.with(spec, dns, spec.extraNetworks());
    LOG.debugf("system-runner puts %s on the node tunnel: resolvers %s", containerName, dns);
    return resolved;
  }

  /**
   * The host of the runner's own server URL ({@code QITS_SYSTEM_RUNNER_URL}, {@code
   * RunnerSettings.URL}) — the name the probe asks, one the private edge serves. Null when the URL
   * has none, and then the probe hears nothing.
   */
  static String hostOf(String url) {
    try {
      return url == null ? null : URI.create(url).getHost();
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** Whether {@code run}'s resolvers are the node tunnel's: {@value #DNS_ADDRESS} first. */
  static boolean on(ContainerSpec run) {
    return !run.dns().isEmpty() && run.dns().getFirst().equals(DNS_ADDRESS);
  }

  /**
   * One {@code <name> A IN} query, recursion desired, to {@code server}: {@code true} only for a
   * reply from that server carrying the query's id, QR set and RCODE 0 (NOERROR) — an empty answer
   * section still counts, the server is there. A reply of another id is ignored and the wait goes
   * on; silence until {@code timeout}, an I/O failure, a name that cannot be asked or any other
   * RCODE is {@code false}. Never throws.
   */
  static boolean udpAnswers(InetSocketAddress server, String name, Duration timeout) {
    try {
      // No Random held in a static field: a native image would bake its seed in at build time.
      int id = ThreadLocalRandom.current().nextInt(0x10000);
      byte[] query = query(id, name);
      if (query == null || server == null || server.isUnresolved()) {
        return false;
      }
      long deadline = System.nanoTime() + timeout.toNanos();
      try (DatagramSocket socket = new DatagramSocket()) {
        // Connected: the socket only receives datagrams from the server itself.
        socket.connect(server);
        socket.send(new DatagramPacket(query, query.length));
        byte[] buffer = new byte[512];
        while (true) {
          long left = Math.max(0, deadline - System.nanoTime()) / 1_000_000;
          if (left <= 0) {
            return false;
          }
          socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, left));
          DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
          socket.receive(reply);
          if (reply.getLength() < 12 || !server.equals(reply.getSocketAddress())) {
            continue;
          }
          int replyId = ((buffer[0] & 0xff) << 8) | (buffer[1] & 0xff);
          if (replyId != id) {
            continue;
          }
          boolean response = (buffer[2] & 0x80) != 0;
          int rcode = buffer[3] & 0x0f;
          return response && rcode == 0;
        }
      }
    } catch (IOException | RuntimeException e) {
      // A SocketTimeoutException among them: silence until the timeout.
      return false;
    }
  }

  /** The query's bytes, or null when {@code name} is no askable host name. */
  static byte[] query(int id, String name) {
    if (name == null) {
      return null;
    }
    String host = name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
    if (host.isEmpty()) {
      return null;
    }
    ByteBuffer out = ByteBuffer.allocate(12 + host.length() + 2 + 4);
    out.putShort((short) id);
    out.putShort((short) 0x0100); // QR 0, OPCODE QUERY, RD 1
    out.putShort((short) 1); // QDCOUNT
    out.putShort((short) 0); // ANCOUNT
    out.putShort((short) 0); // NSCOUNT
    out.putShort((short) 0); // ARCOUNT
    for (String label : host.split("\\.", -1)) {
      byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
      if (bytes.length == 0 || bytes.length > 63 || !label.chars().allMatch(c -> c < 0x80)) {
        return null;
      }
      out.put((byte) bytes.length);
      out.put(bytes);
    }
    out.put((byte) 0);
    if (out.position() > 12 + 255) {
      return null;
    }
    out.putShort((short) 1); // QTYPE A
    out.putShort((short) 1); // QCLASS IN
    return out.array();
  }
}
