# qits-system-runner-daemon

The **qits target runner**: a container you start on any Linux machine with docker so that machine
becomes an **execution target** of qits-system — a host with typed slots that runs the **leases**
qits-system allocates it: coding workspaces (qits-workspaces) and project front desks
(qits-projects) and CI runs (qits-ci), one lease per run. It registers once, holds one outbound
connection to qits-system through the platform's public edge, and keeps every lease it is told
about converged as a plain `docker run` container on this machine, under its slot type's fixed
policy.

qits-system keeps the leases — which lease, on which target, with which spec. The runner only
starts, recreates, stops and removes the lease containers it is told to, and reports what it has.

    ./mvnw verify                                                          # the JVM suite
    docker build -t qits-system-runner:dev -f docker/Dockerfile .          # the image (see docker/Dockerfile)

## Identity

Every name the runner uses is derived from these roots (`SystemRunnerProtocol`, built into
`SystemRunnerIdentity`; the toolkit's `KnownRunnerNamespaces.SYSTEM` holds the first four):

| | |
|---|---|
| kind | `targets` |
| environment prefix | `QITS_SYSTEM_RUNNER_` |
| label root | `qits.system.runner` |
| name prefix | `qits-system-runner` |
| image | `qits/qits-system-runner` |
| state directory | `/var/lib/qits-system-runner` |
| register door | `/system/api/targets/{id}/register` |
| control socket | `/system/targets/socket` |
| log source | `qits-system-runner` |
| work label suffix | `lease` (`qits.system.runner.lease=<leaseId>`) |
| held / adopted lists | `heldLeases` / `adoptedLeases` |
| capability version | `1` |

## What the runner does with a lease

The server's `estate` is the whole desired state of this target, and the runner converges every
lease in it (`SystemRunnerDomain`, `LeaseLauncher`):

- **RUNNING, no container** — the image is pulled when absent (under the runner's own registry
  login, never your docker config), the work volume and the node's credential and cache volumes
  are created labelled, and the lease is `docker run` under its `SlotPolicy`.
- **RUNNING, same spec hash** — left alone; reported `RUNNING`, or `EXITED` with its exit code. An
  exited lease is **never restarted** by the runner: its owner ends it.
- **RUNNING, another spec hash** — stopped, the container removed (never its work volume), run
  again with the new spec.
- **ENDED** — stopped and removed; the work volume stays.
- A lease container the estate no longer lists is stopped and removed — and nothing at all is
  removed before an estate has arrived on the current connection.
- A **work volume is removed only on an `estate.purges` entry** naming it (one of this runner's
  own, or a retired runner's adopted volume), and never while a RUNNING lease of the estate mounts
  it.

It reports an `inventory` at the greeting, around every convergence and whenever the periodic look
sees a change; probes the agent logins on the credential volume (`loginState`) after every
connection, on request, then every 5 minutes while either is not PRESENT and hourly otherwise; and
answers a `healthCheck` with the toolkit's `docker`, `nodeInventory` and `session` checks plus
`login` (reports only — the server decides), `buildkit`, `network` and `idRange` (see "The build
plane"). It never asks for work: there is no `reserve`, the
server allocates.

### Slot policies

A spec carries an image, an environment, a user and limits — nothing else. Everything below is
fixed in the runner (`SlotPolicy`, every cell asserted by `SlotPolicyTest`):

| | CI | WORKSPACE | DESK |
|---|---|---|---|
| image must start with | `Ack.slotImages.ci` | `Ack.slotImages.workspace` | `Ack.slotImages.desk` |
| user | spec, never 0 | spec, never 0 | spec, never 0 |
| `/claude-home` | never | credential volume, rw | credential volume, rw |
| `/workspace` | container filesystem | work volume | work volume |
| caches | `ci-git` → `/cache/git`, `ci-m2` → `/caches/m2`, `ci-pnpm` → `/caches/pnpm` | `m2` → `/caches/m2`, `pnpm` → `/caches/pnpm` | as WORKSPACE |
| networks | `bridge` + the build network | `bridge` | `bridge` |
| `BUILDKIT_HOST` | injected | never | never |
| cap-drop ALL, no-new-privileges | yes, yes | no, no | no, no |
| `--init` | yes | no | yes |
| restart | no | no | unless-stopped |
| on ENDED | container removed | container removed, work volume kept | as WORKSPACE |

A CI lease gets `BUILDKIT_HOST=tcp://qits-system-runner-buildkitd-<id8>:1234`, mounts the CI caches
and nothing else — **never the credential volume, the agent caches or a work volume** — and is run
on `bridge`, then joined to the build network with `docker network connect` (the driver's
`ContainerSpec` carries one network); a connect that fails removes the container again and fails
the lease. Its user is the spec's (1001 from qits-ci). WORKSPACE and DESK get `CLAUDE_CONFIG_DIR=/claude-home/.claude`
from the policy; a spec env key the policy owns — `BUILDKIT_HOST`, `HOME`, `CLAUDE_CONFIG_DIR` — is
`FAILED` with a detail naming it. A WORKSPACE lease's work key is `ws-<rowId>`, a DESK lease's
`desk-<deskId>`.

### Legacy adoption

A lease may carry `adopt{container, volume}`: the retired runner's container and work volume it
takes over. Only names matching the retired runners' own patterns are accepted
(`LegacyAdoption`):

- WORKSPACE: `^qits-workspaces-runner-ws-[0-9a-f-]{36}-[0-9a-f]{8}$`
- DESK: `^qits-projects-desk-runner-desk-[0-9a-f-]{36}-[0-9a-f]{8}$`

Before the lease starts, the runner stops and removes that container (if present) and mounts that
volume as `/workspace`. It never creates such a volume, removes one only on a `purges` entry, and
fails any other name with `adopt name not allowed`.

## Installing a target (for the person at the machine)

A target is **one docker container**: the static `qits-system-runner` binary in the image
`registry.qits.<domain>/qits/qits-system-runner:<version>`, started with docker's own restart
policy (`unless-stopped`) and the host's docker socket mounted so it can start leases. No service
to install, no binary on the host. It is never on the platform's internal network: it reaches the
platform only through its public names.

### What you need

- **Linux on x86-64** with **Docker** installed and running.
- **Outbound HTTPS** to the platform's domain (`system.qits.<domain>`, `idp.qits.<domain>`,
  `registry.qits.<domain>`). **No inbound port** is ever opened.
- **root**, or a user in the `docker` group — the install line uses `sudo`.
- Disk for the leases: their work volumes, the caches and the workspace and project-agent images.

### Install

The one line from the system targets page — qits-runner-toolkit's `InstallScript`, served at
`GET /system/api/targets/install.sh`:

    curl -fsSL -H 'Authorization: Bearer qits_tok_…' https://system.qits.<domain>/system/api/targets/install.sh | sudo env QITS_SYSTEM_RUNNER_URL=https://system.qits.<domain> QITS_SYSTEM_RUNNER_ID=<id> QITS_SYSTEM_RUNNER_REGISTRATION_TOKEN=qits_tok_… QITS_SYSTEM_RUNNER_SLOTS=<slots> sh

The page passes `QITS_SYSTEM_RUNNER_CREDENTIAL_VOLUME`, `_M2_VOLUME` and `_PNPM_VOLUME` through when
set. Treat the line as a secret until the runner has registered. The script replaces any earlier
install of this target (a container labelled `qits.system.runner.process=<id>` — **never a lease**)
and starts `qits-system-runner-<id8>-<version>` with the docker socket and the state volume
`qits-system-runner-state-<id8>` mounted at `/var/lib/qits-system-runner`.

On the **platform host** the line sets

    QITS_SYSTEM_RUNNER_CREDENTIAL_VOLUME=qits_shared_dot_claude QITS_SYSTEM_RUNNER_M2_VOLUME=qits_shared_m2 QITS_SYSTEM_RUNNER_PNPM_VOLUME=qits_shared_pnpm

so the target's one credential volume is the one the admin workspaces and qits-workspaces'
transcript archive already use. Every other target gets its own derived volumes.

### Signing in

`loginState.volume` names the credential volume; the targets page renders the login command from
it, so it can never name another volume:

    docker run --rm -it --user 1000 --entrypoint claude -v <volume>:/claude-home -e HOME=/claude-home -e CLAUDE_CONFIG_DIR=/claude-home/.claude <probeImage>

Kimi's has the same shape with `--entrypoint kimi … login`.

### Updating

Nothing to do: the toolkit's `Upgrade` / `Retire`, on every target, the platform host included.
qits-system pins the runner version it hands out (the version of the `qits-system-runner-protocol`
jar it compiles against). **An update never stops a lease**: the successor starts at once and adopts
the running leases by their label.

### The node namespace

`<id8>` is the target id's first eight characters.

| What | Name | Labels |
|---|---|---|
| the runner | `qits-system-runner-<id8>-<version>` | `qits.system.runner.process=<id>`, `qits.system.runner.version=<version>` |
| its state | volume `qits-system-runner-state-<id8>` | — |
| a lease | container `qits-system-runner-lease-<leaseId>-<id8>` | `qits.system.runner=<id>`, `qits.system.runner.lease=<leaseId>`, `qits.system.runner.slot-type=<type>`, `qits.system.runner.spec-hash=<hash>`, `qits.system.runner.mounts=<hash of its volumes>` |
| a work volume | `qits-system-runner-work-<workKey>-<id8>` (`ws-<rowId>` / `desk-<deskId>`) | `qits.system.runner=<id>`, `qits.system.runner.work-key=<workKey>` |
| the credential volume | `QITS_SYSTEM_RUNNER_CREDENTIAL_VOLUME` (external: must exist, never created or removed), else `qits-system-runner-dot-claude-<id8>`, created at the `Ack` | `qits.system.runner=<id>` (external: none) |
| the agent caches | `…_M2_VOLUME` / `…_PNPM_VOLUME`, else `qits-system-runner-m2-<id8>` / `qits-system-runner-pnpm-<id8>` | as the credential volume |
| the login probe | container `qits-system-runner-login-probe-<id8>` (`--rm`) | `qits.system.runner=<id>` |

| the CI caches | `qits-system-runner-ci-git-<id8>`, `qits-system-runner-ci-m2-<id8>`, `qits-system-runner-ci-pnpm-<id8>`, created on the first CI lease, never mounted into a WORKSPACE or DESK lease | `qits.system.runner=<id>` |
| the builder | container `qits-system-runner-buildkitd-<id8>` (`--privileged`, `--restart unless-stopped`), on the build network | `qits.system.runner.buildkitd=<stamp>` — **not** the owner label, which the reconcile removes by |
| the build network | bridge `qits-system-runner-build-<id8>` | `qits.system.runner=<id>` |
| the builder's state | volume `qits-system-runner-buildkitd-state-<id8>` | `qits.system.runner.buildkitd=state` |

Never `qits-buildkitd` or `qits-buildkitd-state`: those are qits-containers' platform builder and
its volume, on the same docker on the platform host. No build-plane name is configurable.

### The build plane

`BuildPlane`, ported from qits-ci-runner-daemon. Started on the **first CI lease**, not at boot,
and re-checked on every later one: the network and the state volume made, the builder run (its
image pulled when absent) or started, or replaced when its **stamp** — a hash of the image, the
rendered `buildkitd.toml`, the bootstrap and the state volume — moved; the state volume rides
across. A container under the builder's name without the stamp label is somebody else's: the CI
lease fails and the container is left alone. The toml bounds the worker (`max-parallelism`, GC at
72h / 20 GiB) and carries one `[registry."host"]` table per host: the environment's
`_BUILDKIT_HTTP_REGISTRIES` (`http = true`) and `_BUILDKIT_REGISTRY_MIRRORS`, with the `Ack`'s
`registryMirrors` merged over the latter per source host. The builder runs through the runner's
docker process seam, not the driver: `ContainerSpec` cannot say `--privileged`, and the driver's
`runBuildkitd` always names `qits-buildkitd`. There is no host CA bundle mount (the CI runner's
in-container behaviour). At decommission the builder, the network and the state volume are the
domain's teardown items, removed in that order after the lease containers.

Health: `buildkit` fails on a stale stamp, a stopped builder or a foreign container under its name,
and is ok while no CI lease ever needed one; `network` is ok while missing only when no builder
exists; `idRange` fails when this process's user namespace maps fewer than 4294967295 uids/gids
(buildkit could not unpack every layer), ok when unknown. All three are read-only.

### Environment

| Variable | Meaning | Default |
|---|---|---|
| `QITS_SYSTEM_RUNNER_URL` | qits-system's public url, `https://system.qits.<domain>`. | required |
| `QITS_SYSTEM_RUNNER_ID` | The target id the targets page minted. | required |
| `QITS_SYSTEM_RUNNER_REGISTRATION_TOKEN` | One-time registration token, needed until registered. | required until registered |
| `QITS_SYSTEM_RUNNER_SLOTS` | How many leases this machine runs. **The server takes it as the target's total.** | `1` |
| `QITS_SYSTEM_RUNNER_CREDENTIAL_VOLUME` | An existing docker volume every WORKSPACE and DESK lease (and the login probe) mounts at `/claude-home`. | unset: `qits-system-runner-dot-claude-<id8>` |
| `QITS_SYSTEM_RUNNER_M2_VOLUME` | Likewise for `/caches/m2`. | unset: the runner's own |
| `QITS_SYSTEM_RUNNER_PNPM_VOLUME` | Likewise for `/caches/pnpm`. | unset: the runner's own |
| `QITS_SYSTEM_RUNNER_STATE_DIR` | Where `client.json` lives — the state volume's mount point. | `/var/lib/qits-system-runner` |
| `QITS_SYSTEM_RUNNER_DOCKER_BINARY` | The docker CLI. | `docker` |
| `QITS_SYSTEM_RUNNER_DOCKER_TIMEOUT` | Seconds any one docker call may take. | `120` |
| `QITS_SYSTEM_RUNNER_ROLLOVER_TIMEOUT` | Seconds a successor has to take over. | `180` |
| `QITS_SYSTEM_RUNNER_SELF_UPDATE` | `false`: ignore updates. | `true` |
| `QITS_SYSTEM_RUNNER_TELEMETRY_URL` | Where the runner's own log is shipped (OTLP). Empty switches it off. | derived |
| `QITS_SYSTEM_RUNNER_PRINT_VERSION` | `1`: print the version and exit 0, needing nothing else. | unset |
| `QITS_SYSTEM_RUNNER_DECOMMISSION` | The decommission helper's mode (the toolkit's). | unset |

| `QITS_SYSTEM_RUNNER_BUILDKIT_IMAGE` | The builder's image. | `moby/buildkit:v0.33.0` |
| `QITS_SYSTEM_RUNNER_BUILDKIT_MAX_PARALLELISM` | Build operations the builder runs at once. | `1` |
| `QITS_SYSTEM_RUNNER_BUILDKIT_HTTP_REGISTRIES` | Comma list of `host[:port]` the builder speaks plain HTTP to. | none |
| `QITS_SYSTEM_RUNNER_BUILDKIT_REGISTRY_MIRRORS` | Comma list of `from=to` (`to` may carry a path); the `Ack`'s `registryMirrors` win per host. | none |

### Exit codes

The toolkit's: 0 retired or decommissioned; **2 a variable is missing or unparseable — also the
binary run with no environment**; 3 registration unreachable or 5xx; 4 a protocol version this
runner does not speak; 5 registration refused (4xx); 6 the state directory or `client.json` is
unusable. `qits-system-runner health` is the container healthcheck.

## The conversation

The lifecycle frames are qits-runner-protocol's, byte-identical to every runner's; the target
frames are this repository's (`SystemRunnerCodec`).

    runner → hello{runnerVersion, capabilityVersion, slots, capabilities{docker, arch, os, labels,
             slots, idRange, engineId, hostname, credentialVolume, buildkit}, heldLeases}
    ack{capabilityVersion, slots, slotImages{ci, workspace, desk}, probeImage, registryMirrors, adoptedLeases}
    estate{leases:[{leaseId, slotType, desired, spec, specHash, workVolume, adopt{container, volume}}], purges:[name]}
    runner → inventory{leases:[{leaseId, state, specHash, exitCode, detail}], volumes:[name]}
    probeLogin → loginState{claude, kimi, checkedAt, volume}                (PRESENT|ABSENT|UNKNOWN)
    healthCheck{requestId} → healthChecked{ok, detail, requestId, checks:[{name, ok, detail, data}]}
    upgrade{…} … retire{…} … quarantined{…} … reinstated{…}

`slotType` is `CI|WORKSPACE|DESK`, `desired` `RUNNING|ENDED`, `state`
`STARTING|RUNNING|EXITED|FAILED|ENDED`. `spec` is `{image, env{}, user, limits{memory, memorySwap,
pidsLimit, cpus, oomScoreAdj}}`; `workVolume` is the lease's **work key** (`ws-<rowId>`,
`desk-<deskId>`, null for CI), from which the runner derives the volume's name.

## Layout

- `system-runner-protocol/` — published as `eu.wohlben.qits:qits-system-runner-protocol`: the
  frames, `SystemRunnerCodec`, the Hello and Ack extensions (`SystemRunnerCapabilities`,
  `SystemRunnerAck`), the identity roots and `SystemRunnerBinary.VERSION` — the image tag. Depends
  on `qits-runner-protocol` only.
- `system-runner/` — the binary: `Main`, `SystemRunnerIdentity`, `SystemRunnerDomain`,
  `SlotPolicy`, `LeaseLauncher`, `LeaseContainers`, `TargetVolumes`, `LegacyAdoption`,
  `LoginProbe`, `BuildPlane`, `BuildkitSettings`, `TargetHealthChecks`, on `qits-runner-toolkit`.
- `docker/` — the native musl compile and the image (`Dockerfile`), and the toolchain
  (`Dockerfile.musl-builder`, a copy of qits-workspaces-runner-daemon's).
- `pacts/` — the toolkit's REST calls as this daemon's consumer pacts.
- `.config/qits/release.yml` — the gate (the suite, the native binary's smoke) and the release (the
  image, then the jar).

## Releasing

A release request gates on `./mvnw verify` and a smoke test of the built native binary, run in the
step itself (no docker socket): it prints this tree's version with
`QITS_SYSTEM_RUNNER_PRINT_VERSION=1` and exits 2 with no environment. The release pushes the image
first and publishes the protocol jar after it. See `AGENTS.md` for the working rules.
