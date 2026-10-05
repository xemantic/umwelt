/*
 * Umwelt - The web as your AI agent's Umwelt - every page transduced into the language a model natively perceives
 * Copyright (C) 2026  Kazimierz Pogoda / Xemantic
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.xemantic.umwelt.e2e.harness

import com.xemantic.umwelt.core.BrowserConfig
import com.xemantic.umwelt.core.ProfileConfig
import com.xemantic.umwelt.server.UmweltConfig
import com.xemantic.umwelt.server.loadUmweltConfig
import com.xemantic.umwelt.server.saveUmweltConfig
import com.xemantic.umwelt.server.serverEngineFactory
import com.xemantic.umwelt.server.umweltAppModule
import io.ktor.server.application.serverConfig
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * The real umwelt daemon, in this test process: the same `umweltAppModule` the
 * shipped binary installs, on the same engine — Netty on the JVM, CIO on
 * native, exactly as `serverEngineFactory` decides for the shipped binary —
 * driving a real Chromium-family browser over CDP. Only the port is ephemeral
 * and the configuration lives under `build/`.
 *
 * Its configuration is written **as files** before the engine starts — a
 * `config.yaml` and one `profiles/<name>.yaml` per profile, the layout the
 * shipped daemon writes on a first run and the one an agent on this machine
 * would find and edit — and then read back through the shipped
 * `loadUmweltConfig`, so the daemon under test boots from exactly what the
 * loader made of that directory: the names taken from the file names, the
 * sorted order, the data directories derived beside the files. Nothing
 * re-reads it while the daemon runs, and no command reports it: a
 * configuration change is a restart, and the files are read once.
 *
 * @param profiles the profiles the daemon offers, by name — the file each
 *   becomes. Every one is forced headless: an e2e run must not put windows on
 *   the developer's screen, and the handoff sequences assert on what the
 *   daemon *reports*, not on a window anybody can see.
 * @param defaultProfile the one an unnamed session lands on.
 * @param configDir where the configuration and every profile's data go; under
 *   `build/`, never `~/.umwelt`, so an e2e run can never collide with a daemon
 *   the developer is actually using.
 */
class UmweltDaemon(
    private val profiles: Map<String, ProfileConfig> = UmweltUnderTest.PROFILES,
    private val defaultProfile: String = UmweltUnderTest.E2E,
    private val configDir: Path = Path("build/e2e"),
) {

    /** The `config.yaml` the daemon boots from. */
    val configPath: Path = Path(configDir, "config.yaml")

    /** The directory holding one `<name>.yaml` per profile, and each persistent one's data. */
    val profilesDir: Path = Path(configDir, "profiles")

    /** `http://127.0.0.1:<port>` — the address to point the CLI at. */
    lateinit var baseUrl: String
        private set

    private lateinit var server: EmbeddedServer<*, *>

    fun start() {
        // Chrome allows one process per user-data-dir, and a browser leaked by
        // an interrupted run keeps holding this one — which would not fail the
        // next run, it would *block* it for as long as the leak lives. Dropping
        // the directory releases the lock, so a run is never at the mercy of
        // the one before it. Everything here is under build/, never the user's.
        configDir.deleteRecursively()
        SystemFileSystem.createDirectories(profilesDir)
        saveUmweltConfig(
            UmweltConfig(
                server = UmweltConfig.Server(
                    host = "127.0.0.1",
                    // the connector below binds the real (ephemeral) port; this
                    // one is only what the config would have said
                    port = 0,
                    openBrowser = false,
                ),
                defaultProfile = defaultProfile,
                profiles = profiles.mapValues { (_, profile) ->
                    profile.copy(browser = profile.browser.headless())
                },
            ),
            configPath
        )
        // the shipped loader, over the files just written: what the daemon runs
        // with is what a user's directory would have given it
        val config = loadUmweltConfig(configPath)
        server = embeddedServer(
            serverEngineFactory,
            serverConfig(applicationEnvironment()) {
                module { umweltAppModule(config) }
            }
        ) {
            connector {
                host = "127.0.0.1"
                port = 0
            }
        }
        server.start(wait = false)
        val port = runBlocking { server.engine.resolvedConnectors().first().port }
        baseUrl = "http://127.0.0.1:$port"
    }

    /**
     * Stops the daemon gracefully, which is what runs the DI cleanup that kills
     * Chrome. A hard stop would leak the browser process and its temp profile.
     */
    fun stop() {
        server.stop(gracePeriodMillis = 1_000, timeoutMillis = 10_000)
    }

    private fun BrowserConfig.headless(): BrowserConfig = when (this) {
        is BrowserConfig.Local -> copy(headless = true)
    }

}

/**
 * The one daemon and the one fixture site the whole suite shares.
 *
 * Chrome costs seconds to launch, so paying for it once per test process —
 * rather than once per test class — is the difference between a suite you run
 * and one you avoid. Sessions are the per-test unit of isolation instead: each
 * test opens its own and closes it. What a persistent session leaves behind
 * stays in the `e2e` profile for the rest of the run, so a sequence that
 * depends on a blank browser asks for an ephemeral session.
 *
 * Both are torn down by [onProcessExit], because a process-exit hook is the
 * only place multiplatform `kotlin.test` leaves for a teardown wider than a
 * single test method.
 */
object UmweltUnderTest {

    /**
     * The persistent default profile — a user-data-dir of its own under
     * `build/`, and where an unnamed session lands, exactly as on a user's
     * machine after the first run.
     */
    const val E2E = "e2e"

    /** A profile that keeps nothing: every session in it is ephemeral. */
    const val E2E_SCRAPER = "e2e-scraper"

    const val E2E_DESCRIPTION =
        "The persistent identity of the e2e suite: a user-data-dir of its own, " +
            "shared by every session opened in it."

    const val E2E_SCRAPER_DESCRIPTION =
        "Keeps nothing: every session gets a fresh context, for reading without an identity."

    /**
     * The profiles the daemon under test is started with — the two kinds a
     * deployment can hold: a persistent default, the shape a first run
     * writes, and one that keeps nothing (the shape of the hosted service, or
     * of a headless scraper beside the user's own). An ephemeral session in
     * the persistent profile is the third case the sequences exercise, and it
     * needs no profile of its own. Two profiles is the only multi-profile
     * shape this suite can boot: there is no remote provider to point at, and
     * nothing may open a window on a developer's screen.
     */
    val PROFILES: Map<String, ProfileConfig> = mapOf(
        E2E to ProfileConfig(
            description = E2E_DESCRIPTION,
            persistent = true,
        ),
        E2E_SCRAPER to ProfileConfig(
            description = E2E_SCRAPER_DESCRIPTION,
            persistent = false,
        ),
    )

    val site: FixtureSite by lazy {
        FixtureSite().also {
            it.start()
            onProcessExit { it.stop() }
        }
    }

    val daemon: UmweltDaemon by lazy {
        UmweltDaemon().also {
            it.start()
            onProcessExit { it.stop() }
        }
    }

}
