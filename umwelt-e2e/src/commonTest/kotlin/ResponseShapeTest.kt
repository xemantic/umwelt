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
import com.xemantic.umwelt.e2e.harness.readBytes
import com.xemantic.umwelt.e2e.harness.tempPath
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * The output contract itself, stated once rather than assumed by every
 * sequence: what an `umwelt` invocation is allowed to write, and where.
 *
 * There is **one** output stream. Every invocation writes to stdout and to
 * nothing else — the harness fails any run that touches stderr, so these tests
 * do not restate it — and what lands there is either a **document** (Markdown,
 * NDJSON, bytes) or exactly one **`CliResponse`** JSON object naming itself in
 * `type`.
 *
 * That leaves one rule an agent has to know, and these tests are what pin it:
 * **the exit code decides what stdout is.** Zero means the payload the command
 * promised; anything else means a `{"type": "Error"}` record, whatever the
 * command would have produced on success. A document command is no exception —
 * a failed `dump` writes an error record where its Markdown would have gone.
 *
 * The sequences in the other files show the shapes in use; this file covers the
 * corners they do not reach — the `-o -` payloads that leave no room for a
 * record at all, the `-o <file>` record of each kind of payload, the CLI's own
 * failures, a session id that is not one, a stream that fails before it
 * starts, and the daemon it cannot reach.
 */
class ResponseShapeTest {

    private val site = UmweltUnderTest.site.baseUrl
    private val daemonUrl = UmweltUnderTest.daemon.baseUrl
    private val cli = UmweltCli()

    @AfterTest
    fun cleanUp() {
        cli.closeEverything(daemonUrl)
    }

    /**
     * A download asks for the payload and nothing else — which is what stdout
     * carries, with no `-o` and with `-o -` alike. There is no second stream
     * for a record to go to, so there is no record: the transfer's size and
     * type are properties of what the caller now holds, and exit 0 is the whole
     * of what the command has left to say.
     */
    @Test
    fun `should write no record beside a download sent to stdout`() = runTest {

        val sid = (cli.umwelt("--api-base=$daemonUrl session new") should { have(exitCode == 0) }).sessionId
        cli.umwelt("--api-base=$daemonUrl goto -s $sid $site/article.html") should { have(exitCode == 0) }
        val article = cli.umwelt("--api-base=$daemonUrl dump -s $sid")
        article should { have(exitCode == 0) }
        val ref = article.out.linkRef("report as data")

        cli.umwelt("--api-base=$daemonUrl download -s $sid $ref") should {
            have(exitCode == 0)
            // the file, and nothing wrapped around it
            out sameAs FixtureSite.REPORT_CSV
            have(bytes.isEmpty())
        }
    }

    /**
     * The same rule for a payload that is not text: it goes to stdout too, as
     * the bytes it is. Text and bytes leave the CLI through different sinks —
     * a `ByteArray` has no way through the text one — so the assertion is that
     * the byte stream carries the file *verbatim* and the text stream carries
     * nothing at all.
     */
    @Test
    fun `should send a binary download to stdout as its own bytes`() = runTest {

        val sid = (cli.umwelt("--api-base=$daemonUrl session new") should { have(exitCode == 0) }).sessionId
        cli.umwelt("--api-base=$daemonUrl goto -s $sid $site/article.html") should { have(exitCode == 0) }

        cli.umwelt("--api-base=$daemonUrl download -s $sid --url $site/logo.png") should {
            have(exitCode == 0)
            have(out.isEmpty())
            have(bytes.contentEquals(FixtureSite.LOGO_PNG))
        }
    }

    /**
     * As for a download: the image is the output, it goes to stdout with no
     * `-o` at all, and nothing rides alongside it. `screenshot` used to write
     * `./screenshot.png` into the caller's working directory instead — the one
     * command that left a file behind unasked.
     */
    @Test
    fun `should write no record beside a screenshot sent to stdout`() = runTest {

        val sid = (cli.umwelt("--api-base=$daemonUrl session new") should { have(exitCode == 0) }).sessionId
        cli.umwelt("--api-base=$daemonUrl goto -s $sid $site/article.html") should { have(exitCode == 0) }

        cli.umwelt("--api-base=$daemonUrl screenshot -s $sid") should {
            have(exitCode == 0)
            have(out.isEmpty())
            // a real PNG on the byte stream, not an empty one
            have(bytes.startsWith(PNG_SIGNATURE))
        }
    }

    @Test
    fun `should print a record when a screenshot goes to a file`() = runTest {

        val target = tempPath("umwelt-shot", ".png")

        val sid = (cli.umwelt("--api-base=$daemonUrl session new") should { have(exitCode == 0) }).sessionId
        cli.umwelt("--api-base=$daemonUrl goto -s $sid $site/article.html") should { have(exitCode == 0) }

        val shot = cli.umwelt("""--api-base=$daemonUrl screenshot -s $sid -o "$target"""")

        // a PNG signature, so the record is not describing an empty file
        val bytes = target.readBytes()
        assert(bytes.startsWith(PNG_SIGNATURE))

        // the size cannot be known in advance, but it can be held to the file
        // the record describes
        shot should {
            have(exitCode == 0)
            out sameAsJson """
                {
                  "type": "ScreenshotSaved",
                  "format": "png",
                  "file": "$target",
                  "bytes": ${bytes.size}
                }
            """.trimIndent()
        }

        target.deleteIfExists()
    }

    @Test
    fun `should print a record when a dump goes to a file`() = runTest {

        val target = tempPath("umwelt-dump", ".md")

        val sid = (cli.umwelt("--api-base=$daemonUrl session new") should { have(exitCode == 0) }).sessionId
        cli.umwelt("--api-base=$daemonUrl goto -s $sid $site/article.html") should { have(exitCode == 0) }

        // the document went to a file, so stdout carries the record of it
        // rather than the document — the same swap `read -o` makes
        val dump = cli.umwelt("""--api-base=$daemonUrl dump -s $sid -o "$target"""")
        val bytes = target.readBytes()
        // the file holds exactly the Markdown stdout would have carried
        bytes.decodeToString() sameAsMarkdown ARTICLE_DUMP
        dump should {
            have(exitCode == 0)
            out sameAsJson """
                {
                  "type": "FileWritten",
                  "file": "$target",
                  "bytes": ${bytes.size}
                }
            """.trimIndent()
        }

        target.deleteIfExists()
    }

    @Test
    fun `should print the semantic events as a document`() = runTest {

        val sid = (cli.umwelt("--api-base=$daemonUrl session new") should { have(exitCode == 0) }).sessionId
        cli.umwelt("--api-base=$daemonUrl goto -s $sid $site/article.html") should { have(exitCode == 0) }

        // NDJSON is a document just as Markdown is: it goes to stdout verbatim,
        // one JSON object per line, with no envelope wrapped around it — the
        // page's DOM as captured, refs included, before any Markdown rendering
        cli.umwelt("--api-base=$daemonUrl events -s $sid") should {
            have(exitCode == 0)
            out sameAs ARTICLE_EVENTS
        }
    }

    @Test
    fun `should print a record when the semantic events go to a file`() = runTest {

        val target = tempPath("umwelt-events", ".ndjson")

        val sid = (cli.umwelt("--api-base=$daemonUrl session new") should { have(exitCode == 0) }).sessionId
        cli.umwelt("--api-base=$daemonUrl goto -s $sid $site/article.html") should { have(exitCode == 0) }

        // the same swap `dump -o` makes, for the other document command
        val events = cli.umwelt("""--api-base=$daemonUrl events -s $sid -o "$target"""")
        val bytes = target.readBytes()
        bytes.decodeToString() sameAs ARTICLE_EVENTS
        events should {
            have(exitCode == 0)
            out sameAsJson """
                {
                  "type": "FileWritten",
                  "file": "$target",
                  "bytes": ${bytes.size}
                }
            """.trimIndent()
        }

        target.deleteIfExists()
    }

    @Test
    fun `should print a record when a lossy full page screenshot goes to a file`() = runTest {

        val target = tempPath("umwelt-shot", ".jpeg")

        val sid = (cli.umwelt("--api-base=$daemonUrl session new") should { have(exitCode == 0) }).sessionId
        cli.umwelt("--api-base=$daemonUrl goto -s $sid $site/article.html") should { have(exitCode == 0) }

        val shot = cli.umwelt(
            """--api-base=$daemonUrl screenshot -s $sid --format jpeg --quality 50 --full-page -o "$target""""
        )

        // the encoding asked for is the encoding written, and the record says
        // which: a JPEG starts with its SOI marker
        val bytes = target.readBytes()
        assert(bytes.startsWith(JPEG_SIGNATURE))

        shot should {
            have(exitCode == 0)
            out sameAsJson """
                {
                  "type": "ScreenshotSaved",
                  "format": "jpeg",
                  "file": "$target",
                  "bytes": ${bytes.size}
                }
            """.trimIndent()
        }

        target.deleteIfExists()
    }

    @Test
    fun `should report a usage mistake as a record like any other failure`() = runTest {

        val sid = (cli.umwelt("--api-base=$daemonUrl session new") should { have(exitCode == 0) }).sessionId

        // the CLI's own refusal — nothing was sent to the daemon, so there is
        // no typed error to nest and `error` is null. It is still a record, on
        // the same stream, in the same shape: an agent parses one thing.
        cli.umwelt("--api-base=$daemonUrl download -s $sid 1 --url $site/report.csv") should {
            have(exitCode == 1)
            out sameAsJson """
                {
                  "type": "Error",
                  "code": 1,
                  "message": "give either a <ref> argument or --url, not both",
                  "error": null
                }
            """.trimIndent()
        }
    }

    @Test
    fun `should report an unknown option as a record rather than a usage dump`() = runTest {

        // Clikt's own parse failures come through the same rendering, so a
        // caller never has to tell "the CLI printed usage text" apart from "the
        // daemon refused" — both are one object carrying a message
        cli.umwelt("--api-base=$daemonUrl session new --no-such-option") should {
            have(exitCode == 1)
            out sameAsJson """
                {
                  "type": "Error",
                  "code": 1,
                  "message": "no such option --no-such-option",
                  "error": null
                }
            """.trimIndent()
        }
    }

    @Test
    fun `should report a missing session as a record rather than a usage dump`() = runTest {

        // the mistake an agent is likeliest to make: there is no remembered
        // session to fall back on, so forgetting `-s` must say which option is
        // missing — a Clikt usage error carries no `message` of its own, and
        // reading it naively reported this as an empty string
        cli.umwelt("--api-base=$daemonUrl dump") should {
            have(exitCode == 1)
            out sameAsJson """
                {
                  "type": "Error",
                  "code": 1,
                  "message": "missing option --session",
                  "error": null
                }
            """.trimIndent()
        }
    }

    @Test
    fun `should report an unreachable daemon with its own exit code`() = runTest {

        // a later --api-base wins over the one the harness pins, so this run
        // is aimed at the discard port, where nothing answers
        cli.umwelt("--api-base=$daemonUrl --api-base http://127.0.0.1:9 session list") should {
            have(exitCode == 3)
            // the parenthesis quotes the HTTP engine's own reason, which is
            // worded differently on each platform ("Connection refused" on the
            // jvm, "Failed to connect to InetSocketAddress(…)" on native) — so
            // that one span is masked, and the rest of the record is pinned
            val engineReason = Regex("""\(.*\);(?= point the CLI elsewhere)""")
            out.replace(engineReason, "(<engine reason>);") sameAsJson """
                {
                  "type": "Error",
                  "code": 3,
                  "message": "cannot reach an umwelt server at http://127.0.0.1:9 (<engine reason>); point the CLI elsewhere with --api-base / UMWELT_API_BASE, or start the local daemon and pass its address",
                  "error": null
                }
            """.trimIndent()
        }
    }

    @Test
    fun `should write an error record where a document would have gone`() = runTest {

        // `dump` produces a document, but this one fails — so stdout carries the
        // error record instead, and nothing half-written is left for a caller to
        // mistake for Markdown. The exit code is what says which of the two
        // arrived, which is the whole rule this file exists to pin.
        val unknown = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"
        cli.umwelt("--api-base=$daemonUrl dump -s $unknown") should {
            have(exitCode == 1)
            out sameAsJson """
                {
                  "type": "Error",
                  "code": 1,
                  "message": "no session with id '$unknown'",
                  "error": {
                    "type": "SessionNotFound",
                    "id": "$unknown",
                    "message": "no session with id '$unknown'"
                  }
                }
            """.trimIndent()
        }
    }

    /**
     * The same rule for `events`, which reaches the daemon on its own path — a
     * raw NDJSON stream rather than a resolved session — and so has its own
     * way to get it wrong: a stream whose failure arrives after a `200` header
     * would reach the caller as a truncated document with exit `0`.
     */
    @Test
    fun `should write an error record where an event stream would have gone`() = runTest {

        val unknown = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"
        cli.umwelt("--api-base=$daemonUrl events -s $unknown") should {
            have(exitCode == 1)
            out sameAsJson """
                {
                  "type": "Error",
                  "code": 1,
                  "message": "no session with id '$unknown'",
                  "error": {
                    "type": "SessionNotFound",
                    "id": "$unknown",
                    "message": "no session with id '$unknown'"
                  }
                }
            """.trimIndent()
        }

        // a session that exists but has no page: the precondition is checked
        // before the stream starts, never from inside it
        val sid = (cli.umwelt("--api-base=$daemonUrl session new") should { have(exitCode == 0) }).sessionId
        cli.umwelt("--api-base=$daemonUrl events -s $sid") should {
            have(exitCode == 1)
            out sameAsJson """
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
        }
    }

    @Test
    fun `should report a session id that is not one as a typed error`() = runTest {

        // not a UUID at all: the daemon refuses to look it up, and says what
        // it was given
        cli.umwelt("--api-base=$daemonUrl status -s not-a-session") should {
            have(exitCode == 1)
            out sameAsJson """
                {
                  "type": "Error",
                  "code": 1,
                  "message": "invalid session id 'not-a-session'",
                  "error": {
                    "type": "InvalidSessionId",
                    "idText": "not-a-session",
                    "message": "invalid session id 'not-a-session'"
                  }
                }
            """.trimIndent()
        }
    }

    private companion object {

        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )

        val JPEG_SIGNATURE = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

        /** The article as `dump` renders it, refs included. */
        @Suppress("MarkdownUnresolvedFileReference")
        val ARTICLE_DUMP = /* language=markdown */ """
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

        /** The article as `events` relays it: the DOM as captured, one event per line. */
        val ARTICLE_EVENTS = /* language=ndjson */ """
            {"type":"mark","name":"html","tagged":true,"attributes":{"lang":"en"}}
            {"type":"mark","name":"head","tagged":true}
            {"type":"mark","name":"meta","tagged":true,"attributes":{"charset":"utf-8"}}
            {"type":"unmark","name":"meta","tagged":true}
            {"type":"mark","name":"title","tagged":true}
            {"type":"text","text":"The Article"}
            {"type":"unmark","name":"title","tagged":true}
            {"type":"unmark","name":"head","tagged":true}
            {"type":"text","text":"\n    "}
            {"type":"mark","name":"body","tagged":true}
            {"type":"text","text":"\n    "}
            {"type":"mark","name":"h1","tagged":true}
            {"type":"text","text":"The Article"}
            {"type":"unmark","name":"h1","tagged":true}
            {"type":"text","text":"\n"}
            {"type":"mark","name":"p","tagged":true}
            {"type":"text","text":"Umwelt is the world as an organism perceives it."}
            {"type":"unmark","name":"p","tagged":true}
            {"type":"text","text":"\n"}
            {"type":"mark","name":"p","tagged":true}
            {"type":"mark","name":"a","tagged":true,"attributes":{"href":"/report.csv","data-markanywhere-ref":"1","data-markanywhere-display":"inline"}}
            {"type":"text","text":"the report as data"}
            {"type":"unmark","name":"a","tagged":true}
            {"type":"unmark","name":"p","tagged":true}
            {"type":"text","text":"\n"}
            {"type":"mark","name":"p","tagged":true}
            {"type":"mark","name":"a","tagged":true,"attributes":{"href":"/attachment.csv","download":"","data-markanywhere-ref":"2","data-markanywhere-display":"inline"}}
            {"type":"text","text":"download the table"}
            {"type":"unmark","name":"a","tagged":true}
            {"type":"unmark","name":"p","tagged":true}
            {"type":"text","text":"\n    \n    "}
            {"type":"unmark","name":"body","tagged":true}
            {"type":"unmark","name":"html","tagged":true}
            
        """.trimIndent()

    }

}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
