# You Must Have Skysoft Downloaded

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

