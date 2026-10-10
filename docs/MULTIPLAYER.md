# Multiplayer

Two players, both online. **A move can only be made while both are connected and their saves agree.** If either player drops, the game pauses (the board locks, the game is autosaved and the status shows "Waiting for opponent…"). The host keeps listening and the guest retries with backoff (1, 2, 4, 8, then every 15 s). "Leave (resume later)" is always available.

## Connecting

- **Direct (default port 5757):** the host clicks *Host a game* and shares the invite (`chessbot:192.168.1.20:5757`, with a *Copy invite* button). The guest pastes it under *Join a game*. This works on the same LAN. Over the internet it **only** works if the host forwards the port on their router or both players are on the same VPN (Tailscale, ZeroTier, …).
- **Relay:** the app can't reach across the internet by itself. If port forwarding isn't possible, someone runs the tiny relay on a machine both players can reach (a cheap VPS, or a PC with one forwarded port):

  ```
  java src/net/relay/relayServer.java 5758
  ```

  The host picks *Through a relay server* and enters the relay address and a room code. The invite then looks like `chessbot-relay:relay.example.com:5758/ROOM42`. The relay pairs the two players by room code and forwards their lines. It stores nothing and doesn't understand chess.

The connection is plain TCP with no TLS, so only play with people you trust on networks you trust.

## Identity and keys

On first use the app asks for a display name. It stores a random `playerId` and the name in `src/assets/saves/profile.json`. When the host creates a game it generates a random 128-bit `gameKey`, sends it to the guest, and both store it in the save. Every reconnect proves knowledge of that key with HMAC-SHA256 over a fresh nonce. A wrong game, wrong key or wrong player is refused with a message, and the guest stops retrying.

## Saves and agreement

Each move carries its ply number and a hash chain value: `hash0 = SHA-256(gameId + startFen)`, then `hash_n = SHA-256(hash_{n-1} + move_n as UCI)`. Both sides check every incoming move with the rules engine. An illegal or out-of-order move is rejected and opens an "Out of sync" dialog with a Re-sync button. A move only counts once the opponent has ACKed it. Until then it is saved as `"pending": true`.

Whenever the two players connect for an existing game, they exchange `RESUME` (per-ply hash lists) and:

1. **Identical:** "States match" and play resumes.
2. **One is a prefix of the other:** the side that's ahead proposes. The other sees "Opponent has N more move(s) (ply X → Y). Accept?"
3. **Diverged:** both see a comparison dialog showing the common position and each side's moves after it. The options are *Roll back both*, *Use mine* and *Use theirs*. One side proposes and the other must accept. Mismatched choices keep the game paused.
4. **Local save missing or corrupt:** the corrupt file is renamed `.corrupt`. The other player's copy is offered, and you must accept it.
5. **Result mismatch** (for example, one side thinks the game was resigned): the same propose/accept flow as 3.

A save is never overwritten before both players agree. On agreement, the old file is first copied to `<gameId>.bak.<timestamp>.json`.

## Messages

Each message is one JSON object per line: `HELLO`, `AUTH`, `ERROR`, `GAME_OFFER` / `GAME_ACCEPT` / `GAME_DECLINE`, `REMATCH_REQUEST`, `MOVE`, `ACK`, `REJECT`, `RESIGN`, `DRAW_OFFER` / `DRAW_ACCEPT` / `DRAW_DECLINE`, `PING` / `PONG` (every 5 s, dropped after 15 s of silence), `LEAVE`, `RESUME`, `SYNC_MOVES`, `SYNC_PROPOSE` / `SYNC_ACCEPT` / `SYNC_REJECT`. The code is in `src/net/netSession.java`, and the loopback tests are in `test/runTests.java`.
