# Publishing

The library is published two ways from the same tags:

| Where | Coordinates | How |
| --- | --- | --- |
| Maven Central | `com.fload:attribution:<version>` | `.github/workflows/publish.yml` on a tag, signed, released by the approval |
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
   record on `fload.com` (name `@`, value the key), then press Verify. Keep
   the record until the namespace shows Verified.
3. **A Portal user token.** Portal > account menu > View Account > Generate
   User Token. It gives a username and a password. The account's sign-in
   password does not work here. To check a token without publishing anything:

   ```sh
   curl -s -o /dev/null -w '%{http_code}\n' \
     -H "Authorization: Bearer $(printf '%s:%s' "$USERNAME" "$PASSWORD" | base64)" \
     'https://central.sonatype.com/api/v1/publisher/published?namespace=com.fload&name=attribution&version=1.1.0'
   ```

   `200` is a valid token, `401` is not.
4. **The signing key.** Maven Central accepts only signed files, and checks
   each signature against the public key servers. The key is RSA 4096,
   sign-only, `Fload <dev@fload.com>`, made on 9 Nov 2026 and valid until
   8 Nov 2029:

   ```text
   8430 C320 22BE 00B9 7F4E  3684 6F31 40B0 8FA2 099A
   ```

   Its public half is on keyserver.ubuntu.com and keys.openpgp.org. The
   secret key and its passphrase are in 1Password. To replace it (or extend
   it before 2029), use RSA, which every verifier accepts, and publish the
   public key to both servers:

   ```sh
   gpg --quick-gen-key "Fload <dev@fload.com>" rsa4096 sign 3y
   gpg --list-secret-keys --keyid-format long          # note the fingerprint
   gpg --keyserver hkps://keyserver.ubuntu.com --send-keys <fingerprint>
   gpg --export <fingerprint> | curl -T - https://keys.openpgp.org
   gpg --armor --export-secret-keys <fingerprint>      # the SIGNING_KEY value
   ```

   keys.openpgp.org drops the user id until someone confirms the email
   address. That is fine for Central, which matches the key by its id.
5. **GitHub.** In this repository's Settings > Environments, the
   `maven-central` environment has a required reviewer and accepts only
   tags (`*.*.*`). Every secret lives in it, not in the repository, so no
   other branch's workflow can read them:

   | Secret | Value |
   | --- | --- |
   | `MAVEN_CENTRAL_USERNAME` | the user token's username |
   | `MAVEN_CENTRAL_PASSWORD` | the user token's password |
   | `SIGNING_KEY` | the armored secret key from step 4 |
   | `SIGNING_KEY_PASSWORD` | its passphrase |

   `gh secret set NAME --env maven-central -R fload-ai/fload-attribution-android`
   takes the value on stdin. Then set the repository variable
   `MAVEN_CENTRAL_ENABLED` to `true`
   (`gh variable set MAVEN_CENTRAL_ENABLED --body true -R fload-ai/fload-attribution-android`).
   Until then the publish workflow skips. From then on, every release tag
   pushed starts a run that waits for the approval.

## A release

1. Set `VERSION` and `SdkInfo.VERSION` to the new version and merge it.
2. Tag `main` with the same version and push the tag.
3. The `Publish to Maven Central` run waits for the `maven-central`
   environment's approval. **Approving it releases that version**: the run
   tests, signs and uploads, and the portal releases the upload once it
   validates (`automaticRelease = true`). Validation refuses a bundle with a
   missing file, signature or POM field, and then nothing is released.
4. Follow the deployment on
   <https://central.sonatype.com/publishing/deployments>. The version reaches
   <https://repo1.maven.org/maven2/com/fload/attribution/> within about 30
   minutes.

To publish a tag that already exists, or to retry one, run the workflow on
that tag. The environment accepts only tags, and the first job fails a run
whose tag input differs from the tag it runs on:

```sh
gh workflow run publish.yml -R fload-ai/fload-attribution-android --ref 1.1.0 -f tag=1.1.0
```

A run uses the workflow and the build files **as they are at that tag**.
Tags up to 1.1.0 predate `automaticRelease = true`: their upload stops at
Validated in the portal, and someone with the portal sign-in presses
Publish there.

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
