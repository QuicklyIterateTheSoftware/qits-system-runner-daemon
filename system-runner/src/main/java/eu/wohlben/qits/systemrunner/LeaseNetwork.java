package eu.wohlben.qits.systemrunner;

import eu.wohlben.qits.runner.toolkit.RunnerDocker;
import eu.wohlben.qits.runner.toolkit.RunnerIdentity;
import eu.wohlben.qits.runner.toolkit.TeardownItem;
import java.util.List;
import java.util.Optional;

/**
 * <b>The network WORKSPACE and DESK leases start on</b> (qits-2269): {@code
 * qits-system-runner-leases-<id8>}, a user-defined bridge of the runner's own, in place of docker's
 * default {@code bridge}.
 *
 * <p>Why a network of its own: on the default {@code bridge} docker writes a lease's {@code --dns}
 * servers straight into its {@code /etc/resolv.conf}. A node tunnel lease gets {@code 100.96.0.1}
 * and the LAN's upstream there ({@link NodeTunnel}); musl's resolver — the {@code qits} CLI's —
 * asks every nameserver at once and takes the first answer, the LAN's public address wins, and the
 * edge answers 421. On a user-defined network docker writes {@code nameserver 127.0.0.11} from the
 * first instant, and its embedded resolver asks the {@code --dns} servers in order.
 *
 * <p>Made the way {@link BuildPlane#ensure} makes the build network: a read-only {@code network
 * inspect} first, and a {@code network create --driver bridge}, owner-labelled, only when docker
 * says there is none; an "already exists" answer is the state asked for. {@code enable_icc=false}:
 * two leases on one node do not reach each other over it — on {@code bridge} they could, by
 * address. Every call goes through {@link RunnerDocker} under its deadline; the driver has no
 * network create. Asked at every WORKSPACE or DESK create, never
 * cached, so a network somebody removed by hand is made again by the next lease. It is the domain's
 * {@link TeardownItem}, after every container that may still be on it.
 */
final class LeaseNetwork {

  /** The network's purpose in {@code <namePrefix>-<purpose>-<id8>}. */
  static final String PURPOSE = "leases";

  /** Docker's bridge option that cuts traffic between two containers on the network. */
  static final String NO_ICC = "com.docker.network.bridge.enable_icc=false";

  private final RunnerIdentity identity;
  private final String targetId;
  private final RunnerDocker docker;
  private final String name;

  /**
   * @throws IllegalStateException if the derivation ever spelled a platform name or one the
   *     runner does not own, as {@link BuildPlane.Names#of} refuses
   */
  LeaseNetwork(RunnerIdentity identity, String targetId, RunnerDocker docker) {
    this.identity = identity;
    this.targetId = targetId;
    this.docker = docker;
    this.name = name(identity, targetId);
  }

  /** {@code <namePrefix>-leases-<id8>}, refused unless it is the runner's own. */
  static String name(RunnerIdentity identity, String targetId) {
    String name = identity.nodeName(targetId, PURPOSE);
    if (BuildPlane.PLATFORM_NAMES.contains(name) || !identity.ownsName(name)) {
      throw new IllegalStateException(name + " is not this runner's to create");
    }
    return name;
  }

  String name() {
    return name;
  }

  /** The decommission's: removed after the containers on it. */
  TeardownItem teardown() {
    return new TeardownItem(TeardownItem.Type.NETWORK, name);
  }

  /**
   * The network there, made when docker says it is absent. Empty when a lease may run on it; else
   * why not, with docker's words — the lease then fails, and never falls back to {@code bridge}.
   * Synchronized: two leases created at once make it once.
   */
  synchronized Optional<String> ensure() {
    BuildPlane.Network network = network();
    if (network.presence() == BuildPlane.Presence.UNKNOWN) {
      return Optional.of("docker could not say whether " + name + " exists: " + network.detail());
    }
    if (network.presence() == BuildPlane.Presence.ABSENT) {
      RunnerDocker.Result created = docker.run(createArgv());
      if (!created.ok() && !String.valueOf(created.detail()).contains("already exists")) {
        return Optional.of(
            "could not create the lease network " + name + ": " + created.detail());
      }
    }
    return Optional.empty();
  }

  /** The one create: a bridge, owner-labelled, with inter-container traffic off. */
  List<String> createArgv() {
    return List.of(
        docker.binary(),
        "network",
        "create",
        "--driver",
        "bridge",
        "--label",
        identity.ownerLabel() + "=" + targetId,
        "--opt",
        NO_ICC,
        name);
  }

  /** The network's presence and driver — read-only, one {@code docker network inspect}. */
  BuildPlane.Network network() {
    RunnerDocker.Result inspected =
        docker.run(
            List.of(docker.binary(), "network", "inspect", "--format", "{{.Driver}}", name));
    if (!inspected.ok()) {
      return BuildPlane.absent(inspected)
          ? new BuildPlane.Network(BuildPlane.Presence.ABSENT, null, null)
          : new BuildPlane.Network(BuildPlane.Presence.UNKNOWN, null, inspected.detail());
    }
    String driver = inspected.stdout().strip();
    return new BuildPlane.Network(
        BuildPlane.Presence.PRESENT, driver.isEmpty() ? null : driver, null);
  }
}
