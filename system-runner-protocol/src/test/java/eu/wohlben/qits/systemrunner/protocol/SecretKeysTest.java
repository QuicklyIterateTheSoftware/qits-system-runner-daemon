package eu.wohlben.qits.systemrunner.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** {@link SecretKeys}: segments, never substrings — the table qits-1621's verify asked for. */
class SecretKeysTest {

  @ParameterizedTest(name = "{0} is secret: {1}")
  @CsvSource({
    "QITS_TOKEN, true",
    "QITS_TOKEN_SUBJECT, true",
    "QITS_WORKSPACE_DAEMON_API_TOKEN, true",
    "DB_PASSWORD, true",
    "BASIC_AUTH, true",
    "X_API_KEY, true",
    "SSH_PRIVATE_KEY, true",
    "API_KEY, true",
    "apikey, true",
    "my-credentials, true",
    "AWS_SECRET_ACCESS_KEY, true",
    "MYSQL_PASSWD, true",
    "client.secret, true",
    "GIT_AUTHOR_NAME, false",
    "GIT_AUTHOR_EMAIL, false",
    "QITS_GIT_AUTH_HOST, false",
    "GIT_COMMITTER_EMAIL, false",
    "TOKENIZER_PATH, false",
    "AUTHOR, false",
    "KEY, false",
    "PUBLIC_KEY, false",
    "API_KEYRING, false",
    "TZ, false",
    "'', false",
    "___, false"
  })
  void aKeyIsSecretByItsSegments(String key, boolean secret) {
    assertEquals(secret, SecretKeys.isSecret(key), key);
  }

  @Test
  void aNullKeyIsNotSecret() {
    assertFalse(SecretKeys.isSecret(null));
  }
}
