package eu.wohlben.qits.systemrunner.protocol;

import eu.wohlben.qits.runner.protocol.RunnerCapabilities;
import eu.wohlben.qits.runner.protocol.WireMaps;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerProtocol.Field;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a target says about itself in its {@code Hello}'s {@code capabilities} (and its
 * registration): the base's {@code docker}, {@code arch} and {@code os}, and this runner's
 * extension keys {@code slots}, {@code engineId}, {@code hostname}, {@code credentialVolume} and
 * {@code buildkit}, in that order after the base's.
 *
 * @param docker whether the docker socket is here
 * @param arch {@code amd64}, {@code arm64}, …
 * @param os {@code linux}
 * @param slots {@code QITS_SYSTEM_RUNNER_SLOTS}: <b>the server takes it as the target's total</b>
 * @param engineId {@code docker info --format '{{.ID}}'}, or null when docker did not say: what
 *     legacy adoption matches an old runner's node by
 * @param hostname the docker engine's host name ({@code docker info --format '{{.Name}}'}), or
 *     null: the fallback match when no engine id is recorded
 * @param credentialVolume the credential volume every WORKSPACE and DESK lease mounts
 * @param buildkit whether this target has a build plane; false until the CI feature
 */
public record SystemRunnerCapabilities(
    boolean docker,
    String arch,
    String os,
    int slots,
    String engineId,
    String hostname,
    String credentialVolume,
    boolean buildkit) {

  /** The wire value: the base's keys and these extensions, no labels. */
  public RunnerCapabilities toWire() {
    Map<String, Object> ext = new LinkedHashMap<>();
    ext.put(Field.SLOTS, slots);
    ext.put(Field.ENGINE_ID, engineId);
    ext.put(Field.HOSTNAME, hostname);
    ext.put(Field.CREDENTIAL_VOLUME, credentialVolume);
    ext.put(Field.BUILDKIT, buildkit);
    return new RunnerCapabilities(docker, arch, os, Map.of(), ext);
  }

  /** The values out of a decoded {@code capabilities}; an absent extension is null, 0 or false. */
  public static SystemRunnerCapabilities of(RunnerCapabilities capabilities) {
    Map<String, Object> ext = capabilities.extensions();
    return new SystemRunnerCapabilities(
        capabilities.docker(),
        capabilities.arch(),
        capabilities.os(),
        WireMaps.intVal(ext, Field.SLOTS),
        WireMaps.str(ext, Field.ENGINE_ID),
        WireMaps.str(ext, Field.HOSTNAME),
        WireMaps.str(ext, Field.CREDENTIAL_VOLUME),
        WireMaps.boolVal(ext, Field.BUILDKIT));
  }
}
