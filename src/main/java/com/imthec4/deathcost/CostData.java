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
 * ~/.runelite/death-cost-tracker/&lt;accountHash&gt;.json.
 *
 * Times are ISO-8601 strings so the injected Gson needs no type adapters. Nothing in here
 * identifies the player beyond the account hash that names the file.
 */
class CostData
{
	static final int VERSION = 1;
	static final int MAX_HISTORY = 200;

	int version = VERSION;
	Total total;
	Today today;
	Session session;
	Coffer coffer;
	Savings savings;
	List<Payment> history;
	/** Everything paid per day (yyyy-MM-dd -> coins), kept forever; only days with costs. */
	Map<String, Long> days;

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
		List<Sacrificed> items;
	}

	/** Running sum per (unnoted) item id: how many were sacrificed and the coffer credit received. */
	static class Sacrificed
	{
		int id;
		long quantity;
		long credit;
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
		if (savings.items == null)
		{
			savings.items = new ArrayList<>();
		}
		savings.items.removeIf(s -> s == null || s.id <= 0);
		if (history == null)
		{
			history = new ArrayList<>();
		}
		history.removeIf(p -> p == null);
		days = days == null ? new TreeMap<>() : new TreeMap<>(days);
		days.values().removeIf(v -> v == null);
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

	void startSession()
	{
		session.start = now();
		session.coins = 0;
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

	void addSacrifice(int itemId, long quantity, long credit)
	{
		for (Sacrificed s : savings.items)
		{
			if (s.id == itemId)
			{
				s.quantity += quantity;
				s.credit += credit;
				return;
			}
		}
		Sacrificed s = new Sacrificed();
		s.id = itemId;
		s.quantity = quantity;
		s.credit = credit;
		savings.items.add(s);
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
