# NanoCore

An all-in-one plugin for small Paper survival servers that want a fair, community-driven economy.

- **Economy** with coins, `/pay` and `/baltop` (VaultUnlocked provider)
- **Sell-only admin shop** for rare, non-farmable items, with a daily cap
- **Player shops** without signs: put a chest down, type `/pshop`, fill in a form
- **Quest board** with daily quests of mixed difficulty and a weekly community quest
- **Events**, automatic or hand-run: sidebar, votes, ready checks, mob waves, bosses, races and Discord announcements
- **Milestones** that lock the Nether, End, netherite, elytra and player shops until the server unlocks them together

**Status:** early development (v0.1.0 only loads and answers `/nanocore version`). Requires Paper 26.3 and Java 25.

## Building

```
mvn verify
bash tools/smoke-test.sh target/NanoCore-*.jar 26.3
```

The smoke test boots a throwaway Paper server with the plugin. GitHub Actions runs both on every push.

## License

MIT
