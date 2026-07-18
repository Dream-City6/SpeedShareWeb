# SpeedShareWeb Product Quality Audit

This document tracks product-readiness risks separately from release notes. A checked item means the implementation has been changed and covered by an automated check where practical; it does not replace Android device testing.

## Quality goals

1. Never expose or destroy files outside the intended sharing root.
2. Never present an incomplete file as a successful upload, copy, move, restore, or overwrite.
3. Keep authentication and management endpoints bounded under malformed or hostile local-network traffic.
4. Release every socket, stream, file descriptor, worker, wake lock, and cache entry predictably.
5. Keep the Android and browser interfaces understandable under failure, interruption, and permission loss.

## Completed in the app-quality branch

### Authentication and request parsing

- [x] Expire browser sessions and limit their in-memory count.
- [x] Validate password verifier structure before expensive PBKDF2 work.
- [x] Bound Basic Authorization input.
- [x] Rate-limit repeated failed password attempts per client and bound limiter memory.
- [x] Validate HTTP request lines, versions, methods, targets, and header syntax.
- [x] Reject ambiguous duplicate length/authentication headers.
- [x] Reject unsupported chunked request bodies and invalid content lengths.
- [x] Require an application-specific request header for browser mutations other than login and logout.
- [x] Add a bounded body-read idle timeout so slow clients cannot hold workers indefinitely.

### Upload and file-operation integrity

- [x] Write uploads into hidden unique staging files.
- [x] Sync and verify the complete upload before exposing its final name.
- [x] Commit same-name concurrent uploads without overwriting existing files.
- [x] Resolve nested upload directories through the sharing-root boundary.
- [x] Serialize remote copy, move, delete, trash, and restore mutations.
- [x] Collapse duplicate and parent/child-overlapping source selections.
- [x] Preserve transactional overwrite behavior during cancellation.
- [x] Finalize queued cancellations instead of leaving them indefinitely cancellable.
- [x] Prevent late completion from overwriting a cancellation state.
- [x] Make trash metadata recoverable after interruption between data movement and metadata commit.
- [x] Validate recoverable trash data before exposing it in the recycle-bin list.
- [x] Keep incomplete pending trash entries hidden.
- [x] Apply an explicit symbolic-link policy: do not recursively copy or ZIP link targets, and delete only the link itself.

### Long-running resource stability

- [x] Isolate UI/service observer failures from transfer and history bookkeeping.
- [x] Make transfer tracker shutdown idempotent and prevent post-close work.
- [x] Synchronize transfer speed sampling.
- [x] Limit simultaneous SSE event streams so status connections cannot consume every request worker.
- [x] Bound pending ZIP preparation tokens and expire old tokens.
- [x] Serialize generation of the same thumbnail key.
- [x] Use unique and synced thumbnail staging files.
- [x] Correctly handle interruption while waiting for thumbnail generation capacity.
- [x] Bound thumbnail cache size and remove stale temporary files.
- [x] Throttle cache-directory maintenance.

### Engineering safeguards

- [x] Run JVM unit tests and Android Lint on pull requests without packaging an APK.
- [x] Upload failure diagnostics from CI.
- [x] Monitor Gradle and GitHub Actions dependency updates with Dependabot.
- [x] Keep an auditable product-quality checklist in the repository.

## Highest-priority remaining work

### P1 — availability and Android lifecycle

- [ ] **Dedicated event-stream execution.** Event streams are now capped, but they still occupy request-worker threads. Move them to a separate bounded executor after socket-level integration tests exist.
- [ ] **Network binding policy.** Decide and test whether the server should bind all interfaces or only the selected local interface. The displayed LAN address and actual listening interfaces must agree.
- [ ] **Network transition handling.** Verify Wi-Fi loss, hotspot changes, VPN changes, and address replacement; stop or rebind when the advertised endpoint is no longer valid.
- [ ] **Graceful file-operation shutdown.** Wait for bounded worker termination and report interrupted operations consistently when the service stops.
- [ ] **Crash cleanup for stale transaction artifacts.** Detect and safely remove hidden upload/replacement staging files left by process death.
- [ ] **Foreground-service lifecycle verification.** Test notification denial, background start restrictions, wake-lock release, process recreation, and OEM battery controls across supported Android versions.

### P2 — performance and product comfort

- [ ] Virtualize or page very large browser directories rather than rendering all entries at once.
- [ ] Add explicit error codes and user actions for permission loss, storage exhaustion, conflicts, network loss, rate limiting, and client cancellation.
- [ ] Warn clearly when whole-storage management is enabled without a password.
- [ ] Review large-file ZIP CPU/thermal behavior and cancellation.
- [ ] Add accessibility checks for touch targets, dynamic text, screen readers, and keyboard-only browser operation.
- [ ] Verify light/dark/system themes and all three languages on narrow phones, tablets, landscape, and enlarged fonts.

### P3 — maintainability and release engineering

- [ ] Split `SpeedShareServer.kt` into parser/router, authentication, upload, download, ZIP, clipboard, and response components only after integration tests protect current behavior.
- [ ] Split Android UI state/control responsibilities out of `MainActivity.kt` without changing visible behavior.
- [ ] Add socket-level integration tests that start the real server and exercise login, upload, Range download, interruption, management operations, SSE limits, rate limiting, and malformed traffic.
- [ ] Add instrumented tests for foreground-service, permission, process recreation, notification, quick-tile, and share-intent flows.
- [ ] Evaluate release minification/resource shrinking only after release-mode smoke tests exist.

## Manual verification matrix

Before merging a release candidate, test at minimum:

- Android 8/9, Android 12, Android 14, and Android 16 where devices or emulators are available.
- One Xiaomi/HyperOS device with aggressive background restrictions.
- Chrome and Edge on desktop; Chrome and another browser on mobile.
- 0-byte, small, 100 MB, 1 GB+, non-ASCII, emoji, long-name, and deeply nested files.
- Concurrent same-name uploads, interrupted uploads, folder uploads, Range seeking, ZIP download, copy, move, trash, restore, permanent delete, and service stop during activity.
- Password success/failure/rate limiting, login/logout/session expiry, and management requests with and without the trusted mutation header.
- Wi-Fi disconnect/reconnect, address change, screen lock, notification denial, all-files permission revocation, storage nearly full, and port conflict.

## Merge policy for this branch

Keep the pull request in Draft until:

1. `testDebugUnitTest` and `lintDebug` pass in GitHub Actions.
2. The changed authentication, upload, trash, file-operation, thumbnail, ZIP, and event-stream flows pass focused manual smoke tests.
3. Any unresolved data-loss or externally reachable management risk introduced by the diff is addressed.
