package eu.wohlben.qits.systemrunner.protocol;

/**
 * The image repository prefix each slot type's lease image must start with, as qits-system pins
 * them in its {@code Ack} ({@link SystemRunnerAck#slotImages()}). A lease whose image does not
 * start with its type's prefix is refused; a null prefix refuses every lease of that type.
 *
 * @param ci the CI step images' prefix
 * @param workspace the workspace image's prefix
 * @param desk the project-agent image's prefix
 */
public record SlotImages(String ci, String workspace, String desk) {

  /** None known: every lease is refused until an {@code Ack} names them. */
  public static final SlotImages NONE = new SlotImages(null, null, null);

  /** {@code type}'s prefix, or null. */
  public String of(SlotType type) {
    return switch (type) {
      case CI -> ci;
      case WORKSPACE -> workspace;
      case DESK -> desk;
    };
  }
}
