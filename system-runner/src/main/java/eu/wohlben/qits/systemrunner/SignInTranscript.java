package eu.wohlben.qits.systemrunner;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a login CLI printed so far, read for the three things a sign-in relays: the browser url,
 * Kimi's user code, and the last line a failure ends on. Output arrives in chunks that may cut a
 * line, an escape sequence or a url anywhere, so every reading is made over the whole transcript,
 * cleaned of ANSI escapes, and a url or a code counts only once whitespace follows it — a half-read
 * url is never relayed.
 *
 * <p>The measured transcripts (Claude Code 2.1.283, kimi, in the workspace image):
 *
 * <pre>
 *   Opening browser to sign in…
 *   If the browser didn't open, visit: https://claude.com/cai/oauth/authorize?code=true&amp;…
 *   Paste code here if prompted &gt; ␣         ← no newline
 *
 *   Opening browser for Kimi device login: https://www.kimi.com/code/authorize_device?user_code=ABCD-1234
 *   If the browser did not open, paste the URL above and enter code: ABCD-1234
 *   Code expires in 1800s.
 *   Waiting for authorization to complete...
 * </pre>
 *
 * <p>Not thread-safe: the sign-in that owns it reads and appends under its own lock.
 */
final class SignInTranscript {

  /** What is kept: the tail, so a CLI that never stops printing cannot grow the runner. */
  static final int KEEP = 64 * 1024;

  /** A failure's detail is at most this long. */
  static final int DETAIL_MAX = 300;

  private static final Pattern OSC =
      Pattern.compile("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)");
  private static final Pattern CSI = Pattern.compile("\u001B\\[[0-?]*[ -/]*[@-~]");
  private static final Pattern ESCAPE = Pattern.compile("\u001B[@-Z\\\\-_]?");
  private static final Pattern CONTROL =
      Pattern.compile("[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]");

  private static final Pattern CLAUDE_URL = Pattern.compile("visit:\\s*(https://\\S+)\\s");
  private static final Pattern URL = Pattern.compile("(https://\\S+)\\s");
  private static final Pattern ENTER_CODE = Pattern.compile("enter code:\\s*([A-Za-z0-9-]+)\\s");
  private static final Pattern USER_CODE_PARAM = Pattern.compile("[?&]user_code=([A-Za-z0-9-]+)");

  /**
   * Claude Code's prompt. Stdin is a pipe, so nothing echoes the code and the CLI's next words
   * land on the prompt's own line; a failure's detail is those words, without the prompt.
   */
  static final String CLAUDE_PROMPT = "Paste code here if prompted >";

  private final StringBuilder raw = new StringBuilder();

  void append(String chunk) {
    raw.append(chunk);
    if (raw.length() > KEEP) {
      raw.delete(0, raw.length() - KEEP);
    }
  }

  /** The transcript as a person would read it: no escapes, a carriage return a line break. */
  String text() {
    return clean(raw.toString());
  }

  /** Claude Code's url: the {@code https} one on the {@code visit:} line, once whole. */
  Optional<String> claudeUrl() {
    return group(CLAUDE_URL, text());
  }

  /** Kimi Code's device url: the first {@code https} url, once whole. */
  Optional<String> kimiUrl() {
    return group(URL, text());
  }

  /** Kimi Code's user code: after {@code enter code:}, else the url's {@code user_code}. */
  Optional<String> kimiUserCode() {
    String text = text();
    Optional<String> entered = group(ENTER_CODE, text);
    if (entered.isPresent()) {
      return entered;
    }
    return kimiUrl().flatMap(url -> group(USER_CODE_PARAM, url));
  }

  /**
   * The last non-blank line, stripped, without a {@link #CLAUDE_PROMPT} in front of other words, and
   * at most {@link #DETAIL_MAX} long; null when none.
   */
  String lastLine() {
    String[] lines = text().split("\n");
    for (int i = lines.length - 1; i >= 0; i--) {
      String line = lines[i].strip();
      if (line.startsWith(CLAUDE_PROMPT) && line.length() > CLAUDE_PROMPT.length()) {
        line = line.substring(CLAUDE_PROMPT.length()).strip();
      }
      if (!line.isEmpty()) {
        return line.length() > DETAIL_MAX ? line.substring(0, DETAIL_MAX) : line;
      }
    }
    return null;
  }

  /** {@code text} without ANSI escapes or control characters but newlines and tabs. */
  static String clean(String text) {
    String s = text.replace("\r\n", "\n").replace('\r', '\n');
    s = OSC.matcher(s).replaceAll("");
    s = CSI.matcher(s).replaceAll("");
    s = ESCAPE.matcher(s).replaceAll("");
    return CONTROL.matcher(s).replaceAll("");
  }

  private static Optional<String> group(Pattern pattern, String text) {
    Matcher m = pattern.matcher(text);
    return m.find() ? Optional.of(m.group(1)) : Optional.empty();
  }
}
