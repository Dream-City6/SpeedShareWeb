# Changelog

All notable changes to SpeedShareWeb will be documented in this file.

The format is based on Keep a Changelog. Version numbers follow Semantic Versioning where practical.

## [1.4.0] - 2026-07-15

### Added

- Add a new SpeedShareWeb launcher icon across adaptive, round, shortcut, Quick Settings and store assets
- Add a branded launch screen that uses the system adaptive-icon mask on current Android versions
- Add live Android transfer progress with a compact progress bar and running-status pulse
- Add restrained aurora backgrounds, tonal panels and motion feedback across the Android and browser interfaces
- Add persistent system, light and dark appearance modes with readable native dark palettes
- Add clickable single-file transfer history with secure file opening and guided APK installation

### Changed

- Redesign the Android home, settings and recycle-bin screens with clearer hierarchy, consistent typography and responsive phone/tablet layouts
- Keep Android content inside status-bar, display-cutout and navigation-bar safe areas while allowing the background to extend edge to edge
- Prewarm the settings screen after the home page's first frame and move it with a GPU layer for consistently smooth repeated navigation
- Improve the browser's wide-screen utility layout, drag feedback, progress animation, dialogs and reduced-motion behavior
- Make the Android home screen state-aware: tap the stopped status card to start, then use focused running controls to copy, scan, replace or stop sharing
- Collapse low-frequency settings into compact summaries that show their current values
- Stop copying the server address automatically; copying now happens only after an explicit user action

### Fixed

- Prevent the Android header from overlapping notches, punch-hole cameras and status-bar content on edge-to-edge devices
- Remove repeated full-page measurement and alpha blending that could cause settings navigation to stutter
- Use the complete adaptive or round icon on the launch screen to avoid square black corners and incorrect legacy artwork
- Prevent OEM force-dark conversion from producing dark text on dark surfaces
- Route APK history items through Android's unknown-source permission flow and continue installation after authorization

## [1.3.4] - 2026-07-14

### Added

- Show available and total phone storage in the Android status card and browser live-status header
- Show the combined size of selected upload files before transfer
- Check upload capacity in both the browser and server while reserving 256 MB for the Android system
- Add a browser-friendly password-only sign-in page with temporary sessions and a sign-out action

### Changed

- Track only the unwritten portion of concurrent uploads as reserved capacity, preventing false low-space rejections while keeping parallel transfers safe
- Return a clear `507 Insufficient Storage` response when an upload cannot fit safely
- Keep HTTP Basic authentication available for explicitly configured clients without showing its confusing username prompt during normal browser access

## [1.3.3] - 2026-07-12

### Added

- Add optional password protection for the browser and all server requests, disabled by default and stored as a salted PBKDF2 verifier
- Add connection limits, request-header timeouts, stricter browser security headers, and regression tests for authentication and upload refresh behavior

### Fixed

- Prevent live page refresh events from interrupting other files in a parallel upload queue
- Preserve the complete copied file when cross-storage trash or restore cleanup is cancelled or fails
- Replace existing files transactionally so failed or cancelled overwrite operations keep the original destination intact
- Report skipped recycle-bin restores accurately and exclude them from completed restore history
- Restrict external shortcut invocation, require remote-management permission for task cancellation, consume ZIP request tokens after use, and correctly escape JSON control characters

## [1.3.2] - 2026-07-10

### Added

- Add a compact desktop right-click and mobile long-press menu for common file and folder actions, with less-used and destructive actions grouped under More
- Add file details, copy-name, copy-download-link, keyboard activation, focus restoration, Escape handling, and settings reset controls to the browser interface
- Add removable pre-upload queue entries and Ctrl/Command/Shift range selection for desktop file management
- Add regression tests covering managed and read-only context-menu behavior and settings availability on selected-download pages

### Changed

- Keep large upload queues inside a scrollable area and throttle progress rendering to reduce browser work during parallel transfers
- Reduce status polling while idle or in the background while preserving fast updates during active transfers
- Keep the browser's native context menu available when remote management is disabled
- Use consistent SVG action icons, show long-press actions as a mobile bottom sheet, and keep permanent deletion out of the quick menu
- Improve large-directory rendering with batched DOM updates, off-screen content deferral, and thumbnail loading placeholders

## [1.3.1] - 2026-07-10

### Changed

- Replace resumable chunk uploads with direct uploads for better local-network throughput
- Allow the browser upload queue to send up to three files at the same time
- Add browser-side settings for upload/download parallelism, clearer upload progress, direct failed-item retry, search clearing, empty search feedback, and more helpful media playback failure guidance

## [1.3.0] - 2026-07-09

### Changed

- Keep the browser upload drop zone visible and provide clearer drag, queue, progress, cancel, retry, success and failure feedback
- Send browser uploads in resumable chunks so failed items can continue from the server-side partial offset when retried
- Merge live connection and transfer metrics into the compact sticky header

### Fixed

- Improve browser video preview compatibility by handling HTTP range requests more strictly and surfacing media load failures
- Preserve nested folder structure when dropping folders into the browser upload area

## [1.2.0] - 2026-07-09

### Added

- Transfer history in the Android app and browser, covering uploads, downloads and file-management actions
- Browser folder uploads with preserved directory structure, including drag-and-drop support
- New visual icons and quicker access to common actions in the Android interface

### Changed

- Redesigned the browser header, toolbar, upload panel, file cards and management dialogs for clearer desktop and mobile use
- Improved Android controls and status presentation across compact layouts

### Fixed

- Reject uploads targeting invalid or recycle-bin paths
- Keep concurrent transfer-history updates ordered so the interface cannot regress to a stale snapshot
- Avoid bursty transfer-stat updates after Android resumes a cached process

## [1.1.3] - 2026-07-01

### Added

- Optional clipboard sync between the Android app and the browser page

### Changed

- Published SpeedShareWeb as an official stable release
- Updated the APK launcher icon to the new colorful folder-and-lightning design
- Updated monochrome notification, Quick Settings tile and shortcut icons to match the new visual identity

## [1.1.1] - 2026-06-23

### Added

- Native in-app recycle-bin manager with multi-select, restore-to-original, permanent delete and empty-all actions
- Optional system file-manager entry for the SpeedShareWeb recycle-bin directory
- TokyoAlex.com production credit in Settings

### Changed

- Unified all user-facing product branding as SpeedShareWeb
- Reworked phone portrait statistics into an adaptive 2 × 2 layout
- Separated transfer values and units to prevent truncation
- Made server status text shorter and independent from the Stop button
- Redesigned Custom Start and compact Settings controls for narrow screens
- Renamed the recycle-bin directory to `.SpeedShareWebTrash` with automatic migration from `.SpeedShareTrash`

### Fixed

- Android system Back now returns from Settings or Recycle Bin instead of closing the app
- Long English, Chinese and Japanese status text no longer gets squeezed by action buttons

## [0.1.0] - 2026-06-22

### Added

- Initial Android local file-server implementation
- Browser-based file access
- File browsing and transfer interface
- List and grid display modes
- Initial privacy and security documentation
- English, Simplified Chinese and Japanese interfaces

### Security

- Added initial privacy and security documentation
