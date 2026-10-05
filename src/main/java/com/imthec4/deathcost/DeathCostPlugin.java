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

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.TreeMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.VarClientIntChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;

/**
 * Tracks what the player pays Death to get items back, per session, per day and in total,
 * and what the Death's Coffer saved compared to buying the sacrificed items back.
 *
 * How it detects things was established from an in-game capture (discovery build):
 * <ul>
 * <li>A grave reclaim always ends with "Death charges you N x Coins." followed by either
 * "You have 331,655 x Coins left in Death's Coffer." or "You have nothing left in Death's
 * Coffer." N is the whole fee.</li>
 * <li>Whatever the coffer could not cover is announced first, in the same tick:
 * "Payment has been taken from your bank: 180,096 x Coins". It is part of N, not extra.</li>
 * <li>A boss reclaim NPC (Torfinn, interface GRAVESTONE_RETRIEVAL) paid from the bank sends
 * only the bank message, with no "Death charges you" after it. Partly from the coffer, it
 * sends both, exactly like a grave.</li>
 * <li>Death's Office (interface DEATH_OFFICE) paid from the bank sends only
 * "Payment has been taken from your bank: 5,964 coins".</li>
 * <li>While the sacrifice interface is open, varp IF1 (261) holds the coffer balance. A
 * sacrifice raises it by the credit in the same tick the items leave the inventory.</li>
 * <li>The gravestone interface puts the coffer balance in varc 400.</li>
 * </ul>
 * Private boss instances (optional, off by default) are paid with SPAM-type messages, coffer
 * first and the bank for the rest, in the same tick:
 * "Payment has been taken from your coffer: 15,325 x Coins" and
 * "Payment has been taken from your bank: 9,675 x Coins". With an empty coffer only the bank
 * line comes; it is recognised by the "Pay 25,000 x Coins?" dialog shown just before it.
 * <p>
 * The bank message is counted only while a reclaim interface is open: the game uses similar
 * wording elsewhere ("Some of your payment has been taken from your bank." on the Grand
 * Exchange), and that must never count as a death cost.
 *
 * All data is stored locally, one JSON file per character. Nothing is sent anywhere.
 */
@Slf4j
@PluginDescriptor(
	internalName = "death-cost-tracker",
	name = "Death Cost Tracker",
	description = "Tracks what you pay to reclaim items after dying, per session, per day and in total",
	tags = {"death", "coffer", "gravestone", "reclaim", "cost", "sacrifice"}
)
public class DeathCostPlugin extends Plugin
{
	/** The whole reclaim fee, however it was paid. The tail says what is left in the coffer. */
	private static final Pattern DEATH_FEE = Pattern.compile("^Death charges you ([\\d,]+) x Coins\\.(.*)$");
	private static final Pattern COFFER_LEFT = Pattern.compile("You have ([\\d,]+) x Coins left in Death's Coffer");
	private static final String COFFER_EMPTY = "You have nothing left in Death's Coffer";

	/** Sent once per death, for every kind of death seen in the captures. */
	private static final String YOU_DIED = "Oh dear, you are dead!";

	/**
	 * The part of a reclaim fee the bank paid. Graves and boss NPCs say "180,096 x Coins",
	 * Death's Office says "5,964 coins".
	 */
	private static final Pattern BANK_PAID = Pattern.compile(
		"^Payment has been taken from your bank: ([\\d,]+) (?:x Coins|coins)\\.?$");

	/** Coffer spending on a private boss instance (a SPAM message, unlike the reclaim ones). */
	private static final Pattern INSTANCE_COFFER = Pattern.compile(
		"^Payment has been taken from your coffer: ([\\d,]+) x Coins\\.?$");

	/** The instance offer dialog (InterfaceID.CHATMENU): "Pay 25,000 x Coins?". */
	private static final Pattern INSTANCE_OFFER = Pattern.compile("^Pay ([\\d,]+) x Coins\\?$");

	/** A bank-only instance payment must follow the offer dialog within this many ticks. */
	private static final int OFFER_TICKS = 50;

	/**
	 * How long a bank payment waits for a "Death charges you" message that includes it. In the
	 * capture both arrived in the same tick; if none comes, the bank payment is the whole fee.
	 */
	private static final int BANK_WAIT_TICKS = 2;

	/** Sacrifice credit and the removed items must arrive within this many ticks of each other. */
	private static final int PAIR_TICKS = 1;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private DeathCostConfig config;

	@Inject
	private DeathCostOverlay overlay;

	@Inject
	private DeathCountOverlay deathOverlay;

	@Inject
	private CostStore store;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private DeathCostPanel panel;

	@Nullable
	private NavigationButton navButton;

	/** Data of the logged-in character; null while logged out or still loading. */
	@Getter
	@Nullable
	private CostData data;

	private long accountHash = -1;

	/** Changes that arrived while the character's file was still loading. */
	private final List<Consumer<CostData>> pending = new ArrayList<>();

	/** Inventory as of the last change, unnoted item id -> quantity. */
	private final Map<Integer, Long> inventory = new HashMap<>();

	/** Instance payment being collected: coffer part, then a bank part in the same tick. */
	private long instanceOffer;
	private int instanceOfferTick;
	private long pendingInstanceCoffer;
	private long pendingInstanceBank;
	private int pendingInstanceTick;

	private long pendingBank;
	private int pendingBankTick;
	private String pendingBankKind;

	private boolean cofferOpen;
	private int lastCofferVarp;
	private long pendingCredit;
	private int pendingCreditTick;
	private final Map<Integer, Long> pendingRemoved = new HashMap<>();
	private int pendingRemovedTick;

	@Provides
	DeathCostConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(DeathCostConfig.class);
	}

	@Override
	protected void startUp()
	{
		store.start(this::getPluginDirectory);
		overlayManager.add(overlay);
		overlayManager.add(deathOverlay);
		navButton = NavigationButton.builder()
			.tooltip("Death Cost Tracker")
			.icon(ImageUtil.loadImageResource(getClass(), "panel_icon.png"))
			.priority(8)
			.panel(panel)
			.build();
		if (config.showPanel())
		{
			clientToolbar.addNavigation(navButton);
		}
		publishDays();
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			clientThread.invoke(this::onLoggedIn);
		}
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		overlayManager.remove(deathOverlay);
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		flushBank();
		flushInstance();
		save();
		store.stop();
		data = null;
		accountHash = -1;
		pending.clear();
		inventory.clear();
		closeCoffer();
	}

	// ------------------------------------------------------------------ session and loading

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOGIN_SCREEN)
		{
			// Logout, disconnect or the 6-hour logout: the session ends here
			flushBank();
			flushInstance();
			save();
			data = null;
			accountHash = -1;
			pending.clear();
			inventory.clear();
			closeCoffer();
			publishDays();
		}
		else if (state == GameState.LOGGED_IN)
		{
			// Also fires after loading screens and world hops; onLoggedIn only acts on a new character
			onLoggedIn();
		}
	}

	private void onLoggedIn()
	{
		long hash = client.getAccountHash();
		if (hash == -1 || hash == accountHash)
		{
			return;
		}
		save();
		accountHash = hash;
		data = null;
		store.load(hash, loaded -> clientThread.invoke(() -> install(hash, loaded)));
	}

	private void install(long hash, CostData loaded)
	{
		if (hash != accountHash)
		{
			return; // logged out or switched character while loading
		}
		data = loaded;
		data.fillDaysFromHistory(config.dayBoundary().zone());
		data.startSession();
		data.rollDay(config.dayBoundary().today());
		for (Consumer<CostData> change : pending)
		{
			change.accept(data);
		}
		pending.clear();
		save();
		publishDays();
	}

	/** Applies a change now, or as soon as the character's file has loaded. */
	private void change(Consumer<CostData> change)
	{
		if (data != null)
		{
			change.accept(data);
			save();
			publishDays();
		}
		else if (accountHash != -1)
		{
			pending.add(change);
		}
	}

	private void save()
	{
		if (data != null && accountHash != -1)
		{
			store.save(accountHash, data);
		}
	}

	/** Hands the side panel copies of the per-day records (the panel lives on the Swing thread). */
	private void publishDays()
	{
		CostData d = data;
		Map<String, Long> costs = d != null ? new TreeMap<>(d.days) : new TreeMap<>();
		Map<String, Long> deaths = d != null ? new TreeMap<>(d.deathDays) : new TreeMap<>();
		boolean loggedIn = d != null;
		SwingUtilities.invokeLater(() -> panel.setDays(costs, deaths, loggedIn));
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!DeathCostConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		if ("showPanel".equals(event.getKey()) && navButton != null)
		{
			if (config.showPanel())
			{
				clientToolbar.addNavigation(navButton);
			}
			else
			{
				clientToolbar.removeNavigation(navButton);
			}
		}
		SwingUtilities.invokeLater(panel::refresh);
	}

	/** Called by the overlay every frame: starts a new day lazily when the date changes. */
	void rollDay()
	{
		if (data != null && data.rollDay(config.dayBoundary().today()))
		{
			save();
		}
	}

	// ------------------------------------------------------------------ reclaim fees

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() == ChatMessageType.SPAM)
		{
			onInstancePayment(Text.removeTags(event.getMessage()));
			return;
		}
		if (event.getType() != ChatMessageType.GAMEMESSAGE)
		{
			return;
		}
		String message = Text.removeTags(event.getMessage());

		if (YOU_DIED.equals(message))
		{
			change(d ->
			{
				d.rollDay(config.dayBoundary().today());
				d.addDeath();
			});
			return;
		}

		Matcher fee = DEATH_FEE.matcher(message);
		if (fee.matches())
		{
			long total = parseCoins(fee.group(1));
			String tail = fee.group(2);
			// A bank payment just before this message is part of this fee, not a second one
			long bank = Math.min(pendingBank, total);
			pendingBank = 0;
			String source = bank <= 0 ? "coffer" : bank >= total ? "bank" : "mixed";
			String kind = reclaimKind();
			Matcher left = COFFER_LEFT.matcher(tail);
			long balance = left.find() ? parseCoins(left.group(1)) : tail.contains(COFFER_EMPTY) ? 0 : -1;
			recordPayment(total, bank, source, kind != null ? kind : "other", balance);
			return;
		}

		Matcher bank = BANK_PAID.matcher(message);
		if (bank.matches())
		{
			String kind = reclaimKind();
			if (kind == null)
			{
				return; // not at a gravestone or reclaim NPC: some other bank payment
			}
			flushBank();
			pendingBank = parseCoins(bank.group(1));
			pendingBankTick = client.getTickCount();
			pendingBankKind = kind;
		}
	}

	/**
	 * Private instance payments. The coffer part always comes first; a bank message counts only
	 * as the rest of an instance payment in the same tick, never on its own, so no other
	 * bank payment can be mistaken for one.
	 */
	private void onInstancePayment(String message)
	{
		int tick = client.getTickCount();
		Matcher coffer = INSTANCE_COFFER.matcher(message);
		if (coffer.matches())
		{
			flushInstance();
			pendingInstanceCoffer = parseCoins(coffer.group(1));
			pendingInstanceBank = 0;
			pendingInstanceTick = tick;
			return;
		}
		Matcher bank = BANK_PAID.matcher(message);
		if (!bank.matches())
		{
			return;
		}
		long coins = parseCoins(bank.group(1));
		if (pendingInstanceCoffer > 0 && pendingInstanceTick == tick)
		{
			pendingInstanceBank += coins; // the rest of a coffer payment
		}
		else if (instanceOffer == coins && tick - instanceOfferTick <= OFFER_TICKS)
		{
			// Empty coffer: the bank paid the whole offer
			instanceOffer = 0;
			recordInstance(0, coins);
		}
	}

	/** Remembers the amount of a "Pay 25,000 x Coins?" dialog, if one is showing. */
	private void readInstanceOffer()
	{
		Widget options = client.getWidget(InterfaceID.CHATMENU, 1);
		Widget[] lines = options != null ? options.getDynamicChildren() : null;
		if (lines == null)
		{
			return;
		}
		for (Widget line : lines)
		{
			Matcher offer = line != null ? INSTANCE_OFFER.matcher(Text.removeTags(line.getText())) : null;
			if (offer != null && offer.matches())
			{
				instanceOffer = parseCoins(offer.group(1));
				instanceOfferTick = client.getTickCount();
				return;
			}
		}
	}

	/** Records the collected instance payment, if any. */
	private void flushInstance()
	{
		if (pendingInstanceCoffer <= 0)
		{
			return;
		}
		long fromCoffer = pendingInstanceCoffer;
		long fromBank = pendingInstanceBank;
		pendingInstanceCoffer = 0;
		pendingInstanceBank = 0;
		instanceOffer = 0;
		recordInstance(fromCoffer, fromBank);
	}

	private void recordInstance(long fromCoffer, long fromBank)
	{
		boolean count = config.countInstances();
		change(d ->
		{
			// The coffer is empty once the bank had to help; otherwise it went down by the payment
			if (fromBank > 0)
			{
				d.setCofferBalance(0);
			}
			else if (d.coffer.balance >= fromCoffer)
			{
				d.setCofferBalance(d.coffer.balance - fromCoffer);
			}
			if (count)
			{
				d.rollDay(config.dayBoundary().today());
				String source = fromCoffer == 0 ? "bank" : fromBank > 0 ? "mixed" : "coffer";
				d.addPayment(fromCoffer + fromBank, fromBank, source, "instance");
			}
		});
	}

	/** "grave", "npc" or "office" while a reclaim interface is open, otherwise null. */
	@Nullable
	private String reclaimKind()
	{
		if (isOpen(InterfaceID.GRAVESTONE_GENERIC))
		{
			return "grave";
		}
		if (isOpen(InterfaceID.GRAVESTONE_RETRIEVAL))
		{
			return "npc";
		}
		if (isOpen(InterfaceID.DEATH_OFFICE))
		{
			return "office";
		}
		return null;
	}

	/** Records a bank payment that no "Death charges you" message claimed: it was the whole fee. */
	private void flushBank()
	{
		if (pendingBank > 0)
		{
			long coins = pendingBank;
			pendingBank = 0;
			recordPayment(coins, coins, "bank", pendingBankKind, -1);
		}
	}

	private void recordPayment(long total, long bank, String source, String kind, long cofferBalance)
	{
		change(d ->
		{
			d.rollDay(config.dayBoundary().today());
			d.addPayment(total, bank, source, kind);
			if (cofferBalance >= 0)
			{
				d.setCofferBalance(cofferBalance);
			}
		});
	}

	private static long parseCoins(String digits)
	{
		return Long.parseLong(digits.replace(",", ""));
	}

	@Subscribe
	public void onVarClientIntChanged(VarClientIntChanged event)
	{
		if (event.getIndex() == VarClientID.GRAVESTONE_TRANSMIT_COFFER)
		{
			int balance = client.getVarcIntValue(VarClientID.GRAVESTONE_TRANSMIT_COFFER);
			if (balance >= 0)
			{
				change(d -> d.setCofferBalance(balance));
			}
		}
	}

	// ------------------------------------------------------------------ coffer sacrifices

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == InterfaceID.CHATMENU)
		{
			// Option texts are filled in after the load event
			clientThread.invokeLater(this::readInstanceOffer);
		}
		if (event.getGroupId() == InterfaceID.DEATH_COFFER)
		{
			cofferOpen = true;
			lastCofferVarp = client.getVarpValue(VarPlayerID.IF1);
			clearPendingSacrifice();
			// Start from the real inventory, in case no change event has been seen yet
			inventory.clear();
			inventory.putAll(count(client.getItemContainer(InventoryID.INV)));
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == InterfaceID.DEATH_COFFER)
		{
			closeCoffer();
		}
	}

	private void closeCoffer()
	{
		cofferOpen = false;
		clearPendingSacrifice();
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (!cofferOpen || event.getVarbitId() != -1 || event.getVarpId() != VarPlayerID.IF1)
		{
			return;
		}
		int now = client.getVarpValue(VarPlayerID.IF1);
		long credit = (long) now - lastCofferVarp;
		lastCofferVarp = now;
		change(d -> d.setCofferBalance(now));
		if (credit > 0)
		{
			pendingCredit = credit;
			pendingCreditTick = client.getTickCount();
			pairSacrifice();
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() != InventoryID.INV)
		{
			return;
		}
		Map<Integer, Long> now = count(event.getItemContainer());
		if (cofferOpen)
		{
			Map<Integer, Long> removed = new HashMap<>();
			for (Map.Entry<Integer, Long> e : inventory.entrySet())
			{
				long gone = e.getValue() - now.getOrDefault(e.getKey(), 0L);
				if (gone > 0)
				{
					removed.put(e.getKey(), gone);
				}
			}
			if (!removed.isEmpty())
			{
				pendingRemoved.clear();
				pendingRemoved.putAll(removed);
				pendingRemovedTick = client.getTickCount();
				pairSacrifice();
			}
		}
		inventory.clear();
		inventory.putAll(now);
	}

	private Map<Integer, Long> count(@Nullable ItemContainer container)
	{
		Map<Integer, Long> counts = new HashMap<>();
		if (container != null)
		{
			for (Item item : container.getItems())
			{
				if (item.getId() > 0 && item.getQuantity() > 0)
				{
					counts.merge(itemManager.canonicalize(item.getId()), (long) item.getQuantity(), Long::sum);
				}
			}
		}
		return counts;
	}

	/** Records a sacrifice once both halves - the coffer credit and the removed items - are in. */
	private void pairSacrifice()
	{
		if (pendingCredit <= 0 || pendingRemoved.isEmpty()
			|| Math.abs(pendingCreditTick - pendingRemovedTick) > PAIR_TICKS)
		{
			return;
		}
		long credit = pendingCredit;
		Map<Integer, Long> items = new HashMap<>(pendingRemoved);
		clearPendingSacrifice();

		// One item type per sacrifice in practice; if several left at once, split the
		// credit by their current price so the total still adds up exactly
		Map<Integer, Long> worth = new HashMap<>();
		long totalWorth = 0;
		for (Map.Entry<Integer, Long> e : items.entrySet())
		{
			long w = Math.max(1, itemManager.getItemPrice(e.getKey())) * e.getValue();
			worth.put(e.getKey(), w);
			totalWorth += w;
		}
		long assigned = 0;
		int left = items.size();
		Map<Integer, Long> share = new HashMap<>();
		for (Map.Entry<Integer, Long> e : worth.entrySet())
		{
			long part = --left == 0 ? credit - assigned : Math.round((double) credit * e.getValue() / totalWorth);
			share.put(e.getKey(), part);
			assigned += part;
		}
		change(d ->
		{
			for (Map.Entry<Integer, Long> e : items.entrySet())
			{
				d.addSacrifice(e.getKey(), e.getValue(), share.get(e.getKey()));
			}
		});
	}

	private void clearPendingSacrifice()
	{
		pendingCredit = 0;
		pendingRemoved.clear();
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		// Settle a lone bank payment; drop half of a sacrifice whose other half never came
		int tick = client.getTickCount();
		if (pendingBank > 0 && tick - pendingBankTick >= BANK_WAIT_TICKS)
		{
			flushBank();
		}
		if (pendingInstanceCoffer > 0 && tick - pendingInstanceTick >= 1)
		{
			flushInstance();
		}
		if (pendingCredit > 0 && tick - pendingCreditTick > PAIR_TICKS)
		{
			pendingCredit = 0;
		}
		if (!pendingRemoved.isEmpty() && tick - pendingRemovedTick > PAIR_TICKS)
		{
			pendingRemoved.clear();
		}
	}

	/** Coffer credit received minus what selling the sacrificed items on the GE would have paid. */
	long savings()
	{
		CostData d = data;
		if (d == null)
		{
			return 0;
		}
		long result = 0;
		for (CostData.Sacrificed s : d.savings.items)
		{
			result += CostData.saved(s.quantity, s.credit);
		}
		return result;
	}

	private boolean isOpen(int group)
	{
		return client.getWidget(group, 0) != null;
	}

	// ------------------------------------------------------------------ resets

	@Getter
	@RequiredArgsConstructor
	enum Counter
	{
		SESSION("Session", "session", false),
		TODAY("Today", "today", false),
		TOTAL("Total", "total", false),
		SAVINGS("Coffer savings", "coffer savings", false),
		SESSION_DEATHS("Session deaths", "session deaths", true),
		ALL_DEATHS("All deaths", "all deaths", true);

		private final String menuTarget;
		private final String spoken;
		/** A death count rather than coins; lives on the deaths overlay. */
		private final boolean deaths;
	}

	/** Asks for confirmation on the Swing thread, then resets on the client thread. */
	void confirmReset(Counter counter)
	{
		CostData d = data;
		if (d == null)
		{
			return;
		}
		long raw = value(counter);
		String value = counter.isDeaths() ? String.valueOf(raw) : config.coinFormat().format(raw);
		SwingUtilities.invokeLater(() ->
		{
			int answer = JOptionPane.showConfirmDialog(client.getCanvas(),
				"Reset " + counter.getSpoken() + " (" + value + ") to zero?",
				"Death Cost Tracker", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
			if (answer == JOptionPane.YES_OPTION)
			{
				clientThread.invoke(() -> reset(counter, value));
			}
		});
	}

	long value(Counter counter)
	{
		CostData d = data;
		if (d == null)
		{
			return 0;
		}
		switch (counter)
		{
			case SESSION:
				return d.session.coins;
			case TODAY:
				return d.today.coins;
			case TOTAL:
				return d.total.coins;
			case SESSION_DEATHS:
				return d.deaths.session;
			case ALL_DEATHS:
				return d.deaths.total;
			default:
				return savings();
		}
	}

	private void reset(Counter counter, String oldValue)
	{
		CostData d = data;
		if (d == null)
		{
			return;
		}
		switch (counter)
		{
			case SESSION:
				d.resetSessionCost();
				break;
			case TODAY:
				d.today.coins = 0;
				break;
			case TOTAL:
				d.total.coins = 0;
				d.total.since = CostData.now();
				break;
			case SAVINGS:
				d.savings.items.clear();
				d.savings.since = CostData.now();
				break;
			case SESSION_DEATHS:
				d.deaths.session = 0;
				break;
			case ALL_DEATHS:
				d.deaths.total = 0;
				d.deaths.since = CostData.now();
				break;
		}
		save();
		if (config.resetMessage())
		{
			chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.CONSOLE)
				.runeLiteFormattedMessage("Death Cost Tracker: " + counter.getSpoken() + " reset (was " + oldValue + ").")
				.build());
		}
	}
}
