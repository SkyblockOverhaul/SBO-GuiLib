# Publishing GuiLib

Releases go to a **static Maven repository**: the public GitHub repo
[`SkyblockOverhaul/maven`](https://github.com/SkyblockOverhaul/maven), served by GitHub Pages at
`https://skyblockoverhaul.github.io/maven`. No server needed; consumers need no token.

## One-time setup

1. **Maven repo:** create the public repository `SkyblockOverhaul/maven` (it may start empty; add a README if you like).
   In its *Settings → Pages* choose "Deploy from a branch", branch `main`, folder `/ (root)`.
2. **Token:** create a fine-grained personal access token (*Settings → Developer settings → Fine-grained tokens*):
   resource owner `SkyblockOverhaul`, repository access *only* `SkyblockOverhaul/maven`,
   permission *Contents: Read and write*. (The organisation may have to allow fine-grained tokens.)
3. **Secret:** in `SkyblockOverhaul/SBO-GuiLib` → *Settings → Secrets and variables → Actions* add the token as
   `MAVEN_REPO_TOKEN`.

## Releasing a version

1. Set `mod.version` in `gradle.properties` (e.g. `0.1.1`) and commit.
2. Tag and push: `git tag v0.1.1 && git push origin v0.1.1`.
3. The `publish` workflow checks that the tag matches `mod.version`, refuses to overwrite an existing version, builds
   both MC versions, runs the tests, publishes into a checkout of the Maven repo and pushes it.
   GitHub Pages updates about a minute later.

Published artifacts: `net.sbo:guilib-26.1.2-fabric:<version>` and `net.sbo:guilib-26.2-fabric:<version>`
(jar, sources, POM with license information).

## Testing locally

```bash
./gradlew publishAllPublicationsToStaticRepository -Pguilib.maven.dir=../maven   # into a local checkout
./gradlew publishToMavenLocal                                                     # into ~/.m2
```
