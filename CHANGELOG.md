# Changelog

All notable changes to Kaze SLauncher.

## [0.17.1] - 2026-07

### Fixed
- HTTP connection leak in ServerCoreManager (12 functions) — all URLConnections now use try/finally
- HTTP connection leak in SpigotBuildManager
- Duplicate CI workflow (build-debug.yml removed, kept build.yml)
- Gradle OOM during APK compression: heap 4096m → 6144m
- Keystore password hardcoded → now supports env vars (KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD)

### Changed
- Compose BOM: 2024.06.00 → 2025.02.00
- Dependencies: activity 1.9.0→1.10.1, lifecycle 2.8.2→2.9.0,
  navigation 2.7.7→2.8.8, coroutines 1.8.1→1.10.1,
  core-ktx 1.13.1→1.15.0, coil 3.0.4→3.1.0, datastore 1.1.1→1.1.3
- .gitignore: added .DS_Store *.log *.bak *.swp *.swo

### Added
- English translations (values-en/strings.xml)
- CHANGELOG.md with version history
- Issues/PR badges in README
- README contribution links

## [0.17.0-pre] - 2026-07

### Added
- Multi-instance server management (create/switch/delete servers)
- Health check diagnostic report with disk space & memory analysis
- Dual memory check (device physical + app heap)
- Performance advisor with TPS/CPU/memory scoring
- Argon dashboard UI with extended color scheme
- Crash report viewer with pattern analysis
- Shell script startup mode for Forge/NeoForge
- Auto-generated RCON password when empty
- ListHeader component for settings sections
- Foreground service event notifications
- Log tail with OOM-safe RandomAccessFile reader

### Fixed
- HTTP connection leak in ServerCoreManager (12 functions)
- HTTP connection leak in SpigotBuildManager
- HealthChecker multi-instance directory isolation
- javaPath serialization missing in PreferencesManager
- Duplicate CI workflow (build-debug.yml removed)
- ArrowBack import missing in MainActivity
- ListHeader import missing in SettingsScreen

### Changed
- Gradle JVM heap: 4096m → 6144m (fixes CI OOM on large APK)
- Compose BOM: 2024.06.00 → 2025.02.00
- Dependencies: activity 1.9.0→1.10.1, lifecycle 2.8.2→2.9.0,
  navigation 2.7.7→2.8.8, coroutines 1.8.1→1.10.1,
  core-ktx 1.13.1→1.15.0, coil 3.0.4→3.1.0
- MIT License → GNU LGPLv3
- Keystore passwords: env var support (KEYSTORE_PASSWORD etc.)
- .gitignore: added .DS_Store *.log *.bak *.swp *.swo

### Added
- English translations (values-en/strings.xml)
- Issues/PR badges in README
- README contribution links

## [0.15.2] - 2026-07

### Fixed
- tailLines() OOM-safe log reading in ProotServerManager
- IllegalStateException separate catch in writeCommandToPipe
- javaPath serialization in PreferencesManager configToJson/jsonToConfig/migrateFromLegacy
- CI compile errors: Long vs Int type mismatch in tailLines

## [0.15.1] - 2026-07

### Fixed
- HealthChecker dual memory check (device physical + app heap)
- HealthChecker multi-instance serverDir(config) isolation
- checkDiskSpace/checkDirectoryWritable using config-aware serverDir
- checkSystemResources/generateRecommendations using device total memory

## [0.15.0-pre] - 2026-07

- Initial pre-release with proot + Ubuntu 24.04 environment
- Multi-core support (Vanilla/Forge/Fabric/Paper/Purpur/Spigot/NeoForge)
- Modrinth & CurseForge integration
- Backup/Restore with pre-restore safety backup
- RCON remote console protocol support
- Performance monitoring (CPU/Memory/TPS/threads)
- Foreground service with persistent notification
- Material You dynamic color theme