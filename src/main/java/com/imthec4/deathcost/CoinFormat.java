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

import java.util.Locale;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CoinFormat
{
	SHORT("Short (1.25M)"),
	EXACT("Exact (1,250,000)");

	private final String label;

	@Override
	public String toString()
	{
		return label;
	}

	String format(long coins)
	{
		if (this == EXACT)
		{
			return String.format(Locale.US, "%,d", coins);
		}
		long abs = Math.abs(coins);
		String sign = coins < 0 ? "-" : "";
		if (abs < 1_000)
		{
			return sign + abs;
		}
		if (abs < 1_000_000)
		{
			return sign + trim(abs / 1_000.0, abs < 100_000 ? 1 : 0) + "K";
		}
		if (abs < 1_000_000_000)
		{
			return sign + trim(abs / 1_000_000.0, 2) + "M";
		}
		return sign + trim(abs / 1_000_000_000.0, 2) + "B";
	}

	/** Rounds down to the given number of decimals and drops trailing zeros: 1.50 -> 1.5, 2.00 -> 2. */
	private static String trim(double value, int decimals)
	{
		double factor = Math.pow(10, decimals);
		double floored = Math.floor(value * factor) / factor;
		String s = String.format(Locale.ROOT, "%." + decimals + "f", floored);
		if (s.indexOf('.') >= 0)
		{
			s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
		}
		return s;
	}
}
