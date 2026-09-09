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

`0.2.0` is already prepared. `VERSION_NAME`, the changelog section, the
README's coordinates and both consumer builds are at it, and everything below
except the tag has been run. What is left is the part that cannot be done
without an account.

1. Verify locally, the same way CI will:

   ```bash
   ./gradlew build verifyPublishedCoordinates
   ```

   ```bash
   cd consumer-check && ./gradlew run
   ```

   ```bash
   cd consumer-check-android && ./gradlew assembleDebug
   ```

2. Make the two claims that are only true on the day true:

   - replace `unreleased` on the `## [0.2.0]` heading in `CHANGELOG.md` with
     the date;
   - in `README.md`, delete the *Why `mavenLocal()` and not `mavenCentral()`?*
     section and remove `mavenLocal()` from the `repositories` block above it.

   Commit both.

3. Tag and push:

   ```bash
   git tag v0.2.0 && git push origin main v0.2.0
   ```

4. The `Release` workflow checks that the tag matches `VERSION_NAME`, that the
   version is not a `SNAPSHOT`, and that the changelog has a matching section;
   then it runs the tests and both consumer builds; then it publishes.

5. Central holds the upload in a validation state. Log in to
   [central.sonatype.com](https://central.sonatype.com), check the deployment,
   and release it. Artifacts appear on `mavenCentral()` within an hour or so.

6. Confirm from outside: in a scratch directory, resolve
   `io.github.masterplaycoding.documentkit:documentkit-io:0.2.0` from
   `mavenCentral()` alone. The consumer builds prove the artifacts are correct;
   only this proves they are reachable.

7. Set `VERSION_NAME` to the next `-SNAPSHOT` and commit. Leave the README at
   the released version — it advertises what a user can actually depend on, not
   what the working tree is building.

## Subsequent releases

Steps 1-7 with the new version substituted, plus the two edits step 2 no longer
covers: the coordinates in `README.md`, and the roadmap row in it.

## What is deliberately not automated

**Central does not auto-release.** The workflow uploads; you confirm. A publish
to Central cannot be undone — versions are immutable — so the last step before
something becomes permanent is a human looking at it.

**Nothing publishes from a branch.** Only a `v*` tag triggers the workflow.
