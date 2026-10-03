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
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * Reads and writes one JSON file per character on a background thread, so the client
 * thread never waits on the disk. Writes go to a temporary file first and are then moved
 * over the real one, so a crash mid-write cannot leave a half-written file behind.
 * Nothing is ever sent anywhere.
 */
@Slf4j
@Singleton
class CostStore
{
	static final File DIR = new File(RuneLite.RUNELITE_DIR, "death-cost-tracker");

	private final Gson gson;
	private ExecutorService io;

	@Inject
	CostStore(Gson gson)
	{
		this.gson = gson;
	}

	void start()
	{
		io = Executors.newSingleThreadExecutor(r ->
		{
			Thread t = new Thread(r, "death-cost-tracker-io");
			t.setDaemon(true);
			return t;
		});
	}

	/** Lets queued writes finish without blocking the caller. */
	void stop()
	{
		if (io != null)
		{
			io.shutdown();
			io = null;
		}
	}

	/** Loads the character's data in the background and hands it to {@code done} (on the io thread). */
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
		ExecutorService e = io;
		if (e == null)
		{
			return;
		}
		try
		{
			e.execute(task);
		}
		catch (RejectedExecutionException ignored)
		{
			// plugin is shutting down
		}
	}

	private static File file(long accountHash)
	{
		return new File(DIR, accountHash + ".json");
	}

	private CostData read(long accountHash)
	{
		File f = file(accountHash);
		if (!f.exists())
		{
			return CostData.fresh();
		}
		try
		{
			String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
			CostData data = gson.fromJson(json, CostData.class);
			if (data == null)
			{
				throw new JsonParseException("empty file");
			}
			return data.normalize();
		}
		catch (IOException | RuntimeException e)
		{
			// Keep the unreadable file for inspection and start over rather than fail
			log.warn("Death Cost Tracker: {} is unreadable, moving it aside as .bad", f.getName(), e);
			try
			{
				Files.move(f.toPath(), new File(DIR, f.getName() + ".bad").toPath(),
					StandardCopyOption.REPLACE_EXISTING);
			}
			catch (IOException moveFailed)
			{
				log.warn("Death Cost Tracker: could not move {} aside", f.getName(), moveFailed);
			}
			return CostData.fresh();
		}
	}

	private static void write(long accountHash, String json)
	{
		try
		{
			if (!DIR.exists() && !DIR.mkdirs())
			{
				log.warn("Death Cost Tracker: could not create {}", DIR);
				return;
			}
			Path target = file(accountHash).toPath();
			Path tmp = new File(DIR, accountHash + ".json.tmp").toPath();
			Files.write(tmp, json.getBytes(StandardCharsets.UTF_8));
			try
			{
				Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			}
			catch (AtomicMoveNotSupportedException e)
			{
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		catch (IOException e)
		{
			log.warn("Death Cost Tracker: could not save data", e);
		}
	}
}
