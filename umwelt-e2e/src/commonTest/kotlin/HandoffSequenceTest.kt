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

package com.xemantic.umwelt.e2e

import com.xemantic.kotlin.test.assert
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.sameAsJson
import com.xemantic.kotlin.test.should
import com.xemantic.umwelt.e2e.harness.UmweltCli
import com.xemantic.umwelt.e2e.harness.UmweltUnderTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Handing the live tab back to the user — the half of the "sign in yourself"
 * story that happens at the moment of handover. The profiles a user signs into
 * are the other half, in `ProfileSequenceTest`.
 *
 * ## Every session can be handed over — what differs is how
 *
 * `umwelt focus` used to be "raise the window, or fail", and a deployment with
 * no window on anybody's screen got a typed refusal. That was right while a
 * window was the only way a human could reach a tab, and it stopped being right
 * when the daemon started serving one at `<base>/sessions/<id>`: the watch page
 * streams the tab over CDP, which a headless browser and a browser at a remote
 * provider both do just as well as a visible one. So the command no longer
 * fails for want of a window. It reports **which of three things happened**:
 *
 * - `WINDOW` — the profile's own browser window came to the front of the
 *   screen. Only a headful local browser can do this.
 * - `WATCH_PAGE` — there was no window to raise, so the **CLI** opened the
 *   watch page in the system browser where the CLI runs.
 * - `LINK` — nothing was opened, and the URL is the handover: the agent gives
 *   it to the user, who opens it themselves.
 *
 * Every one of them carries `watchUrl`, so the old objection to a
 * `focused: false` — that it leaves an agent promising a handover the user
 * never got — no longer applies: the agent always holds something to give
 * them. A record that cannot hand over *anything* is the only failure left,
 * and there is exactly one way to get there (below).
 *
 * ## Why the CLI opens the page and the daemon never does
 *
 * `open`/`xdg-open` act on the machine they run on. The daemon's machine is the
 * user's desktop only in the default loopback deployment; on a server, or on
 * the hosted service, a daemon "opening a browser" would raise a window nobody
 * is in front of and report success — the very false promise the old refusal
 * existed to prevent, one level up. The CLI runs where the agent runs, which is
 * where the user is, so it is the CLI that opens the page, from the `--api-base`
 * it already used to reach the daemon. The daemon reports only what it did on
 * its own desktop — whether a window was raised — and never guesses its own
 * public URL, which a proxy or a port mapping would make wrong anyway.
 *
 * ## What this suite can and cannot pin
 *
 * The daemon under test is **headless**, so `WINDOW` never happens here;
 * whether a real window comes forward on a desktop stays a human check.
 * `WATCH_PAGE` cannot be asserted either — it opens a browser on whoever is
 * running the build — so **every `focus` below passes `--no-open`**, the same
 * rule that makes the daemon headless in the first place. What is left, and
 * what is pinned, is the `LINK` handover and the URL it carries.
 *
 * The one remaining failure — an API-keyed deployment, where the watch page has
 * nowhere to get the key and no window can be raised, so nothing can be handed
 * over — is not here either: the suite runs a single unkeyed daemon, and that
 * refusal is the CLI's own (it knows it is presenting a key), so it belongs in
 * `umwelt-cli`'s own tests rather than in a sequence.
 */
class HandoffSequenceTest {

    private val site = UmweltUnderTest.site.baseUrl
    private val daemonUrl = UmweltUnderTest.daemon.baseUrl
    private val cli = UmweltCli()

    // the plain HTTP client for the one thing the CLI does not do: looking at
    // the live stream of a tab. CIO is the engine both targets have.
    private val http = HttpClient(CIO) {
        // no request timeout on purpose: it would have to cover the endless
        // screencast body that [statusOf] deliberately never reads
        install(HttpTimeout) { connectTimeoutMillis = 5_000 }
    }

    @AfterTest
    fun cleanUp() {
        cli.closeEverything(daemonUrl)
        http.close()
    }

    /**
     * Just the status line. The MJPEG screencast body never ends by design, so
     * reading the body would hang the suite rather than fail it —
     * `prepareGet(…).execute { }` hands back the response as soon as its
     * headers are in. Returning from that block is not enough to let go of
     * the body, though: it only *completes* the response, and the engine keeps
     * reading the endless stream in the background, where `runTest` waits for
     * it until its timeout. So the request is cancelled outright once the
     * status is in, which closes the connection.
     *
     * The daemon sends those headers together with the first frame, so a tab
     * Chrome is not painting answers with no status line at all: a broken
     * stream shows up here as a test that times out, not as a wrong status.
     */
    private suspend fun statusOf(url: String): Int = coroutineScope {
        val status = CompletableDeferred<Int>()
        val request = launch {
            http.prepareGet(url).execute {
                status.complete(it.status.value)
                awaitCancellation()
            }
        }
        status.await().also { request.cancelAndJoin() }
    }

    @Test
    fun `should hand over the watch page of a session with no window to raise`() = runTest {

        val session = cli.umwelt("--api-base=$daemonUrl session new")
        session should { have(exitCode == 0) }
        val sid = session.sessionId

        cli.umwelt("--api-base=$daemonUrl goto -s $sid $site/article.html") should { have(exitCode == 0) }

        // headless: the tab is real, the window is not — and the handover
        // happens anyway, as the address the tab can be watched at. The URL is
        // the SPA route, not an API path: it is meant for a human with a
        // browser, and the agent's next move is to put it in a sentence.
        cli.umwelt("--api-base=$daemonUrl focus -s $sid --no-open") should {
            have(exitCode == 0)
            out sameAsJson """
                {
                  "type": "SessionFocused",
                  "sessionId": "$sid",
                  "watchUrl": "$daemonUrl/sessions/$sid",
                  "handover": "LINK"
                }
            """.trimIndent()
        }

        // and the session is untouched by the handover — still there, still on
        // the page, still drivable. `focus` acts on the desktop, never on the
        // tab: no navigation, no history entry, and every ref stays valid.
        cli.umwelt("--api-base=$daemonUrl status -s $sid") should {
            have(exitCode == 0)
            out sameAsJson """
                {
                  "type": "Navigation",
                  "navigation": {
                    "url": "$site/article.html",
                    "status": 200,
                    "title": "The Article",
                    "mimeType": "text/html",
                    "type": "DOCUMENT",
                    "canGoBack": false,
                    "canGoForward": false
                  }
                }
            """.trimIndent()
        }
    }

    @Test
    fun `should hand over the same watch page for a session on a named profile`() = runTest {

        // a profile session is the one a handover is actually for — the user
        // signs in, the agent carries on in the same tab afterwards — and it
        // reaches the human the same way a private one does
        val session = cli.umwelt("--api-base=$daemonUrl session new --profile e2e")
        session should { have(exitCode == 0) }
        val sid = session.sessionId

        cli.umwelt("--api-base=$daemonUrl goto -s $sid $site/article.html") should { have(exitCode == 0) }

        cli.umwelt("--api-base=$daemonUrl focus -s $sid --no-open") should {
            have(exitCode == 0)
            out sameAsJson """
                {
                  "type": "SessionFocused",
                  "sessionId": "$sid",
                  "watchUrl": "$daemonUrl/sessions/$sid",
                  "handover": "LINK"
                }
            """.trimIndent()
        }
    }

    @Test
    fun `should fail to focus a session that does not exist`() = runTest {

        // the failure that survives: there is no session to hand over, so
        // there is no URL to hand over either
        cli.umwelt("--api-base=$daemonUrl focus -s 3f2504e0-4f89-11d3-9a0c-0305e82c3301 --no-open") should {
            have(exitCode == 1)
            out sameAsJson """
                {
                  "type": "Error",
                  "code": 1,
                  "message": "no session with id '3f2504e0-4f89-11d3-9a0c-0305e82c3301'",
                  "error": {
                    "type": "SessionNotFound",
                    "id": "3f2504e0-4f89-11d3-9a0c-0305e82c3301",
                    "message": "no session with id '3f2504e0-4f89-11d3-9a0c-0305e82c3301'"
                  }
                }
            """.trimIndent()
        }
    }

    @Test
    fun `should stream the tab that the watch page shows`() = runTest {

        val session = cli.umwelt("--api-base=$daemonUrl session new")
        session should { have(exitCode == 0) }
        val sid = session.sessionId
        cli.umwelt("--api-base=$daemonUrl goto -s $sid $site/article.html") should { have(exitCode == 0) }

        // what makes the link worth handing over: the page behind it is this
        // stream, and it answers for a headless tab exactly as for a visible
        // one. The watch page itself is not asserted here — it is the embedded
        // web bundle, which an e2e run usually builds without
        // (`-Pumwelt.embedWeb=false`), while the stream is the daemon's own.
        assert(statusOf("$daemonUrl/api/v1/sessions/$sid/stream") == 200)
    }

    /**
     * Chrome paints only the front tab of a window, and a screencast of a tab
     * it does not paint never produces a frame. The persistent sessions of a
     * profile are tabs of one window, so only the newest could be watched: the
     * stream of every other one answered nothing, forever. Starting a stream
     * now brings its tab to the front.
     */
    @Test
    fun `should stream a session that is not the newest one of its profile`() = runTest {

        val first = cli.umwelt("--api-base=$daemonUrl session new")
        first should { have(exitCode == 0) }
        val older = first.sessionId
        cli.umwelt("--api-base=$daemonUrl goto -s $older $site/article.html") should { have(exitCode == 0) }

        // a second session in the same persistent profile, opened after it
        val second = cli.umwelt("--api-base=$daemonUrl session new")
        second should { have(exitCode == 0) }
        cli.umwelt("--api-base=$daemonUrl goto -s ${second.sessionId} $site/article.html") should { have(exitCode == 0) }

        assert(statusOf("$daemonUrl/api/v1/sessions/$older/stream") == 200)
    }

    /**
     * Chrome sends a screencast frame only when the picture changes, so on a
     * static page the only frame there is goes to whoever was watching first.
     * The stream used to hand a later viewer nothing until the next repaint —
     * and since the MJPEG response starts with its first frame, not even a
     * status line: reopening the watch page of a still page showed nothing,
     * forever. The latest frame is now replayed to each viewer that joins.
     */
    @Test
    fun `should stream the same session to one viewer after another`() = runTest {

        val session = cli.umwelt("--api-base=$daemonUrl session new")
        session should { have(exitCode == 0) }
        val sid = session.sessionId
        cli.umwelt("--api-base=$daemonUrl goto -s $sid $site/article.html") should { have(exitCode == 0) }

        assert(statusOf("$daemonUrl/api/v1/sessions/$sid/stream") == 200)
        assert(statusOf("$daemonUrl/api/v1/sessions/$sid/stream") == 200)
    }

}
