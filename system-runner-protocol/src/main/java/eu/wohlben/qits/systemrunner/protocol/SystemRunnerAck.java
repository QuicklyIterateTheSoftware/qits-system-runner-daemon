package eu.wohlben.qits.systemrunner.protocol;

import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.runner.protocol.WireMaps;
import eu.wohlben.qits.systemrunner.protocol.SystemRunnerProtocol.Field;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The target runner's keys in the host's {@link Ack} — its {@link Ack#extensions()}: {@code
 * slotImages{ci, workspace, desk}}, {@code probeImage} and {@code registryMirrors}. The host writes
 * them with {@link #extensions()}, the runner reads them with {@link #of(Ack)}; one spelling, here.
 *
 * @param slotImages each slot type's image prefix; never null when decoded ({@link SlotImages#NONE})
 * @param probeImage the pinned workspace image the login probe runs, or null
 * @param registryMirrors {@code from → to} registry mirrors for the build plane; null when the host
 *     sent none, which is not the same as an empty map
 */
public record SystemRunnerAck(
    SlotImages slotImages, String probeImage, Map<String, String> registryMirrors) {

  public SystemRunnerAck {
    slotImages = slotImages == null ? SlotImages.NONE : slotImages;
  }

  /** The keys as the host puts them into {@code new Ack(capability, slots, adopted, extensions)}. */
  public Map<String, Object> extensions() {
    Map<String, Object> map = new LinkedHashMap<>();
    Map<String, Object> images = new LinkedHashMap<>();
    images.put(Field.CI, slotImages.ci());
    images.put(Field.WORKSPACE, slotImages.workspace());
    images.put(Field.DESK, slotImages.desk());
    map.put(Field.SLOT_IMAGES, images);
    map.put(Field.PROBE_IMAGE, probeImage);
    map.put(Field.REGISTRY_MIRRORS, registryMirrors == null ? null : new LinkedHashMap<>(registryMirrors));
    return map;
  }

  /** The keys out of {@code ack}; every absent one is null (or {@link SlotImages#NONE}). */
  public static SystemRunnerAck of(Ack ack) {
    return of(ack.extensions());
  }

  /**
   * The keys out of an {@code Ack}'s extension map.
   *
   * @throws IllegalArgumentException when a key holds the wrong shape
   */
  public static SystemRunnerAck of(Map<String, Object> extensions) {
    Map<String, Object> images = WireMaps.object(extensions, Field.SLOT_IMAGES);
    SlotImages slotImages =
        images == null
            ? SlotImages.NONE
            : new SlotImages(
                WireMaps.str(images, Field.CI),
                WireMaps.str(images, Field.WORKSPACE),
                WireMaps.str(images, Field.DESK));
    return new SystemRunnerAck(
        slotImages,
        WireMaps.str(extensions, Field.PROBE_IMAGE),
        WireMaps.nullableStringMap(extensions, Field.REGISTRY_MIRRORS));
  }
}
