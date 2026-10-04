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

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.LayoutManager;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * Side panel: pick a date range and see what dying cost in it, day by day.
 * Lives on the Swing thread and only ever works on its own copy of the per-day record,
 * which the plugin hands over after every change. Colours come from RuneLite's ColorScheme
 * and are set explicitly on every component, so nothing falls back to the look-and-feel's
 * light defaults.
 */
class DeathCostPanel extends PluginPanel
{
	private static final Color ERROR = new Color(0xE57373);
	private static final Color BACKGROUND = ColorScheme.DARK_GRAY_COLOR;
	private static final Color CARD = ColorScheme.DARKER_GRAY_COLOR;
	private static final Color CARD_HOVER = ColorScheme.DARKER_GRAY_HOVER_COLOR;

	private final DeathCostConfig config;

	private final JTextField fromField = new JTextField();
	private final JTextField toField = new JTextField();
	private final JLabel totalLabel = new JLabel(" ", SwingConstants.CENTER);
	private final JLabel infoLabel = new JLabel(" ", SwingConstants.CENTER);
	private final JPanel dayList = new JPanel(new GridLayout(0, 1, 0, 2));
	private final JPanel deathSection = new JPanel(new BorderLayout(0, 6));
	private final JLabel deathTotalLabel = new JLabel(" ", SwingConstants.CENTER);
	private final JLabel deathInfoLabel = new JLabel(" ", SwingConstants.CENTER);
	private final JPanel deathList = new JPanel(new GridLayout(0, 1, 0, 2));
	private final List<QuickRange> quickRanges = new ArrayList<>();

	/** Copy of the logged-in character's per-day costs; empty while logged out. */
	private NavigableMap<String, Long> days = new TreeMap<>();
	/** Copy of the per-day death counts. */
	private NavigableMap<String, Long> deathDays = new TreeMap<>();
	private boolean loggedIn;

	@Inject
	DeathCostPanel(DeathCostConfig config)
	{
		this.config = config;

		setBackground(BACKGROUND);
		setBorder(new EmptyBorder(10, 10, 10, 10));
		setLayout(new BorderLayout(0, 10));

		JPanel top = panel(new BorderLayout(0, 8));

		JLabel title = new JLabel("Death costs by date");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(ColorScheme.BRAND_ORANGE);
		top.add(title, BorderLayout.NORTH);

		JPanel controls = panel(new GridLayout(0, 1, 0, 6));
		controls.add(dateRow("From", fromField));
		controls.add(dateRow("To", toField));

		JPanel quick = panel(new GridLayout(1, 4, 4, 0));
		quick.add(new QuickRange("7d", 7).label);
		quick.add(new QuickRange("30d", 30).label);
		quick.add(new QuickRange("Month", -1).label);
		quick.add(new QuickRange("All", 0).label);
		controls.add(quick);
		top.add(controls, BorderLayout.CENTER);

		top.add(totalCard(), BorderLayout.SOUTH);
		add(top, BorderLayout.NORTH);

		dayList.setBackground(BACKGROUND);
		deathList.setBackground(BACKGROUND);
		deathSection.setBackground(BACKGROUND);
		deathSection.add(card("Deaths", deathTotalLabel, deathInfoLabel), BorderLayout.NORTH);
		deathSection.add(deathList, BorderLayout.CENTER);

		JPanel lists = panel(new BorderLayout(0, 10));
		lists.add(dayList, BorderLayout.NORTH);
		lists.add(deathSection, BorderLayout.CENTER);
		add(lists, BorderLayout.CENTER);

		JLabel note = new JLabel("<html><div style='width:170px'>Enter a date and press Enter. Resetting the overlay counters"
			+ " does not change this history.</div></html>");
		note.setFont(FontManager.getRunescapeSmallFont());
		note.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		add(note, BorderLayout.SOUTH);

		setRange(30);
	}

	private static JPanel panel(LayoutManager layout)
	{
		JPanel p = new JPanel(layout);
		p.setBackground(BACKGROUND);
		return p;
	}

	private JPanel dateRow(String name, JTextField field)
	{
		JPanel row = panel(new BorderLayout(8, 0));
		JLabel label = new JLabel(name);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setPreferredSize(new Dimension(36, 0));
		row.add(label, BorderLayout.WEST);

		field.setBackground(CARD);
		field.setForeground(Color.WHITE);
		field.setCaretColor(Color.WHITE);
		field.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(5, 6, 5, 6)));
		field.setToolTipText("yyyy-mm-dd, e.g. 2026-10-04 - press Enter to apply");
		field.addActionListener(e ->
		{
			selectQuick(null);
			refresh();
		});
		row.add(field, BorderLayout.CENTER);
		return row;
	}

	private JPanel totalCard()
	{
		return card("Paid to Death", totalLabel, infoLabel);
	}

	private static JPanel card(String captionText, JLabel total, JLabel info)
	{
		JPanel card = new JPanel(new GridLayout(0, 1, 0, 2));
		card.setBackground(CARD);
		card.setBorder(new EmptyBorder(8, 8, 8, 8));

		JLabel caption = new JLabel(captionText, SwingConstants.CENTER);
		caption.setFont(FontManager.getRunescapeSmallFont());
		caption.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		card.add(caption);

		total.setFont(FontManager.getRunescapeBoldFont());
		total.setForeground(Color.WHITE);
		card.add(total);

		info.setFont(FontManager.getRunescapeSmallFont());
		card.add(info);
		return card;
	}

	/** A flat, RuneLite-style toggle: 7d / 30d / Month / All. */
	private final class QuickRange
	{
		private final JLabel label;
		private final int span;

		/** span > 0: the last N days; -1: this month; 0: everything recorded. */
		QuickRange(String text, int span)
		{
			this.span = span;
			label = new JLabel(text, SwingConstants.CENTER);
			label.setOpaque(true);
			label.setBackground(CARD);
			label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			label.setFont(FontManager.getRunescapeSmallFont());
			label.setBorder(new EmptyBorder(5, 0, 5, 0));
			label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			label.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					setRange(QuickRange.this.span);
				}

				@Override
				public void mouseEntered(MouseEvent e)
				{
					label.setBackground(CARD_HOVER);
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					label.setBackground(CARD);
				}
			});
			quickRanges.add(this);
		}

		void setSelected(boolean selected)
		{
			label.setForeground(selected ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
		}
	}

	private void selectQuick(QuickRange chosen)
	{
		for (QuickRange q : quickRanges)
		{
			q.setSelected(q == chosen);
		}
	}

	private void setRange(int span)
	{
		LocalDate today = LocalDate.parse(config.dayBoundary().today());
		LocalDate from;
		if (span > 0)
		{
			from = today.minusDays(span - 1);
		}
		else if (span < 0)
		{
			from = today.withDayOfMonth(1);
		}
		else
		{
			from = days.isEmpty() ? today : LocalDate.parse(days.firstKey());
		}
		fromField.setText(from.toString());
		toField.setText(today.toString());
		for (QuickRange q : quickRanges)
		{
			q.setSelected(q.span == span);
		}
		refresh();
	}

	/** Called by the plugin, on the Swing thread, with a fresh copy after every change. */
	void setDays(Map<String, Long> newDays, Map<String, Long> newDeathDays, boolean loggedIn)
	{
		this.days = new TreeMap<>(newDays);
		this.deathDays = new TreeMap<>(newDeathDays);
		this.loggedIn = loggedIn;
		refresh();
	}

	void refresh()
	{
		dayList.removeAll();
		deathList.removeAll();
		deathSection.setVisible(config.showDeathHistory());
		deathTotalLabel.setText("-");
		deathInfoLabel.setText(" ");
		if (!loggedIn)
		{
			show("-", "Log in to see this character's history", ColorScheme.LIGHT_GRAY_COLOR);
			return;
		}

		LocalDate from;
		LocalDate to;
		try
		{
			from = LocalDate.parse(fromField.getText().trim());
			to = LocalDate.parse(toField.getText().trim());
		}
		catch (DateTimeParseException e)
		{
			show("-", "Use dates like 2026-10-04", ERROR);
			return;
		}
		if (from.isAfter(to))
		{
			show("-", "\"From\" is after \"To\"", ERROR);
			return;
		}

		CoinFormat format = config.coinFormat();
		NavigableMap<String, Long> range = days.subMap(from.toString(), true, to.toString(), true);
		long sum = 0;
		for (Map.Entry<String, Long> day : range.descendingMap().entrySet())
		{
			sum += day.getValue();
			dayList.add(dayRow(day.getKey(), format.format(day.getValue())));
		}

		int count = range.size();
		show(format.format(sum) + " gp",
			count == 0 ? "No death costs in this range" : count + (count == 1 ? " day" : " days") + " with costs",
			ColorScheme.LIGHT_GRAY_COLOR);
		totalLabel.setToolTipText(CoinFormat.EXACT.format(sum) + " coins");

		NavigableMap<String, Long> deathRange = deathDays.subMap(from.toString(), true, to.toString(), true);
		long deaths = 0;
		for (Map.Entry<String, Long> day : deathRange.descendingMap().entrySet())
		{
			deaths += day.getValue();
			deathList.add(dayRow(day.getKey(), day.getValue() + (day.getValue() == 1 ? " death" : " deaths")));
		}
		int deathDayCount = deathRange.size();
		deathTotalLabel.setText(deaths + (deaths == 1 ? " death" : " deaths"));
		deathInfoLabel.setText(deathDayCount == 0 ? "No deaths in this range"
			: deathDayCount + (deathDayCount == 1 ? " day" : " days") + " with deaths");
		deathInfoLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		deathList.revalidate();
		deathList.repaint();
	}

	private void show(String total, String info, Color infoColor)
	{
		totalLabel.setText(total);
		infoLabel.setText(info);
		infoLabel.setForeground(infoColor);
		dayList.revalidate();
		dayList.repaint();
	}

	private static JPanel dayRow(String date, String coins)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(CARD);
		row.setBorder(new EmptyBorder(6, 8, 6, 8));

		JLabel weekday = new JLabel(LocalDate.parse(date).getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH));
		weekday.setFont(FontManager.getRunescapeSmallFont());
		weekday.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		weekday.setPreferredSize(new Dimension(26, 0));
		row.add(weekday, BorderLayout.WEST);

		JLabel day = new JLabel(date);
		day.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		row.add(day, BorderLayout.CENTER);

		JLabel value = new JLabel(coins);
		value.setForeground(ColorScheme.BRAND_ORANGE);
		row.add(value, BorderLayout.EAST);

		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				row.setBackground(CARD_HOVER);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				row.setBackground(CARD);
			}
		});
		return row;
	}
}
