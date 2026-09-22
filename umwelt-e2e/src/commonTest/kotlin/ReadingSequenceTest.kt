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

import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.sameAs
import com.xemantic.kotlin.test.sameAsJson
import com.xemantic.kotlin.test.sameAsMarkdown
import com.xemantic.kotlin.test.should
import com.xemantic.umwelt.e2e.harness.FixtureSite
import com.xemantic.umwelt.e2e.harness.UmweltCli
import com.xemantic.umwelt.e2e.harness.UmweltUnderTest
import com.xemantic.umwelt.e2e.harness.deleteIfExists
import com.xemantic.umwelt.e2e.harness.readText
import com.xemantic.umwelt.e2e.harness.tempPath
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Sequences with no session to manage — `umwelt read`, the one-shot the skill
 * calls the right default for pure reading.
 *
 * What these pin is that the cheap path really is the cheap path: one command,
 * a page in your hands, and nothing left running behind you. The Markdown is
 * asserted **whole**, because the transduction is the product here — a change
 * in the frontmatter, the heading level or the link shape is a change to what
 * every agent reads.
 */
class ReadingSequenceTest {

    private val site = UmweltUnderTest.site.baseUrl
    private val daemonUrl = UmweltUnderTest.daemon.baseUrl
    private val cli = UmweltCli()

    @AfterTest
    fun cleanUp() {
        cli.closeEverything(daemonUrl)
    }

    @Test
    fun `should read a page as markdown with plain links and no ref handles`() = runTest {

        // links keep their plain destination: there is no live session left to
        // act on, so a ref: handle would be a lie
        cli.umwelt("--api-base=$daemonUrl read $site/article.html") should {
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
                
                [the report as data](/report.csv)
                
                [download the table](/attachment.csv)
                
            """.trimIndent()
        }

        // and the ephemeral tab the daemon opened for it is gone again
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

    @Test
    fun `should read what only a real browser can see`() = runTest {

        // the sentence below exists only in the rendered DOM — this is the whole
        // reason for driving a browser rather than curl
        cli.umwelt("--api-base=$daemonUrl read $site/scripted.html") should {
            have(exitCode == 0)
            out sameAsMarkdown """
                ---
                lang: en
                title: Scripted
                status: 200
                ---
                
                # Scripted
                
                this sentence was written by javascript
                
            """.trimIndent()
        }
    }

    @Test
    fun `should supply the scheme a target left out`() = runTest {

        // the daemon cannot do this - the scheme-less branch of its tailcard
        // route is the SPA namespace - so the CLI is where a bare host becomes
        // a URL. Loopback goes to http rather than https, the way Chrome's own
        // HTTPS-First exempts it: a dev server is plaintext. This is the only
        // half of that rule a fixture on loopback can show; the https half
        // needs a real host with a real certificate, which is why it is pinned
        // as a string rule in `TargetUrlTest` and, as a run that reaches a
        // browser, in `DeploymentSequenceTest` against example.com
        val schemeless = site.removePrefix("http://")

        cli.umwelt("--api-base=$daemonUrl read $schemeless/article.html") should {
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
                
                [the report as data](/report.csv)
                
                [download the table](/attachment.csv)
                
            """.trimIndent()
        }
    }

    @Test
    fun `should refuse a path-shaped target before opening a connection`() = runTest {

        // a relative path is only meaningful against a page, and the CLI never
        // sees which page a session is on - so this is the CLI's own refusal,
        // not the daemon's: no request is made, so there is no typed `error` to
        // nest and the record carries `null` there
        cli.umwelt("--api-base=$daemonUrl read /report.csv") should {
            have(exitCode == 1)
            out sameAsJson """
                {
                  "type": "Error",
                  "code": 1,
                  "message": "the target must be an http(s) URL or a host name: \"/report.csv\" is a path",
                  "error": null
                }
            """.trimIndent()
        }
    }

    @Test
    fun `should hand back a non page as its own bytes`() = runTest {

        val target = tempPath("umwelt-report", ".txt")

        // not a page; transducing it to Markdown would be a lie, so what lands
        // on disk is the origin's bytes rather than Chrome's rendering of them.
        // The document went to a file, so stdout carries the record of it —
        // and `contentType` is what says "not a page", rather than a sentence
        // on a second stream saying the same thing in prose. `status` is the
        // origin's, the same field `FileDownloaded` carries for a file fetched
        // through a session: the daemon already relays it in a header on this
        // branch, and a record is the one place a `-o` caller can read it.
        //
        // FAILS TODAY: `FileRetrieved` has no `status` field.
        cli.umwelt("""--api-base=$daemonUrl read $site/report.txt -o "$target"""") should {
            have(exitCode == 0)
            out sameAsJson """
                {
                  "type": "FileRetrieved",
                  "url": "$site/report.txt",
                  "status": 200,
                  "contentType": "text/plain; charset=UTF-8",
                  "file": "$target",
                  "bytes": ${FixtureSite.REPORT_CSV.length}
                }
            """.trimIndent()
        }
        target.readText() sameAs FixtureSite.REPORT_CSV

        target.deleteIfExists()
    }

    @Test
    fun `should hand back a file Chrome would rather download`() = runTest {

        val target = tempPath("umwelt-report", ".csv")

        // a content type Chrome downloads instead of displaying, which is what
        // "a PDF, an archive" means in the skill.
        //
        // FAILS TODAY: SKILL.md promises the file, and the daemon aborts the
        // navigation instead (net::ERR_ABORTED), because the anonymous read
        // navigates a tab and downloads are denied on it. Either the read grows
        // a non-navigating retrieval path, or the skill stops promising this.
        cli.umwelt("""--api-base=$daemonUrl read $site/report.csv -o "$target"""") should {
            have(exitCode == 0)
            out sameAsJson """
                {
                  "type": "FileRetrieved",
                  "url": "$site/report.csv",
                  "status": 200,
                  "contentType": "text/csv; charset=UTF-8",
                  "file": "$target",
                  "bytes": ${FixtureSite.REPORT_CSV.length}
                }
            """.trimIndent()
        }
        target.readText() sameAs FixtureSite.REPORT_CSV

        target.deleteIfExists()
    }

    @Test
    fun `should still return the page of a 404`() = runTest {

        // like a browser, umwelt settles on whatever came back: the content is
        // real and readable, so it is the document that arrives, with exit 0 -
        // a non-zero exit means an Error record on stdout where the document
        // would have been, and this is no error. The status rides the
        // frontmatter instead, because a payload on stdout carries no record
        // beside it: without it an agent could tell this page from a real one
        // only by reading the prose, and a 500 with a body, or a 200 that says
        // "not found" in words, is the same situation - no exit code covers
        // those, and a field every dump carries does.
        //
        // FAILS TODAY: the frontmatter carries `lang` and `title` only.
        cli.umwelt("--api-base=$daemonUrl read $site/no-such-page") should {
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

}
