package eu.wohlben.qits.systemrunner;

import eu.wohlben.qits.runner.toolkit.Heartbeat;
import eu.wohlben.qits.runner.toolkit.RunnerExit;
import eu.wohlben.qits.runner.toolkit.RunnerLauncher;
import eu.wohlben.qits.runner.toolkit.RunnerSettings;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import java.io.PrintStream;
import java.util.function.Function;
import org.jboss.logging.Logger;

/**
 * Entry point, and <b>the one CDI shell</b>: it hands {@link SystemRunnerIdentity#IDENTITY}, the
 * domain's constructor and the managed {@code Vertx} to qits-runner-toolkit's {@link
 * RunnerLauncher}, which parses the environment ({@code RunnerSettings}, exit 2 when it is
 * missing), registers, connects and runs. Nothing below reads configuration itself.
 *
 * <p><b>Three answers come before Quarkus starts</b>, as they do in qits-projects-desk-runner-daemon's
 * {@code Main}: the {@code health} subcommand (the container healthcheck: one file's mtime), {@code
 * QITS_SYSTEM_RUNNER_PRINT_VERSION} (the image's smoke probe, whose stdout must be the
 * version and nothing else — no startup line beside it) and {@code
 * QITS_SYSTEM_RUNNER_DECOMMISSION} (the decommission helper). {@link RunnerLauncher} answers
 * each of them without touching Vert.x, so they are handed none; {@code MainTest} holds that true
 * for the pinned toolkit.
 *
 * <p><b>The runner's own settings</b> — the external node volumes ({@link ExternalVolumes}: the credential volume and the agent caches) and
 * the build plane's ({@link BuildkitSettings}: {@code …_BUILDKIT_IMAGE}, {@code
 * …_BUILDKIT_MAX_PARALLELISM}, {@code …_BUILDKIT_HTTP_REGISTRIES}, {@code
 * …_BUILDKIT_REGISTRY_MIRRORS}) — are
 * parsed here from the same environment, in runner mode only, and an invalid one refuses to start
 * with {@link RunnerExit#MISCONFIGURED} and one sentence naming the variable, as the toolkit's own
 * settings do.
 */
@QuarkusMain
public class Main {

  private static final Logger LOG = Logger.getLogger(Main.class);

  public static void main(String... args) {
    if (answersBeforeQuarkus(args, System::getenv)) {
      System.exit(
          RunnerLauncher.main(
              SystemRunnerIdentity.IDENTITY, SystemRunnerDomain::new, null, args));
    }
    Quarkus.run(RunnerApplication.class, args);
  }

  /**
   * Whether {@link RunnerLauncher} answers this invocation from its arguments or one variable
   * alone, in the order it reads them: {@code health}, the version probe, helper mode.
   */
  static boolean answersBeforeQuarkus(String[] args, Function<String, String> env) {
    if (args != null && args.length > 0 && Heartbeat.ARG.equals(args[0])) {
      return true;
    }
    var identity = SystemRunnerIdentity.IDENTITY;
    if (RunnerSettings.printsVersion(env.apply(identity.env(RunnerSettings.PRINT_VERSION)))) {
      return true;
    }
    String decommission = env.apply(identity.env(RunnerLauncher.DECOMMISSION));
    return decommission != null && !decommission.isBlank();
  }

  /** The runner itself, on the Vert.x Quarkus manages. */
  public static class RunnerApplication implements QuarkusApplication {

    @Inject Vertx vertx;

    @Override
    public int run(String... args) {
      return runner(vertx, args, System::getenv, System.out);
    }
  }

  /**
   * The runner: {@link ExternalVolumes} parsed (exit 2 when invalid) unless the toolkit answers
   * this invocation by itself, then {@link RunnerLauncher} with a domain that mounts them.
   */
  static int runner(Vertx vertx, String[] args, Function<String, String> env, PrintStream out) {
    var identity = SystemRunnerIdentity.IDENTITY;
    ExternalVolumes external = ExternalVolumes.NONE;
    BuildkitSettings buildkit = BuildkitSettings.DEFAULTS;
    if (!answersBeforeQuarkus(args, env)) {
      try {
        external = ExternalVolumes.parse(identity, env);
        buildkit = BuildkitSettings.parse(identity, env);
      } catch (RunnerSettings.Invalid invalid) {
        LOG.errorf("%s cannot start: %s. Exiting.", identity.logName(), invalid.getMessage());
        return RunnerExit.MISCONFIGURED;
      }
    }
    ExternalVolumes volumes = external;
    BuildkitSettings plane = buildkit;
    return RunnerLauncher.main(
        identity,
        settings -> new SystemRunnerDomain(settings, volumes, plane),
        vertx,
        args,
        env,
        out);
  }
}
