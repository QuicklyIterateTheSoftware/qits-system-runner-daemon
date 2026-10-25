package eu.wohlben.qits.systemrunner;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The process seam a sign-in runs its {@code docker run -i} through: stdin open, stdout and stderr
 * merged and streamed as they come. The toolkit's {@code RunnerDocker.run} captures a whole run
 * under a deadline and gives a command no stdin, which is right for every other docker call and
 * wrong for a login that prints a url, then waits minutes for a code on its stdin — so this seam is
 * the runner's own, and {@link SignIns}'s suite drives a fake of it rather than docker.
 */
interface SignInProcesses {

  /** One started process. */
  interface Running {

    /** Write {@code text} to its stdin and flush. */
    void write(String text) throws IOException;

    /** Completes with the exit code once the process ended and every byte of its output was read. */
    CompletableFuture<Integer> exit();

    /** Kill it; idempotent. The container behind a {@code docker run} is the caller's to remove. */
    void kill();
  }

  /**
   * Start {@code argv}. {@code output} is called with each chunk as it is read — not per line: a
   * prompt is printed without a newline after it — from one thread of the process's own.
   */
  Running start(List<String> argv, Consumer<String> output) throws IOException;

  /** The real thing: a forked process and a daemon thread reading its output in chunks. */
  SignInProcesses FORKED =
      (argv, output) -> {
        Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
        CompletableFuture<Integer> exit = new CompletableFuture<>();
        Thread reader =
            new Thread(
                () -> {
                  char[] buffer = new char[4096];
                  try (Reader in =
                      new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                    int n;
                    while ((n = in.read(buffer)) >= 0) {
                      if (n > 0) {
                        output.accept(new String(buffer, 0, n));
                      }
                    }
                  } catch (IOException | RuntimeException ended) {
                    // a killed process closes its stream mid-read; its exit code still comes
                  }
                  try {
                    exit.complete(process.waitFor());
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    exit.complete(-1);
                  }
                },
                "system-runner-sign-in-output");
        reader.setDaemon(true);
        reader.start();
        return new Running() {
          @Override
          public void write(String text) throws IOException {
            OutputStream in = process.getOutputStream();
            in.write(text.getBytes(StandardCharsets.UTF_8));
            in.flush();
          }

          @Override
          public CompletableFuture<Integer> exit() {
            return exit;
          }

          @Override
          public void kill() {
            process.destroyForcibly();
          }
        };
      };
}
