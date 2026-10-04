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

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

/**
 * Reads and writes one JSON file per character in the plugin's own data directory
 * (.runelite/plugin-data/death-cost-tracker), through RuneLite's {@link Filepath}. All file
 * work runs on RuneLite's background executor, so the client thread never waits on the disk.
 * Writes go to a temporary file first and are then moved over the real one, so a crash
 * mid-write cannot leave a half-written file behind. Nothing is ever sent anywhere.
 */
@Slf4j
@Singleton
class CostStore
{
	private final Gson gson;
	private final ScheduledExecutorService executor;

	/** Set on the executor by {@link #start}; tasks queued after it always see it. */
	@Nullable
	private volatile Filepath dir;

	@Inject
	CostStore(Gson gson, ScheduledExecutorService executor)
	{
		this.gson = gson;
		this.executor = executor;
	}

	/** Resolves the data directory in the background (Plugin.getPluginDirectory may touch the disk). */
	void start(Callable<Filepath> pluginDirectory)
	{
		submit(() ->
		{
			try
			{
				dir = pluginDirectory.call();
			}
			catch (Exception e)
			{
				log.warn("Death Cost Tracker: no data directory, nothing will be saved", e);
			}
		});
	}

	void stop()
	{
		// Writes already queued still run; nothing else to release
		submit(() -> dir = null);
	}

	/** Loads the character's data in the background and hands it to {@code done} (on the executor). */
	void load(long accountHash, Consumer<CostData> done)
	{
		submit(() -> done.accept(read(accountHash)));
	}

	/** Serialises on the calling (client) thread, writes in the background. */
	void save(long accountHash, CostData data)
	{
		String json = gson.toJson(data);
		submit(() -> write(accountHash, json));
	}

	private void submit(Runnable task)
	{
		try
		{
			executor.execute(task);
		}
		catch (RejectedExecutionException ignored)
		{
			// client is shutting down
		}
	}

	private CostData read(long accountHash)
	{
		Filepath d = dir;
		if (d == null)
		{
			return CostData.fresh();
		}
		Filepath file = d.joinSegment(accountHash + ".json");
		if (!file.exists())
		{
			return CostData.fresh();
		}
		try (Reader reader = file.openReader())
		{
			CostData data = gson.fromJson(reader, CostData.class);
			if (data == null)
			{
				throw new JsonParseException("empty file");
			}
			return data.normalize();
		}
		catch (IOException | RuntimeException e)
		{
			// Keep the unreadable file for inspection and start over rather than fail
			log.warn("Death Cost Tracker: {} is unreadable, moving it aside as .bad", file.getFileName(), e);
			try
			{
				file.moveTo(d.joinSegment(accountHash + ".json.bad"), StandardCopyOption.REPLACE_EXISTING);
			}
			catch (IOException moveFailed)
			{
				log.warn("Death Cost Tracker: could not move {} aside", file.getFileName(), moveFailed);
			}
			return CostData.fresh();
		}
	}

	private void write(long accountHash, String json)
	{
		Filepath d = dir;
		if (d == null)
		{
			return;
		}
		try
		{
			Filepath target = d.joinSegment(accountHash + ".json");
			Filepath tmp = d.joinSegment(accountHash + ".json.tmp");
			tmp.write(json);
			try
			{
				tmp.moveTo(target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			}
			catch (AtomicMoveNotSupportedException e)
			{
				tmp.moveTo(target, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		catch (IOException e)
		{
			log.warn("Death Cost Tracker: could not save data", e);
		}
	}
}
