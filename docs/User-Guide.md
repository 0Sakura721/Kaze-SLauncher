# Kaze SLauncher — User Guide

> **Language**: [简体中文](使用说明.md) · **English**

A launcher that runs a **Minecraft Java Edition server** on Android, powered by proot + an Ubuntu 24.04 rootfs.

---

## Contents

1. [Install and first launch](#1-install-and-first-launch)
2. [Create a server (three-step wizard)](#2-create-a-server-three-step-wizard)
3. [Start / stop / restart](#3-start--stop--restart)
4. [Console](#4-console)
5. [Player management](#5-player-management)
6. [Instance details](#6-instance-details)
7. [Plugins and mods](#7-plugins-and-mods)
8. [Logs and troubleshooting](#8-logs-and-troubleshooting)
9. [Backups and migration](#9-backups-and-migration)
10. [Settings](#10-settings)
11. [Battery and keep-alive](#11-battery-and-keep-alive)
12. [AI assistant](#12-ai-assistant)
13. [MCP service](#13-mcp-service)
14. [Known limitations](#14-known-limitations)
15. [FAQ](#faq)

---

## 1. Install and first launch

Install the APK (`arm64-v8a` for nearly all modern phones; `armeabi-v7a` is experimental), open it, and the first screen walks you through:

1. **Deploy the runtime** — downloads and unpacks the Ubuntu 24.04 rootfs plus proot. This is the slowest step (hundreds of MB) and only happens once.
2. **Install Java** — inferred from the MC version by default (1.8–1.16.5 → Java 8; 1.17–1.20.4 → Java 17; 1.20.5+ and 24.x–25.x → Java 21/25). You can pick another version manually.
3. **Download a server core, plugins/mods, and check for updates.**

> The first run needs a network connection. **Once a server is set up, it starts offline** — the runtime, Java and the core jar all live on disk.

## 2. Create a server (three-step wizard)

### Step 1: pick a core

| Core | Best for |
|---|---|
| Paper / Purpur / Spigot | Plugin servers; best performance and ecosystem (Purpur has the most tunables) |
| Fabric / Forge / NeoForge | Modded servers (Fabric is lightweight; Forge/NeoForge have the older ecosystem) |
| Import your own jar | You already have a server jar |

- **Core build / loader version**: e.g. Paper's build number or Fabric's loader version. The default is usually fine.
- Tap confirm to start downloading the core; progress is shown.

### Step 2: pick a version and resources

- **Game version**: search (e.g. `1.21`). Snapshots and old alphas/betas appear under their own labels.
- **Memory**: auto-allocated from available device memory, or set it manually (512 MB – 8192 MB).

### Step 3: confirm and install

The server properties you set here are written to `server.properties` and can be changed later from instance details.

## 3. Start / stop / restart

Three entry points: the instance menu on Home, the action row in instance details (only while running), and the ⋮ menu in the server list.

| State | Meaning |
|---|---|
| Starting | Deploying / installing Java / downloading or installing the core |
| Running | The server process is alive |
| Stopping | Doing a graceful stop (saving the world) |
| First start | Generating `eula.txt`; the app accepts it and restarts automatically |

Changing config or installing plugins is the most common reason to restart.

> **Restart** stops the server gracefully first, then starts it again.
> **Force stop** gives the server no time to save — recent block changes may be lost.

## 4. Console

- **Live output** from the server process, plus a command input.
- **Quick command chips**: tap to send, long-press to delete (up to 12).
- **Level filter** (all / info / warning / error) and keyword search.
- **Font size** (10–20sp) and an optional timestamp prefix.
- **Pause auto-scroll** to keep reading while logs keep coming.

The console is only a sliding window — the full log is always written to disk.

## 5. Player management

- **Whitelist** edits the file directly, so it works whether or not the server is running.
- **OP / kick** require a running server.
- Whitelist changes need a server restart (or `whitelist reload`) to take effect.

## 6. Instance details

Four blocks on one page (no tabs):

| Block | Contents |
|---|---|
| **Run** | Status pill, start/stop/restart, uptime, **memory limit**, players online, player management, **core version (check for updates / upgrade)** |
| **Config** | Visual `server.properties` editor; also "open with another app". Advanced settings at the bottom. |
| **World** | World folder and backups: create / restore / import / export; **auto-backup on stop** |
| **Addons** | This instance's plugin / mod list and install entry |

### Core version (updating the server core)

The **Core version** card shows the current core, build and MC version, and offers **Check for core updates**:

| Core | How "is there a new version" is decided |
|---|---|
| Paper / Fabric | Compares the **build number / loader version** (both publish full version lists — most accurate) |
| Purpur / Spigot / Vanilla / Forge / NeoForge | Compares the **download URL** (these embed the version, so a changed URL means a new upstream package) |
| Self-imported jar | **Not supported** — there is no upstream to compare; download the new version yourself |

**The server must be stopped before upgrading**: a running process still holds the old jar, so after replacing it the server keeps running the old code while looking "upgraded". The card disables the upgrade button while the server runs and explains why.

Safety measures during an upgrade: the old jar is renamed to `.old` first, the new package must pass a SHA-1 check before the backup is deleted, and an interrupted download restores the original — you never end up with "no jar and no server".

> **Forge / NeoForge are not replaced automatically**: new versions need the installer re-run to generate `libraries/`, and a straight jar swap leaves the instance half-installed.
>
> **Older instances show "build unknown"**: instances created before v0.8.0 did not record a build number. The check then offers the latest version for you to decide, rather than falsely claiming "up to date".
>
> If upstream has deleted the version you are running (uncommon, but happens with cleaned-up build lists), the check treats it as "newer available" and says so, rather than silently reporting nothing.

### Connection address

The status card shows two addresses, each with a copy button:

- **This device**: `127.0.0.1:port` — when playing on the same phone.
- **LAN**: `192.168.x.x:port` — for friends on the **same Wi-Fi** (works when the phone runs a hotspot too).
- The console prints both addresses as soon as the server starts.
- For friends outside your network you need port forwarding or a tunnel (public IP / frp etc.); the app does not do this.

## 7. Plugins and mods

**Addons** → search Modrinth, install, enable/disable individually. **A server restart is required** for changes to take effect.

> Plugins (Paper/Spigot/Purpur) and mods (Fabric/Forge/NeoForge) are not interchangeable — picking the wrong loader means they will not load.

### Checking for plugin / mod updates

**Check for updates** sits to the right of the "Installed (N)" heading. Results come in four groups:

| Result | Meaning |
|---|---|
| **N updates available** | Lists "file: old → new" with an **Update all** button |
| **Already up to date** | Everything checked, nothing newer |
| **N unrecognized** | These files were not installed from Modrinth (or were modified), so they are **outside update management** — reinstall them from this app to bring them in |
| **N without a compatible version** | Upstream has no package for the current core / MC version (the plugin is unmaintained or does not support this version yet) |

Upgrades download **one at a time**: a failure does not abort the rest, and the failures are reported together at the end. If upstream changed the filename, the old file is deleted so the server does not load two copies.

> **Why "unrecognized" happens**: when the app installs a plugin it records its source in `plugins/.kaze-addons.json` (or `mods/`). Plugins installed by older versions have no such record, so the app hashes the file with SHA-512 and asks Modrinth — if it was originally downloaded from Modrinth it is recognized, **no reinstall needed**. Only files that also fail that lookup (downloaded elsewhere, self-compiled) end up as "unrecognized".
>
> The registry only records which project and version a file came from; it contains no personal data. Deleting it only means the next check has to re-identify the files.
>
> **Downgrades are never treated as updates.** The check compares positions in the upstream version list, so an older-but-newer-looking version number (a beta followed by a stable release) cannot silently replace something newer.

## 8. Logs and troubleshooting

### Launch logs

Grouped per instance: crash reports (`crash-reports/`), console output (`console-output.log`) and the server log (`latest.log`). One tap exports them.

### Diagnostics

App logs plus an environment self-check: key rootfs files, permissions, container DNS and an actual `execve` probe. Share the report when asking for help.

## 9. Backups and migration

- **Back up now**: packs the world and config; a running server is first told to save.
- **Auto-backup on stop**: backs up automatically after the server exits, keeping the latest 5 (`auto_` prefix) and never touching manual backups.
- **Restore requires a stopped server** — restoring over a running process would have it keep writing into a renamed directory.
- **Import / export** backups to move a world between devices.

## 10. Settings

### Appearance

**Theme style** has four choices, applied immediately without a restart:

| Style | Look |
|---|---|
| **Material 3** | Material You dynamic color, follows the system light/dark setting |
| **Miuix** | HyperOS style: grouped grey background, white cards, large radii, HyperOS blue |
| **Shell (岚)** | Custom-drawn view layer with Paper / Ink night palettes (switching restarts the UI shell) |
| *(Liquid glass)* | Not listed — it is a combination of background image + blur and overlaps the background setting |

### Language

**App language** offers **Follow system / 简体中文 / English**. The current choice is highlighted; the subtitle shows what "follow system" resolves to, so you always know which one will actually be used.

> **Translation coverage**: interface text is translated through a runtime dictionary, and coverage is currently **99.7%** (1051 of 1054 strings). The few untranslated spots fall back to Chinese rather than showing blanks or raw keys. Two cases are intentional: AI system prompts (sent to the model, not shown to you) and a handful of strings that embed nested Kotlin expressions, where rewriting them would risk behaviour.
>
> Text that can grow longer in English (titles, list items, metric labels) uses ellipsis truncation, so nothing is cut mid-word when a translation is longer than the Chinese original.

> The default is **Follow system**: a Chinese system language shows Chinese, anything else shows English. Pick 简体中文 or English to override it at any time — the change takes effect immediately, without restarting the app or the server.
>
> Chinese is the original text of this app's UI and English is the translation layer, so on a Chinese device a fresh install looks exactly like previous versions.

### Other

- **Theme mode**: follow system / light / dark.
- **Dark style**: normal black / AMOLED black (the latter only applies in dark mode).
- **Icon and text color**: follow theme / white / black (useful over a background image).
- **Background image**: pick and go, with blur and scrim sliders.
- **Instance folder**: defaults to `KazeS/` in device root; can be moved to another volume or the SD card.
- **Java**: install/uninstall versions; the server picks the Java it needs per game version.
- **MCP**: expose a local endpoint so external AI clients can control the server.
- **Updates**: automatic check at startup, update channel (stable / preview), incremental patch or full package.

## 11. Battery and keep-alive

A running server is kept alive by a foreground service with a persistent notification. On aggressive OEM skins, also allowlist the app for battery optimization, otherwise the system may reclaim the background service.

## 12. AI assistant

Optional. It can read status, read logs, change config (writes need your confirmation) and look things up online.

| Item | Detail |
|---|---|
| **What is sent** | Instance snapshot (name, MC version, core type, Java version, memory limit, run state and uptime) |
| **Console log** | The last 100 lines plus earlier warnings — this includes **player chat**, nicknames and plugin output |
| **Where the API key lives** | The system keystore (never plain text); the app does not downgrade to plain-text storage |
| **Command execution** | Three levels; every execution is written to the audit log |
| **Tool permissions** | Per tool: automatic / ask every time / forbidden. Reading sensitive files (credentials, `ops.json`, `server.properties` with rcon) always requires confirmation |


### Skills

Skills are **your own local documents** the AI can consult. Put one at `skills/<name>/SKILL.md` inside the instance folder and it appears in the AI's list; when it needs that knowledge it reads the file itself.

- They are plain text on your device — no network, no downloads.
- Each skill is per instance: a build guide for one server is useless for another.
- Disable a skill and it stops appearing in the AI's list; nothing is deleted.

#### Adding skills quickly

Above the skill list there are three entries, so you no longer need to hunt for a file manager:

| Action | What it does |
|---|---|
| **Create skill** | Fill in a name, a one-line description and the body, then tap Create. The frontmatter is generated for you, so a malformed one cannot happen |
| **Import from file** | Pick a `.md` with the system file picker. **An existing frontmatter is kept as-is**; if there is none, one is added using the filename and the first line |
| **Delete** (right of each skill) | Asks for confirmation, then removes the whole `skills/<name>/` folder |

A few rules worth knowing:

- **Existing skills are never overwritten.** Creating a name that already exists fails with an explanation — skills are documents you wrote by hand, and a silent overwrite loses data. Delete and recreate, or pick another name.
- **Names are normalised into folder names.** Letters, digits, CJK characters, `-`, `_` and spaces are kept; anything else becomes `-` (so `../evil` turns into an ordinary name — that is the boundary keeping writes inside `skills/`).
- **The body is capped at 20,000 characters.** Skills are injected into the AI's prompt; an oversized one would crowd out the live information that actually matters.
- **Newly created or imported skills start enabled.** Whatever you disabled before stays disabled.

## 13. MCP service

Exposes a local endpoint so external AI clients can control the server. You configure the listen address, port and access token (Bearer auth). Binding `0.0.0.0` lets devices on your LAN connect; use `127.0.0.1` for local-only.

## 14. Known limitations

- **The first run requires a network connection** (runtime + Java + core). Spigot goes through a community CDN with no official hash to verify.
- **CurseForge packs that only carry mod IDs cannot be installed automatically**: their download API needs an official API key the app does not have. Workarounds: use the pack's **Server Pack** (mods are real files), or install with the CurseForge app first and re-zip `mods` / `config`.
- **Quilt** is not supported yet; the app explains why and keeps the pack's files.
- **The server needs a real device** — emulators with ARM translation (x86_64 + libhoudini) generally cannot run proot.
- **Limit is `targetSdk 28`** on purpose: SELinux domains at API 30+ forbid `execve` from private directories, which proot needs.

---

## FAQ

**Q: The server will not start.**
Open the launch log first — it contains the exit code and the full error. Typical causes: wrong Java version (`UnsupportedClassVersionError`), a corrupt jar, or a port already in use.

**Q: I cannot connect from another device.**
Check that you are on the same Wi-Fi, that the port matches, and that the server is actually listening (`server-port` in `server.properties`). Mobile data will not work without port forwarding.

**Q: Plugins do not load.**
Usually a loader mismatch (a Paper plugin on Forge, or the wrong MC version range) — check the console for the exact rejection.

**Q: Will updating the app lose my servers?**
No. Instances, worlds and settings live in the app's data directory; an in-place update keeps them. Back up first if you plan to uninstall.

---

## Reporting a problem

Include the **diagnostics report** (Settings → Diagnostics → Share) plus the **launch log** of the affected instance. Both are plain text and contain no credentials.

## License

GNU GPL-3.0. Third-party components are listed in [THIRD_PARTY_NOTICES](../THIRD_PARTY_NOTICES.md).

Minecraft is a trademark of Mojang Studios; this project is not affiliated with it. Running a Minecraft server means accepting the [Minecraft EULA](https://aka.ms/MinecraftEULA).