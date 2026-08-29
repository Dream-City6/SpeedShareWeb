# SpeedShareWeb Beta signing identity

The separately installable Beta application (`com.alex.speedshare.migration`) must use one persistent signing identity so testers can install future Beta versions as in-place updates without clearing app data.

## Expected certificate

- Alias label: `speedshare-beta`
- Certificate subject: `CN=SpeedShareWeb Beta, OU=Testing, O=Dream-City6, C=JP`
- SHA-256 certificate fingerprint:
  `88:A6:D2:28:45:3E:E9:B1:AB:72:E7:AF:01:BC:FD:D7:48:4E:6D:35:3D:47:82:FF:34:74:1B:22:87:F7:D7:E3`
- Certificate validity ends: 2054-01-14

## Security rule

The private key, keystore bytes, aliases/password material, and any recovery secret **must never be committed to this repository, uploaded as a public artifact, or printed in CI logs**.

CI may build an unsigned/temporary-debug-signed APK and may publish the official Android `zipalign` / `apksigner` binaries. Final tester APKs are post-signed outside the public repository with the persistent Beta key, then verified with `apksigner verify --print-certs`.

Before distributing a Beta APK, the reported signer SHA-256 digest must match the fingerprint above. A mismatch means the APK must not be distributed as an update.
