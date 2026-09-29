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

Review and merge the pull request after both validation workflows pass. When both workflows also pass for the resulting `main` commit, **Create Release Tag** creates the corresponding `v<version>` tag and dispatches **Release Production**. The production workflow verifies that the tag matches the Gradle version, publishes the artifacts to Maven Central, and creates the GitHub release with generated release notes.

The release tag acts as the dispatch marker, so repeated gate events cannot enqueue the same Maven Central publication twice. If the initial production dispatch fails after the tag was created, rerun **Release Production** manually with that existing tag.

The production workflow also accepts existing prerelease tags such as `v1.10.0-RC1`. It preserves the suffix in the Maven coordinates and marks the corresponding GitHub release as a prerelease.

The main CI includes a release automation dry run. It prepares synthetic minor and patch releases in a temporary repository copy, builds the resulting documentation, verifies version and redirect behavior, and generates an RC Maven POM with the expected coordinates. It never creates a tag, Maven Central deployment, or GitHub release.

GitHub builds the release notes from merged pull requests using `.github/release.yml`. Apply `enhancement`, `bug`, `documentation`, `dependencies`, or `breaking-change` labels to place pull requests in a specific section. Unlabelled pull requests remain visible under **Other Changes**. The prompt in `.github/prompts/release-notes.prompt.md` is an optional editorial aid and is not used by CI.

Release preparation pull requests receive the `release` label and are excluded from the generated release notes.

The repository must allow GitHub Actions to create pull requests. The Maven Central and signing secrets used by `.github/workflows/release.yml` must remain configured.
