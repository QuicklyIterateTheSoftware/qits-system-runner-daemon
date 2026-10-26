package eu.wohlben.qits.systemrunner.protocol;

import eu.wohlben.qits.runner.protocol.RunnerVocabulary;
import java.util.Set;

/**
 * The single source of truth for the target runner's control socket: its address, its capability
 * version, its own frames' tags and field names, the {@code Hello} and {@code Ack} extension keys,
 * and the roots every name the runner uses is derived from.
 *
 * <p>The lifecycle frames — {@code hello}, {@code ack}, {@code heartbeat}, {@code backlog}, {@code
 * reserve}, {@code nothing}, {@code upgrade}, {@code retire}, {@code quarantined} and {@code
 * reinstated} — are qits-runner-protocol's and byte-identical to every other runner's; none is
 * declared here. The pull frames ({@code reserve}, {@code backlog}, a take) are never used: the
 * server allocates. This class holds the target frames, which travel through {@link
 * SystemRunnerCodec}:
 *
 * <pre>
 *   Hello{heldLeases, capabilities{…, slots, engineId, hostname, credentialVolume, buildkit}}
 *     → Ack{slots, adoptedLeases, slotImages{ci, workspace, desk}, probeImage, registryMirrors}
 *   Estate{leases[{leaseId, slotType, desired, spec, specHash, workVolume, adopt}], purges[]}
 *     ← Inventory{leases[{leaseId, state, specHash, exitCode, detail}], volumes[]}
 *   ProbeLogin → LoginState{claude, kimi, checkedAt, volume}
 *   HealthCheck{requestId} → HealthChecked{ok, detail, requestId, checks}
 *   SignIn{signInId, harness}, SignInCode{signInId, code}, SignInCancel{signInId}
 *     → SignInProgress{signInId, harness, state, url, userCode, detail} … (LoginState after SUCCEEDED)
 *   Docker{requestId, op, name, tail, since, argv, timeoutSeconds}
 *     → DockerResult{requestId, status, body, truncated, exitCode, stderr}
 * </pre>
 *
 * <p><b>Identity is not on the wire.</b> The runner presents a bearer minted from the idp client it
 * registered; qits-system knows which target this is before the first frame is read.
 */
public final class SystemRunnerProtocol {

  /**
   * The capability version the runner announces in its {@code Hello} and qits-system echoes in its
   * {@code Ack}. Bumped only when either side must branch on a change: a new frame or a new field
   * is not a bump, because both codecs drop an unknown type and ignore an unknown field.
   */
  public static final int CAPABILITY_VERSION = 1;

  /** The control socket's path below the service's public url: {@code wss://system.qits.<d>…}. */
  public static final String SOCKET_PATH = "/system/targets/socket";

  /**
   * The target runner's names for the two lists the base carries across a lost socket: the leases
   * it holds ({@code Hello}) and the ones the host kept ({@code Ack}).
   */
  public static final RunnerVocabulary VOCABULARY =
      new RunnerVocabulary("heldLeases", "adoptedLeases");

  /**
   * A CI lease's {@code FAILED} detail on runner versions before the CI slot policy (qits-1543);
   * kept so a server can still read an old target's answer.
   */
  public static final String CI_NOT_ENABLED = "ci slots are not enabled on this runner version";

  /** An {@code adopt} naming something outside the retired runners' patterns. */
  public static final String ADOPT_NOT_ALLOWED = "adopt name not allowed";

  // --- the identity roots ------------------------------------------------------------------------
  //
  // qits-runner-toolkit's RunnerIdentity is built from exactly these, by the runner and by
  // qits-system-service for its install script. They are java.* constants rather than an identity
  // because RunnerIdentity lives in the toolkit, which this jar must not depend on; both embedders
  // pass them in the same order, with SystemRunnerBinary.VERSION, CAPABILITY_VERSION and
  // VOCABULARY. The toolkit's KnownRunnerNamespaces spells the first four too, and the runner
  // module's SystemRunnerIdentityTest holds them equal.

  /** {@code RunnerIdentity.kind}. */
  public static final String KIND = "targets";

  /** {@code RunnerIdentity.envPrefix}: every variable is {@code QITS_SYSTEM_RUNNER_<NAME>}. */
  public static final String ENV_PREFIX = "QITS_SYSTEM_RUNNER_";

  /** {@code RunnerIdentity.labelRoot}: {@code qits.system.runner=<targetId>} and below it. */
  public static final String LABEL_ROOT = "qits.system.runner";

  /** {@code RunnerIdentity.namePrefix}: every container and volume the runner names. */
  public static final String NAME_PREFIX = "qits-system-runner";

  /** {@code RunnerIdentity.imageRepository}, below the registry host and without a tag. */
  public static final String IMAGE_REPOSITORY = "qits/qits-system-runner";

  /** {@code RunnerIdentity.defaultStateDir}: where the state volume is mounted. */
  public static final String STATE_DIR = "/var/lib/qits-system-runner";

  /** {@code RunnerIdentity.registerPath}: the register door, {@code {id}} the target id. */
  public static final String REGISTER_PATH = "/system/api/targets/{id}/register";

  /** {@code RunnerIdentity.serviceName}: the runner's own log lands in {@code _service/<this>}. */
  public static final String SERVICE_NAME = "qits-system-runner";

  /** {@code RunnerIdentity.workLabelSuffix}: {@code qits.system.runner.lease=<leaseId>}. */
  public static final String WORK_LABEL_SUFFIX = "lease";

  private SystemRunnerProtocol() {}

  /** The {@code "type"} discriminator values of the target frames. */
  public static final class Type {
    // qits-system -> runner
    public static final String ESTATE = "estate";
    public static final String PROBE_LOGIN = "probeLogin";
    public static final String HEALTH_CHECK = "healthCheck";
    public static final String SIGN_IN = "signIn";
    public static final String SIGN_IN_CODE = "signInCode";
    public static final String SIGN_IN_CANCEL = "signInCancel";
    public static final String DOCKER = "docker";
    // runner -> qits-system
    public static final String INVENTORY = "inventory";
    public static final String LOGIN_STATE = "loginState";
    public static final String HEALTH_CHECKED = "healthChecked";
    public static final String SIGN_IN_PROGRESS = "signInProgress";
    public static final String DOCKER_RESULT = "docker-result";

    /** Every target type — what {@link SystemRunnerCodec#types()} answers. */
    public static final Set<String> ALL =
        Set.of(
            ESTATE,
            PROBE_LOGIN,
            HEALTH_CHECK,
            SIGN_IN,
            SIGN_IN_CODE,
            SIGN_IN_CANCEL,
            DOCKER,
            INVENTORY,
            LOGIN_STATE,
            HEALTH_CHECKED,
            SIGN_IN_PROGRESS,
            DOCKER_RESULT);

    private Type() {}
  }

  /** The field names of the target frames, of the objects nested in them, and of the extensions. */
  public static final class Field {
    public static final String TYPE = "type";
    // estate
    public static final String LEASES = "leases";
    public static final String PURGES = "purges";
    // a lease entry
    public static final String LEASE_ID = "leaseId";
    public static final String SLOT_TYPE = "slotType";
    public static final String DESIRED = "desired";
    public static final String SPEC = "spec";
    public static final String SPEC_HASH = "specHash";
    public static final String WORK_VOLUME = "workVolume";
    public static final String ADOPT = "adopt";
    // adopt
    public static final String CONTAINER = "container";
    public static final String VOLUME = "volume";
    // spec
    public static final String IMAGE = "image";
    public static final String ENV = "env";
    public static final String USER = "user";
    public static final String LIMITS = "limits";
    public static final String MEMORY = "memory";
    public static final String MEMORY_SWAP = "memorySwap";
    public static final String PIDS_LIMIT = "pidsLimit";
    public static final String CPUS = "cpus";
    public static final String OOM_SCORE_ADJ = "oomScoreAdj";
    // inventory
    public static final String STATE = "state";
    public static final String EXIT_CODE = "exitCode";
    public static final String DETAIL = "detail";
    public static final String VOLUMES = "volumes";
    // loginState (VOLUME above)
    public static final String CLAUDE = "claude";
    public static final String KIMI = "kimi";
    public static final String CHECKED_AT = "checkedAt";
    // healthCheck — healthChecked is HealthWire's
    public static final String REQUEST_ID = "requestId";
    // signIn, signInCode, signInCancel and signInProgress (STATE and DETAIL above)
    public static final String SIGN_IN_ID = "signInId";
    public static final String HARNESS = "harness";
    public static final String CODE = "code";
    public static final String URL = "url";
    public static final String USER_CODE = "userCode";
    // docker (REQUEST_ID above)
    public static final String OP = "op";
    public static final String NAME = "name";
    public static final String TAIL = "tail";
    public static final String SINCE = "since";
    public static final String ARGV = "argv";
    public static final String TIMEOUT_SECONDS = "timeoutSeconds";
    // docker-result (REQUEST_ID and EXIT_CODE above)
    public static final String STATUS = "status";
    public static final String BODY = "body";
    public static final String TRUNCATED = "truncated";
    public static final String STDERR = "stderr";
    // the Hello capabilities extension
    public static final String SLOTS = "slots";
    public static final String ENGINE_ID = "engineId";
    public static final String HOSTNAME = "hostname";
    public static final String CREDENTIAL_VOLUME = "credentialVolume";
    public static final String BUILDKIT = "buildkit";
    // the Ack extension
    public static final String SLOT_IMAGES = "slotImages";
    public static final String CI = "ci";
    public static final String WORKSPACE = "workspace";
    public static final String DESK = "desk";
    public static final String PROBE_IMAGE = "probeImage";
    public static final String REGISTRY_MIRRORS = "registryMirrors";

    private Field() {}
  }
}
