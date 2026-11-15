# AGENTS.md

Guidance for automated agents and reviewers working on PylaAndroid — an on-device
Kotlin/Compose port of PylaAI for Android (ANCS-1.0, early beta). Nothing here
promises unattended gameplay. Read `README.md`, `docs/FORK_STATUS.md`,
`docs/KNOWN_ISSUES.md`, `docs/CI.md` and `docs/DIAGNOSTICS.md` before changing code.

## Repository layout

- `app/src/main/java/com/pyla/ai/` — `engine` (bot logic, OCR/vision, state gates),
  `input` (Accessibility gestures), `overlay` (floating controls), `capture`
  (MediaProjection), `config` (TOML settings), `diagnostics` (opt-in reports),
  `pyla` (Python interpreter), `ui` (Compose screens).
- `app/src/test/` — JVM unit tests. `app/src/legacy/` — legacy sources in no
  registered source set. `.github/workflows/android-ci.yml` — Android validation;
  the label workflows manage metadata only (see `docs/PR_AUTOMATION.md`).

## Build and verification

- Use Java 17 to match CI (module `compileOptions` and Kotlin `jvmTarget` are 17).
  Use the checked-in wrapper from a fresh checkout:
  - `./gradlew :app:testDebugUnitTest` — JVM unit tests
  - `./gradlew :app:lintDebug` — Android lint
  - `./gradlew :app:assembleDebug` — debug APK
- `Android CI` runs those three tasks in the check **Tests, lint and debug build**.
  Whether that check is required depends on separately configured branch protection.
  No path filters, no `continue-on-error`, no auto-update of dependencies or lint
  baselines. Green means tests, lint and a debug build — nothing more. A fresh
  checkout must build without `local.properties` or any secret.
- CI APKs use the runner's disposable debug signer, not an owner release signer.
  A debug build does not prove release signing, installed-build update compatibility
  or gameplay behaviour.
- `copyPylaAssets` (attached to `preBuild`) copies `cfg/`, `models/`, `images/` and
  `playstyles/` from an optional sibling `../PylaAI-OriginalPCVersion` tree into the
  committed `app/src/main/assets/pyla`. If that tree is absent the task copies
  nothing. Before building, ensure that sibling tree is absent unless asset syncing
  is explicitly authorized. Inspect asset diffs after builds; do not commit
  accidentally regenerated assets.

## Code Review Rules

**Fresh, fail-safe evidence.** Bind results to the current source and runtime.
Reuse earlier evidence only after verifying its covered files, dependencies and
conditions are unchanged; otherwise regenerate it. Missing input must fail safe
— skip, stop or report — never silently pass or guess.

**Name the kind of evidence.** State what was actually executed. A case skipped
because the private fixture directory (`PYLA_DETAIL_FIXTURE_DIR`) is unset is not a
pass. A green JVM test is not native, on-device or live-gameplay evidence, and no CI
artifact is release-signing proof. Do not blur these categories in summaries.

**Stop/Pause, retries and input safety.** Any change to the input path (Accessibility
gestures, overlay controls, engine gates) must keep Stop/Pause authoritative over
every input source, keep retry loops bounded with backoff, and inject no input while
state is unverified, paused or stopping. Failing safe beats continuing.

**Config and UI compatibility.** Keep existing TOML config keys, user settings,
persisted preferences, `BuildConfig` fields and Compose screens loadable. Prefer
additive changes; renaming or removing a key, preference or screen needs a migration
or an explicit compatibility note in the change description.

**Scope.** These rules apply to all code in the repository, including
`app/src/legacy/**`. Legacy files are in no registered source set, so standard CI
does not compile or test them — a green build says nothing about them; review them
by reading the code, and say so when reporting results.

**Performance claims.** No speed, memory or throughput claim without inspecting the
real library implementation and version (`gradle/libs.versions.toml`) plus realistic
measurements on a stated setup. Do not add heavy microbenchmarks to `app/src/test`
that assert nothing or assert wall-clock time: they cost CI time, flake, and prove
nothing.

**Diagnostics and privacy.** The app has no network access and must not request the
`INTERNET` permission; diagnostic reports leave the device only as a file the user
saves or shares. Exported text must remain sanitized. Never publish private
credentials, tokens or raw private logs in code, issues or pull requests.

**Releases, versions and signing.** No change to `versionCode`/`versionName`,
signing configuration, release build output, or publishing workflow without explicit authorization in the request.

**Repository hygiene.** This is a public repository: no secrets or API keys, no
local machine paths or usernames in code, docs or config. Write documentation and
change descriptions in English.
