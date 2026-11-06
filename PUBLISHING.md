# Publishing

The library is published two ways from the same tags:

| Where | Coordinates | How |
| --- | --- | --- |
| Maven Central | `com.fload:attribution:<version>` | `.github/workflows/publish.yml` on a tag, signed, after an approval |
| JitPack | `com.github.fload-ai:fload-attribution-android:<tag>` | JitPack builds any tag on request (`JITPACK=true` keeps these coordinates) |

A release is a plain semver tag (`1.0.2`), equal to the `VERSION` file.
Gradle takes the version from the tag (`-Pversion=<tag>`) and falls back to
`VERSION`. Every install reports `SdkInfo.VERSION`
(`attribution/src/main/kotlin/com/fload/attribution/core/Model.kt`), and
`VersionTest` holds it to `VERSION`. A tag that differs from `VERSION` fails
the `Version` workflow, and the publish workflow refuses it
(`./gradlew :attribution:verifySdkVersion -Pversion=<tag>`).

## One-time setup (an owner of fload.com and of this repository)

1. **Central Portal account.** Sign in at <https://central.sonatype.com>
   with a Fload account, not a personal one.
2. **The `com.fload` namespace.** Portal > Namespaces > Add Namespace >
   `com.fload`. The portal shows a verification key. Add it as a DNS TXT
   record on `fload.com` (name `@`, value the key), then press Verify. The
   record can be removed once the namespace shows Verified.
3. **A Portal user token.** Portal > account menu > View Account > Generate
   User Token. It gives a username and a password.
4. **A signing key.** Maven Central only accepts signed artifacts and checks
   the signature against a public key server.

   ```sh
   gpg --quick-gen-key "Fload <dev@fload.com>" ed25519 sign 3y
   gpg --list-secret-keys --keyid-format long          # note the key id
   gpg --keyserver keyserver.ubuntu.com --send-keys <key id>
   gpg --armor --export-secret-keys <key id>           # the SIGNING_KEY value
   ```

   Keep the key and its passphrase in 1Password.
5. **GitHub.** In this repository's Settings > Environments, create
   `maven-central` with Hassan (or Daniel) as a required reviewer, and add
   its secrets:

   | Secret | Value |
   | --- | --- |
   | `MAVEN_CENTRAL_USERNAME` | the user token's username |
   | `MAVEN_CENTRAL_PASSWORD` | the user token's password |
   | `SIGNING_KEY` | the armored secret key from step 4 |
   | `SIGNING_KEY_PASSWORD` | its passphrase |

   Then set the repository variable `MAVEN_CENTRAL_ENABLED` to `true`
   (Settings > Secrets and variables > Actions > Variables). Until then the
   publish workflow skips.

## A release

1. Set `VERSION` and `SdkInfo.VERSION` to the new version and merge it.
2. Tag `main` with the same version and push the tag.
3. Approve the `Publish to Maven Central` run. It runs the tests, signs, and
   uploads to the Central Portal without releasing.
4. On <https://central.sonatype.com/publishing/deployments>, check the
   deployment (validated, with the `.aar`, `.pom`, sources and javadoc jars
   and their `.asc` signatures) and press Publish. It reaches Maven Central
   within about 30 minutes.

To retry a tag, run the workflow by hand (Actions > Publish to Maven Central
> Run workflow) with that tag.

## Checking a publication locally

```sh
./gradlew :attribution:publishToMavenLocal -Pversion=0.0.0-local
ls ~/.m2/repository/com/fload/attribution/0.0.0-local/
```

Signing is skipped without a key. With `signingInMemoryKey` and
`signingInMemoryKeyPassword` set (as Gradle properties or
`ORG_GRADLE_PROJECT_*` variables), each file gets an `.asc`.

`JITPACK=true ./gradlew publishToMavenLocal -Pversion=0.0.0-local` builds
what JitPack serves, under the JitPack coordinates.
