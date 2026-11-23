package eu.wohlben.qits.systemrunner.protocol;

/**
 * The image repository prefix each slot type's lease image must start with, as qits-system pins
 * them in its {@code Ack} ({@link SystemRunnerAck#slotImages()}). A lease whose image does not
 * start with its type's prefix is refused; a null prefix refuses every lease of that type.
 *
 * @param ci the CI step images' prefix
 * @param workspace the workspace image's prefix
 * @param desk the project-agent image's prefix
 * @param agent the long-running agent image's prefix (qits-2519); null from a host that names none
 */
public record SlotImages(String ci, String workspace, String desk, String agent) {

  /** None known: every lease is refused until an {@code Ack} names them. */
  public static final SlotImages NONE = new SlotImages(null, null, null, null);

  /** Without an agent prefix, as a host before qits-2519 names them: every AGENT is refused. */
  public SlotImages(String ci, String workspace, String desk) {
    this(ci, workspace, desk, null);
  }

  /** {@code type}'s prefix, or null. */
  public String of(SlotType type) {
    return switch (type) {
      case CI -> ci;
      case WORKSPACE -> workspace;
      case DESK -> desk;
      case AGENT -> agent;
    };
  }
}
