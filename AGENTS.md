# qits-system-runner-daemon — working notes

Read `README.md` first: it is the operator's view of what the runner does on a node. This file is
the working conventions on top of it. It deliberately mirrors qits-projects-desk-runner-daemon's
`AGENTS.md`, the template this repository was copied from; where it differs, it says why.

## The rules that shape everything

**The runner is qits-runner-javalib's, embedded.** Registration, the bearer, the control socket,
the slot ledger, self-update, decommission, the heartbeat, housekeeping, the runner's own log
shipping and the install-script template are `eu.wohlben.qits:qits-runner-toolkit`. This repository
supplies exactly what the toolkit asks an embedder for: a `RunnerIdentity` (`SystemRunnerIdentity`,
whose roots are `KnownRunnerNamespaces.SYSTEM`), a sealed frame set with its `DomainCodec` (the
protocol module), a `RunnerDomain` (`SystemRunnerDomain`) and a `main` (`Main`). **Never fork or copy
a toolkit class.** A case the toolkit's public API cannot express is a gap fixed there, released,
and pinned here.

**The server allocates.** The `estate` is the whole desired state; there is no `reserve`, `take`
or `backlog`. The slot ledger only counts running leases, so `heldLeases` says what a reconnecting
runner still runs.

**A clone of this repo alone builds and tests green** — given the platform's maven repository.
No docker, no prior `mvn install` elsewhere, no credentials. `./mvnw verify` is the gate.

**It compiles to a fully static musl GraalVM native image** (`docker/Dockerfile.musl-builder`, a
copy). No Jackson, no REST client, no docker SDK; every docker call goes through the toolkit's
process seam (`RunnerDocker`, `qits-containers-driver`), under a deadline. **Every docker argv is a
`qits-containers-driver` builder** (`DockerArgv`, `SwarmArgv`, `HostRunArgv`, `ExecArgv`;
qits-2289): the binary is only ever a builder's first argument, never the head of a hand-built
list. A spelling the library lacks is added there and released, never spelled here. The one fork
of its own is the sign-in's (`SignInProcesses`), whose argv is still `HostRunArgv`'s.

**A healthy runner never exits**, and **a rollover never drains**: `quiescent()` is "no convergence
in flight", never "holds nothing". Lease containers are node state that outlives the process.

**Nothing is removed before an `estate` has arrived on the current connection**, an absent list
is malformed, never "holds nothing", and **a work volume goes only on an `estate.purges` entry**.

## The slot policy is the boundary

`SlotPolicy` is the table in the README, one enum value per slot type, and `SlotPolicyTest`
asserts every cell. A `LeaseSpec` can say an image, an environment, a user and limits — never a
mount, a network, a capability, `--init` or a restart. A change to what a slot type may do is a
change to that table, reviewed as one, with the test cell beside it.

- **CI never mounts the credential volume, the agent caches or a work volume**, and WORKSPACE and
  DESK never a CI cache or `BUILDKIT_HOST`; `SlotPolicyTest` holds both. A CI lease's build
  network is a `docker network connect` of the runner's own (`BuildPlane.connect`); a failed
  connect removes the container again.
- **No lease process starts before its networks are joined** (qits-2270): `docker create`, then
  each `docker network connect` (`qits-private`, then the build network), then `docker start`
  (`LeaseLauncher.launch`). Never the driver's `run`, which starts the container before it joins
  `extraNetworks`: a CI lease on a tunnel node would see the tunnel's resolver beside a public one,
  and musl asks both at once. `EstateReconcileTest` pins the order.
- **WORKSPACE and DESK run on the runner's lease network, CI on `bridge`** (`LeaseNetwork`,
  qits-2269): `qits-system-runner-leases-<id8>`, owner-labelled, `enable_icc=false`, made by the
  runner's own `docker network create` when a look says it is absent, before every WORKSPACE or DESK
  `docker create`, whose `--network` it is — so the lease has docker's embedded `127.0.0.11` from
  the create on. One that cannot be made fails the lease — never a fallback to `bridge`, where
  docker writes the `--dns` servers into resolv.conf and musl asks them all at once. It is the
  create's network (`SlotPolicy.RunNetwork`), a teardown item after every container, and a
  WORKSPACE or DESK lease found on other networks is reported `resolversStale`.
- **Every lease joins `qits-private` with a resolver edge's VIP as first resolver where one of
  `PrivateEdge.APPLICATIONS` is visible on the node's daemon** (`PrivateEdge`, qits-2062,
  qits-2304): `qits-edge-overlay` first, `qits-edge-private` as fallback — the same preference
  qits-containers' `PrivateEdgeResolver` and qits-deployments' `SwarmDeploymentDriver` walk. An
  application with no swarm service at all is skipped in favour of the next one; any other failure
  for an application stops the walk with no resolver rather than trying the next, so a passing
  docker hiccup never flips a lease onto the fallback edge and back. Applied to the policy's spec
  just before the create and never part of the spec-hash or mounts label — a moved VIP, or a more
  preferred edge arriving, never recreates a running lease. All or nothing, read-only, never
  throws; off the platform host it adds nothing. The create argv is the driver's (`DockerArgv.create`,
  its run argv as a `docker create`), so `--dns` is the driver's; the runner does the join
  (`ContainerSpec.extraNetworks`) before the start.
- **The build plane is the target's own** (`BuildPlane`): every name derived, none configurable,
  never `qits-buildkitd` / `qits-buildkitd-state` (`BuildPlaneNamesTest`). The builder carries the
  stamp label, never the owner label — the toolkit's reconcile removes every owner-labelled
  container that is not a held lease. It is the one privileged `docker run`, a fixed argv through
  `RunnerDocker`, and its three names are the domain's `TeardownItem`s.
- **The builder runs under a derived memory cap** (qits-2503): `--memory`
  `BuildkitSettings.memoryCapBytes()` = `…_BUILDER_CONCURRENT_BUILDS` × 6 GiB (`MEMORY_PER_BUILD`,
  a CI lease's `qits.ci.slot.memory`), no `--memory-swap`, no setting of its own. The cap is in the
  stamp, so a changed width replaces the builder (never under a running build, qits-2260), and the
  inventory reports it as `node.builderMemoryBytes` (`NodeMemory`'s fifth, nullable component) —
  the builds a lease sends there run in the builder's cgroup, outside every lease's cap.
- **A CI lease's builds queue at a target-wide FIFO build gate before they reach the builder**,
  implemented by qits-ci-daemon's `buildctl` shim against a gate directory under `/cache/git` — not
  by buildkitd's own `max-parallelism`. The gate's width is `…_BUILDER_CONCURRENT_BUILDS`
  (`BuildkitSettings`, `1` when unset), handed to the lease as `QITS_CI_BUILDER_SLOTS`
  (`SlotPolicy.Build`). Waiting at the gate does not count against the CI step's deadline
  (qits-1807); at the default width of one, a build no longer waits inside buildkitd's own
  per-operation semaphore instead.
- **The user is the spec's and never root** (`0`, `root`, with or without a group).
- **The image must start with `Ack.slotImages.<type>`**.
- **Policy-owned env keys** (`BUILDKIT_HOST`, `HOME`, `CLAUDE_CONFIG_DIR`) in a spec fail the lease
  with a detail naming the key. The policy sets `CLAUDE_CONFIG_DIR=/claude-home/.claude` for every
  type that mounts the credential volume; `HOME` stays the image's.
- **Legacy adoption** accepts exactly the two retired runners' patterns (`LegacyAdoption`); anything
  else is `adopt name not allowed`.

## Module conventions

`eu.wohlben.qits.systemrunner.protocol` (the published jar `qits-system-runner-protocol`) and
`eu.wohlben.qits.systemrunner` (the binary), one package per module.

**The protocol module depends on `qits-runner-protocol` and nothing else**, enforced by
`maven-enforcer-plugin`. qits-system-service (Jackson) and the native runner (Vert.x) both link it.
Plain records, plain constructors, no annotations; fields are read through `WireMaps`.

**`Main` is the only CDI bean.** Everything below it is a plain class taking its collaborators as
constructor arguments; nothing reads configuration — the toolkit's `RunnerSettings` parses the
environment (`QITS_SYSTEM_RUNNER_*`), and `Main` parses the runner's own beside it
(`ExternalVolumes`: `…_CREDENTIAL_VOLUME`, `…_M2_VOLUME`, `…_PNPM_VOLUME`, exit 2 on a bad name,
resolved against the state volume's `node-volumes.json` by `NodeVolumeRecord` so a reinstall keeps
them — unset means recorded, `-` means the runner's own;
`BuildkitSettings`: `…_BUILDKIT_IMAGE`, `…_BUILDKIT_MAX_PARALLELISM`, `…_BUILDER_CONCURRENT_BUILDS`,
`…_BUILDKIT_HTTP_REGISTRIES`, `…_BUILDKIT_REGISTRY_MIRRORS`, likewise).
`…_BUILDKIT_MAX_PARALLELISM` defaults to the runner's own slot total. It only sets how far the
builds the build gate admitted fan out their own stages; the gate (`…_BUILDER_CONCURRENT_BUILDS`),
not buildkitd's semaphore, bounds how many builds — native-image builds included — run at once
(qits-1807).

**An external node volume is never the runner's.** It is never created, labelled or removed; a
missing one fails the lease before `docker run`, because `-v` would create it empty.

**Every name on a node is derived** (`TargetNames`, `TargetVolumes`, the identity), never taken
whole from a frame: a lease id and a work key pass `TargetNames.ID` before they become part of a
name. The names are spelled `<namePrefix>-<purpose>-<id>-<id8>` by hand because
`RunnerIdentity.nodeName` caps its purpose at 41 characters and `lease-<uuid>` is 42.

## Testing

Plain JUnit 5. **No Mockito, no `@QuarkusTest`.** The runner is driven through the toolkit's own
fixtures: `FakeRunnerHost` and `FakeDocker`. `EstateReconcileTest` is the lifecycle suite; start a
new case from it. Test names are sentences describing the behaviour.

## Adding a target frame or field

1. A record in `system-runner-protocol`, added to `SystemRunnerMessage`'s `permits` list.
2. `SystemRunnerProtocol.Type`/`Field` constants — never a bare string at a call site.
3. Encode and decode arms in `SystemRunnerCodec`, the round-trip list and the golden spelling in
   `SystemRunnerCodecTest`.
4. Bump `SystemRunnerProtocol.CAPABILITY_VERSION` only if a peer must branch on it.
5. **Release this repository, then the maintenance bump moves the pin in qits-system-service.**

## Untrusted input

A `LeaseSpec` arrives over a socket and becomes an argv to a root-equivalent daemon:

- **The shape is the boundary**, and the slot policy is the rest of it.
- **The runner's own label namespace (`qits.system.runner.`) is the driver's.**
- **Every listing and removal selects by the runner's own label or a name it derived** (or, for
  adoption and purges, a name matching a retired runner's pattern), never host-wide and never by
  `name=`.
- **A spec's environment is never logged**: it carries the lease's token.

## The toolkit's calls are pacted here (qits-1163)

`ToolkitPactTest` declares the token mint (qits-idp), the log export (qits-observability) and the
register door (qits-system-service, operation `registerTarget`, application `qits-system`) through
`eu.wohlben.qits:qits-runner-toolkit-contracts`, and writes
`pacts/qits-system-runner-daemon_qits-runner-javalib_<provider>.json`. qits-system-service records
no golden masters yet, so its register rows are skipped, naming the states they need; once its
golden masters are published, pin them in the root pom, rerun with `-Dgolden.update=true`, commit
the new file and declare `qits-system-service` under `contracts.pacts` in `.config/qits/release.yml`.

## Formatting

`google-java-format`, 100 columns, two-space indent. Javadoc explains *why*.

## Building

Every maven invocation on the shared host goes through the lock:

    flock /tmp/qits-maven.lock ./mvnw -B -ntp verify
