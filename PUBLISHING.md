# Maven Central Publishing Guide

This project publishes to Maven Central using the new Sonatype Central Portal.

## Quick Start

Run the setup script to configure everything:

```bash
./setup-maven-central.sh
```

## Manual Setup

If you prefer to set up manually:

### 1. GPG Key Setup

**Extend existing expired key:**
```bash
gpg --edit-key 234AB32DF0E44AA3
# Type: expire
# Choose new expiration date (e.g., 2y for 2 years)
# Type: save
```

**Or create new key:**
```bash
gpg --gen-key
# Follow prompts with your name and email
```

### 2. Export GPG Key

```bash
# Get your key ID
gpg --list-secret-keys --keyid-format=long

# Export private key
gpg --export-secret-keys --armor <KEY_ID> > private-key.asc

# Get short key ID (last 8 characters of KEY_ID)
# Example: if KEY_ID is 234AB32DF0E44AA3, short ID is F0E44AA3
```

### 3. Get Sonatype Central Portal Token

1. Go to https://central.sonatype.com/account
2. Log in (create account if needed - use same email as GPG key)
3. Click "Generate User Token"
4. Save the username and password shown

### 4. Add GitHub Secrets

Add these secrets to your GitHub repository:

```bash
gh secret set ORG_GRADLE_PROJECT_mavenCentralUsername
# Paste: token username from step 3

gh secret set ORG_GRADLE_PROJECT_mavenCentralPassword
# Paste: token password from step 3

gh secret set ORG_GRADLE_PROJECT_signingInMemoryKey < private-key.asc
# Reads from file

gh secret set ORG_GRADLE_PROJECT_signingInMemoryKeyId
# Paste: short key ID (8 characters)

gh secret set ORG_GRADLE_PROJECT_signingInMemoryKeyPassword
# Paste: your GPG key passphrase
```

### 5. Upload Public Key

Upload your public key to keyservers:

```bash
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
```

## Creating a Release

1. Update version in `build.gradle` and `jafar-gradle-plugin/build.gradle`
2. Update `CHANGELOG.md` with release notes
3. Commit changes
4. Create and push tag:

```bash
git add .
git commit -m "Release version X.Y.Z"
git tag vX.Y.Z
git push origin main vX.Y.Z
```

5. Monitor GitHub Actions workflow
6. Wait 2-10 minutes for Maven Central sync
7. Verify at: https://central.sonatype.com/artifact/io.btrace/jafar-parser

## Published Artifacts

The following artifacts are published to Maven Central under groupId `io.btrace`:

- `jafar-parser` - Core JFR parser library
- `jafar-tools` - JFR manipulation tools
- `jfr-shell` - Interactive CLI tool (shadow JAR with dependencies)
- `jafar-gradle-plugin` - Gradle plugin for type generation

## Troubleshooting

### Build fails with "gpg: signing failed: No secret key"

Your GPG key is not available or expired. Run:
```bash
gpg --list-secret-keys --keyid-format=long
```

Check if key is expired and extend it:
```bash
gpg --edit-key <KEY_ID>
# Type: expire, then save
```

### Build fails with "401 Unauthorized"

Check that GitHub secrets are set correctly:
```bash
gh secret list
```

Should show:
- `ORG_GRADLE_PROJECT_mavenCentralUsername`
- `ORG_GRADLE_PROJECT_mavenCentralPassword`
- `ORG_GRADLE_PROJECT_signingInMemoryKey`
- `ORG_GRADLE_PROJECT_signingInMemoryKeyId`
- `ORG_GRADLE_PROJECT_signingInMemoryKeyPassword`

### Artifacts not appearing on Maven Central

1. Check GitHub Actions logs for errors
2. Go to https://central.sonatype.com/publishing
3. Check deployment status
4. Wait up to 30 minutes for sync (usually 2-10 minutes)

### Missing signature errors

Ensure all 5 GPG-related secrets are set correctly and the GPG key ID matches the uploaded public key.

## References

- [Sonatype Central Portal Documentation](https://central.sonatype.org/publish/publish-portal-gradle/)
- [vanniktech maven-publish Plugin](https://vanniktech.github.io/gradle-maven-publish-plugin/)
- [GPG Quick Start](https://central.sonatype.org/publish/requirements/gpg/)
