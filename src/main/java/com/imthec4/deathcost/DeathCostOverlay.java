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
import java.awt.Point;
import java.awt.image.BufferedImage;
import javax.annotation.Nullable;
import javax.inject.Inject;
import net.runelite.api.MenuAction;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.ComponentOrientation;
import net.runelite.client.ui.overlay.components.ImageComponent;
import net.runelite.client.ui.overlay.components.LayoutableRenderableEntity;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.SplitComponent;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;

/**
 * Death rune icon plus Session / Today / Total and the coffer lines. Movable with Alt-drag
 * like every RuneLite overlay; right-click offers a reset for each counter.
 */
class DeathCostOverlay extends OverlayPanel
{
	private static final Color GAIN = new Color(0x4CAF50);
	private static final Color LOSS = new Color(0xE57373);

	private final DeathCostPlugin plugin;
	private final DeathCostConfig config;
	private final ItemManager itemManager;

	@Nullable
	private BufferedImage icon;
	private boolean iconRequested;

	@Inject
	DeathCostOverlay(DeathCostPlugin plugin, DeathCostConfig config, ItemManager itemManager)
	{
		super(plugin);
		this.plugin = plugin;
		this.config = config;
		this.itemManager = itemManager;
		setPosition(OverlayPosition.TOP_LEFT);

		for (DeathCostPlugin.Counter counter : DeathCostPlugin.Counter.values())
		{
			addMenuEntry(MenuAction.RUNELITE_OVERLAY, "Reset", counter.getMenuTarget(), e -> plugin.confirmReset(counter));
		}
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		CostData data = plugin.getData();
		if (data == null)
		{
			return null;
		}
		plugin.rollDay();

		boolean session = config.showSession();
		boolean today = config.showToday();
		boolean total = config.showTotal();
		boolean savings = config.showSavings();
		boolean coffer = config.showCoffer();
		long savingsValue = savings ? plugin.savings() : 0;

		if (config.hideWhenZero()
			&& (!session || data.session.coins == 0)
			&& (!today || data.today.coins == 0)
			&& (!total || data.total.coins == 0)
			&& (!savings || savingsValue == 0))
		{
			return null;
		}

		CoinFormat format = config.coinFormat();
		panelComponent.getChildren().add(header());
		if (session)
		{
			line("Session", format.format(data.session.coins), Color.WHITE);
		}
		if (today)
		{
			line("Today", format.format(data.today.coins), Color.WHITE);
		}
		if (total)
		{
			line("Total", format.format(data.total.coins), Color.WHITE);
		}
		if (savings)
		{
			line("Coffer saved", format.format(savingsValue),
				savingsValue > 0 ? GAIN : savingsValue < 0 ? LOSS : Color.WHITE);
		}
		if (coffer)
		{
			line("Coffer", data.coffer.balance >= 0 ? format.format(data.coffer.balance) : "?", Color.WHITE);
		}
		return super.render(graphics);
	}

	private void line(String left, String right, Color rightColor)
	{
		panelComponent.getChildren().add(LineComponent.builder()
			.left(left)
			.right(right)
			.rightColor(rightColor)
			.build());
	}

	private LayoutableRenderableEntity header()
	{
		LineComponent title = LineComponent.builder().left("Death costs").leftColor(Color.ORANGE).build();
		BufferedImage img = icon();
		if (img == null)
		{
			return title;
		}
		return SplitComponent.builder()
			.first(new ImageComponent(img))
			.second(title)
			.orientation(ComponentOrientation.HORIZONTAL)
			.gap(new Point(4, 0))
			.build();
	}

	/** The death rune sprite, scaled down. Loaded once; null until the game has drawn it. */
	@Nullable
	private BufferedImage icon()
	{
		if (!iconRequested)
		{
			iconRequested = true;
			AsyncBufferedImage image = itemManager.getImage(ItemID.DEATHRUNE);
			image.onLoaded(() -> icon = ImageUtil.resizeImage(image, 18, 16));
		}
		return icon;
	}
}
