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

## Recover rules alongside an older GitHub debug build

The RingPass-recovery artifact installs **RingPass Recovery** alongside the older RingPass app. Use an authorized ADB connection to install RingPass-recovery.apk, then run bash copy-recovery-data.sh before opening RingPass Recovery. The script copies the Room database and confirms that the bytes match before moving the copy into place. The original app keeps its database.

Open RingPass Recovery and grant notification access and any other permissions you use. Ringtone files selected with the system picker must be selected again to give this installation access. The global ringtone preference is stored separately from the copied contacts and notes, so set it again in Setup.
