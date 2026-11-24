# NanoCore (code)

Paper plugin, Java 25, Maven. Design: `../Information/design.md`. Phases and checklists: `../Information/PLAN.md`.

## Commands

- Build + unit tests: `mvn -B verify` (no JDK on the laptop: push and let GitHub Actions build, or build on nanoserver in Docker:
  `docker run --rm -u 1000:1000 -v <src>:/src -v ~/build/m2:/m2 -w /src maven:3-eclipse-temurin-25 mvn -B -q -Dmaven.repo.local=/m2/repository verify`)
- Boot test: `bash tools/smoke-test.sh target/NanoCore-*.jar 26.3` (downloads Paper, starts it with the plugin, runs a console command, checks the log). Runs in CI after the build.
- Player test: `BOT=1` adds ViaVersion, ViaBackwards and VaultUnlocked, then `tools/bot/scenario.mjs first` play-tests as an op bot (speaks 26.1), the server restarts, and `scenario.mjs second` checks everything was saved. Add a step there for every player-facing feature. Bots come from `tools/bot/lib.mjs` (it sends the `tick_end` packet Mineflayer 4.39 forgets; without it the server kicks for "invalid player movement"). `DEBUG_PACKETS=1` logs sent packets; `diag-bot.mjs` checks a bot can stay and walk.
- The test server is a flat world (grass at y -61) with whitelist off; the quest steps use a board at 42 -60 45 and `/quests set <n> <kind> <amount>` for fixed quests; `/give`, `/fill`, `/tp`, `/execute` work for the op bot.
- CI results: `gh run list -R GLr-P/NanoCore`, `gh run view <id> -R GLr-P/NanoCore --log-failed`. The jar is the `NanoCore` artifact.

## Rules

- **Public Paper API only.** No NMS or CraftBukkit internals, so a Minecraft update is a version bump (`paper.version` in pom.xml, `api-version` in paper-plugin.yml).
- Commands through Brigadier (`LifecycleEvents.COMMANDS`), not plugin.yml. Text with Adventure/MiniMessage; player-facing text lives in `messages.yml`.
- The main thread never waits on the database. DB work runs on the plugin's single DB executor; caches are what gameplay reads.
- Server-specific values (currency name, prices, times, Discord webhook) go in config, never in code. The webhook URL is a secret: never commit it.
- Keep it vanilla-feeling: no chat prefixes, no perks for ranks. Rewards are coins and trophy items.
- Never test against the live server's `plugins/`. Use the smoke test or the separate test server.
- Every logic piece gets a JUnit test in `src/test` (pure logic kept free of Bukkit types where possible).

## Layout (`io.github.glrp.nanocore`)

- `NanoCorePlugin`: wires everything; services are reached through its getters
- `db/`: `Database` (single DB thread: `submit`, `run`, `sync` only at startup/login/shutdown), `Migrations` (append only)
- `economy/`: `Accounts` (pure rules), `EconomyService` (cache + DB + log + change listeners), `VaultHook`, `Coins`
- `blocks/`: `PlacedBlockTracker` (chunk PDC), `BlockKey`
- `stats/`: `StatKeys` (counter names), `StatsService` (subscribe for quests/events), `StatsListener` (register before the tracker)
- `milestone/`: `Milestone` enum, `MilestoneService`, `LockListener`, `MilestoneMenu`
- `shop/`: `SellQuote` + `DailyLimit` (pure rules), `ShopService` (prices from config, today's totals, sales log), `SellMenu` + `SellListener` (/sell window: top 4 rows take items, items left go back on close/stop), `PriceListMenu`
- `quest/`: pure rules `QuestTemplate` + `QuestTemplates` (reads quests.yml), `QuestGenerator` (board, amounts, rewards, rerolls), `PlayerQuests` (take/progress/claim), `CommunitySplit`, `QuestClock`, `QuestSettings`; `QuestService` (board state, progress from StatsService, payouts, saves), `QuestBoards` (board blocks, labels, storehouse + backlog), `QuestMenu` (board window and quest page buttons)
- `gui/`: `Menu` + `MenuListener`; `area/`: `Area`, `AreaService`; `command/`: `CoreCommands`, `QuestCommands` (/quests + admin), `StatsCommands` (/stats); `text/Messages`; `config/Settings`
- `tools/smoke-test.sh`, `tools/bot/`: CI boot and play tests
