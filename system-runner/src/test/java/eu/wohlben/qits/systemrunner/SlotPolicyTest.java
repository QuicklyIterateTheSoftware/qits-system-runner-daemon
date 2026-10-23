package eu.wohlben.qits.systemrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.driver.spec.ContainerSpec;
import eu.wohlben.qits.systemrunner.SlotPolicy.Cache;
import eu.wohlben.qits.systemrunner.protocol.DesiredState;
import eu.wohlben.qits.systemrunner.protocol.LeaseEntry;
import eu.wohlben.qits.systemrunner.protocol.LeaseSpec;
import eu.wohlben.qits.systemrunner.protocol.Limits;
import eu.wohlben.qits.systemrunner.protocol.SlotImages;
import eu.wohlben.qits.systemrunner.protocol.SlotType;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerProtocol;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Every cell of the dossier's slot policy table, as {@link SlotPolicy} holds it and as the container
 * spec it builds carries it — and the refusals: CI on this runner version, an image outside its
 * type's prefix, no user or root, a policy-owned environment key (named in the detail), and a
 * missing or misshapen work key.
 */
class SlotPolicyTest {

  private static final String REGISTRY = "registry.qits.example.eu/qits/";
  private static final SlotImages IMAGES =
      new SlotImages(REGISTRY + "ci-", REGISTRY + "workspace", REGISTRY + "project-agent");
  private static final String WS_IMAGE = REGISTRY + "workspace:2026.1023.1";
  private static final String DESK_IMAGE = REGISTRY + "project-agent:2026.1023.1";
  private static final String NAME = "qits-system-runner-lease-l1-1a2b3c4d";
  private static final SlotPolicy.Mounts MOUNTS =
      new SlotPolicy.Mounts(
          "qits-system-runner-work-ws-42-1a2b3c4d",
          "qits_shared_dot_claude",
          "qits_shared_m2",
          "qits_shared_pnpm");

  // --- the table ---------------------------------------------------------------------------------

  @Test
  void ciIsDeclaredWithItsCellsAndIsNotEnabledOnThisVersion() {
    SlotPolicy ci = SlotPolicy.of(SlotType.CI);
    assertFalse(ci.enabled);
    assertFalse(ci.credentialHome, "/claude-home: never");
    assertFalse(ci.workVolume, "/workspace: the container filesystem");
    assertEquals(List.of(Cache.CI_GIT, Cache.CI_M2, Cache.CI_PNPM), ci.caches);
    assertEquals("/cache/git", Cache.CI_GIT.target);
    assertEquals("/caches/m2", Cache.CI_M2.target);
    assertEquals("/caches/pnpm", Cache.CI_PNPM.target);
    assertTrue(ci.buildNetwork, "bridge + the build network");
    assertTrue(ci.injectsBuildkitHost);
    assertTrue(ci.capDropAll);
    assertTrue(ci.noNewPrivileges);
    assertTrue(ci.init);
    assertFalse(ci.restartUnlessStopped);
    assertFalse(ci.lifecycle().restartsUnlessStopped());
    assertNull(ci.workKeyPrefix);
    assertThrows(
        IllegalStateException.class,
        () -> ci.containerSpec(NAME, spec(REGISTRY + "ci-step:1", Map.of(), "1001"), MOUNTS));
  }

  @Test
  void workspaceIsTheDossiersColumn() {
    SlotPolicy ws = SlotPolicy.of(SlotType.WORKSPACE);
    assertTrue(ws.enabled);
    assertTrue(ws.credentialHome);
    assertTrue(ws.workVolume);
    assertEquals(List.of(Cache.M2, Cache.PNPM), ws.caches);
    assertFalse(ws.buildNetwork);
    assertFalse(ws.injectsBuildkitHost);
    assertFalse(ws.capDropAll);
    assertFalse(ws.noNewPrivileges);
    assertFalse(ws.init, "today's workspace spec: no --init");
    assertFalse(ws.restartUnlessStopped);
    assertFalse(ws.lifecycle().restartsUnlessStopped(), "restart: no");
    assertEquals("ws-", ws.workKeyPrefix);

    ContainerSpec c = ws.containerSpec(NAME, spec(WS_IMAGE, env(), "1000"), MOUNTS);
    assertContainer(c, WS_IMAGE, "1000", false);
  }

  @Test
  void deskIsTheDossiersColumn() {
    SlotPolicy desk = SlotPolicy.of(SlotType.DESK);
    assertTrue(desk.enabled);
    assertTrue(desk.credentialHome);
    assertTrue(desk.workVolume);
    assertEquals(List.of(Cache.M2, Cache.PNPM), desk.caches);
    assertFalse(desk.buildNetwork);
    assertFalse(desk.injectsBuildkitHost);
    assertFalse(desk.capDropAll);
    assertFalse(desk.noNewPrivileges);
    assertTrue(desk.init, "--init, as the desk has always run");
    assertTrue(desk.restartUnlessStopped);
    assertTrue(desk.lifecycle().restartsUnlessStopped(), "restart: unless-stopped");
    assertEquals("desk-", desk.workKeyPrefix);

    ContainerSpec c = desk.containerSpec(NAME, spec(DESK_IMAGE, env(), "1234"), MOUNTS);
    assertContainer(c, DESK_IMAGE, "1234", true);
  }

  /**
   * What WORKSPACE and DESK share: the spec's image, user, env and limits; the credential volume
   * at {@code /claude-home} and {@code CLAUDE_CONFIG_DIR} beside it; the work volume at {@code
   * /workspace}; the agent caches; the bridge alone; no docker socket, no extra host, no alias, and
   * no sandbox flags.
   */
  private static void assertContainer(ContainerSpec c, String image, String user, boolean init) {
    assertEquals(image, c.image());
    assertEquals(NAME, c.explicitName());
    assertEquals(user, c.user());
    assertEquals(init, c.init());
    assertEquals("bridge", c.network());
    assertTrue(c.aliases().isEmpty());
    assertTrue(c.addHosts().isEmpty());
    assertFalse(c.hostDockerSocket(), "never the docker socket");
    assertTrue(c.entrypoint().isEmpty() && c.args().isEmpty(), "the image's own command");
    assertEquals(
        List.of(new ContainerSpec.VolumeMount(MOUNTS.workVolume(), "/workspace")), c.volumeMounts());
    assertEquals(
        List.of(
            new ContainerSpec.SharedMount("qits_shared_dot_claude", "/claude-home"),
            new ContainerSpec.SharedMount("qits_shared_m2", "/caches/m2"),
            new ContainerSpec.SharedMount("qits_shared_pnpm", "/caches/pnpm")),
        c.sharedMounts());
    Map<String, String> expected = new LinkedHashMap<>(env());
    expected.put("CLAUDE_CONFIG_DIR", "/claude-home/.claude");
    assertEquals(expected, c.env());
    assertFalse(c.env().containsKey("BUILDKIT_HOST"), "never");
    assertFalse(c.env().containsKey("HOME"), "the image's own");
    assertEquals(
        new ContainerSpec.SecurityPosture(false, false, "4g", "8g", 512L, "2", 800), c.security());
    assertEquals(ContainerSpec.PullPolicy.MISSING, c.pullPolicy());
  }

  @Test
  void aWorkspaceOrDeskLeaseAlwaysMountsTheCredentialVolumeAtClaudeHome() {
    for (SlotType type : List.of(SlotType.WORKSPACE, SlotType.DESK)) {
      for (String credential : List.of("qits-system-runner-dot-claude-1a2b3c4d", "qits_shared_dot_claude")) {
        ContainerSpec c =
            SlotPolicy.of(type)
                .containerSpec(
                    NAME,
                    spec(type == SlotType.DESK ? DESK_IMAGE : WS_IMAGE, Map.of(), "1000"),
                    new SlotPolicy.Mounts("w", credential, "m", "p"));
        assertTrue(
            c.sharedMounts().contains(new ContainerSpec.SharedMount(credential, "/claude-home")),
            type + " " + credential);
        assertEquals("/claude-home/.claude", c.env().get("CLAUDE_CONFIG_DIR"));
      }
    }
  }

  // --- the refusals ------------------------------------------------------------------------------

  @Test
  void aCiLeaseFailsWithTheDossiersDetail() {
    assertEquals(
        Optional.of("ci slots are not enabled on this runner version"),
        SlotPolicy.CI.refusal(
            entry(SlotType.CI, spec(REGISTRY + "ci-step:1", Map.of(), "1001"), null), IMAGES));
    assertEquals(SystemRunnerProtocol.CI_NOT_ENABLED, SlotPolicy.CI.refusal(
        entry(SlotType.CI, spec(REGISTRY + "ci-step:1", Map.of(), "1001"), null), IMAGES).get());
  }

  @Test
  void aSpecEnvKeyThePolicyOwnsIsRefusedNamingIt() {
    for (SlotType type : List.of(SlotType.WORKSPACE, SlotType.DESK)) {
      for (String key : Set.of("BUILDKIT_HOST", "HOME", "CLAUDE_CONFIG_DIR")) {
        Map<String, String> env = new LinkedHashMap<>(env());
        env.put(key, "/somewhere");
        Optional<String> refused =
            SlotPolicy.of(type).refusal(entry(type, spec(imageOf(type), env, "1000"), key(type)), IMAGES);
        assertTrue(refused.isPresent(), type + " " + key);
        assertTrue(refused.get().contains(key), refused.get());
      }
      assertEquals(
          Optional.empty(),
          SlotPolicy.of(type).refusal(entry(type, spec(imageOf(type), env(), "1000"), key(type)), IMAGES));
    }
  }

  @Test
  void theImageMustStartWithItsTypesPinnedPrefix() {
    assertTrue(
        SlotPolicy.WORKSPACE
            .refusal(entry(SlotType.WORKSPACE, spec(DESK_IMAGE, Map.of(), "1000"), "ws-1"), IMAGES)
            .orElseThrow()
            .contains("is not a WORKSPACE image"));
    assertTrue(
        SlotPolicy.DESK
            .refusal(entry(SlotType.DESK, spec("docker.io/library/alpine:3", Map.of(), "1000"), "desk-1"), IMAGES)
            .orElseThrow()
            .contains("is not a DESK image"));
    assertTrue(
        SlotPolicy.WORKSPACE
            .refusal(entry(SlotType.WORKSPACE, spec(WS_IMAGE, Map.of(), "1000"), "ws-1"), SlotImages.NONE)
            .orElseThrow()
            .contains("no WORKSPACE image is pinned"));
  }

  @Test
  void theUserComesFromTheSpecAndIsNeverRoot() {
    for (String root : List.of("0", "00", "root", "0:0", "root:1000", "ROOT")) {
      assertTrue(
          SlotPolicy.WORKSPACE
              .refusal(entry(SlotType.WORKSPACE, spec(WS_IMAGE, Map.of(), root), "ws-1"), IMAGES)
              .isPresent(),
          root);
    }
    for (String none : new String[] {null, "", "  "}) {
      assertTrue(
          SlotPolicy.DESK
              .refusal(entry(SlotType.DESK, spec(DESK_IMAGE, Map.of(), none), "desk-1"), IMAGES)
              .orElseThrow()
              .contains("must name its user"));
    }
    for (String user : List.of("1000", "1001", "1000:1000", "10")) {
      assertEquals(
          Optional.empty(),
          SlotPolicy.DESK.refusal(entry(SlotType.DESK, spec(DESK_IMAGE, Map.of(), user), "desk-1"), IMAGES),
          user);
    }
  }

  @Test
  void aWorkspaceOrDeskLeaseNeedsAWorkKeyOfItsOwnKind() {
    assertTrue(
        SlotPolicy.WORKSPACE
            .refusal(entry(SlotType.WORKSPACE, spec(WS_IMAGE, Map.of(), "1000"), null), IMAGES)
            .isPresent());
    assertTrue(
        SlotPolicy.WORKSPACE
            .refusal(entry(SlotType.WORKSPACE, spec(WS_IMAGE, Map.of(), "1000"), "desk-1"), IMAGES)
            .isPresent());
    assertTrue(
        SlotPolicy.DESK
            .refusal(entry(SlotType.DESK, spec(DESK_IMAGE, Map.of(), "1000"), "desk-../x"), IMAGES)
            .isPresent());
    assertEquals(
        Optional.empty(),
        SlotPolicy.DESK.refusal(
            entry(
                SlotType.DESK,
                spec(DESK_IMAGE, Map.of(), "1000"),
                "desk-5ca0e7e9-7dd8-4128-bbdd-58aa40d9b397"),
            IMAGES));
  }

  // --- fixtures ----------------------------------------------------------------------------------

  private static Map<String, String> env() {
    Map<String, String> env = new LinkedHashMap<>();
    env.put("QITS_TOKEN", "qits_tok_X");
    env.put("TZ", "UTC");
    return env;
  }

  private static LeaseSpec spec(String image, Map<String, String> env, String user) {
    return new LeaseSpec(image, env, user, new Limits("4g", "8g", 512L, "2", 800));
  }

  private static LeaseEntry entry(SlotType type, LeaseSpec spec, String workKey) {
    return new LeaseEntry("l1", type, DesiredState.RUNNING, spec, "h1", workKey, null);
  }

  private static String imageOf(SlotType type) {
    return type == SlotType.DESK ? DESK_IMAGE : WS_IMAGE;
  }

  private static String key(SlotType type) {
    return type == SlotType.DESK ? "desk-7" : "ws-42";
  }
}
