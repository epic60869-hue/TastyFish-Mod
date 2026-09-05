# TastyFish Mod

Fabric 26.2 companion mod for the Tasty Fish farming leaderboard.

## What it does

The mod reads **SkySoft's live FARMING session tracker only**. It does not read `profitTracker.totals` or historical SkySoft data.

Every 30 seconds it sends the current session snapshot to the Tasty Fish backend:

- Minecraft username and UUID
- SkyBlock profile
- farming profit
- active farming time
- actions
- item counts
- pest kills
- a unique session ID

The server is responsible for accumulating the leaderboard. If Minecraft is restarted, the new session gets a new ID and the player's existing leaderboard total is preserved.

## Setup

1. Install Fabric Loader 0.19.3+ for Minecraft 26.2.
2. Install Fabric API 0.153.0+26.2.
3. Install SkySoft for 26.2.
4. Install this mod.
5. Start Minecraft once so `config/tastyfish-mod.json` is created.
6. Put the same `FARMING_API_KEY` value used by the BridgeBot into `apiKey` in that file.
7. Keep the endpoint as `https://shadowisabot.com/api/farming/update` unless the backend URL changes.

The uploader is disabled until a real API key is configured.

## Important

The leaderboard still only counts data after an administrator starts the current leaderboard period with `/leaderboard-admin start`.

## 26.2 toolchain

This project uses the current Fabric 26.2 toolchain: Minecraft 26.2, Java 25, Fabric Loader 0.19.3, Fabric API 0.153.0+26.2, Fabric Loom 1.17.12 and Gradle 9.5.1.
