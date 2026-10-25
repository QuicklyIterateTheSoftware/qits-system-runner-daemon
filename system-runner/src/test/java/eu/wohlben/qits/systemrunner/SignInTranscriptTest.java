package eu.wohlben.qits.systemrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link SignInTranscript} over the measured transcripts — Claude Code 2.1.283's {@code auth
 * login} and kimi's {@code login} in the workspace image — fed in chunks that cut lines, urls and
 * escape sequences, with the ANSI noise a CLI writes around them.
 */
class SignInTranscriptTest {

  private static final String CLAUDE_URL =
      "https://claude.com/cai/oauth/authorize?code=true"
          + "&client_id=9d1c250a-e61b-44d9-88ed-5944d1962f5e&response_type=code"
          + "&redirect_uri=https%3A%2F%2Fplatform.claude.com%2Foauth%2Fcode%2Fcallback"
          + "&scope=org%3Acreate_api_key+user%3Aprofile&code_challenge=x-Y_z&state=AbC123";
  private static final String KIMI_URL =
      "https://www.kimi.com/code/authorize_device?user_code=ABCD-1234";

  @Test
  void claudesUrlIsTheOneOnTheVisitLineOnceItIsWholeAndThePromptNeedsNoNewline() {
    SignInTranscript t = new SignInTranscript();
    t.append("\u001B[?25l\u001B[2mOpening browser to sign in…\u001B[22m\n");
    assertEquals(Optional.empty(), t.claudeUrl());
    t.append("If the browser didn't open, visit: " + CLAUDE_URL.substring(0, 60));
    assertEquals(Optional.empty(), t.claudeUrl(), "half a url is never relayed");
    t.append(CLAUDE_URL.substring(60) + "\n");
    t.append("\u001B[1mPaste code here if prompted > \u001B[0m");
    assertEquals(Optional.of(CLAUDE_URL), t.claudeUrl());
    assertEquals("Paste code here if prompted >", t.lastLine());
  }

  @Test
  void aHyperlinkEscapeAroundTheUrlAndACarriageReturnAreCleanedAway() {
    SignInTranscript t = new SignInTranscript();
    t.append(
        "If the browser didn't open, visit: \u001B]8;;"
            + CLAUDE_URL
            + "\u001B\\"
            + CLAUDE_URL
            + "\u001B]8;;\u001B\\\r\nPaste code here if prompted > ");
    assertEquals(Optional.of(CLAUDE_URL), t.claudeUrl());
  }

  @Test
  void anEscapeSequenceCutBetweenTwoChunksIsCleanedOnceTheRestArrives() {
    SignInTranscript t = new SignInTranscript();
    t.append("visit: " + CLAUDE_URL + "\u001B[");
    t.append("0m\nPaste code here if prompted > ");
    assertEquals(Optional.of(CLAUDE_URL), t.claudeUrl());
  }

  @Test
  void kimisUrlAndUserCodeAreReadFromItsDeviceLogin() {
    SignInTranscript t = new SignInTranscript();
    t.append("Opening browser for Kimi device login: " + KIMI_URL);
    assertEquals(Optional.empty(), t.kimiUrl(), "not whole until whitespace follows");
    t.append("\n");
    assertEquals(Optional.of(KIMI_URL), t.kimiUrl());
    assertEquals(Optional.of("ABCD-1234"), t.kimiUserCode(), "from the url's user_code");
    t.append(
        "If the browser did not open, paste the URL above and enter code: "
            + "\u001B[1mWXYZ-9876\u001B[0m\n"
            + "Code expires in 1800s.\nWaiting for authorization to complete...\n");
    assertEquals(Optional.of("WXYZ-9876"), t.kimiUserCode(), "the printed code wins");
    assertEquals("Waiting for authorization to complete...", t.lastLine());
  }

  @Test
  void theLastLineIsTheLastNonBlankOneCutAtThreeHundredCharacters() {
    SignInTranscript t = new SignInTranscript();
    assertNull(t.lastLine());
    t.append(
        "Paste code here if prompted > "
            + "\u001B[31mLogin failed: Request failed with status code 400\u001B[0m\n\n  \n");
    assertEquals(
        "Login failed: Request failed with status code 400",
        t.lastLine(),
        "the code is not echoed, so the failure lands on the prompt's line");
    t.append("x".repeat(500) + "\n");
    assertEquals("x".repeat(300), t.lastLine());
  }

  @Test
  void onlyTheTailIsKept() {
    SignInTranscript t = new SignInTranscript();
    t.append("visit: " + CLAUDE_URL + "\n");
    t.append("y".repeat(SignInTranscript.KEEP));
    assertEquals(Optional.empty(), t.claudeUrl());
    assertEquals(SignInTranscript.KEEP, t.text().length());
  }
}
