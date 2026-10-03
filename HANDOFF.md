# Handoff: Death Cost Tracker

State as of 2026-10-04. Every reclaim path has been captured in game (grave, boss NPC, Death's
Office; coffer, bank and mixed) plus private instances. The current tracker build has not
been re-run since bank/NPC/office/instance support was added.

## Read first

1. `..\death-coffer-tracker\RUNELITE-LESSONS.md` — Plugin Hub rules and past mistakes
2. `..\death-coffer-tracker\BRIEF.md` — original brief
3. This file — what is built and what the user decided since

## Evidence from the discovery run (2026-10-03, log in `~\.runelite\death-cost-tracker\`)

- Coffer-paid grave reclaim, exactly one GAMEMESSAGE:
  `Death charges you 1,000 x Coins. You have 331,655 x Coins left in Death's Coffer.`
- No persistent varp holds the coffer balance. `VarPlayerID.IF1` (261) holds it only while
  `InterfaceID.DEATH_COFFER` (670) is open; the bank reuses 261 and resets it to 0.
- Sacrifice: in the same tick, IF1 rises by the credit and the items leave the inventory
  (5 x Strength amulet (t) -> +110,885 = 22,177 each = 105 % GE). No chat message.
- Gravestone interface `GRAVESTONE_GENERIC` (672) shows `Fee: 1,000 coins`;
  `VarClientID.GRAVESTONE_TRANSMIT_COFFER` (400) holds the coffer balance while it is open.
- Trap: `Some of your payment has been taken from your bank.` is a **Grand Exchange**
  message (seen on GE buy offers), not a death fee.
- Grave, coffer could not cover it (same tick, in this order):
  `Payment has been taken from your bank: 180,096 x Coins`
  `Death charges you 315,000 x Coins. You have nothing left in Death's Coffer.`
  The "Death charges" amount is the whole fee; the bank line is a part of it.
- Boss reclaim NPC (Torfinn, Vorkath), empty coffer, interface `GRAVESTONE_RETRIEVAL` (602):
  only `Payment has been taken from your bank: 100,000 x Coins`, no "Death charges" line.
- Boss NPC partly from the coffer: same two messages as a grave (bank line, then
  `Death charges you 100,000 x Coins. You have nothing left in Death's Coffer.`).
- Death's Office (`InterfaceID.DEATH_OFFICE` 669), bank only, different wording:
  `Payment has been taken from your bank: 5,964 coins` (lowercase, no "x Coins").
- Private instance (Dagannoth Kings crevice), type **SPAM**, not GAMEMESSAGE:
  `Payment has been taken from your coffer: 25,000 x Coins`; when the coffer runs short, the
  same tick adds `Payment has been taken from your bank: 9,675 x Coins` (also SPAM).
  Empty coffer: only `Payment has been taken from your bank: 25,000 x Coins` (SPAM), right
  after the `InterfaceID.CHATMENU` dialog "Pay 25,000 x Coins?"; the plugin pairs them.
  Counted only with the `countInstances` option (default off).
- Possible risk: SPAM messages might not reach plugins when the game's chat filter is on
  (not verified).

## Done (phase 2)

- `DeathCostPlugin` — session/day/total, coffer fee parsing, sacrifice pairing, resets.
- `CostData` / `CostStore` — per-character JSON, injected Gson, atomic write, `.bad` backup,
  history capped at 200 payments, sacrifices aggregated per unnoted item id.
- `DeathCostOverlay` — death rune icon, Session/Today/Total/Coffer saved/Coffer lines,
  right-click Reset entries with Swing confirmation + optional chat message.
- Compiled with `javac --release 11` against RuneLite **1.13.1** from the Gradle cache.
- The discovery build lives on in `..\death-cost-tracker-discovery` (never publish it).
  Its VARC logging now writes only changed values (varc 73/74 flooded the first log).
  Unknown "Death charges you…" variants are also logged at debug level by phase 2.

## Version 2.0 (user's name for the first public release)

- `CostData.days`: yyyy-MM-dd -> coins, uncapped, never touched by resets; older files are
  filled from `history` once (`fillDaysFromHistory`).
- `DeathCostPanel` (side panel, gravestone icon `panel_icon.png`): From/To fields, quick
  ranges (7 / 30 days, month, all), range total, per-day rows. Gets a copy of `days` from the
  plugin via `publishDays()` after every change; never touches `CostData` on the Swing thread.
- `icon.png` (48x48, repo root) is the Plugin Hub icon, made from the user's chest artwork.

## Next step

1. User re-tests with RUN.bat: a bank/mixed grave reclaim and an NPC reclaim each count once
   with the right total; hop keeps session, logout resets it, resets ask.
2. Publish to the Plugin Hub (RUNELITE-LESSONS §6). Do not upload HANDOFF.md or the
   discovery folder.

## How the user works

- Slovak in chat, English in code comments.
- GitHub through the web UI only (no git CLI). Step-by-step click instructions.
- Never "it should work": compile against the real jars before handing over.
