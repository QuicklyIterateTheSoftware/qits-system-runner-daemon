package eu.wohlben.qits.systemrunner;

import eu.wohlben.qits.containers.driver.spec.ContainerSpec;
import eu.wohlben.qits.containers.driver.spec.ContainersIdentifiers;
import eu.wohlben.qits.runner.toolkit.DockerCommand;
import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;

/**
 * <b>A lease on the private edge where the node's daemon has one</b> (qits-2062): a membership of
 * the {@code qits-private} overlay, and the private edge's VIP on it as the first resolver,
 * followed by the runner's own upstreams. qits-containers' {@code PrivateEdgeResolver} (qits-1991)
 * does the same for the platform's own flagged containers; this is that logic for every runner
 * lease, asked of the runner's own docker daemon at each lease create, read-only and under its
 * deadline:
 *
 * <ol>
 *   <li>{@code docker network inspect --format {{.Id}} qits-private} — the overlay's id. Absent
 *       is every off-host target, and the lease runs exactly as before, with a DEBUG line;
 *   <li>{@code docker service ls -q --filter label=<APP_NAME_LABEL>=qits-edge-private} — exactly
 *       one swarm service;
 *   <li>{@code docker service inspect --format} over {@code .Endpoint.VirtualIPs}, one {@code
 *       <NetworkID> <Addr>} line each — the line on the overlay, its {@code /prefix} stripped, is
 *       the VIP. Read by hand: the native image carries no JSON parser;
 *   <li>the upstreams from the runner's own {@code /etc/resolv.conf}: the first {@code #
 *       ExtServers: [a b host(c)]} line when docker wrote one, else the {@code nameserver} lines in
 *       order — loopback dropped (a lease's own loopback is not the runner's), de-duplicated, the
 *       VIP dropped if listed.
 * </ol>
 *
 * <p><b>All or nothing</b>, stricter than {@code PrivateEdgeResolver}, which keeps a join without
 * a resolver: only the overlay, exactly one VIP <em>and</em> at least one upstream put the lease on
 * {@code qits-private} with {@code --dns <vip> --dns <upstream>…}. {@code --dns} replaces the
 * upstream rather than prepending to it, so a VIP-only list would answer every public name with the
 * edge's REFUSED; and a join without the VIP as resolver buys a lease nothing. Any other answer —
 * a docker failure, a timeout, a missing piece, a spec the driver's belts refuse — is the spec
 * unchanged and one log line: WARN once the network exists, because then the node is the platform
 * host and something is off. Never throws.
 *
 * <p>The result is applied to the driver's spec just before the run and is <b>still not hashed</b>:
 * the spec-hash label is the server's and the mounts label {@link TargetVolumes#mountsHash}'s, so a
 * running lease is not recreated by the runner when this class arrives or when the edge's VIP
 * moves. It is <b>reported per lease</b> instead (qits-2091): {@link #choice} answers the same
 * lookup without a spec, {@link NodeResolvers} asks it from the periodic look, and a lease whose
 * first resolver is not the node's choice is reported {@code resolversStale}. Only the owner
 * refreshes such a lease — by raising its {@code LeaseEntry.generation}, which the runner answers
 * with a recreate that keeps the volume and asks this class again.
 *
 * <p>Neither the environment nor anything else of the spec is logged: it carries the lease's token.
 */
final class PrivateEdge {

  private static final Logger LOG = Logger.getLogger(PrivateEdge.class);

  /**
   * The attachable overlay the private edge serves on — qits-containers' {@code
   * PrivateEdgeResolver} network ({@code qits.containers.private-edge.network}), created by
   * qits-deployments. Restated: neither jar is on this classpath.
   */
  static final String NETWORK = "qits-private";

  /**
   * The private edge's application name, as {@code PrivateEdgeResolver}'s {@code
   * qits.containers.private-edge.application} names it.
   */
  static final String APPLICATION = "qits-edge-private";

  /**
   * qits-deployments' {@code DeploymentDriver.APP_NAME_LABEL}, as {@code
   * PrivateEdgeResolver.APP_NAME_LABEL} restates it: the label every swarm service it deploys
   * carries, holding the application's name.
   */
  static final String APP_NAME_LABEL = "qits.platform.deployments.app-name";

  /** Where the runner's own resolvers are read from. */
  static final Path RESOLV_CONF = Path.of("/etc/resolv.conf");

  /** The comment line docker writes into a container's resolv.conf naming its upstream servers. */
  static final String EXT_SERVERS = "# ExtServers:";

  /** The service inspect's template: one {@code <NetworkID> <Addr>} line per virtual IP. */
  static final String VIPS_FORMAT =
      "{{range .Endpoint.VirtualIPs}}{{.NetworkID}} {{.Addr}}{{\"\\n\"}}{{end}}";

  /** A docker object id as {@code network inspect} and {@code service ls -q} print one. */
  private static final Pattern DOCKER_ID = Pattern.compile("[0-9a-z]{1,128}");

  /** The {@code host(x)} spelling docker uses for a server reached from the host's namespace. */
  private static final Pattern HOST_FORM = Pattern.compile("host\\((.*)\\)");

  private final DockerCommand docker;
  private final Path resolvConf;

  PrivateEdge(DockerCommand docker) {
    this(docker, RESOLV_CONF);
  }

  /** With where the resolv.conf is read from — another path for the suite only. */
  PrivateEdge(DockerCommand docker, Path resolvConf) {
    this.docker = docker;
    this.resolvConf = resolvConf;
  }

  /**
   * What the node's daemon says of the private edge: its VIP on the overlay and the upstreams to
   * follow it — or why not, with {@code overlay} saying whether the network is there (the WARN case)
   * and {@code why} null for the quiet one, a node without it. Asked by {@link #apply} at each
   * create and by {@link #choice} for the report.
   */
  private record Lookup(String vip, List<String> upstreams, boolean overlay, String why) {

    static Lookup none(boolean overlay, String why) {
      return new Lookup(null, List.of(), overlay, why);
    }

    boolean found() {
      return vip != null;
    }
  }

  /**
   * {@code spec} on {@code qits-private} with the edge's VIP first among its resolvers, or {@code
   * spec} itself — see the class javadoc. Never throws.
   */
  ContainerSpec apply(ContainerSpec spec, String containerName) {
    try {
      return resolve(spec, containerName);
    } catch (RuntimeException e) {
      LOG.warnf("system-runner runs %s off the private edge: %s", containerName, e.getMessage());
      return spec;
    }
  }

  /**
   * The resolver this node would put first on a lease created now: the edge's VIP when the overlay,
   * exactly one edge service, its VIP on the overlay and an upstream are all there — the very
   * answer {@link #apply} acts on — else empty (qits-2091). No spec is asked and nothing is joined;
   * logged at DEBUG only, since the periodic look asks it. Never throws.
   */
  Optional<String> choice() {
    try {
      Lookup lookup = lookup();
      if (!lookup.found()) {
        LOG.debugf(
            "system-runner's node offers no private edge resolver: %s",
            lookup.why() == null ? "this node has no " + NETWORK + " network" : lookup.why());
        return Optional.empty();
      }
      return Optional.of(lookup.vip());
    } catch (RuntimeException e) {
      LOG.debugf("system-runner's node offers no private edge resolver: %s", e.getMessage());
      return Optional.empty();
    }
  }

  private ContainerSpec resolve(ContainerSpec spec, String containerName) {
    Lookup lookup = lookup();
    if (!lookup.found()) {
      if (lookup.overlay()) {
        return off(spec, containerName, lookup.why());
      }
      if (lookup.why() != null) {
        LOG.warnf("system-runner runs %s off the private edge: %s", containerName, lookup.why());
      } else {
        LOG.debugf(
            "system-runner runs %s off the private edge: this node has no %s network",
            containerName, NETWORK);
      }
      return spec;
    }
    List<String> dns = new ArrayList<>();
    dns.add(lookup.vip());
    dns.addAll(lookup.upstreams());
    List<String> extraNetworks = new ArrayList<>(spec.extraNetworks());
    if (!NETWORK.equals(spec.network()) && !extraNetworks.contains(NETWORK)) {
      extraNetworks.add(NETWORK);
    }
    ContainerSpec resolved;
    try {
      resolved = with(spec, dns, extraNetworks);
    } catch (IllegalArgumentException e) {
      return off(spec, containerName, e.getMessage());
    }
    LOG.debugf(
        "system-runner puts %s on the private edge: joins %s, resolvers %s",
        containerName, NETWORK, dns);
    return resolved;
  }

  /** The class javadoc's four steps, read-only; a step that fails says why. */
  private Lookup lookup() {
    RunnerDocker.Result network =
        docker.run(
            List.of(docker.binary(), "network", "inspect", "--format", "{{.Id}}", NETWORK));
    String networkId = network.ok() ? lastLine(network.stdout()) : "";
    if (!DOCKER_ID.matcher(networkId).matches()) {
      return Lookup.none(false, network.timedOut() ? String.valueOf(network.detail()) : null);
    }

    RunnerDocker.Result listed =
        docker.run(
            List.of(
                docker.binary(),
                "service",
                "ls",
                "-q",
                "--filter",
                "label=" + APP_NAME_LABEL + "=" + APPLICATION));
    if (!listed.ok()) {
      return Lookup.none(true, "docker service ls answered " + brief(listed));
    }
    List<String> services = lines(listed.stdout());
    if (services.size() != 1 || !DOCKER_ID.matcher(services.getFirst()).matches()) {
      return Lookup.none(
          true,
          "expected exactly one swarm service labelled "
              + APP_NAME_LABEL
              + "="
              + APPLICATION
              + ", found "
              + services.size());
    }

    String service = services.getFirst();
    RunnerDocker.Result inspected =
        docker.run(
            List.of(docker.binary(), "service", "inspect", "--format", VIPS_FORMAT, service));
    if (!inspected.ok()) {
      return Lookup.none(
          true, "docker service inspect " + service + " answered " + brief(inspected));
    }
    Optional<String> vip = vipOn(inspected.stdout(), networkId);
    if (vip.isEmpty()) {
      return Lookup.none(
          true,
          "the service "
              + service
              + " has no single virtual IP on "
              + NETWORK
              + " ("
              + networkId
              + ")");
    }

    List<String> upstreams = upstreamsBesides(resolvConf, vip.get());
    if (upstreams.isEmpty()) {
      return Lookup.none(
          true,
          resolvConf
              + " names no upstream resolver, and a resolver list holding only the edge's VIP would"
              + " answer every public name REFUSED");
    }
    return new Lookup(vip.get(), upstreams, true, null);
  }

  /** The spec unchanged, with the WARN: the overlay is here, so this is the platform host. */
  private static ContainerSpec off(ContainerSpec spec, String containerName, String why) {
    LOG.warnf(
        "system-runner runs %s off the private edge although this node has the %s network: %s",
        containerName, NETWORK, why);
    return spec;
  }

  /**
   * The {@code Addr} of the one line whose {@code NetworkID} is the overlay's, without its prefix;
   * empty when no line or more than one names it, or the address is not a literal.
   */
  static Optional<String> vipOn(String vipLines, String networkId) {
    List<String> found = new ArrayList<>();
    for (String line : lines(vipLines)) {
      String[] fields = line.split("\\s+");
      if (fields.length == 2 && fields[0].equals(networkId)) {
        int slash = fields[1].indexOf('/');
        found.add(slash < 0 ? fields[1] : fields[1].substring(0, slash));
      }
    }
    return found.size() == 1 ? dnsServer(found.getFirst()) : Optional.empty();
  }

  /**
   * The runner's upstream resolvers as {@link #upstreams} reads them from {@code resolvConf},
   * without {@code first} — the address the caller puts ahead of them, never listed twice; empty
   * when the file cannot be read. Shared with {@link NodeTunnel}, whose first resolver is the node
   * tunnel's.
   */
  static List<String> upstreamsBesides(Path resolvConf, String first) {
    String text;
    try {
      text = Files.readString(resolvConf, StandardCharsets.UTF_8);
    } catch (Exception e) {
      LOG.debugf("system-runner could not read %s: %s", resolvConf, e.getMessage());
      return List.of();
    }
    return upstreams(text).stream().filter(s -> !s.equals(first)).toList();
  }

  /**
   * The servers of the first {@code # ExtServers: [a b host(c)]} line — {@code host(x)} is {@code
   * x} — when there is one, else every {@code nameserver} line's, in order. Loopback and anything
   * that is not an address literal are skipped, repeats kept once. On a user-defined network the
   * {@code nameserver} is docker's embedded {@code 127.0.0.11} and the comment holds the real
   * upstreams; on {@code bridge} there is no comment and the nameservers are the host's.
   */
  static List<String> upstreams(String resolvConfText) {
    List<String> lines = resolvConfText.lines().map(String::strip).toList();
    List<String> candidates = new ArrayList<>();
    Optional<String> ext = lines.stream().filter(l -> l.startsWith(EXT_SERVERS)).findFirst();
    if (ext.isPresent()) {
      String rest = ext.get().substring(EXT_SERVERS.length()).strip();
      if (rest.startsWith("[")) {
        rest = rest.substring(1);
      }
      int close = rest.lastIndexOf(']');
      if (close >= 0) {
        rest = rest.substring(0, close);
      }
      for (String token : rest.strip().split("\\s+")) {
        var host = HOST_FORM.matcher(token);
        candidates.add(host.matches() ? host.group(1) : token);
      }
    } else {
      for (String line : lines) {
        String[] fields = line.split("\\s+");
        if (fields.length >= 2 && fields[0].equals("nameserver")) {
          candidates.add(fields[1]);
        }
      }
    }
    List<String> servers = new ArrayList<>();
    for (String candidate : candidates) {
      dnsServer(candidate)
          .filter(s -> !loopback(s) && !servers.contains(s))
          .ifPresent(servers::add);
    }
    return List.copyOf(servers);
  }

  private static Optional<String> dnsServer(String candidate) {
    try {
      return Optional.of(ContainersIdentifiers.requireDnsServer(candidate));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /** 127.0.0.0/8 or ::1, of a value {@link ContainersIdentifiers#requireDnsServer} passed. */
  private static boolean loopback(String literal) {
    try {
      // A literal never reaches a resolver: ofLiteral parses and nothing else.
      return InetAddress.ofLiteral(literal).isLoopbackAddress();
    } catch (IllegalArgumentException e) {
      return true;
    }
  }

  /**
   * The spec with these two components replaced and every other one as it was — also {@link
   * NodeTunnel}'s route, so both build the spec the same way.
   */
  static ContainerSpec with(
      ContainerSpec spec, List<String> dns, List<String> extraNetworks) {
    return new ContainerSpec(
        spec.image(),
        spec.entrypoint(),
        spec.args(),
        spec.env(),
        spec.extraLabels(),
        spec.network(),
        spec.aliases(),
        spec.addHosts(),
        spec.volumeMounts(),
        spec.sharedMounts(),
        spec.hostDockerSocket(),
        spec.security(),
        spec.pullPolicy(),
        spec.explicitName(),
        spec.user(),
        spec.init(),
        dns,
        extraNetworks);
  }

  private static List<String> lines(String output) {
    return output == null
        ? List.of()
        : output.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
  }

  private static String lastLine(String output) {
    List<String> lines = lines(output);
    return lines.isEmpty() ? "" : lines.getLast();
  }

  private static String brief(RunnerDocker.Result result) {
    return (result.timedOut() ? "" : "exit " + result.exitCode() + ": ")
        + LeaseLauncher.brief(result.detail());
  }
}
