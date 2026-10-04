/*
 * Copyright (c) 2026, ImTheC4
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.imthec4.deathcost;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(DeathCostConfig.GROUP)
public interface DeathCostConfig extends Config
{
	String GROUP = "deathcosttracker";

	@ConfigSection(
		name = "Display",
		description = "What the overlay shows",
		position = 0
	)
	String DISPLAY = "display";

	@ConfigSection(
		name = "Deaths",
		description = "Death counter: a separate overlay and a table in the side panel",
		position = 1
	)
	String DEATHS = "deaths";

	@ConfigSection(
		name = "Counting",
		description = "How costs are counted",
		position = 2
	)
	String COUNTING = "counting";

	// ------------------------------------------------------------------ display

	@ConfigItem(
		keyName = "showSession",
		name = "Show session",
		description = "Reclaim fees paid since you logged in. World hops keep the session",
		section = DISPLAY,
		position = 0
	)
	default boolean showSession()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showToday",
		name = "Show today",
		description = "Reclaim fees paid today",
		section = DISPLAY,
		position = 1
	)
	default boolean showToday()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showTotal",
		name = "Show total",
		description = "Reclaim fees paid since the total was last reset",
		section = DISPLAY,
		position = 2
	)
	default boolean showTotal()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showSavings",
		name = "Show coffer savings",
		description = "Coffer credit received for sacrificed items minus what those items cost on the"
			+ " Grand Exchange now",
		section = DISPLAY,
		position = 3
	)
	default boolean showSavings()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showCoffer",
		name = "Show coffer balance",
		description = "The last Death's Coffer balance the game showed you",
		section = DISPLAY,
		position = 4
	)
	default boolean showCoffer()
	{
		return true;
	}

	@ConfigItem(
		keyName = "coinFormat",
		name = "Number format",
		description = "Short (1.25M) or exact (1,250,000)",
		section = DISPLAY,
		position = 5
	)
	default CoinFormat coinFormat()
	{
		return CoinFormat.SHORT;
	}

	@ConfigItem(
		keyName = "showPanel",
		name = "Show side panel",
		description = "Side panel with death costs per day and a date range total",
		section = DISPLAY,
		position = 7
	)
	default boolean showPanel()
	{
		return true;
	}

	@ConfigItem(
		keyName = "hideWhenZero",
		name = "Hide when zero",
		description = "Hide the overlay while every cost it shows is zero",
		section = DISPLAY,
		position = 6
	)
	default boolean hideWhenZero()
	{
		return false;
	}

	// ------------------------------------------------------------------ deaths

	@ConfigItem(
		keyName = "showDeathOverlay",
		name = "Show deaths overlay",
		description = "A separate box with deaths this session and all deaths",
		section = DEATHS,
		position = 0
	)
	default boolean showDeathOverlay()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showDeathHistory",
		name = "Deaths in side panel",
		description = "A table of deaths per day for the chosen date range in the side panel",
		section = DEATHS,
		position = 1
	)
	default boolean showDeathHistory()
	{
		return true;
	}

	// ------------------------------------------------------------------ counting

	@ConfigItem(
		keyName = "dayBoundary",
		name = "New day starts at",
		description = "00:00 UTC is the Old School daily reset",
		section = COUNTING,
		position = 0
	)
	default DayBoundary dayBoundary()
	{
		return DayBoundary.UTC;
	}

	@ConfigItem(
		keyName = "countInstances",
		name = "Count private instances",
		description = "Also count Death's Coffer spending on private boss instances (plus any part the"
			+ " bank paid). Not a death cost, so off by default",
		section = COUNTING,
		position = 1
	)
	default boolean countInstances()
	{
		return false;
	}

	@ConfigItem(
		keyName = "resetMessage",
		name = "Chat message on reset",
		description = "Post a game chat message after a counter is reset",
		section = COUNTING,
		position = 2
	)
	default boolean resetMessage()
	{
		return true;
	}
}
