# Security Policy

## Supported versions

Security fixes are provided for the latest published SpeedShareWeb release. Older releases may remain available for comparison, but users should update before reporting an issue that is already fixed in the latest version.

## Reporting a vulnerability

Please do not publish a suspected vulnerability, private file, password, access token, local address, or other sensitive evidence in a public GitHub Issue.

Use GitHub Private Vulnerability Reporting when it is available for this repository. Otherwise, open a public issue containing only a request for a private reporting channel and no vulnerability details. A useful private report includes:

- A description of the issue and its security impact
- Minimal steps to reproduce it
- The affected app version and Android version
- Expected and actual behavior
- Whether access protection, upload, clipboard sync, or remote management was enabled
- Proof-of-concept material that can be shared safely

## Security model and limitations

SpeedShareWeb is a local-network file server. It is designed for short-lived sharing between devices on a network the user trusts.

- Browser traffic currently uses plain HTTP, not HTTPS. Access passwords prevent unauthenticated use but do not encrypt file names, file contents, clipboard text, session cookies, or passwords in transit.
- A person or device capable of observing or modifying traffic on the same network may be able to read or alter that traffic.
- The app is not intended to be exposed through router port forwarding, a public IP address, a reverse proxy, a public tunnel, or an untrusted VPN.
- Whole-storage mode can expose a broad set of files. Upload and remote-management permissions expand what a connected browser can change.
- Access protection is an additional boundary, not a substitute for using a trusted network and stopping the server after use.

## Safe-use guidance

- Use SpeedShareWeb only on a private, trusted local network.
- Do not forward or tunnel the server port to the public internet.
- Avoid open public Wi-Fi, shared hotel networks, and networks where other users are not trusted.
- Enable access protection before sharing sensitive files or enabling upload, clipboard sync, or remote management.
- Do not reuse an important account password as the SpeedShareWeb access password.
- Treat every authenticated browser as a device that may receive the exposed files and perform the enabled actions.
- Check the address and active permissions shown in the Android app before sharing it.
- Stop the server when it is not in use.
- Keep Android and SpeedShareWeb updated.
- Do not use the app to access files without authorization.

## Disclosure process

The maintainer will review valid reports, reproduce the issue where possible, prepare a fix, and publish release notes after an update is available. Reporters should allow a reasonable remediation period before public disclosure and should avoid retaining user data obtained during testing.
