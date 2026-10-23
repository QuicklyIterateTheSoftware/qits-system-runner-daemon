# HOW THE RUNNER IS BUILT — qits-projects-desk-runner-daemon's docker/Dockerfile with this repository's
# names, itself qits-ci-runner-daemon's with one input more: the platform's own maven repository.
#
# THE ARTIFACT IS AN IMAGE. A target runner is the static `qits-system-runner` binary running
# inside a container on its node, under docker's restart policy, with the host's docker socket
# bind-mounted — the install script qits-system renders (qits-runner-toolkit's template)
# `docker run`s it, and a runner rolls itself over to a new version by starting a successor container
# from the next tag of the same image. So the last stage, and the default target, is `image`: the
# binary and a docker CLI, pushed as `<registry>/qits/qits-system-runner:<version>`. The `binary`
# target still exports the bare file for a developer who wants it; nothing publishes it.
#
# The toolchain image is `docker/Dockerfile.musl-builder`, built first and named here by
# BUILDER_IMAGE; its LAST stage must stay the toolchain, which is why the build stages live in this
# file and not in that one.
#
# A developer's build is two lines and needs the platform's maven repository (see below):
#
#     docker build -t qits/graalvmce-musl-builder:jdk-25 -f docker/Dockerfile.musl-builder docker/
#     docker build -t qits-system-runner:dev -f docker/Dockerfile .
#
# (`--target binary --output type=local,dest=out` instead of `-t` for the file alone.)
#
ARG BUILDER_IMAGE=qits/graalvmce-musl-builder:jdk-25

# --- stage `build`: the musl native compile -------------------------------------------------------
# USER root because the base image ends on 1001 and the checkout arrives owned by root.
FROM ${BUILDER_IMAGE} AS build
USER root
WORKDIR /qits-build

# The whole reactor. `-am` builds system-runner's dependency from source, and it has to: the
# published `eu.wohlben.qits:qits-system-runner-protocol` is never at the version being built
# here — the reactor's version is the one about to be tagged — so the binary and the protocol it
# speaks come out of one compile of one tree. `.dockerignore` keeps target/ and out/ out of the
# context.
COPY . .

# Both platform hosts are code plus one input, the public domain (qits-731):
#
#   https://registry.qits.$QITS_DOMAIN/artifacts/maven/maven   hosted Maven (qits-artifacts) — NEW
#     against qits-ci-runner-daemon: qits-runner-protocol, qits-runner-toolkit and the
#     qits-containers-driver beneath them live only here. `-s .qits-maven-settings.xml` is what makes
#     it resolvable: its exact-id `qits-maven-network` mirror is the one thing that outranks Maven's
#     http blocker, and it reads QITS_MAVEN_REPOSITORY_URL — set in the RUN below, as
#     qits-projects-daemon's docker/Dockerfile sets it.
#   https://mirror.qits.$QITS_DOMAIN/mirror/maven/central      Maven Central cache (qits-mirror)
#
# Both answer 401 anonymously on the edge, so every build that resolves through them needs a
# credential: the two secret mounts on the mvnw RUN below, exported as the QITS_MAVEN_AUTH_USR/
# QITS_MAVEN_AUTH_PSW `.qits-maven-settings.xml` reads for both servers. qits-ci-daemon's
# docker/Dockerfile has the measurement.
ARG QITS_DOMAIN=wohlben.eu

# `quarkus.native.container-build=false`: we are already inside the container it would have launched.
#
# The `ldd` assertion rides on the same RUN because this is where the binary is: GraalVM emits a
# static-pie executable, so `file` never says "statically linked", and a glibc-linked runner would not
# start on half the hosts a person might install it on.
#
# `makeBom` rides on the same invocation so the QA build and the release build are byte-identical LLB
# and the release's `target=sbom` export is a cache hit. cyclonedx.skipNotDeployed=false is
# load-bearing (qits-ci-daemon's Dockerfile has the measurement): `system-runner` is packaging
# quarkus and skips maven-deploy, and the plugin silently skips deploy-skipping modules without it.
RUN --mount=type=secret,id=qits-client-id \
    --mount=type=secret,id=qits-client-secret \
    export QITS_MAVEN_AUTH_USR="$(cat /run/secrets/qits-client-id 2>/dev/null || true)"; \
    export QITS_MAVEN_AUTH_PSW="$(cat /run/secrets/qits-client-secret 2>/dev/null || true)"; \
    export QITS_MAVEN_CENTRAL_URL="https://mirror.qits.$QITS_DOMAIN/mirror/maven/central"; \
    export QITS_MAVEN_REPOSITORY_URL="https://registry.qits.$QITS_DOMAIN/artifacts/maven/maven"; \
    ./mvnw -B -ntp -s .qits-maven-settings.xml -pl system-runner -am package -Dnative -DskipTests \
      -Dqits.maven.repository.url="$QITS_MAVEN_REPOSITORY_URL" \
      -Dquarkus.native.container-build=false \
      org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeBom \
      -DoutputFormat=json -DoutputName=sbom -DschemaVersion=1.6 \
      -Dcyclonedx.skipNotDeployed=false \
 && ldd system-runner/target/qits-system-runner 2>&1 | grep -qE "statically linked|not a dynamic executable"

# --- sbom -----------------------------------------------------------------------------------------
# The system-runner module's CycloneDX document, reached with `--opt target=sbom --output
# type=local`. BEFORE the `image` stage on purpose: the last stage is what a plain build produces,
# and that must stay the artifact.
FROM scratch AS sbom
COPY --from=build /qits-build/system-runner/target/sbom.json /sbom.json

# --- binary ---------------------------------------------------------------------------------------
# The bare file, for a developer: `--opt target=binary --output type=local,dest=out` writes
# `out/qits-system-runner`. Not released — the image below is. `.dockerignore` excludes `out/` so
# a second export against the same context stays a cache hit.
FROM scratch AS binary
COPY --from=build /qits-build/system-runner/target/qits-system-runner /qits-system-runner

# --- image ----------------------------------------------------------------------------------------
# THE ARTIFACT, AND THE DEFAULT TARGET: `<registry>/qits/qits-system-runner:<version>`.
#
# The base is the official docker CLI image because the runner shells `docker` for every container it
# starts, removes and inspects — its own successor and every lease included — against the host's
# daemon through the bind-mounted socket; it needs the CLI and nothing else. Pinned to a full version,
# the same one qits-ci-runner-daemon's image carries, not `cli`: a runner image is a release, and the
# CLI in it should not move between two builds of one tag. A bare Docker Hub name on purpose — the
# platform's builder rewrites docker.io to its mirror, and a developer's build pulls from Hub
# directly. The static binary needs no libc from the base at all.
#
# ENTRYPOINT replaces the base's `docker-entrypoint.sh`, and the empty CMD drops its `sh`, which
# would otherwise arrive as the runner's first argument. It runs as root: the socket it is handed is
# root-equivalent whoever holds it, and a user inside the image would only need the socket's group
# id, which differs per host. The state directory is where the install script mounts the runner's
# state volume; creating it here makes a mount-less `docker run` of the image fail on registration
# rather than on a missing directory.
#
# No HEALTHCHECK, as in qits-ci-runner-daemon's image: `qits-system-runner health` is there for
# whoever wants to gate on it, and the install script and a successor's `docker run` carry the same
# container contract without one.
FROM docker:29.8.1-cli AS image
COPY --from=build /qits-build/system-runner/target/qits-system-runner /usr/local/bin/qits-system-runner
RUN mkdir -p /var/lib/qits-system-runner && chmod 700 /var/lib/qits-system-runner
ENTRYPOINT ["/usr/local/bin/qits-system-runner"]
CMD []
