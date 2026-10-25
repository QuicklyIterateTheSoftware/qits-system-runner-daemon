package eu.wohlben.qits.systemrunner.protocol;

import eu.wohlben.qits.runner.protocol.DomainCodec;
import eu.wohlben.qits.runner.protocol.WireMaps;
import eu.wohlben.qits.runner.protocol.health.HealthWire;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerProtocol.Field;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerProtocol.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The target frames over {@code Map<String,Object>}: the {@link DomainCodec} qits-runner-protocol's
 * {@code RunnerCodec} delegates every non-lifecycle {@code type} to. Both peers use it — the runner
 * bridging the map with a Vert.x {@code JsonObject}, qits-system with Jackson — so a field is
 * spelled in exactly one place.
 *
 * <p>Build it into a socket codec as {@code new RunnerCodec<>(SystemRunnerProtocol.VOCABULARY, new
 * SystemRunnerCodec())}.
 *
 * <p><b>Tolerant where it is safe, strict where it is not.</b> Every field is read through {@link
 * WireMaps}, so unknown fields are ignored and either box for a number is fine. A missing lease id,
 * slot type, desired state — or, on a RUNNING entry, spec, spec hash or spec image — is a refusal
 * ({@code RunnerCodec} reports it as MALFORMED and the receiver drops the frame), and so is an enum
 * value this version does not know, and a missing {@code leases} on an {@link Estate} or an {@link
 * Inventory}: those lists are desired and observed state, and an absence read as "empty" would end
 * every lease on a node. A login presence is the one tolerant enum: unknown reads as UNKNOWN. A
 * sign-in frame without its {@code signInId} (and a {@code signIn} without its harness, a {@code
 * signInCode} without its code, a {@code signInProgress} without a known state) is refused too.
 *
 * <p>Every frame writes {@code type} first and its fields in record order; a nullable field is
 * written as {@code null} rather than left out. {@code healthCheck} and {@code healthChecked} are
 * {@link HealthWire}'s, every runner kind's.
 */
public final class SystemRunnerCodec implements DomainCodec<SystemRunnerMessage> {

  @Override
  public Class<SystemRunnerMessage> messageType() {
    return SystemRunnerMessage.class;
  }

  @Override
  public Set<String> types() {
    return Type.ALL;
  }

  @Override
  public Map<String, Object> encode(SystemRunnerMessage message) {
    Map<String, Object> map = new LinkedHashMap<>();
    switch (message) {
      case Estate m -> {
        map.put(Field.TYPE, Type.ESTATE);
        List<Object> leases = new ArrayList<>();
        for (LeaseEntry entry : m.leases()) {
          leases.add(entry(entry));
        }
        map.put(Field.LEASES, leases);
        map.put(Field.PURGES, new ArrayList<>(m.purges()));
      }
      case ProbeLogin _ -> map.put(Field.TYPE, Type.PROBE_LOGIN);
      case HealthCheck m -> map.putAll(HealthWire.encodeCheck(m.requestId()));
      case SignIn m -> {
        map.put(Field.TYPE, Type.SIGN_IN);
        map.put(Field.SIGN_IN_ID, m.signInId());
        map.put(Field.HARNESS, m.harness());
      }
      case SignInCode m -> {
        map.put(Field.TYPE, Type.SIGN_IN_CODE);
        map.put(Field.SIGN_IN_ID, m.signInId());
        map.put(Field.CODE, m.code());
      }
      case SignInCancel m -> {
        map.put(Field.TYPE, Type.SIGN_IN_CANCEL);
        map.put(Field.SIGN_IN_ID, m.signInId());
      }
      case Inventory m -> {
        map.put(Field.TYPE, Type.INVENTORY);
        List<Object> leases = new ArrayList<>();
        for (LeaseReport report : m.leases()) {
          Map<String, Object> lease = new LinkedHashMap<>();
          lease.put(Field.LEASE_ID, report.leaseId());
          lease.put(Field.STATE, report.state().name());
          lease.put(Field.SPEC_HASH, report.specHash());
          lease.put(Field.EXIT_CODE, report.exitCode());
          lease.put(Field.DETAIL, report.detail());
          leases.add(lease);
        }
        map.put(Field.LEASES, leases);
        map.put(Field.VOLUMES, new ArrayList<>(m.volumes()));
      }
      case LoginState m -> {
        map.put(Field.TYPE, Type.LOGIN_STATE);
        map.put(Field.CLAUDE, m.claude().name());
        map.put(Field.KIMI, m.kimi().name());
        map.put(Field.CHECKED_AT, m.checkedAt());
        map.put(Field.VOLUME, m.volume());
      }
      case HealthChecked m -> map.putAll(HealthWire.encodeChecked(m.report()));
      case SignInProgress m -> {
        map.put(Field.TYPE, Type.SIGN_IN_PROGRESS);
        map.put(Field.SIGN_IN_ID, m.signInId());
        map.put(Field.HARNESS, m.harness());
        map.put(Field.STATE, m.state().name());
        map.put(Field.URL, m.url());
        map.put(Field.USER_CODE, m.userCode());
        map.put(Field.DETAIL, m.detail());
      }
    }
    return map;
  }

  @Override
  public SystemRunnerMessage decode(String type, Map<String, Object> map) {
    return switch (type) {
      case Type.ESTATE -> new Estate(entries(map), WireMaps.stringList(map, Field.PURGES));
      case Type.PROBE_LOGIN -> new ProbeLogin();
      case Type.HEALTH_CHECK -> new HealthCheck(HealthWire.decodeCheck(map));
      case Type.SIGN_IN ->
          new SignIn(required(map, Field.SIGN_IN_ID), required(map, Field.HARNESS));
      case Type.SIGN_IN_CODE ->
          new SignInCode(required(map, Field.SIGN_IN_ID), required(map, Field.CODE));
      case Type.SIGN_IN_CANCEL -> new SignInCancel(required(map, Field.SIGN_IN_ID));
      case Type.INVENTORY -> new Inventory(reports(map), requiredStrings(map, Field.VOLUMES));
      case Type.LOGIN_STATE ->
          new LoginState(
              LoginPresence.fromWire(WireMaps.str(map, Field.CLAUDE)),
              LoginPresence.fromWire(WireMaps.str(map, Field.KIMI)),
              WireMaps.str(map, Field.CHECKED_AT),
              WireMaps.str(map, Field.VOLUME));
      case Type.HEALTH_CHECKED -> HealthChecked.of(HealthWire.decodeChecked(map));
      case Type.SIGN_IN_PROGRESS ->
          new SignInProgress(
              required(map, Field.SIGN_IN_ID),
              WireMaps.str(map, Field.HARNESS),
              constant(SignInState.class, WireMaps.str(map, Field.STATE), Field.STATE),
              WireMaps.str(map, Field.URL),
              WireMaps.str(map, Field.USER_CODE),
              WireMaps.str(map, Field.DETAIL));
      default -> throw new IllegalArgumentException("not a target runner frame: " + type);
    };
  }

  // --- a lease entry -----------------------------------------------------------------------------

  private static Map<String, Object> entry(LeaseEntry e) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put(Field.LEASE_ID, e.leaseId());
    map.put(Field.SLOT_TYPE, e.slotType().name());
    map.put(Field.DESIRED, e.desired().name());
    map.put(Field.SPEC, e.spec() == null ? null : spec(e.spec()));
    map.put(Field.SPEC_HASH, e.specHash());
    map.put(Field.WORK_VOLUME, e.workVolume());
    if (e.adopt() == null) {
      map.put(Field.ADOPT, null);
    } else {
      Map<String, Object> adopt = new LinkedHashMap<>();
      adopt.put(Field.CONTAINER, e.adopt().container());
      adopt.put(Field.VOLUME, e.adopt().volume());
      map.put(Field.ADOPT, adopt);
    }
    return map;
  }

  private static LeaseEntry entry(Map<String, Object> map) {
    String leaseId = required(map, Field.LEASE_ID);
    SlotType slotType = constant(SlotType.class, WireMaps.str(map, Field.SLOT_TYPE), Field.SLOT_TYPE);
    DesiredState desired =
        constant(DesiredState.class, WireMaps.str(map, Field.DESIRED), Field.DESIRED);
    Map<String, Object> specMap = WireMaps.object(map, Field.SPEC);
    LeaseSpec spec = specMap == null ? null : spec(specMap);
    String specHash = WireMaps.str(map, Field.SPEC_HASH);
    if (desired == DesiredState.RUNNING) {
      if (spec == null) {
        throw new IllegalArgumentException(Field.SPEC + " is missing");
      }
      if (specHash == null || specHash.isBlank()) {
        throw new IllegalArgumentException(Field.SPEC_HASH + " is missing");
      }
    }
    Map<String, Object> adoptMap = WireMaps.object(map, Field.ADOPT);
    Adopt adopt =
        adoptMap == null
            ? null
            : new Adopt(WireMaps.str(adoptMap, Field.CONTAINER), WireMaps.str(adoptMap, Field.VOLUME));
    return new LeaseEntry(
        leaseId, slotType, desired, spec, specHash, WireMaps.str(map, Field.WORK_VOLUME), adopt);
  }

  private static List<LeaseEntry> entries(Map<String, Object> map) {
    List<LeaseEntry> entries = new ArrayList<>();
    for (Map<String, Object> lease : objects(map, Field.LEASES)) {
      entries.add(entry(lease));
    }
    return entries;
  }

  private static List<LeaseReport> reports(Map<String, Object> map) {
    List<LeaseReport> reports = new ArrayList<>();
    for (Map<String, Object> lease : objects(map, Field.LEASES)) {
      Number exit = WireMaps.number(lease, Field.EXIT_CODE);
      reports.add(
          new LeaseReport(
              required(lease, Field.LEASE_ID),
              constant(LeaseState.class, WireMaps.str(lease, Field.STATE), Field.STATE),
              WireMaps.str(lease, Field.SPEC_HASH),
              exit == null ? null : exit.intValue(),
              WireMaps.str(lease, Field.DETAIL)));
    }
    return reports;
  }

  // --- the spec ----------------------------------------------------------------------------------

  private static Map<String, Object> spec(LeaseSpec s) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put(Field.IMAGE, s.image());
    map.put(Field.ENV, new LinkedHashMap<>(s.env()));
    map.put(Field.USER, s.user());
    Map<String, Object> limits = new LinkedHashMap<>();
    limits.put(Field.MEMORY, s.limits().memory());
    limits.put(Field.MEMORY_SWAP, s.limits().memorySwap());
    limits.put(Field.PIDS_LIMIT, s.limits().pidsLimit());
    limits.put(Field.CPUS, s.limits().cpus());
    limits.put(Field.OOM_SCORE_ADJ, s.limits().oomScoreAdj());
    map.put(Field.LIMITS, limits);
    return map;
  }

  private static LeaseSpec spec(Map<String, Object> map) {
    String image = WireMaps.str(map, Field.IMAGE);
    if (image == null || image.isBlank()) {
      throw new IllegalArgumentException(Field.SPEC + "." + Field.IMAGE + " is missing");
    }
    Map<String, Object> l = WireMaps.object(map, Field.LIMITS);
    Limits limits = Limits.NONE;
    if (l != null) {
      Number pids = WireMaps.number(l, Field.PIDS_LIMIT);
      Number oom = WireMaps.number(l, Field.OOM_SCORE_ADJ);
      limits =
          new Limits(
              WireMaps.str(l, Field.MEMORY),
              WireMaps.str(l, Field.MEMORY_SWAP),
              pids == null ? null : pids.longValue(),
              WireMaps.str(l, Field.CPUS),
              oom == null ? null : oom.intValue());
    }
    return new LeaseSpec(
        image, WireMaps.stringMap(map, Field.ENV), WireMaps.str(map, Field.USER), limits);
  }

  // --- readers -----------------------------------------------------------------------------------

  /** A required, non-blank string field. */
  private static String required(Map<String, Object> map, String key) {
    String value = WireMaps.str(map, key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(key + " is missing");
    }
    return value;
  }

  /** An enum spelled as its constant's name; absent or unknown is a refusal, never a guess. */
  private static <E extends Enum<E>> E constant(Class<E> type, String value, String key) {
    if (value == null) {
      throw new IllegalArgumentException(key + " is missing");
    }
    try {
      return Enum.valueOf(type, value);
    } catch (IllegalArgumentException unknown) {
      throw new IllegalArgumentException("unknown " + key + ": " + value);
    }
  }

  /** A required list of strings: absent is a refusal, as {@link #objects} is. */
  private static List<String> requiredStrings(Map<String, Object> map, String key) {
    if (map.get(key) == null) {
      throw new IllegalArgumentException(key + " is missing");
    }
    return WireMaps.stringList(map, key);
  }

  /** A required list of nested objects, each a {@code Map} or a Vert.x {@code JsonObject}. */
  private static List<Map<String, Object>> objects(Map<String, Object> map, String key) {
    Object value = map.get(key);
    if (value == null) {
      throw new IllegalArgumentException(key + " is missing");
    }
    if (!(value instanceof Iterable<?> items) || value instanceof Map<?, ?>) {
      throw new IllegalArgumentException("'" + key + "' is not a list");
    }
    List<Map<String, Object>> objects = new ArrayList<>();
    for (Object item : items) {
      // WireMaps reads a nested object out of a map by key; wrapping the item reuses that reading,
      // so a JsonObject inside a JsonArray is accepted exactly as a nested one is.
      Map<String, Object> holder = new LinkedHashMap<>();
      holder.put(key, item);
      Map<String, Object> object = WireMaps.object(holder, key);
      if (object == null) {
        throw new IllegalArgumentException("'" + key + "' holds a null");
      }
      objects.add(object);
    }
    return objects;
  }
}
