# Distributed Online Gaming Platform

A distributed backend for an online betting / slot-style gaming platform, built for a
Distributed Systems course. It implements a **Master–Worker MapReduce architecture over raw
Java sockets** — no frameworks, no external libraries — to distribute game storage, search,
betting, and profit statistics across multiple machines.

Games are partitioned across Workers by consistent hashing of the game name; searches and
statistics fan out to all Workers (Map) and are combined by a dedicated Reducer node. A
separate random-number server (SRG) drives bet outcomes with hash-verified results.

> The source comments and console UI are written in **Greek** (the project was submitted at
> AUEB / ΟΠΑ). This README documents it in English; the runtime menus remain Greek.



### Components

| File | Role |
|------|------|
| `Master.java` | Entry point for all clients (port **9000**). Routes single-game operations to one Worker by `hash(gameName) % N`; runs MapReduce for search & stats by fanning out to every Worker and forwarding partial results to the Reducer. Falls back to local reduce if the Reducer is down. |
| `Worker.java` | Stores its partition of games in memory. Handles add / remove (logical delete) / modify-risk / rate / search / play / stats. Computes bet outcomes by fetching a number from the SRG server and verifying its SHA-256 hash. |
| `Reducer.java` | Standalone server (port **8500**) that merges Workers' partial results — concatenating search hits or summing per-game / per-player profits. |
| `SRGServer.java` | "Secure Random Generator" (port **4321**). A producer thread fills a bounded buffer of random numbers; on request it hands one out with `SHA-256(number + secretKey)` for tamper verification. |
| `ManagerConsole.java` | Admin CLI: add games from JSON, delete, change risk level, view platform profit per game, view net result per player. |
| `DummyPlayerApp.java` | Player CLI: async filtered search (background thread + monitor), place bets, rate the last game played, manage a token balance. |
| `Game.java` | Serializable game model. Auto-derives `betCategory` from `minBet` and `jackpot` from `riskLevel`; provides JSON (de)serialization for the Android client. |
| `SearchFilters.java` / `PlayRequest.java` | Serializable request objects (min stars, risk, bet category / bet amount) with `requestId` UUIDs and JSON parsing for the Android client. |
| `WorkerInfo.java` | Holds a Worker's `ip:port` for the Master's routing table. |
| `games/` | Sample game definitions (`game1.json` … `game4.json`) loaded by the Manager. |

## How It Works

- **Sharding** — Each game lives on exactly one Worker, chosen by `Math.abs(gameName.hashCode()) % workers.size()`. Add, remove, modify-risk, rate, and play all route to that same Worker.
- **MapReduce search** — The Master sends the filters to *every* Worker in parallel (one thread each), collects each Worker's matching list, and sends the collection to the Reducer, which concatenates them into the final result.
- **MapReduce stats** — Same fan-out for `VIEW_PROFITS` (per-game platform profit) and per-player results; the Reducer sums values per key.
- **Bet resolution** — A Worker requests a number from the SRG server, verifies the returned SHA-256 hash, then applies a payout: a multiple of 100 hits the jackpot, otherwise the last digit indexes a risk-level multiplier table (low / medium / high).
- **Profit accounting** — Each bet updates per-game platform profit (`bet − win`) and per-player net result; positive means the house won.
- **Fault tolerance** — Bet forwarding uses a 2s connect timeout and Workers a 5s read timeout; if the Reducer is unreachable the Master reduces results locally.

## Protocol

Java clients exchange **serialized objects** (`Game`, `SearchFilters`, `PlayRequest`) and
tagged command strings (`REMOVE_GAME:`, `MODIFY_RISK:`, `RATE_GAME:`, `VIEW_PROFITS`,
`VIEW_PROFITS_PLAYER:`). The Android app (different package, can't share classes) uses the
`SEARCH_JSON:` / `PLAY_JSON:` string variants, which the Master parses via the models'
`fromJson` / `toJson` helpers.

## Build & Run

Requires a JDK (compiled with plain `javac`; no build tool). Compiled `.class` files are
git-ignored.

```bash
# 1. Compile everything
javac *.java

# 2. Start the infrastructure (each in its own terminal / machine)
java SRGServer
java Reducer
java Worker 6001 <srg_ip>          # start one or more Workers
java Worker 6002 <srg_ip>

# 3. Start the Master, pointing it at the Reducer and every Worker
java Master <reducer_ip> <worker1_ip:port> <worker2_ip:port> ...
# e.g. java Master localhost localhost:6001 localhost:6002

# 4. Connect clients (point them at the Master's IP)
java ManagerConsole <master_ip>    # admin
java DummyPlayerApp <master_ip>    # player
```

Use `localhost` for all IPs to run everything on one machine, or real LAN IPs to run across
several. Add games via the Manager's "Add game" option, pointing it at a file in `games/`.

## Game JSON Format

```json
{
    "GameName": "Maria's Game",
    "ProviderName": "netEnt",
    "Stars": 3,
    "NoOfVotes": 15,
    "GameLogo": "/path/to/logo.png",
    "MinBet": 0.1,
    "MaxBet": 10,
    "RiskLevel": "low",
    "hashKey": "str_4455_k2"
}
```

`betCategory` (`$` / `$$` / `$$$`) and `jackpot` are derived automatically, so they are not
part of the input file.

## Notes

- All state is **in memory** — nothing is persisted; restarting a Worker clears its games and stats.
- Game deletion is **logical** (tracked in a `removedGames` set), not removed from the map.
- There is also an Android client (separate project) that talks to the Master over the JSON protocol described above.
