package eu.wohlben.qits.systemrunner.protocol;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which environment keys name a secret: the one rule the runner redacts a log or an exec by, and
 * qits-system-service redacts an inspect by, so the two can never disagree (qits-1621).
 *
 * <p>The key is read by its <b>segments</b> — split on {@code _} and every other non-alphanumeric
 * character, case ignored — never by substring, so {@code AUTH} inside {@code GIT_AUTHOR_NAME} or
 * {@code TOKEN} inside {@code TOKENIZER_PATH} is not a match. A key is secret when
 *
 * <ul>
 *   <li>any segment is {@code TOKEN}, {@code SECRET}, {@code PASSWORD}, {@code PASSWD}, {@code
 *       CREDENTIAL}, {@code CREDENTIALS} or {@code APIKEY} ({@code QITS_TOKEN_SUBJECT}, {@code
 *       DB_PASSWORD});
 *   <li>two consecutive segments are {@code API, KEY} or {@code PRIVATE, KEY} ({@code X_API_KEY},
 *       {@code SSH_PRIVATE_KEY});
 *   <li>or the last segment is {@code AUTH} ({@code BASIC_AUTH}, but not {@code
 *       QITS_GIT_AUTH_HOST}).
 * </ul>
 */
public final class SecretKeys {

  private static final Set<String> SECRET_SEGMENTS =
      Set.of("TOKEN", "SECRET", "PASSWORD", "PASSWD", "CREDENTIAL", "CREDENTIALS", "APIKEY");

  private static final Set<String> KEY_QUALIFIERS = Set.of("API", "PRIVATE");

  private SecretKeys() {}

  /** Whether {@code key}'s value is a secret; a null or blank key is not. */
  public static boolean isSecret(String key) {
    if (key == null) {
      return false;
    }
    List<String> segments =
        List.of(key.toUpperCase(Locale.ROOT).split("[^A-Z0-9]+")).stream()
            .filter(s -> !s.isEmpty())
            .toList();
    if (segments.isEmpty()) {
      return false;
    }
    for (int i = 0; i < segments.size(); i++) {
      String segment = segments.get(i);
      if (SECRET_SEGMENTS.contains(segment)) {
        return true;
      }
      if (i > 0 && segment.equals("KEY") && KEY_QUALIFIERS.contains(segments.get(i - 1))) {
        return true;
      }
    }
    return segments.getLast().equals("AUTH");
  }
}
