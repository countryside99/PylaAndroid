# Android continuous integration

`Android CI` runs on pull requests (including forks), pushes to `main`, merge-queue checks, and manual dispatch. The stable required-check name is **Tests, lint and debug build**. Enable it in a branch protection rule only after the workflow has completed successfully; this PR does not change branch protection or merge automatically.

The job runs JVM unit tests, Android debug lint, then builds the debug APK. The debug build is R8-optimized with the same rules as the release build, so its performance is comparable; it stays debuggable and is signed with the runner's debug key, so it cannot update an installed release. Failures are fatal: neither lint errors nor test failures use `continue-on-error`. Reports are retained for seven days even on failure; the debug APK is uploaded only after all checks pass. APKs use only the runner's disposable debug signing identity; they are not owner-signed releases or compatible-update artifacts for an installed owner build.

## Toolchain and compatibility

- Gradle is selected by `gradle/wrapper/gradle-wrapper.properties`, not duplicated in the workflow. The official wrapper JAR is validated by `gradle/actions/setup-gradle`; the distribution checksum prevents silently substituted Gradle downloads.
- Java 17 matches the current Android Gradle Plugin and project compile options. If a future Android Gradle Plugin requires another Java runtime, update the workflow Java version together with that toolchain upgrade.
- Android SDK licenses are accepted by the SDK setup action. Gradle may install the platform/build tools requested by the repository's Android configuration; the workflow does not duplicate `compileSdk` or build-tools versions.
- A fresh checkout must work without `local.properties`, owner credentials, private screenshots or any token. Optional `PYLA_DETAIL_FIXTURE_DIR` image checks are not exercised by hosted CI; JVM success is not live recognition or gameplay proof.
- Workflow actions are pinned to reviewed immutable commit SHAs. Update those pins deliberately when updating actions.
- There are no path filters or hard-coded test totals that would reject legitimate source/test additions or leave a required PR check indefinitely pending.

The workflow has only `contents: read` access, does not persist checkout credentials, and uses `pull_request`, not `pull_request_target`. It never receives owner signing or diagnostic secrets. PRs and merge-queue runs can read the dependency cache but cannot write it. Only the repository's default-branch run writes the cache. Superseded runs are cancelled and each job has a thirty-minute timeout.

When Gradle, AGP, Kotlin or SDK versions change, keep the wrapper distribution checksum in sync with the official Gradle distribution and let the complete tests/lint/build job validate the new combination. CI does not auto-update dependencies, regenerate a lint baseline, publish releases, or merge PRs.
