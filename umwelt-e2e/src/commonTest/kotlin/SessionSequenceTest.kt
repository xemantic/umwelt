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
import com.xemantic.kotlin.test.sameAs
import com.xemantic.kotlin.test.sameAsJson
import com.xemantic.kotlin.test.sameAsMarkdown
import com.xemantic.kotlin.test.should
import com.xemantic.umwelt.e2e.harness.FixtureSite
import com.xemantic.umwelt.e2e.harness.UmweltCli
import com.xemantic.umwelt.e2e.harness.UmweltUnderTest
import com.xemantic.umwelt.e2e.harness.deleteIfExists
import com.xemantic.umwelt.e2e.harness.linkRef
import com.xemantic.umwelt.e2e.harness.readText
import com.xemantic.umwelt.e2e.harness.tempPath
import kotlinx.coroutines.test.runTest
import org.intellij.lang.annotations.Language
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * The perceive -> act -> re-perceive loop, driven end to end against a real
 * browser: every ref these sequences act on was read out of a real dump of a
 * real page, the way an agent has to read it.
 *
 * Each test reads as the transcript it is — one `umwelt` command per line, in
 * the order an agent would type them — and every invocation is asserted whole:
 * the exit code, then the entirety of what it wrote. A command's output shape
 * follows from what it produces and from nothing the caller passes: a document
 * command prints its document, and every other command prints one `CliResponse`
 * object naming itself in `type`. Navigations are pinned as their whole record
 * and dumps as their full Markdown, so a renamed field or a changed rendering
 * is a failing test rather than something the suite quietly stops covering.
 *
 * A failure is a response too — `{"type": "Error", …}` on stdout, with the
 * daemon's own typed error nested under `error` — which is why these sequences
 * pin failures as whole objects rather than searching prose for a substring.
 * `ResponseShapeTest` states that contract on its own.
 */
class SessionSequenceTest {

    private val site = UmweltUnderTest.site.baseUrl
    private val daemonUrl = UmweltUnderTest.daemon.baseUrl
    private val cli = UmweltCli()

    @AfterTest
    fun cleanUp() {
        cli.closeEverything(daemonUrl)
    }

    /**
     * The search page exactly as an agent perceives it, in the state its
     * controls are in *now*: the text typed into the query field and the
     * option chosen (the first one until the agent picks another, since that
     * is what the form would submit). Asserted before and after each act, which
     * is what makes "re-perceive between mutations" visible: each dump shows
     * the act that preceded it, while the refs stay the ones just read.
     *
     * The `<option>`s are part of that perception, not noise to trim: SKILL.md
     * tells an agent that `select` matches the visible label first, so the
     * labels have to be in the dump it read them from. A closed `<select>`
     * keeps its popup out of Chrome's layout tree, so every option arrives
     * annotated `display: none` — a capture artifact markanywhere exempts, and
     * honouring it here would leave the agent guessing a label it was never
     * shown.
     */
    @Language("markdown")
    @Suppress("HtmlUnknownAttribute")
    private fun searchPage(
        query: String? = null,
        documents: Boolean = false,
    ) = """
        ---
        lang: en
        title: Search
        status: 200
        ---
        
        # Search
        
        <form action="/results" method="get">
        <input id="q" type="text" name="q"${query?.let { " value=\"$it\"" } ?: ""} aria-label="query" ref="1">
        <select id="kind" name="kind" aria-label="kind" ref="2">
        <option value="all"${if (documents) "" else " selected=\"\""} ref="3">
        
        Everything
        
        </option>
        <option value="docs"${if (documents) " selected=\"\"" else ""} ref="4">
        
        Documents
        
        </option>
        </select>
        <input id="go" type="submit" value="Search" ref="5">
        </form>
        
    """.trimIndent()

    @Test
    fun `should drive a search form from end to end`() = runTest {

        // the id on stdout is the whole result: it is the only record of the
        // session there is, and every command below names it with -s
        val session = cli.umwelt("--api-base=$daemonUrl session new") should {
            have(exitCode == 0)
            out sameAsJson """
                {
                  "type": "SessionOpened",
                  "sessionId": "$sessionId",
                  "profile": "e2e",
                  "ephemeral": false
                }
            """.trimIndent()
        }
        val sid = session.sessionId

        cli.umwelt("--api-base=$daemonUrl goto -s $sid $site/search.html") should {
            have(exitCode == 0)
            out sameAsJson """
                {
                  "type": "Navigation",
                  "navigation": {
                    "url": "$site/search.html",
                    "status": 200,
                    "title": "Search",
                    "mimeType": "text/html",
                    "type": "DOCUMENT",
                    "canGoBack": false,
                    "canGoForward": false
                  }
                }
            """.trimIndent()
        }

        cli.umwelt("--api-base=$daemonUrl dump -s $sid") should {
            have(exitCode == 0)
            out sameAs searchPage()
        }

        // an act that produces nothing to report still reports itself: the
        // response names what was done, so no invocation prints an empty stdout
        cli.umwelt("""--api-base=$daemonUrl type -s $sid 1 "umwelt"""") should { // ref 1 = input
            have(exitCode == 0)
            out sameAsJson """
                {
                  "type": "Typed",
                  "ref": "1"
                }
            """.trimIndent()
        }

        // re-perceive before every act: the dump shows what was typed, and the
        // refs stay the ones just read
        cli.umwelt("--api-base=$daemonUrl dump -s $sid") should {
            have(exitCode == 0)
            out sameAs searchPage(query = "umwelt")
        }

        cli.umwelt("""--api-base=$daemonUrl select -s $sid 2 "Documents"""") should { // ref 2 = select
            have(exitCode == 0)
            out sameAsJson """
                {
                  "type": "Selected",
                  "ref": "2"
                }
            """.trimIndent()
        }

        cli.umwelt("--api-base=$daemonUrl dump -s $sid") should {
            have(exitCode == 0)
            out sameAs searchPage(query = "umwelt", documents = true)
        }

        cli.umwelt("--api-base=$daemonUrl click -s $sid 5") should { // ref 5 = submit
            have(exitCode == 0)
            out sameAsJson """
                {
                  "type": "Navigation",
                  "navigation": {
                    "url": "$site/results?q=umwelt&kind=docs",
                    "status": 200,
                    "title": "Results",
                    "mimeType": "text/html",
                    "type": "DOCUMENT",
                    "canGoBack": true,
                    "canGoForward": false
                  }
                }
            """.trimIndent()
        }

        cli.umwelt("--api-base=$daemonUrl dump -s $sid") should {
            have(exitCode == 0)
            @Suppress("MarkdownUnresolvedFileReference")
            out sameAsMarkdown """
                ---
                lang: en
                title: Results
                status: 200
                ---
                
                # Results
                
                query was umwelt
                
                kind was docs
                
                - [The Article](ref:1:/article.html)
                
                """.trimIndent()
        }

        cli.umwelt("--api-base=$daemonUrl session close $sid") should {
            have(exitCode == 0)
            out sameAsJson """
                {
                  "type": "SessionsClosed",
                  "closed": [
                    {
                      "id": "$sid",
                      "profile": "e2e",
                      "ephemeral": false
                    }
                  ]
                }
            """.trimIndent()
        }
    }

    @Test
    fun `should follow a link and step back and forward through history`() = runTest {

        val sid = (cli.umwelt("--api-base=$daemonUrl session new") should { have(exitCode == 0) }).sessionId

        cli.umwelt("--api-base=$daemonUrl goto -s $sid $site/") should {
            have(exitCode == 0)
            out sameAsJson """
                {
                  "type": "Navigation",
                  "navigation": {
                    "url": "$site/",
                    "status": 200,
                    "title": "Fixture index",
                    "mimeType": "text/html",
                    "type": "DOCUMENT",
                    "canGoBack": false,
                    "canGoForward": false
                  }
                }
            """.trimIndent()
        }

        cli.umwelt("--api-base=$daemonUrl dump -s $sid") should {
            have(exitCode == 0)
            @Suppress("MarkdownUnresolvedFileReference")
            out sameAsMarkdown """
                ---
                lang: en
                title: Fixture index
                status: 200
                ---
                
                # Fixture index
                
                - [Search the fixtures](ref:1:/search.html)
                - [The Article](ref:2:/article.html)
                - [Scripted page](ref:3:/scripted.html)
                
            """.trimIndent()
        }

        // click the ref, never the href it happens to show
        cli.umwelt("--api-base=$daemonUrl click -s $sid 2") should {
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
                    "canGoBack": true,
                    "canGoForward": false
                  }
                }
            """.trimIndent()
        }

        cli.umwelt("--api-base=$daemonUrl back -s $sid") should {
            have(exitCode == 0)
            out sameAsJson """
                {
                  "type": "Navigation",
                  "navigation": {
                    "url": "$site/",
                    "status": 200,
                    "title": "Fixture index",
                    "mimeType": "text/html",
                    "type": "DOCUMENT",
                    "canGoBack": false,
                    "canGoForward": true
                  }
                }
            """.trimIndent()
        }

        cli.umwelt("--api-base=$daemonUrl forward -s $sid") should {
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
                    "canGoBack": true,
                    "canGoForward": false
                  }
                }
            """.trimIndent()
        }
    }

    @Test
    fun `should refuse to go back from the first history entry`() = runTest {

        val sid = (cli.umwelt("--api-base=$daemonUrl session new") should { have(exitCode == 0) }).sessionId
        cli.umwelt("--api-base=$daemonUrl goto -s $sid $site/article.html") should { have(exitCode == 0) }

        // a boundary fails loudly rather than silently doing nothing, and the
        // daemon's own `CannotGoBack` reaches the caller intact
        cli.umwelt("--api-base=$daemonUrl back -s $sid") should {
            have(exitCode == 1)
            out sameAsJson """
                {
                  "type": "Error",
                  "code": 1,
                  "message": "already at the first history entry; cannot go back",
                  "error": {
                    "type": "CannotGoBack",
                    "message": "already at the first history entry; cannot go back"
                  }
                }
            """.trimIndent()
        }
    }

    @Test
    fun `should report a 404 as a page that loaded`() = runTest {

        val sid = (cli.umwelt("--api-base=$daemonUrl session new") should { have(exitCode == 0) }).sessionId

        // exit 0, because a 404 page is a real, dumpable page — the distinction
        // an agent must not collapse into "could not be reached"
        cli.umwelt("--api-base=$daemonUrl goto -s $sid $site/no-such-page") should {
            have(exitCode == 0)
            out sameAsJson """
                {
                  "type": "Navigation",
                  "navigation": {
                    "url": "$site/no-such-page",
                    "status": 404,
                    "title": "Not found",
                    "mimeType": "text/html",
                    "type": "DOCUMENT",
                    "canGoBack": false,
                    "canGoForward": false
                  }
                }
            """.trimIndent()
        }

        cli.umwelt("--api-base=$daemonUrl dump -s $sid") should {
            have(exitCode == 0)
            out sameAsMarkdown """
                ---
                lang: en
                title: Not found
                status: 404
                ---
                
                # Not found
                
                No page lives here.
                
            """.trimIndent()
        }
    }

    /**
     * The other side of the line the 404 sequence draws: a page that never
     * *finishes* loading still loaded. Its document arrived at once, and only
     * a subresource hangs, so `document.readyState` never reaches `complete`
     * and the settle wait gives up on it.
     *
     * That giving up used to escape as an untyped failure *after* the
     * navigation had happened: the caller got exit `1` and `answered 502 Bad
     * Gateway`, the session recorded no page (so the next `status` said it had
     * never navigated), and an agent would retry — a click that had already
     * gone through, which is what made it dangerous. It surfaced on a Wayback
     * Machine capture, whose toolbar assets can hang the same way.
     *
     * Slower than the other sequences by the settle cap (15 s), since waiting
     * the cap out is the behaviour under test.
     */
    @Test
    fun `should settle on a page that never finishes loading`() = runTest {

        val sid = (cli.umwelt("--api-base=$daemonUrl session new") should { have(exitCode == 0) }).sessionId

        cli.umwelt("--api-base=$daemonUrl goto -s $sid $site/stalled.html") should {
            have(exitCode == 0)
            out sameAsJson """
                {
                  "type": "Navigation",
                  "navigation": {
                    "url": "$site/stalled.html",
                    "status": 200,
                    "title": "Stalled",
                    "mimeType": "text/html",
                    "type": "DOCUMENT",
                    "canGoBack": false,
                    "canGoForward": false
                  }
                }
            """.trimIndent()
        }

        // the session recorded the page: this is what answered NoCurrentPage
        cli.umwelt("--api-base=$daemonUrl dump -s $sid") should {
            have(exitCode == 0)
            @Suppress("WrsUnresolvedAnchorReference", "MarkdownUnresolvedFileReference")
            out sameAsMarkdown """
                ---
                lang: en
                title: Stalled
                status: 200
                ---
                
                # Stalled
                
                The text is here; one image never arrives.
                
                ![never arrives](/stalled.png)
                
            """.trimIndent()
        }
    }

    @Test
    fun `should fail a navigation that loads no document at all`() = runTest {

        val sid = (cli.umwelt("--api-base=$daemonUrl session new") should { have(exitCode == 0) }).sessionId

        // nothing is listening on this port; unlike a 404 there is no page
        // behind it, so it is a real failure — and the browser's own net::
        // reason survives as a field rather than as prose to grep.
        //
        // Not the discard port (9): Chrome refuses that one from its own
        // blocked-port list with `net::ERR_UNSAFE_PORT`, never reaching the
        // network at all, so it would test the blocklist rather than a refused
        // connection. Pinning the whole record is what surfaced that; the
        // substring check this replaced saw only `net::` and was happy.
        cli.umwelt("--api-base=$daemonUrl goto -s $sid http://127.0.0.1:47811/") should {
            have(exitCode == 1)
            out sameAsJson """
                {
                  "type": "Error",
                  "code": 1,
                  "message": "could not load 'http://127.0.0.1:47811/': net::ERR_CONNECTION_REFUSED",
                  "error": {
                    "type": "NavigationFailed",
                    "url": "http://127.0.0.1:47811/",
                    "reason": "net::ERR_CONNECTION_REFUSED",
                    "message": "could not load 'http://127.0.0.1:47811/': net::ERR_CONNECTION_REFUSED"
                  }
                }
            """.trimIndent()
        }
    }

    @Test
    fun `should refuse a ref that was never read from a dump`() = runTest {

        val sid = (cli.umwelt("--api-base=$daemonUrl session new") should { have(exitCode == 0) }).sessionId
        cli.umwelt("--api-base=$daemonUrl goto -s $sid $site/search.html") should { have(exitCode == 0) }

        // no dump has happened, so no ref exists yet — the number an agent
        // might guess from a previous page is simply not registered
        cli.umwelt("""--api-base=$daemonUrl type -s $sid 1 "umwelt"""") should {
            have(exitCode == 4)
            out sameAsJson """
                {
                  "type": "Error",
                  "code": 4,
                  "message": "no actionable element with ref '1' on the current page",
                  "error": {
                    "type": "ReferenceNotFound",
                    "ref": "1",
                    "message": "no actionable element with ref '1' on the current page"
                  }
                }
            """.trimIndent()
        }
    }

    @Test
    fun `should refuse a ref that went stale on the previous navigation`() = runTest {

        val sid = (cli.umwelt("--api-base=$daemonUrl session new") should { have(exitCode == 0) }).sessionId
        cli.umwelt("--api-base=$daemonUrl goto -s $sid $site/") should { have(exitCode == 0) }

        val stale = cli.umwelt("--api-base=$daemonUrl dump -s $sid")
        stale should { have(exitCode == 0) }

        cli.umwelt("--api-base=$daemonUrl goto -s $sid $site/article.html") should { have(exitCode == 0) }

        // the single most load-bearing rule the skill teaches, and the one the
        // ref registry alone cannot enforce: nothing clears it on a navigation
        // and the backendNodeId it holds outlives the document, so the ref still
        // resolves and the failure used to surface deeper as an untyped CDP error
        // — `502 Bad Gateway`, exit 1, "something broke" where the answer is
        // "re-dump". The daemon compares the document (its CDP loaderId) before
        // it touches the ref, so the common *stale after navigation* path answers
        // exactly what the never-registered one above does.
        val ref = stale.out.linkRef("The Article")
        cli.umwelt("--api-base=$daemonUrl click -s $sid $ref") should {
            have(exitCode == 4)
            out sameAsJson """
                {
                  "type": "Error",
                  "code": 4,
                  "message": "no actionable element with ref '$ref' on the current page",
                  "error": {
                    "type": "ReferenceNotFound",
                    "ref": "$ref",
                    "message": "no actionable element with ref '$ref' on the current page"
                  }
                }
            """.trimIndent()
        }
    }

    @Test
    fun `should refuse to dump a session that has not navigated yet`() = runTest {

        val sid = (cli.umwelt("--api-base=$daemonUrl session new") should { have(exitCode == 0) }).sessionId

        val noCurrentPage = """
            {
              "type": "Error",
              "code": 1,
              "message": "the session has not navigated to any page yet",
              "error": {
                "type": "NoCurrentPage",
                "message": "the session has not navigated to any page yet"
              }
            }
        """.trimIndent()

        // SKILL.md names `status`, `reload` and `dump` as the three that fail
        // here with "the session has not navigated to any page yet" — `dump`
        // included, so an agent that forgot to `goto` is told it has no page
        // rather than that the page is blank.
        cli.umwelt("--api-base=$daemonUrl dump -s $sid") should {
            have(exitCode == 1)
            out sameAsJson noCurrentPage
        }

        // the same session, through the command that already gets it right
        cli.umwelt("--api-base=$daemonUrl status -s $sid") should {
            have(exitCode == 1)
            out sameAsJson noCurrentPage
        }
    }

    @Test
    fun `should download a linked file through the tab without moving it`() = runTest {

        val target = tempPath("umwelt-download", ".csv")

        val sid = (cli.umwelt("--api-base=$daemonUrl session new") should { have(exitCode == 0) }).sessionId
        cli.umwelt("--api-base=$daemonUrl goto -s $sid $site/article.html") should { have(exitCode == 0) }

        val article = cli.umwelt("--api-base=$daemonUrl dump -s $sid")
        article should {
            have(exitCode == 0)
            @Suppress("MarkdownUnresolvedFileReference")
            out sameAsMarkdown """
                ---
                lang: en
                title: The Article
                status: 200
                ---
                
                # The Article
                
                Umwelt is the world as an organism perceives it.
                
                [the report as data](ref:1:/report.csv)
                
                [download the table](ref:2:/attachment.csv)
                
            """.trimIndent()
        }

        val ref = "1"

        // no -o: the resource itself is the output, the way `dump` prints the
        // page. A CSV is text, so it lands in the caller's context as the text
        // it is — verbatim, with no record wrapped around it and nothing else
        // on the stream beside it
        cli.umwelt("--api-base=$daemonUrl download -s $sid $ref") should {
            have(exitCode == 0)
            have(out == FixtureSite.REPORT_CSV)
        }

        // `-` is that default spelled out, and answers identically
        cli.umwelt("--api-base=$daemonUrl download -s $sid $ref -o -") should {
            have(exitCode == 0)
            have(out == FixtureSite.REPORT_CSV)
        }

        // -o <file> diverts the bytes to disk, and the whole transfer takes
        // their place on stdout: what came back, and where it went
        cli.umwelt("""--api-base=$daemonUrl download -s $sid $ref -o "$target"""") should {
            have(exitCode == 0)
            out sameAsJson """
                {
                  "type": "FileDownloaded",
                  "url": "$site/report.csv",
                  "status": 200,
                  "contentType": "text/csv; charset=UTF-8",
                  "file": "$target",
                  "bytes": ${FixtureSite.REPORT_CSV.length}
                }
            """.trimIndent()
        }

        // the origin's bytes, fetched with the tab's own cookies and TLS state
        assert(target.readText() == FixtureSite.REPORT_CSV)

        // and the tab never left the article
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

        target.deleteIfExists()
    }

    @Test
    fun `should download a page as the source the origin sent`() = runTest {

        val sid = (cli.umwelt("--api-base=$daemonUrl session new") should { have(exitCode == 0) }).sessionId
        cli.umwelt("--api-base=$daemonUrl goto -s $sid $site/article.html") should { have(exitCode == 0) }

        // a download is a fetch, not a navigation: an HTML page comes back as
        // the origin served it - what `curl` would see, with the session's
        // browser state - and no script on it has run. The fixture's sentence
        // exists only in the live DOM, so its absence here is the proof; the
        // page as the browser built it is what `dump` and `events` are for
        cli.umwelt("--api-base=$daemonUrl download -s $sid --url /scripted.html") should {
            have(exitCode == 0)
            out sameAs FixtureSite.SCRIPTED_HTML
            have(bytes.isEmpty())
        }
    }

    @Test
    fun `should refuse to click a link that starts a download`() = runTest {

        val sid = (cli.umwelt("--api-base=$daemonUrl session new") should { have(exitCode == 0) }).sessionId
        cli.umwelt("--api-base=$daemonUrl goto -s $sid $site/article.html") should { have(exitCode == 0) }

        val article = cli.umwelt("--api-base=$daemonUrl dump -s $sid")
        article should { have(exitCode == 0) }

        // refused by name, rather than silently writing to the user's disk —
        // and the name arrives as `fileName`, not buried in a sentence
        cli.umwelt("--api-base=$daemonUrl click -s $sid ${article.out.linkRef("download the table")}") should {
            have(exitCode == 1)
            out sameAsJson """
                {
                  "type": "Error",
                  "code": 1,
                  "message": "'$site/attachment.csv' is a download ('table.csv'), not a page; retrieve it with a download request instead",
                  "error": {
                    "type": "DownloadStarted",
                    "url": "$site/attachment.csv",
                    "fileName": "table.csv",
                    "message": "'$site/attachment.csv' is a download ('table.csv'), not a page; retrieve it with a download request instead"
                  }
                }
            """.trimIndent()
        }
    }

    /**
     * Two sessions open at once, driven in turn — the shape a second agent (or
     * a second tab of the same one) produces. Opening the second cannot disturb
     * the first, because there is no shared slot for it to take: every command
     * carries the id of the session it means.
     */
    @Test
    fun `should keep two sessions apart`() = runTest {

        val first = cli.umwelt("--api-base=$daemonUrl session new")
        first should { have(exitCode == 0) }

        cli.umwelt("--api-base=$daemonUrl goto -s ${first.sessionId} $site/article.html") should {
            have(exitCode == 0)
        }

        val second = cli.umwelt("--api-base=$daemonUrl session new")
        second should {
            have(exitCode == 0)
            have(sessionId != first.sessionId)
        }

        cli.umwelt("--api-base=$daemonUrl goto -s ${second.sessionId} $site/search.html") should {
            have(exitCode == 0)
        }

        // each tab is still where its own sequence left it
        cli.umwelt("--api-base=$daemonUrl status -s ${first.sessionId}") should {
            have(exitCode == 0)
            have("$site/article.html" in out)
        }

        cli.umwelt("--api-base=$daemonUrl status -s ${second.sessionId}") should {
            have(exitCode == 0)
            have("$site/search.html" in out)
        }
    }

    @Test
    fun `should close every session on --all`() = runTest {

        val first = cli.umwelt("--api-base=$daemonUrl session new")
        first should { have(exitCode == 0) }
        val second = cli.umwelt("--api-base=$daemonUrl session new")
        second should { have(exitCode == 0) }

        cli.umwelt("--api-base=$daemonUrl session list") should {
            have(exitCode == 0)
            out sameAsJson """
                {
                  "type": "SessionList",
                  "sessions": [
                    {
                      "id": "${first.sessionId}",
                      "profile": "e2e",
                      "ephemeral": false
                    },
                    {
                      "id": "${second.sessionId}",
                      "profile": "e2e",
                      "ephemeral": false
                    }
                  ]
                }
            """.trimIndent()
        }

        // `closed` rather than `sessions`: the tabs are gone, which is the
        // whole information this command adds
        cli.umwelt("--api-base=$daemonUrl session close --all") should {
            have(exitCode == 0)
            out sameAsJson """
                {
                  "type": "SessionsClosed",
                  "closed": [
                    {
                      "id": "${first.sessionId}",
                      "profile": "e2e",
                      "ephemeral": false
                    },
                    {
                      "id": "${second.sessionId}",
                      "profile": "e2e",
                      "ephemeral": false
                    }
                  ]
                }
            """.trimIndent()
        }

        // an empty list is still a record, not an empty stdout
        cli.umwelt("--api-base=$daemonUrl session list") should {
            have(exitCode == 0)
            out sameAsJson """
                {
                  "type": "SessionList",
                  "sessions": []
                }
            """.trimIndent()
        }
    }

}
