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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * Side panel: pick a date range and see what dying cost in it, day by day, the deaths in it
 * and the coffer sacrifices made in it. Clicking a sacrifice lets the player enter what the
 * items cost them.
 * Lives on the Swing thread and only ever works on its own copy of the per-day record,
 * which the plugin hands over after every change. Colours come from RuneLite's ColorScheme
 * and are set explicitly on every component, so nothing falls back to the look-and-feel's
 * light defaults.
 */
class DeathCostPanel extends PluginPanel
{
	private static final Color ERROR = new Color(0xE57373);
	private static final Color GAIN = new Color(0x4CAF50);
	private static final Color LOSS = new Color(0xE57373);
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
	private final JPanel cofferSection = new JPanel(new BorderLayout(0, 6));
	private final JLabel cofferTotalLabel = new JLabel(" ", SwingConstants.CENTER);
	private final JLabel cofferInfoLabel = new JLabel(" ", SwingConstants.CENTER);
	private final JPanel cofferList = new JPanel(new GridLayout(0, 1, 0, 2));
	private final List<QuickRange> quickRanges = new ArrayList<>();

	/** Copy of the logged-in character's per-day costs; empty while logged out. */
	private NavigableMap<String, Long> days = new TreeMap<>();
	/** Copy of the per-day death counts. */
	private NavigableMap<String, Long> deathDays = new TreeMap<>();
	/** Copy of the coffer sacrifices, oldest first. */
	private List<SacrificeRow> sacrifices = new ArrayList<>();
	private boolean loggedIn;

	/** Hands an edited price (sacrifice id, coins or null) to the plugin. */
	@Nullable
	private BiConsumer<Long, Long> costEditor;

	/** Asks for confirmation and wipes this character's data; set by the plugin. */
	@Nullable
	private Runnable resetAllAction;

	private final JLabel resetAll = new JLabel("Reset all data", SwingConstants.CENTER);

	static final String DISCORD_URL = "https://discord.gg/XgxjhyznbZ";
	/** Discord's brand colour and its darker hover shade, so the button reads as Discord at a glance. */
	private static final Color DISCORD = new Color(0x5865F2);
	private static final Color DISCORD_HOVER = new Color(0x4752C4);
	private final JLabel discord = new JLabel("Join the Discord", SwingConstants.CENTER);

	/** One coffer sacrifice as the panel shows it; an immutable snapshot made by the plugin. */
	static final class SacrificeRow
	{
		final long n;
		final String date;
		final String name;
		final long quantity;
		final long credit;
		@Nullable
		final Long cost;
		@Nullable
		final String costSource;
		final long saved;

		SacrificeRow(long n, String date, String name, long quantity, long credit, @Nullable Long cost,
			@Nullable String costSource, long saved)
		{
			this.n = n;
			this.date = date;
			this.name = name;
			this.quantity = quantity;
			this.credit = credit;
			this.cost = cost;
			this.costSource = costSource;
			this.saved = saved;
		}
	}

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

		cofferList.setBackground(BACKGROUND);
		cofferSection.setBackground(BACKGROUND);
		cofferSection.add(card("Coffer savings", cofferTotalLabel, cofferInfoLabel), BorderLayout.NORTH);
		cofferSection.add(cofferList, BorderLayout.CENTER);

		JPanel lower = panel(new BorderLayout(0, 10));
		lower.add(deathSection, BorderLayout.NORTH);
		lower.add(cofferSection, BorderLayout.CENTER);

		JPanel lists = panel(new BorderLayout(0, 10));
		lists.add(dayList, BorderLayout.NORTH);
		lists.add(lower, BorderLayout.CENTER);
		add(lists, BorderLayout.CENTER);

		JLabel note = new JLabel("<html><div style='width:150px'>Enter a date and press Enter. Click a sacrifice to"
			+ " enter what you paid for it. Resetting Session, Today or Total does not change this history;"
			+ " resetting coffer savings clears the sacrifices. Reset all data clears everything.</div></html>");
		note.setFont(FontManager.getRunescapeSmallFont());
		note.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);

		resetAll.setOpaque(true);
		resetAll.setBackground(CARD);
		resetAll.setForeground(ERROR);
		resetAll.setFont(FontManager.getRunescapeSmallFont());
		resetAll.setBorder(new EmptyBorder(6, 0, 6, 0));
		resetAll.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		resetAll.setToolTipText("Delete all of this character's Death Cost Tracker data, after a confirmation");
		resetAll.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (resetAllAction != null && loggedIn)
				{
					resetAllAction.run();
				}
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				resetAll.setBackground(CARD_HOVER);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				resetAll.setBackground(CARD);
			}
		});

		// Opens the browser only when clicked; the plugin itself never contacts Discord
		discord.setOpaque(true);
		discord.setBackground(DISCORD);
		discord.setForeground(Color.WHITE);
		discord.setFont(FontManager.getRunescapeBoldFont());
		discord.setBorder(new EmptyBorder(7, 0, 7, 0));
		discord.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		discord.setToolTipText("Questions, ideas or a bug? Opens " + DISCORD_URL + " in your browser");
		discord.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				LinkBrowser.browse(DISCORD_URL);
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				discord.setBackground(DISCORD_HOVER);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				discord.setBackground(DISCORD);
			}
		});

		JPanel bottom = panel(new BorderLayout(0, 8));
		bottom.add(note, BorderLayout.NORTH);
		bottom.add(discord, BorderLayout.CENTER);
		bottom.add(resetAll, BorderLayout.SOUTH);
		add(bottom, BorderLayout.SOUTH);

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

	void setResetAllAction(Runnable action)
	{
		this.resetAllAction = action;
	}

	void setCostEditor(BiConsumer<Long, Long> costEditor)
	{
		this.costEditor = costEditor;
	}

	/** Called by the plugin, on the Swing thread, with a fresh copy after every change. */
	void setDays(Map<String, Long> newDays, Map<String, Long> newDeathDays, List<SacrificeRow> newSacrifices,
		boolean loggedIn)
	{
		this.days = new TreeMap<>(newDays);
		this.deathDays = new TreeMap<>(newDeathDays);
		this.sacrifices = new ArrayList<>(newSacrifices);
		this.loggedIn = loggedIn;
		refresh();
	}

	void refresh()
	{
		resetAll.setVisible(loggedIn);
		dayList.removeAll();
		deathList.removeAll();
		deathSection.setVisible(config.showDeathHistory());
		deathTotalLabel.setText("-");
		deathInfoLabel.setText(" ");
		cofferList.removeAll();
		cofferSection.setVisible(config.showCofferHistory());
		cofferTotalLabel.setText("-");
		cofferTotalLabel.setForeground(Color.WHITE);
		cofferInfoLabel.setText(" ");
		cofferList.revalidate();
		cofferList.repaint();
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

		String fromKey = from.toString();
		String toKey = to.toString();
		long saved = 0;
		int shown = 0;
		int unknown = 0;
		for (int i = sacrifices.size() - 1; i >= 0; i--)
		{
			SacrificeRow r = sacrifices.get(i);
			if (r.date.compareTo(fromKey) < 0 || r.date.compareTo(toKey) > 0 || "?".equals(r.date))
			{
				continue;
			}
			saved += r.saved;
			shown++;
			if (r.cost == null)
			{
				unknown++;
			}
			cofferList.add(sacrificeRow(r, format));
		}
		cofferTotalLabel.setText(signed(format, saved) + " gp");
		cofferTotalLabel.setForeground(saved > 0 ? GAIN : saved < 0 ? LOSS : Color.WHITE);
		cofferTotalLabel.setToolTipText(CoinFormat.EXACT.format(saved) + " coins");
		cofferInfoLabel.setText(shown == 0 ? "No sacrifices in this range"
			: shown + (shown == 1 ? " sacrifice" : " sacrifices")
			+ (unknown == 0 ? "" : ", " + unknown + " without a price paid"));
		cofferInfoLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		cofferList.revalidate();
		cofferList.repaint();
	}

	private static String signed(CoinFormat format, long coins)
	{
		return (coins > 0 ? "+" : "") + format.format(coins);
	}

	private JPanel sacrificeRow(SacrificeRow r, CoinFormat format)
	{
		JPanel row = new JPanel(new BorderLayout(0, 2));
		row.setBackground(CARD);
		row.setBorder(new EmptyBorder(6, 8, 6, 8));
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		JPanel top = new JPanel(new BorderLayout(6, 0));
		top.setOpaque(false);
		JLabel item = new JLabel(r.quantity + " x " + r.name);
		item.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		top.add(item, BorderLayout.CENTER);
		JLabel value = new JLabel(signed(format, r.saved));
		value.setForeground(r.saved > 0 ? GAIN : r.saved < 0 ? LOSS : Color.WHITE);
		top.add(value, BorderLayout.EAST);
		row.add(top, BorderLayout.NORTH);

		JPanel bottom = new JPanel(new BorderLayout(6, 0));
		bottom.setOpaque(false);
		JLabel date = new JLabel(r.date);
		date.setFont(FontManager.getRunescapeSmallFont());
		date.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		bottom.add(date, BorderLayout.WEST);
		JLabel paid = new JLabel(r.cost != null ? "paid " + format.format(r.cost) : "paid ?");
		paid.setFont(FontManager.getRunescapeSmallFont());
		paid.setForeground(r.cost != null ? ColorScheme.MEDIUM_GRAY_COLOR : ColorScheme.BRAND_ORANGE);
		bottom.add(paid, BorderLayout.EAST);
		row.add(bottom, BorderLayout.SOUTH);

		String paidText = r.cost == null ? "unknown, counted as if sold on the Grand Exchange"
			: CoinFormat.EXACT.format(r.cost) + " coins (" + ("ge".equals(r.costSource) ? "Grand Exchange purchase" : "entered by you") + ")";
		row.setToolTipText("<html>Coffer credit: " + CoinFormat.EXACT.format(r.credit) + " coins<br>Paid: " + paidText
			+ "<br>Click to change what you paid</html>");

		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				editCost(r);
			}

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

	/** Asks what the player paid for one sacrifice and hands the answer to the plugin. */
	private void editCost(SacrificeRow r)
	{
		if (costEditor == null)
		{
			return;
		}
		String message = r.quantity + " x " + r.name + "\nCoffer credit: " + CoinFormat.EXACT.format(r.credit)
			+ " coins\n\nWhat did you pay for all of them? For example 166000, 166k or 1.2m."
			+ "\nEnter 0 for loot. Leave it empty if you do not know.";
		Object answer = JOptionPane.showInputDialog(this, message, "Death Cost Tracker",
			JOptionPane.PLAIN_MESSAGE, null, null, r.cost != null ? CoinFormat.EXACT.format(r.cost) : "");
		if (answer == null)
		{
			return; // cancelled
		}
		String text = answer.toString().trim();
		Long cost = null;
		if (!text.isEmpty())
		{
			cost = parseCoins(text);
			if (cost == null)
			{
				JOptionPane.showMessageDialog(this, "\"" + text + "\" is not an amount. Use for example 166000,"
					+ " 166k or 1.2m.", "Death Cost Tracker", JOptionPane.WARNING_MESSAGE);
				return;
			}
		}
		costEditor.accept(r.n, cost);
	}

	/** Parses "166000", "166,000", "166k", "1.2m" or "1b"; null if it is not a non-negative amount. */
	@Nullable
	static Long parseCoins(String text)
	{
		String t = text.toLowerCase(Locale.ROOT).replace(",", "").replace(" ", "");
		if (t.endsWith("gp"))
		{
			t = t.substring(0, t.length() - 2);
		}
		long multiplier = 1;
		if (t.endsWith("k"))
		{
			multiplier = 1_000;
		}
		else if (t.endsWith("m"))
		{
			multiplier = 1_000_000;
		}
		else if (t.endsWith("b"))
		{
			multiplier = 1_000_000_000;
		}
		if (multiplier > 1)
		{
			t = t.substring(0, t.length() - 1);
		}
		try
		{
			BigDecimal value = new BigDecimal(t).multiply(BigDecimal.valueOf(multiplier));
			if (value.signum() < 0)
			{
				return null;
			}
			return value.setScale(0, RoundingMode.HALF_UP).longValueExact();
		}
		catch (NumberFormatException | ArithmeticException e)
		{
			return null;
		}
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
