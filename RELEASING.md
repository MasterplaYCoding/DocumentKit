# Releasing

Publishing is automated; the accounts and keys are not. This is the one-time
setup, then the per-release steps.

## One-time setup

These require you, not CI.

### 1. Central Portal account and namespace

1. Register at [central.sonatype.com](https://central.sonatype.com).
2. Add the namespace `io.github.masterplaycoding`.
3. Verify it. For an `io.github.*` namespace, Central gives you a code and asks
   you to create a public repository with that name under your GitHub account.
   Create it, then click verify. The repository can be deleted afterwards.

   This is why the group id is `io.github.masterplaycoding.documentkit` — GitHub
   ownership is the cheapest verification Central offers, and it is already
   baked into the package names.

4. Generate a **user token** (Account → Generate User Token). It gives you a
   username and password pair, not your login credentials.

Verification is usually quick but is not instant. Start it before you need it.

### 2. A signing key

Central requires every artifact to be signed.

```bash
gpg --quick-generate-key "Matei Ursache <your@email>" rsa4096 sign 2y
gpg --list-secret-keys --keyid-format=long
```

Publish the public half so Central can check the signatures:

```bash
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
```

Export the private key in the in-memory form the build expects:

```bash
gpg --armor --export-secret-keys <KEY_ID>
```

Keep the whole block, including the `-----BEGIN`/`-----END` lines.

### 3. Repository secrets

In **Settings → Environments → New environment**, create `maven-central`, then
add four secrets to it:

| Secret | Value |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | Central user token username |
| `MAVEN_CENTRAL_PASSWORD` | Central user token password |
| `SIGNING_KEY` | The full ASCII-armoured private key |
| `SIGNING_KEY_PASSWORD` | Its passphrase |

An environment rather than plain repository secrets, so a release can be gated
on your approval and the signing key is not readable by every workflow.

## Releasing

1. Set the version in `gradle.properties`:

   ```properties
   VERSION_NAME=0.1.0
   ```

2. Add a `## [0.1.0] - YYYY-MM-DD` section to `CHANGELOG.md`. The release
   workflow refuses to publish without one.

3. Verify locally, the same way CI will:

   ```bash
   ./gradlew build verifyPublishedCoordinates
   ```

   ```bash
   cd consumer-check && ./gradlew run
   ```

   ```bash
   cd consumer-check-android && ./gradlew assembleDebug
   ```

4. Commit, then tag and push:

   ```bash
   git tag v0.1.0 && git push origin main v0.1.0
   ```

5. The `Release` workflow checks that the tag matches `VERSION_NAME`, that the
   version is not a `SNAPSHOT`, and that the changelog has a matching section;
   then it runs the tests and both consumer builds; then it publishes.

6. Central holds the upload in a validation state. Log in to
   [central.sonatype.com](https://central.sonatype.com), check the deployment,
   and release it. Artifacts appear on `mavenCentral()` within an hour or so.

7. Set `VERSION_NAME` to the next `-SNAPSHOT` and commit.

## After the first release

Update `README.md` to drop the local-build instructions and give the plain
coordinates, and change the roadmap row from *in progress* to *released*.

## What is deliberately not automated

**Central does not auto-release.** The workflow uploads; you confirm. A publish
to Central cannot be undone — versions are immutable — so the last step before
something becomes permanent is a human looking at it.

**Nothing publishes from a branch.** Only a `v*` tag triggers the workflow.
