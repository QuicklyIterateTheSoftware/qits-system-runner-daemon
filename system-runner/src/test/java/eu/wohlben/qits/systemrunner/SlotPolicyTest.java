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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Every cell of the dossier's slot policy table, as {@link SlotPolicy} holds it and as the container
 * spec it builds carries it — and the refusals: an image outside its type's prefix, no user or root, a policy-owned environment key (named in the detail), and a
 * missing or misshapen work key.
 */
class SlotPolicyTest {

  private static final String REGISTRY = "registry.qits.example.eu/qits/";
  private static final SlotImages IMAGES =
      new SlotImages(REGISTRY + "ci-", REGISTRY + "workspace", REGISTRY + "project-agent");
  private static final String WS_IMAGE = REGISTRY + "workspace:2026.1023.1";
  private static final String DESK_IMAGE = REGISTRY + "project-agent:2026.1023.1";
  private static final String CI_IMAGE = REGISTRY + "ci-slot@sha256:abc";
  private static final String NAME = "qits-system-runner-lease-l1-1a2b3c4d";
  private static final SlotPolicy.Mounts MOUNTS =
      new SlotPolicy.Mounts(
          "qits-system-runner-work-ws-42-1a2b3c4d",
          "qits_shared_dot_claude",
          "qits_shared_m2",
          "qits_shared_pnpm");
  private static final SlotPolicy.Mounts ALL_MOUNTS =
      new SlotPolicy.Mounts(
          "qits-system-runner-work-ws-42-1a2b3c4d",
          "qits_shared_dot_claude",
          "qits_shared_m2",
          "qits_shared_pnpm",
          "qits-system-runner-ci-git-1a2b3c4d",
          "qits-system-runner-ci-m2-1a2b3c4d",
          "qits-system-runner-ci-pnpm-1a2b3c4d");
  private static final SlotPolicy.Build BUILD =
      new SlotPolicy.Build(
          "qits-system-runner-build-1a2b3c4d", "tcp://qits-system-runner-buildkitd-1a2b3c4d:1234");

  // --- the table ---------------------------------------------------------------------------------

  @Test
  void ciIsTheDossiersColumn() {
    SlotPolicy ci = SlotPolicy.of(SlotType.CI);
    assertTrue(ci.enabled);
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
    assertFalse(ci.lifecycle().restartsUnlessStopped(), "restart: no");
    assertFalse(ci.agentVolumes(), "no external node volume is ever asked after for CI");
    assertNull(ci.workKeyPrefix);
    assertEquals(List.of("bridge", BUILD.network()), ci.networks(BUILD));

    ContainerSpec c = ci.containerSpec(NAME, spec(CI_IMAGE, env(), "1001"), ALL_MOUNTS, BUILD);
    assertEquals(CI_IMAGE, c.image());
    assertEquals(NAME, c.explicitName());
    assertEquals("1001", c.user());
    assertTrue(c.init(), "--init");
    assertEquals("bridge", c.network(), "the run's network; the build network is joined after");
    assertTrue(c.aliases().isEmpty());
    assertTrue(c.addHosts().isEmpty());
    assertFalse(c.hostDockerSocket(), "never the docker socket");
    assertTrue(c.entrypoint().isEmpty() && c.args().isEmpty(), "the image's own command");
    assertTrue(c.volumeMounts().isEmpty(), "/workspace is the container filesystem");
    assertEquals(
        List.of(
            new ContainerSpec.SharedMount("qits-system-runner-ci-git-1a2b3c4d", "/cache/git"),
            new ContainerSpec.SharedMount("qits-system-runner-ci-m2-1a2b3c4d", "/caches/m2"),
            new ContainerSpec.SharedMount("qits-system-runner-ci-pnpm-1a2b3c4d", "/caches/pnpm")),
        c.sharedMounts());
    Map<String, String> expected = new LinkedHashMap<>(env());
    expected.put("BUILDKIT_HOST", "tcp://qits-system-runner-buildkitd-1a2b3c4d:1234");
    assertEquals(expected, c.env());
    assertFalse(c.env().containsKey("CLAUDE_CONFIG_DIR"), "no /claude-home, no config dir");
    assertFalse(c.env().containsKey("HOME"), "the image's own");
    assertEquals(
        new ContainerSpec.SecurityPosture(true, true, "4g", "8g", 512L, "2", 800), c.security(),
        "cap-drop ALL and no-new-privileges, with the spec's limits");
    assertEquals(ContainerSpec.PullPolicy.MISSING, c.pullPolicy());
  }

  @Test
  void aCiLeaseNeverMountsTheCredentialVolumeTheAgentCachesOrAWorkVolume() {
    for (SlotPolicy.Mounts mounts :
        List.of(
            ALL_MOUNTS,
            new SlotPolicy.Mounts(
                "qits-system-runner-work-ws-42-1a2b3c4d",
                "qits-system-runner-dot-claude-1a2b3c4d",
                "qits-system-runner-m2-1a2b3c4d",
                "qits-system-runner-pnpm-1a2b3c4d",
                "qits-system-runner-ci-git-1a2b3c4d",
                "qits-system-runner-ci-m2-1a2b3c4d",
                "qits-system-runner-ci-pnpm-1a2b3c4d"))) {
      ContainerSpec c =
          SlotPolicy.CI.containerSpec(NAME, spec(CI_IMAGE, Map.of(), "1001"), mounts, BUILD);
      List<String> mounted = new java.util.ArrayList<>();
      c.volumeMounts().forEach(m -> mounted.add(m.volumeName()));
      c.sharedMounts().forEach(m -> mounted.add(m.sharedName()));
      for (String never :
          List.of(mounts.workVolume(), mounts.credential(), mounts.m2(), mounts.pnpm())) {
        assertFalse(mounted.contains(never), never + " in " + mounted);
      }
      assertEquals(List.of(mounts.ciGit(), mounts.ciM2(), mounts.ciPnpm()), mounted);
      List<String> targets = new java.util.ArrayList<>();
      c.volumeMounts().forEach(m -> targets.add(m.containerPath()));
      c.sharedMounts().forEach(m -> targets.add(m.containerPath()));
      assertFalse(targets.contains("/claude-home"));
      assertFalse(targets.contains("/workspace"));
    }
  }

  @Test
  void aWorkspaceOrDeskLeaseNeverMountsACiCacheOrGetsBuildkitHost() {
    for (SlotType type : List.of(SlotType.WORKSPACE, SlotType.DESK)) {
      ContainerSpec c =
          SlotPolicy.of(type).containerSpec(NAME, spec(imageOf(type), env(), "1000"), ALL_MOUNTS, BUILD);
      for (ContainerSpec.SharedMount m : c.sharedMounts()) {
        assertFalse(m.sharedName().contains("-ci-"), type + " " + m);
      }
      assertFalse(c.env().containsKey("BUILDKIT_HOST"), type.name());
      assertEquals(List.of("bridge"), SlotPolicy.of(type).networks(BUILD), type.name());
    }
  }

  @Test
  void aCiLeaseWithoutItsBuildPlaneIsNeverSpecified() {
    assertThrows(
        IllegalStateException.class,
        () -> SlotPolicy.CI.containerSpec(NAME, spec(CI_IMAGE, Map.of(), "1001"), ALL_MOUNTS));
    assertThrows(IllegalStateException.class, () -> SlotPolicy.CI.networks(null));
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
  void aCiLeaseOfTheCiImageAndANonRootUserPasses() {
    assertEquals(
        Optional.empty(),
        SlotPolicy.CI.refusal(entry(SlotType.CI, spec(CI_IMAGE, env(), "1001"), null), IMAGES),
        "no work key: CI has no work volume");
    assertTrue(
        SlotPolicy.CI
            .refusal(entry(SlotType.CI, spec(WS_IMAGE, env(), "1001"), null), IMAGES)
            .orElseThrow()
            .contains("is not a CI image"));
    assertTrue(
        SlotPolicy.CI
            .refusal(entry(SlotType.CI, spec(CI_IMAGE, env(), "0"), null), IMAGES)
            .orElseThrow()
            .contains("may not run as user 0"));
  }

  @Test
  void aSpecEnvKeyThePolicyOwnsIsRefusedNamingIt() {
    for (SlotType type : List.of(SlotType.CI, SlotType.WORKSPACE, SlotType.DESK)) {
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
    return switch (type) {
      case CI -> CI_IMAGE;
      case WORKSPACE -> WS_IMAGE;
      case DESK -> DESK_IMAGE;
    };
  }

  private static String key(SlotType type) {
    return switch (type) {
      case CI -> null;
      case WORKSPACE -> "ws-42";
      case DESK -> "desk-7";
    };
  }
}
