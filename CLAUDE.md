# NanoCore (code)

Paper plugin, Java 25, Maven. Design: `../Information/design.md`. Phases and checklists: `../Information/PLAN.md`.

## Commands

- Build + unit tests: `mvn -B verify` (no JDK on the laptop: push and let GitHub Actions build, or build on nanoserver in Docker:
  `docker run --rm -u 1000:1000 -v <src>:/src -v ~/build/m2:/m2 -w /src maven:3-eclipse-temurin-25 mvn -B -q -Dmaven.repo.local=/m2/repository verify`)
- Boot test: `bash tools/smoke-test.sh target/NanoCore-*.jar 26.3` (downloads Paper, starts it with the plugin, runs a console command, checks the log). Runs in CI after the build.
- CI results: `gh run list -R GLr-P/NanoCore`, `gh run view <id> -R GLr-P/NanoCore --log-failed`. The jar is the `NanoCore` artifact.

## Rules

- **Public Paper API only.** No NMS or CraftBukkit internals, so a Minecraft update is a version bump (`paper.version` in pom.xml, `api-version` in paper-plugin.yml).
- Commands through Brigadier (`LifecycleEvents.COMMANDS`), not plugin.yml. Text with Adventure/MiniMessage; player-facing text lives in `messages.yml`.
- The main thread never waits on the database. DB work runs on the plugin's single DB executor; caches are what gameplay reads.
- Server-specific values (currency name, prices, times, Discord webhook) go in config, never in code. The webhook URL is a secret: never commit it.
- Keep it vanilla-feeling: no chat prefixes, no perks for ranks. Rewards are coins and trophy items.
- Never test against the live server's `plugins/`. Use the smoke test or the separate test server.
- Every logic piece gets a JUnit test in `src/test` (pure logic kept free of Bukkit types where possible).

## Layout

- `io.github.glrp.nanocore.NanoCorePlugin`: entry point, command registration
- `economy/`: coins and accounts
- `tools/smoke-test.sh`: boot test; `.github/workflows/build.yml`: CI
