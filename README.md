# Death Cost Tracker

Tracks what you pay Death to get your items back: this session, today and in total, for each
character. A side panel shows your costs and deaths day by day for any date range.

It also shows how much the Death's Coffer really saved you: the coffer credit minus what you
paid for the items you sacrificed, from your Grand Exchange purchases or a price you enter.
All data stays on your computer.

## The overlay

| Line | Meaning |
| --- | --- |
| Session | Fees paid since you logged in. World hops keep the session; logging out (or the 6-hour logout) ends it |
| Today | Fees paid today. The day starts at 00:00 UTC (the game's daily reset) or local midnight |
| Total | Fees paid since the total was last reset |
| Coffer saved | Coffer credit for sacrificed items minus what you paid for them (see *Coffer savings* below) |
| Coffer | The last Death's Coffer balance the game showed you |

Move it with Alt-drag. Right-click it to reset Session, Today, Total or Coffer savings; each
reset asks for confirmation first and can post a chat message afterwards. Every line can be
turned off in the plugin settings, and the overlay can hide itself while everything is zero.

## History by date

Every fee is also filed under the day it was paid, and that record is kept for good. The side
panel (the gravestone icon in RuneLite's sidebar) lets you pick a range with *From* and *To*,
or with one click for the last 7 days, 30 days, this month or everything. It shows the total
for that range plus each day that had costs. Resetting the overlay counters does not change it.
*Reset all data* at the bottom of the panel deletes everything stored for the logged-in
character (after a confirmation), as if its file had never existed.

## Deaths

A separate overlay counts your deaths: this session and all deaths. It has its own position
(Alt-drag) and can be turned off in the settings. Right-click it to reset either count; all
deaths have their own reset, independent of the cost counters. Deaths are also kept per day,
so the side panel shows a second table with the deaths in the chosen date range (this table
can be turned off as well).

## Coffer savings

For every sacrifice the plugin needs to know what the items cost you:

- **Grand Exchange purchases** are remembered automatically (items worth 5,000 or more each).
  When you later sacrifice that item, the oldest purchase of it is used. Bought 2 staffs for
  83k each and the coffer gave 87k each? That is 8k saved.
- **Anything else** (loot, items bought before installing the plugin, trades): open the side
  panel and click the sacrifice to enter what you paid in total. Enter 0 for loot.
- **Unknown price**: the sacrifice counts as if you had sold the items on the Grand Exchange
  instead. The coffer gives 105% of the guide price, a sale gives the guide price minus the 2%
  tax, so this part is never negative.

With a known price the saving can be negative: an item bought for more than the coffer gave
for it shows as a loss, in red. The side panel lists every sacrifice in the chosen date range
with its saving. Resetting coffer savings clears that list. *Use Grand Exchange prices paid*
and the list can both be turned off.

## What is counted

Every reclaim fee, read from the game's own messages:

- **At a gravestone**, paid from the coffer, the bank, or both. The game ends with
  *"Death charges you 315,000 x Coins."*; when the coffer could not cover it all, the bank's
  share (*"Payment has been taken from your bank: 180,096 x Coins"*) is part of that fee and is
  not counted twice. With an empty coffer some graves (Doom of Mokhaiotl) send only the bank
  line; it counts once the game confirms the grave is empty.
- **At a boss reclaim NPC** (for example Torfinn after Vorkath), including when the bank pays.
- **At Death's Office**, for items left in a grave longer than 15 minutes.
- **Coffer sacrifices**: the items, the credit they gave and what you paid for them, for the
  savings line.

Bank payments are only counted while a reclaim interface is open, so Grand Exchange purchases
paid from the bank never show up as death costs.

**Private boss instances** are not a death cost, so they are not counted by default. Turn on
*Count private instances* in the settings to add them to the counters, whether the coffer, the
bank or both paid for them.
Either way the *Coffer* line goes down when an instance is paid from it.

## Your data

The plugin makes no network requests of its own. Each character gets one file:

```
%USERPROFILE%\.runelite\plugin-data\death-cost-tracker\<account hash>.json
```

The file is named after RuneLite's account hash, not your display name, and holds no name,
world or location. Writes are atomic (temporary file, then a move), so closing or crashing
the client mid-write cannot corrupt it. An unreadable file is renamed to `.bad` and a fresh
one is started.

## Support

Questions, ideas or a bug? Join the [Discord](https://discord.gg/XgxjhyznbZ) or open an issue
on GitHub. The side panel has a *Join the Discord* button as well; it only opens your browser
when you click it.
