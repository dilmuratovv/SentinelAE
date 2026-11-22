<div align="center">

# SentinelAE

### What you cannot see, your client never gets.

An anti-ESP plugin by **azicoo** for Paper servers: players and armor stands are only sent to a player while that player could actually see them, so ESP and wallhack clients have nothing to draw behind walls.

[![Release](https://img.shields.io/github/v/release/dilmuratovv/SentinelAE?color=ec4899&label=release)](https://github.com/dilmuratovv/SentinelAE/releases)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)
[![Paper](https://img.shields.io/badge/paper-1.21%20%E2%80%93%2026.3-0ea5e9.svg)](#compatibility)
[![Author](https://img.shields.io/badge/author-azicoo-8b5cf6.svg)](https://github.com/dilmuratovv/SentinelAE)

[Download](https://github.com/dilmuratovv/SentinelAE/releases) · [How it works](#how-it-works) · [Configuration](#configuration) · [Commands](#commands) · [Compatibility](#compatibility)

</div>

---

## About

ESP and wallhack clients draw every player the server sends them. SentinelAE decides, for every pair of players, whether one can see the other, and while they cannot, the server sends **no packet at all** about the hidden player — no spawn, movement, armor or metadata. There is nothing left to cheat with.

**One JAR for every supported Paper version** (1.21 to 26.3). Players can join with any client version through ViaVersion/ViaBackwards; everything happens in the server's entity tracker, before packets are translated.

SentinelAE is part of the **Sentinel suite** — [SentinelAC](https://github.com/dilmuratovv/SentinelAC) (anticheat), [SentinelGuard](https://github.com/dilmuratovv/SentinelGuard) (moderation), [SentinelAD](https://github.com/dilmuratovv/SentinelAD) (anti-dupe) and [SentinelAX-ray](https://github.com/dilmuratovv/SentinelAX-ray) (anti-xray). Each plugin works on its own; SentinelGuard's `/sg status` shows SentinelAE's live state.

**Created and maintained by azicoo.**

## Features

| Area | What SentinelAE provides |
|---|---|
| **Line of sight** | An exact voxel raycast (Amanatides–Woo) from the viewer's eyes to 15 points of the target. Only full, opaque blocks hide; glass, leaves, slabs and doors do not. |
| **No packets** | A concealed target is removed from Paper's tracker for that viewer: not a single packet about it reaches the client. |
| **Tab list stays** | Hidden players remain in the tab list, and other plugins' vanish/hide features are left alone. |
| **Lag compensation** | Target and viewer velocity are tracked; a player running around a corner appears about 2–3 ticks before they become visible. |
| **No flicker** | A target is concealed only after `conceal-after-checks` blocked checks in a row; revealed immediately when visible. |
| **Budgeted** | Near hidden pairs are checked every tick, far and visible ones less often, within `pairs-per-tick` and `max-micros-per-tick`. |
| **Safe fallback** | If Paper internals ever change, SentinelAE switches to Paper's hide/show API automatically. |

## How it works

### Visibility

For each viewer and each protected target within `max-distance`, SentinelAE casts rays from the viewer's eyes to the target's box: a centre column plus four widened corners at head, waist and feet height. If any ray reaches the target, it is visible. Unloaded chunks never hide anything.

The target's position `lookahead-ticks` ahead (its current velocity) and a `box-margin` are included, so a player stepping out from cover is already on the client when they appear. The prediction never passes through walls, and teleports are not treated as velocity.

### Hiding

In `tracker-mode: auto` SentinelAE controls Paper's entity tracker directly. Paper's own `PlayerTrackEntityEvent`, when cancelled, still adds the viewer to the tracker and keeps sending movement packets — cancelling the event alone does not stop ESP. SentinelAE removes the viewer from the tracker as well, so the client receives nothing.

If the internal names are not found, it falls back to Paper's `hideEntity`/`showEntity` API (`tracker-mode: api`): protection is the same, but a hidden player temporarily leaves the tab list.

### Exempt

Spectators, `sentinelae.bypass`, glowing targets, players riding the same vehicle, hologram armor stands (`skip-holograms`), targets closer than 1 block and beyond `max-distance`.

## Installation

1. Download `SentinelAE-<version>-paper.jar` from [Releases](https://github.com/dilmuratovv/SentinelAE/releases).
2. Put it in `plugins/` and restart.

Protection is on immediately. `/sae status` shows the mode (`direct tracker` on every supported Paper version) and the cost per tick.

## Configuration

`plugins/SentinelAE/config.yml`, reload with `/sae reload` (only `tracker-mode` needs a restart).

| Key | Default | Purpose |
|---|---|---|
| `protected-types` | `[PLAYER, ARMOR_STAND]` | Entity types that are hidden behind walls. |
| `max-distance` | `80` | Targets farther away are not checked. |
| `lookahead-ticks` | `3` | How far ahead a moving target is predicted. |
| `box-margin` | `0.25` | Extra width around the target, in blocks. |
| `conceal-after-checks` | `2` | Blocked checks in a row before hiding (prevents flicker). |
| `pairs-per-tick` · `max-micros-per-tick` | `512` · `2000` | Work budget per tick. |
| `skip-holograms` | `true` | Never hide hologram armor stands. |
| `transparent-blocks` | `[BARRIER, LIGHT, STRUCTURE_VOID]` | Blocks that never hide anything. |
| `tracker-mode` | `auto` | `auto` (direct tracker, falls back to API) or `api`. |

## Commands

| Command | Purpose | Permission |
|---|---|---|
| `/sae status` | Mode, tracked and hidden pairs, check cost (µs), tick time, `lagging` count. | `sentinelae.admin` |
| `/sae check <viewer> <target>` | The current decision for one pair: rays, distance, whether it is sent. | `sentinelae.admin` |
| `/sae reload` | Reload `config.yml`; every pair is re-checked next tick. | `sentinelae.admin` |

`sentinelae.admin` is op by default. `sentinelae.bypass` lets a player see every entity normally (nobody has it by default; give it to trusted staff only).

## Compatibility

| Platform | File | Supported versions | Java |
|---|---|---|---|
| **Paper** | `SentinelAE-<version>-paper.jar` | 1.21 – 1.21.11, 26.1.1, 26.1.2, 26.2, 26.3 | 21 on 1.21.x, 25 on 26.x |

The JAR is compiled against the oldest supported API (Paper 1.21); every class and method it uses was checked to exist on each supported version. Tested on real Paper 1.21, 1.21.4, 1.21.11 and 26.3 servers with ViaVersion/ViaBackwards (27/27 checks each, `direct tracker` mode): zero packets leaked while a hidden player moved, changed armor, got effects, teleported 30 times or ran into walls; a 42-player crowd (1,722 pairs) cost about 1.3 ms per tick.

### Good to know

- The server cannot take back a position the client has already seen; hiding starts about 0.3–0.4 s after a target goes behind a wall (`conceal-after-checks`).
- Sounds, particles, block-entity contents and other entity types (unless added to `protected-types`) are not covered.
- Player name tags are not visible through walls (unlike vanilla).
- Folia, other forks and Paper before 1.21 are not supported.

## Build from source

You need **Maven 3.9+** and **JDK 21 or newer**.

```sh
git clone https://github.com/dilmuratovv/SentinelAE.git
cd SentinelAE
mvn -B package
```

The JAR appears in `target/SentinelAE-<version>-paper.jar`.

## Releases

Each release contains one JAR signed with the Sentinel suite certificate (`CN=Sentinel Suite`, SHA-256 `64:10:FE:2E:43:F2:23:F6:BD:55:94:BA:20:26:F2:54:0B:36:D7:26:F1:2D:69:4E:4C:B5:BF:22:03:C3:82:91`, self-signed) and a `SHA256SUMS.txt`. Check with `jarsigner -verify -certs SentinelAE-<version>-paper.jar`.

## Credits & license

**SentinelAE — created and maintained by azicoo.**

Distributed under **[GPL-3.0](LICENSE)**. Thanks to the maintainers of Paper.
