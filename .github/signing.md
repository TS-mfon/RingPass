# RingPass APK signing

Use `RingPass-signed-apk` for distribution. It keeps the `com.ringpass.app` package ID and verifies the signing certificate before uploading the APK. Increase `versionCode` in `app/build.gradle.kts` for each distributed update. Keep the signing key backed up securely.

Configure these repository Actions secrets:

- `RINGPASS_SIGNING_KEYSTORE_B64` contains the base64-encoded JKS or PKCS#12 keystore.
- `RINGPASS_SIGNING_STORE_PASSWORD` is the keystore password.
- `RINGPASS_SIGNING_KEY_ALIAS` selects the signing key in the keystore.
- `RINGPASS_SIGNING_KEY_PASSWORD` is needed when the key password differs from the store password.

Set the repository Actions variable `RINGPASS_SIGNING_CERT_SHA256` to the SHA-256 fingerprint of the intended signer. `apksigner verify --print-certs <apk>` displays the fingerprint for an APK. The signed artifact is available after the GitHub Actions build passes on `main` or `master`.

Android requires the signing private key used by an installed APK to update it in place. Previous GitHub Actions debug builds generated temporary signing keys. Use the original keystore if it is available and verify its fingerprint against the installed version's APK. A new persistent key supports subsequent upgrades from the first APK signed with that key.

`RingPass-debug-apk` uses a temporary key for testing. Installing it can require a fresh install. Uninstalling RingPass deletes its local data.
