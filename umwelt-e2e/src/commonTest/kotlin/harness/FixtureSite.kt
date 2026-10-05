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

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.CIO
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.intellij.lang.annotations.Language

/**
 * The web this suite drives — a handful of pages it owns, served from an
 * ephemeral port on loopback.
 *
 * Fixtures rather than public URLs, because an e2e suite has to answer
 * "did umwelt do the right thing", not "is validator.w3.org still up". They
 * also express cases the public web cannot be relied on for: a 404 with real
 * content on it, a `Content-Disposition: attachment` link, a page whose text
 * exists only after JavaScript ran.
 *
 * Every page is deliberately small, so a `dump` in a failing assertion is
 * readable in the test report.
 *
 * CIO rather than Netty because this source set compiles to native as well,
 * and CIO is the one server engine both targets have. Nothing here needs an
 * engine's characteristics — it serves a handful of static strings on
 * loopback.
 */
class FixtureSite {

    /** `http://127.0.0.1:<port>`, known once [start] has returned. */
    lateinit var baseUrl: String
        private set

    private val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {

        routing {

            // a 404 that is still a real, readable page — the distinction
            // `umwelt status` reports as "loaded, with status 404", and the one
            // an agent must not collapse into "could not be reached"
            get("/no-such-page") {
                call.respondText(
                    page(
                        title = "Not found",
                        body = "<h1>Not found</h1><p>No page lives here.</p>"
                    ),
                    ContentType.Text.Html,
                    HttpStatusCode.NotFound
                )
            }

            get("/") {
                @Suppress("HtmlUnknownTarget")
                call.respondText(
                    page(
                        title = "Fixture index",
                        body = """
                            <h1>Fixture index</h1>
                            <ul>
                              <li><a href="/search.html">Search the fixtures</a></li>
                              <li><a href="/article.html">The Article</a></li>
                              <li><a href="/scripted.html">Scripted page</a></li>
                            </ul>
                        """.trimIndent()
                    ),
                    ContentType.Text.Html
                )
            }

            get("/search.html") {
                call.respondText(
                    page(
                        title = "Search",
                        body = """
                            <h1>Search</h1>
                            <form action="/results" method="get">
                              <input id="q" name="q" type="text" aria-label="query">
                              <select id="kind" name="kind" aria-label="kind">
                                <option value="all">Everything</option>
                                <option value="docs">Documents</option>
                              </select>
                              <input id="go" type="submit" value="Search">
                            </form>
                        """.trimIndent()
                    ),
                    ContentType.Text.Html
                )
            }

            get("/results") {
                val query = call.request.queryParameters["q"] ?: ""
                val kind = call.request.queryParameters["kind"] ?: ""
                call.respondText(
                    page(
                        title = "Results",
                        body = """
                            <h1>Results</h1>
                            <p>query was $query</p>
                            <p>kind was $kind</p>
                            <ul><li><a href="/article.html">The Article</a></li></ul>
                        """.trimIndent()
                    ),
                    ContentType.Text.Html
                )
            }

            get("/article.html") {
                @Suppress("HtmlUnknownTarget")
                call.respondText(
                    page(
                        title = "The Article",
                        body = """
                            <h1>The Article</h1>
                            <p>Umwelt is the world as an organism perceives it.</p>
                            <p><a href="/report.csv">the report as data</a></p>
                            <p><a href="/attachment.csv" download>download the table</a></p>
                        """.trimIndent()
                    ),
                    ContentType.Text.Html
                )
            }

            // the form controls an agent fills in, other than the plain input of
            // `/search.html`: a <textarea> (the shape every real search box and
            // chat input now has, and the one a value setter for <input> alone
            // breaks on), and a <select> whose labels differ from its values, so
            // which of the two `select` matched is visible in the result
            get("/compose.html") {
                call.respondText(
                    page(
                        title = "Compose",
                        body = """
                            <h1>Compose</h1>
                            <form action="/results" method="get">
                              <textarea id="body" name="q" aria-label="message"></textarea>
                              <select id="tone" name="kind" aria-label="tone">
                                <option value="plain">Plain</option>
                                <option value="warm">Friendly</option>
                              </select>
                              <button id="send" type="submit">Send</button>
                            </form>
                        """.trimIndent()
                    ),
                    ContentType.Text.Html
                )
            }

            // a page that changes without loading another document: a button
            // that rewrites a paragraph in place, and a link to a fragment of
            // the same page. Neither replaces the document, so neither may
            // invalidate a ref read before it
            get("/counter.html") {
                call.respondText(
                    page(
                        title = "Counter",
                        body = """
                            <h1>Counter</h1>
                            <p id="count">clicked 0 times</p>
                            <button id="more" type="button" onclick="document.getElementById('count').textContent = 'clicked ' + (++window.clicks) + ' times'">More</button>
                            <p><a href="#end">to the end</a></p>
                            <p id="end">The end.</p>
                            <script>window.clicks = 0;</script>
                        """.trimIndent()
                    ),
                    ContentType.Text.Html
                )
            }

            // the text below exists only in the live DOM: a plain HTTP fetch of
            // this page sees an empty div, so an assertion on it proves a real
            // browser rendered the page
            get("/scripted.html") {
                call.respondText(SCRIPTED_HTML, ContentType.Text.Html)
            }

            // counts visits in a cookie, so what a session sees here is a
            // function of whose cookie jar it shares: the observable meaning of
            // a persistent profile, and of an ephemeral one
            get("/visits.html") {
                val visits = (call.request.cookies["visits"]?.toIntOrNull() ?: 0) + 1
                call.response.cookies.append("visits", visits.toString())
                call.respondText(
                    page(
                        title = "Visits",
                        body = "<h1>Visits</h1><p>visit number $visits</p>"
                    ),
                    ContentType.Text.Html
                )
            }

            // a non-page Chrome renders inline, so navigating to it settles
            get("/report.txt") {
                call.respondText(REPORT_CSV, ContentType.Text.Plain)
            }

            // the same kind of non-page, with CRLF line endings and no final
            // newline: what a line-by-line copy would rewrite on its way out
            get("/report-crlf.txt") {
                call.respondText(REPORT_CRLF, ContentType.Text.Plain)
            }

            // the same bytes as a type Chrome would rather download than show:
            // navigating here aborts, which is why `download` exists
            get("/report.csv") {
                call.respondText(REPORT_CSV, ContentType.Text.CSV)
            }

            // the same bytes, but announced as an attachment — clicking a link
            // to this must be refused rather than navigated to
            get("/attachment.csv") {
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment
                        .withParameter(ContentDisposition.Parameters.FileName, "table.csv")
                        .toString()
                )
                call.respondText(REPORT_CSV, ContentType.Text.CSV)
            }

            // a page whose readyState never reaches `complete`: the image it
            // embeds is never answered, as a Wayback Machine capture's toolbar
            // assets can hang. The document itself arrives at once, so this is
            // a page that loaded and must be reported as one, after the settle
            // wait gives up on it
            get("/stalled.html") {
                @Suppress("HtmlUnknownTarget")
                call.respondText(
                    page(
                        title = "Stalled",
                        body = """
                            <h1>Stalled</h1>
                            <p>The text is here; one image never arrives.</p>
                            <img src="/stalled.png" alt="never arrives">
                        """.trimIndent()
                    ),
                    ContentType.Text.Html
                )
            }

            // never answered: held open until the fixture site stops
            get("/stalled.png") {
                awaitCancellation()
            }

            // a payload that is not text at all, and not valid UTF-8 either:
            // the only honest test of the byte path is bytes a text decoder
            // would quietly replace
            get("/logo.png") {
                call.respondBytes(LOGO_PNG, ContentType.Image.PNG)
            }

        }
    }

    fun start() {
        server.start(wait = false)
        val port = runBlocking { server.engine.resolvedConnectors().first().port }
        baseUrl = "http://127.0.0.1:$port"
    }

    fun stop() {
        server.stop(gracePeriodMillis = 100, timeoutMillis = 1000)
    }

    companion object {

        /**
         * A local address nothing listens on: a connection to it is refused at
         * once. Deliberately not the discard port (9), which Chrome refuses from
         * its own blocked-port list as `net::ERR_UNSAFE_PORT` without ever
         * reaching the network.
         */
        const val REFUSED_URL: String = "http://127.0.0.1:47811/"

        /**
         * The source of `/scripted.html`, asserted on byte-for-byte where what
         * the origin sent is the point, as opposed to what the browser built.
         */
        val SCRIPTED_HTML: String = page(
            title = "Scripted",
            body = """
                <h1>Scripted</h1>
                <div id="late"></div>
                <script>
                  document.getElementById('late').textContent =
                    'this sentence was written by javascript';
                </script>
            """.trimIndent()
        )

        /** The body of both CSV fixtures, asserted on byte-for-byte. */
        const val REPORT_CSV: String = "quarter,revenue\nQ3,42\nQ4,43\n"

        /** The body of `/report-crlf.txt`: CRLF endings, no final newline. */
        const val REPORT_CRLF: String = "quarter,revenue\r\nQ3,42\r\nQ4,43"

        /**
         * The body of the binary fixture: a PNG signature followed by bytes
         * that are not a valid UTF-8 sequence, so a payload routed through the
         * text sink by mistake would come back changed rather than merely
         * unreadable.
         */
        val LOGO_PNG: ByteArray = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0xFF.toByte(), 0xFE.toByte(), 0x00, 0x01, 0x80.toByte()
        )

    }

}

private fun page(
    title: String,
    @Language("html") body: String
): String =
    """
    <!doctype html>
    <html lang="en">
    <head><meta charset="utf-8"><title>$title</title></head>
    <body>
    $body
    </body>
    </html>
    """.trimIndent()

/**
 * The whole dump of `/visits.html` on its [n]-th visit in one cookie jar —
 * shared because the count is what several sequences read to tell whose jar a
 * session is in.
 */
fun visitsPage(n: Int): String = /* language=markdown */ """
    ---
    lang: en
    title: Visits
    status: 200
    ---
    
    # Visits
    
    visit number $n
    
""".trimIndent()
