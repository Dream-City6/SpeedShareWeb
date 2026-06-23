# Changelog

All notable changes to SpeedShareWeb will be documented in this file.

The format is based on Keep a Changelog. Version numbers follow Semantic Versioning where practical.

## [Unreleased]

## [1.1.2] - 2026-06-23

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
- Initial access-protection functionality
- English, Simplified Chinese and Japanese interfaces

### Security

- Added initial privacy and security documentation
