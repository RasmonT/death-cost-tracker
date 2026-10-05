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

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Everything stored for one character, serialised as
 * ~/.runelite/plugin-data/death-cost-tracker/&lt;accountHash&gt;.json.
 *
 * Times are ISO-8601 strings so the injected Gson needs no type adapters. Nothing in here
 * identifies the player beyond the account hash that names the file.
 */
class CostData
{
	static final int VERSION = 1;
	static final int MAX_HISTORY = 200;
	/** Sacrifices kept one by one; older ones are folded into {@link Savings#archived}. */
	static final int MAX_SACRIFICES = 500;
	/** Grand Exchange purchases remembered for pricing later sacrifices. */
	static final int MAX_LOTS = 300;
	/** The coffer only takes items worth 10,000+; cheaper purchases are not worth remembering. */
	static final long MIN_LOT_EACH = 5_000;
	static final int GE_SLOTS = 8;

	int version = VERSION;
	Total total;
	Today today;
	Session session;
	Coffer coffer;
	Savings savings;
	List<Payment> history;
	/** Everything paid per day (yyyy-MM-dd -> coins), kept forever; only days with costs. */
	Map<String, Long> days;
	Deaths deaths;
	/** Deaths per day (yyyy-MM-dd -> count), kept forever; only days with deaths. */
	Map<String, Long> deathDays;
	/** Grand Exchange purchases not yet matched to a sacrifice, oldest first. */
	List<Lot> lots;
	/** Last seen state of each Grand Exchange slot, to tell new purchases from ones already counted. */
	Offer[] geSlots;

	/** Items bought on the Grand Exchange in one go (or one part of a slowly filling offer). */
	static class Lot
	{
		int id;
		long quantity;
		/** What was paid for those items in total. */
		long cost;
		String at;
	}

	/** A buy offer as last seen in its slot. */
	static class Offer
	{
		int id;
		int total;
		long price;
		int bought;
		long spent;
	}

	static class Deaths
	{
		/** Since login. */
		long session;
		/** Since the death counter was last reset. */
		long total;
		String since;
	}

	static class Total
	{
		long coins;
		String since;
	}

	static class Today
	{
		String date;
		long coins;
	}

	static class Session
	{
		String start;
		long coins;
	}

	static class Coffer
	{
		/** Last balance the game showed, -1 if never seen. */
		long balance = -1;
		String seenAt;
	}

	static class Savings
	{
		String since;
		/** One entry per sacrifice, oldest first. */
		List<Sacrifice> records;
		/** Savings of records dropped to keep the list short. */
		long archived;
		long nextId = 1;
		/** Older files: a running sum per item, converted to records on load. */
		List<Sacrificed> items;
	}

	/** Old format: running sum per (unnoted) item id. Only read, to convert it. */
	static class Sacrificed
	{
		int id;
		long quantity;
		long credit;
	}

	/** Items sacrificed to the coffer in one go. */
	static class Sacrifice
	{
		/** Stable id, used by the side panel to edit the price paid. */
		long n;
		String at;
		int id;
		String name;
		long quantity;
		/** Coffer credit received. */
		long credit;
		/** What the player paid for these items in total; null if unknown. */
		Long cost;
		/** "ge" (matched to a Grand Exchange purchase) or "manual" (entered in the side panel). */
		String costSource;
	}

	/** Death's Coffer credits 105% of the official Grand Exchange guide price. */
	static final double COFFER_RATE = 1.05;
	/** Grand Exchange sales tax: 2% of the price, at most 5M per item. */
	static final double GE_TAX_RATE = 0.02;
	static final long GE_TAX_CAP = 5_000_000;

	/**
	 * What a sacrifice gained over selling the same items on the Grand Exchange: the coffer
	 * credit minus the guide price after the GE tax. The guide price is read back from the
	 * credit itself (credit / 1.05), so the result is never negative and does not move with
	 * later market prices.
	 */
	static long saved(Sacrifice s)
	{
		return s.cost != null ? s.credit - s.cost : saved(s.quantity, s.credit);
	}

	/** Never-negative estimate used when the price paid is unknown: coffer bonus plus avoided GE tax. */
	static long saved(long quantity, long credit)
	{
		if (quantity <= 0 || credit <= 0)
		{
			return 0;
		}
		double guideEach = credit / COFFER_RATE / quantity;
		double taxEach = Math.min(Math.floor(guideEach * GE_TAX_RATE), GE_TAX_CAP);
		long sold = Math.round((guideEach - taxEach) * quantity);
		return Math.max(0, credit - sold);
	}

	static class Payment
	{
		String at;
		/** The whole fee. */
		long coins;
		/** The part of it the bank paid; the rest came from the coffer. */
		long bank;
		/** "coffer", "bank" or "mixed". */
		String source;
		/** "grave" (gravestone), "npc" (boss reclaim NPC), "office" (Death's Office),
		 * "instance" (private boss instance, only with that option on) or "other". */
		String kind;
	}

	static CostData fresh()
	{
		CostData d = new CostData();
		d.normalize();
		return d;
	}

	/** Fills anything a missing, older or hand-edited file left out. */
	CostData normalize()
	{
		String now = now();
		version = VERSION;
		if (total == null)
		{
			total = new Total();
		}
		if (total.since == null)
		{
			total.since = now;
		}
		if (today == null)
		{
			today = new Today();
		}
		if (session == null)
		{
			session = new Session();
		}
		if (coffer == null)
		{
			coffer = new Coffer();
		}
		if (savings == null)
		{
			savings = new Savings();
		}
		if (savings.since == null)
		{
			savings.since = now;
		}
		if (savings.records == null)
		{
			savings.records = new ArrayList<>();
		}
		savings.records.removeIf(s -> s == null || s.id <= 0 || s.quantity <= 0);
		for (Sacrifice r : savings.records)
		{
			savings.nextId = Math.max(savings.nextId, r.n + 1);
			if (r.cost != null && r.cost < 0)
			{
				r.cost = null;
			}
		}
		for (Sacrifice r : savings.records)
		{
			if (r.n <= 0)
			{
				r.n = savings.nextId++;
			}
		}
		if (savings.items != null)
		{
			// Convert the old per-item sums; the price paid is unknown for them
			for (Sacrificed old : savings.items)
			{
				if (old != null && old.id > 0 && old.quantity > 0)
				{
					savings.records.add(record(savings.since, old.id, null, old.quantity, old.credit));
				}
			}
			savings.items = null;
		}
		lots = lots == null ? new ArrayList<>() : lots;
		lots.removeIf(l -> l == null || l.id <= 0 || l.quantity <= 0 || l.cost < 0);
		Offer[] slots = new Offer[GE_SLOTS];
		if (geSlots != null)
		{
			System.arraycopy(geSlots, 0, slots, 0, Math.min(geSlots.length, GE_SLOTS));
		}
		geSlots = slots;
		if (history == null)
		{
			history = new ArrayList<>();
		}
		history.removeIf(p -> p == null);
		days = days == null ? new TreeMap<>() : new TreeMap<>(days);
		days.values().removeIf(v -> v == null);
		if (deaths == null)
		{
			deaths = new Deaths();
		}
		if (deaths.since == null)
		{
			deaths.since = now;
		}
		deathDays = deathDays == null ? new TreeMap<>() : new TreeMap<>(deathDays);
		deathDays.values().removeIf(v -> v == null);
		return this;
	}

	/** Starts a new day if the stored one is not today. Returns true if it changed. */
	boolean rollDay(String date)
	{
		if (date.equals(today.date))
		{
			return false;
		}
		today.date = date;
		today.coins = 0;
		return true;
	}

	/** A new login: both session counters start over. */
	void startSession()
	{
		resetSessionCost();
		deaths.session = 0;
	}

	void resetSessionCost()
	{
		session.start = now();
		session.coins = 0;
	}

	void addDeath()
	{
		deaths.session++;
		deaths.total++;
		deathDays.merge(today.date, 1L, Long::sum);
	}

	void addPayment(long coins, long bank, String source, String kind)
	{
		session.coins += coins;
		today.coins += coins;
		total.coins += coins;

		Payment p = new Payment();
		p.at = now();
		p.coins = coins;
		p.bank = bank;
		p.source = source;
		p.kind = kind;
		history.add(p);
		days.merge(today.date, coins, Long::sum);
		while (history.size() > MAX_HISTORY)
		{
			history.remove(0);
		}
	}

	/**
	 * Files older data (written before the per-day record existed) under days, from the
	 * payment history, plus today's counter for anything the history no longer holds.
	 */
	void fillDaysFromHistory(ZoneId zone)
	{
		if (!days.isEmpty())
		{
			return;
		}
		for (Payment p : history)
		{
			try
			{
				String date = Instant.parse(p.at).atZone(zone).toLocalDate().toString();
				days.merge(date, p.coins, Long::sum);
			}
			catch (RuntimeException ignored)
			{
				// unreadable timestamp in a hand-edited file
			}
		}
		if (today.date != null && today.coins > days.getOrDefault(today.date, 0L))
		{
			days.put(today.date, today.coins);
		}
	}

	/**
	 * Records a sacrifice. With {@code usePurchases} the items are matched to remembered Grand
	 * Exchange purchases, oldest first; any part with no matching purchase gets its own record
	 * with an unknown price, the credit split by quantity.
	 */
	void addSacrifice(int itemId, String name, long quantity, long credit, boolean usePurchases)
	{
		if (quantity <= 0)
		{
			return;
		}
		long coveredQuantity = 0;
		long coveredCost = 0;
		if (usePurchases)
		{
			for (int i = 0; i < lots.size() && coveredQuantity < quantity; i++)
			{
				Lot lot = lots.get(i);
				if (lot.id != itemId)
				{
					continue;
				}
				long take = Math.min(lot.quantity, quantity - coveredQuantity);
				long part = take == lot.quantity ? lot.cost : Math.round((double) lot.cost * take / lot.quantity);
				coveredQuantity += take;
				coveredCost += part;
				lot.quantity -= take;
				lot.cost -= part;
			}
			lots.removeIf(l -> l.quantity <= 0);
		}

		String now = now();
		if (coveredQuantity == quantity)
		{
			addRecord(record(now, itemId, name, quantity, credit), coveredCost);
		}
		else if (coveredQuantity > 0)
		{
			long knownCredit = Math.round((double) credit * coveredQuantity / quantity);
			addRecord(record(now, itemId, name, coveredQuantity, knownCredit), coveredCost);
			addRecord(record(now, itemId, name, quantity - coveredQuantity, credit - knownCredit), null);
		}
		else
		{
			addRecord(record(now, itemId, name, quantity, credit), null);
		}
	}

	private Sacrifice record(String at, int itemId, String name, long quantity, long credit)
	{
		Sacrifice r = new Sacrifice();
		r.n = savings.nextId++;
		r.at = at;
		r.id = itemId;
		r.name = name;
		r.quantity = quantity;
		r.credit = credit;
		return r;
	}

	private void addRecord(Sacrifice r, Long geCost)
	{
		if (geCost != null)
		{
			r.cost = geCost;
			r.costSource = "ge";
		}
		savings.records.add(r);
		while (savings.records.size() > MAX_SACRIFICES)
		{
			savings.archived += saved(savings.records.remove(0));
		}
	}

	/** Sets (or with null clears) what was paid for a sacrifice. Returns false if it is gone. */
	boolean setSacrificeCost(long n, Long cost)
	{
		for (Sacrifice r : savings.records)
		{
			if (r.n == n)
			{
				r.cost = cost;
				r.costSource = cost != null ? "manual" : null;
				return true;
			}
		}
		return false;
	}

	long totalSavings()
	{
		long sum = savings.archived;
		for (Sacrifice r : savings.records)
		{
			sum += saved(r);
		}
		return sum;
	}

	void resetSavings()
	{
		savings.records.clear();
		savings.archived = 0;
		savings.since = now();
	}

	/**
	 * Takes the new state of a Grand Exchange slot. A buy offer that is the same one as last time
	 * adds only what was bought since; a different one adds everything it bought so far. With
	 * {@code track} off the slot is still followed, so turning it on later does not count old
	 * purchases twice. Returns true if anything stored changed.
	 */
	boolean onOffer(int slot, boolean buy, int itemId, int total, long price, int bought, long spent, boolean track)
	{
		if (slot < 0 || slot >= GE_SLOTS)
		{
			return false;
		}
		Offer old = geSlots[slot];
		if (!buy || itemId <= 0)
		{
			// Empty slot or a sell offer: nothing bought here
			if (old == null)
			{
				return false;
			}
			geSlots[slot] = null;
			return true;
		}
		boolean same = old != null && old.id == itemId && old.total == total && old.price == price
			&& bought >= old.bought && spent >= old.spent;
		if (same && old.bought == bought && old.spent == spent)
		{
			return false;
		}
		long newQuantity = same ? bought - old.bought : bought;
		long newCost = same ? spent - old.spent : spent;

		Offer o = new Offer();
		o.id = itemId;
		o.total = total;
		o.price = price;
		o.bought = bought;
		o.spent = spent;
		geSlots[slot] = o;

		if (track && newQuantity > 0 && newCost >= newQuantity * MIN_LOT_EACH)
		{
			Lot lot = new Lot();
			lot.id = itemId;
			lot.quantity = newQuantity;
			lot.cost = newCost;
			lot.at = now();
			lots.add(lot);
			while (lots.size() > MAX_LOTS)
			{
				lots.remove(0);
			}
		}
		return true;
	}

	void setCofferBalance(long balance)
	{
		coffer.balance = balance;
		coffer.seenAt = now();
	}

	static String now()
	{
		return Instant.now().toString();
	}
}
