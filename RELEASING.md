# Releasing Namastack Outbox

Stable releases are prepared from the **Prepare Release** workflow. The only input is the new version without a `v` prefix, for example `1.10.0`.

The workflow creates a `release/<version>` pull request that:

- updates the release version in the root Gradle build;
- updates the example dependency catalog and performance-test snapshot version;
- creates a Docusaurus `major.minor.x` snapshot when that release line does not exist yet;
- rotates the legacy `/outbox/<version>` redirects so each released line keeps pointing to its own documentation;
- moves the current security-support line when a new documentation release line is created;
- builds the documentation; and
- dispatches the Gradle and example validation workflows for the release branch.

Review and merge the pull request after both validation workflows pass. Wait for the resulting `main` checks, then run **Publish Release** manually with the same stable version. The workflow verifies that `main` contains that version, builds and tests the tagged commit, creates the corresponding `v<version>` tag, publishes the artifacts to Maven Central, and creates the GitHub release with generated release notes.

Normal pull request merges and direct pushes to `main` never create a release tag. If the requested tag already exists, the publication stops and requires manual investigation to avoid publishing the same immutable Maven coordinates twice.

GitHub builds the release notes from merged pull requests using `.github/release.yml`. Apply `enhancement`, `bug`, `documentation`, `dependencies`, or `breaking-change` labels to place pull requests in a specific section. Unlabelled pull requests remain visible under **Other Changes**. The prompt in `.github/prompts/release-notes.prompt.md` is an optional editorial aid and is not used by CI.

Release preparation pull requests receive the `release` label and are excluded from the generated release notes.

The repository must allow GitHub Actions to create pull requests. The Maven Central and signing secrets used by `.github/workflows/release.yml` must remain configured.
