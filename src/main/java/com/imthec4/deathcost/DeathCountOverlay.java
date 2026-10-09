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

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.api.MenuAction;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

/**
 * Separate box with the death counts: this session and all deaths since their own reset.
 * Movable with Alt-drag; right-click resets either count after a confirmation.
 */
class DeathCountOverlay extends OverlayPanel
{
	private final DeathCostPlugin plugin;
	private final DeathCostConfig config;

	@Inject
	DeathCountOverlay(DeathCostPlugin plugin, DeathCostConfig config)
	{
		super(plugin);
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);

		for (DeathCostPlugin.Counter counter : DeathCostPlugin.Counter.values())
		{
			if (counter.isDeaths())
			{
				addMenuEntry(MenuAction.RUNELITE_OVERLAY, "Reset", counter.getMenuTarget(), e -> plugin.confirmReset(counter));
			}
		}
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		CostData data = plugin.getData();
		if (data == null || !config.showDeathOverlay())
		{
			return null;
		}

		if (!config.deathsSession() && !config.deathsToday() && !config.deathsTotal())
		{
			return null; // every line switched off: nothing to show
		}
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Deaths")
			.leftColor(Color.ORANGE)
			.build());
		if (config.deathsSession())
		{
			line("Session", data.deaths.session);
		}
		if (config.deathsToday())
		{
			line("Today", data.deaths.today);
		}
		if (config.deathsTotal())
		{
			line("All deaths", data.deaths.total);
		}
		return super.render(graphics);
	}

	private void line(String name, long count)
	{
		panelComponent.getChildren().add(LineComponent.builder()
			.left(name)
			.right(String.valueOf(count))
			.build());
	}
}
