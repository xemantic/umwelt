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

import com.github.ajalt.clikt.testing.test
import com.xemantic.umwelt.api.umweltJson
import com.xemantic.umwelt.cli.runUmweltCommand
import com.xemantic.umwelt.cli.umweltCli
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals

/**
 * What one `umwelt …` invocation produced.
 *
 * Not a `data class`: [bytes] would give it an `equals` comparing arrays by
 * identity while reading as value equality, and a result is asserted on field
 * by field anyway.
 */
class CliResult(
    val exitCode: Int,
    /**
     * The text the invocation wrote to stdout, verbatim.
     *
     * There is no second stream to consult: the CLI writes to stdout and
     * nothing else, and [UmweltCli.umwelt] fails the test if anything ever
     * reaches stderr.
     */
    val out: String,
    /**
     * The *binary* payload the invocation put on stdout — a screenshot, a
     * downloaded image — and empty for every invocation that produced text.
     *
     * The two are separate fields because they are separate sinks in the CLI: a
     * `ByteArray` cannot go through the text one, so a binary payload takes
     * [com.xemantic.umwelt.cli.UmweltCliContext.bytesOut] instead, which this
     * harness hands a buffer. They never both carry something — a payload is
     * one or the other, and a record never rides beside a payload at all.
     */
    val bytes: ByteArray,
) {

    /**
     * A string field of the JSON record this command printed, read back out of
     * the object exactly as a caller has to read it — there is no bare value on
     * stdout to pick up, and pinning one would test something no caller can do.
     */
    fun field(name: String): String =
        (umweltJson.parseToJsonElement(out) as? JsonObject)
            ?.get(name)?.jsonPrimitive?.contentOrNull
            ?: error("no string field '$name' in:\n$out")

    /** The `type` discriminator of the record this command printed. */
    val type: String get() = field("type")

    /** The id of the session `umwelt session new` just opened. */
    val sessionId: String get() = field("sessionId")

}

/**
 * Runs real `umwelt` command lines against the real daemon.
 *
 * Sequences are written the way they would be typed —
 * `umwelt("goto -s $sid $url")`, `umwelt("dump -s $sid")` — because that is the
 * unit these tests are about: not "does this route answer", but "does this
 * *order of commands* get an agent where it needed to go".
 *
 * There is deliberately no "just run this and assume it worked" helper: every
 * invocation in a sequence is asserted on, exit code first and then the whole
 * of what it wrote. A step nobody looks at is a step that can rot silently.
 *
 * ## What an invocation may produce
 *
 * **stdout is the only channel.** Every invocation writes there and nowhere
 * else, which is why [CliResult] has no `stderr` field to assert on: [umwelt]
 * fails the test outright if a run puts anything on stderr, so the whole suite
 * holds that invariant once rather than restating it at every step.
 *
 * On stdout an invocation prints exactly one of two things:
 *
 * - a **payload** — the Markdown of a page, the NDJSON of an event stream, the
 *   bytes of a file or a screenshot — for the commands whose result *is* the
 *   thing they retrieved or produced;
 * - one **`CliResponse`** JSON object for everything else, carrying a `"type"`
 *   discriminator that says which response it is — including in place of a
 *   payload that `-o <file>` sent to disk.
 *
 * A payload that is text reads back from [CliResult.out], and a binary one from
 * [CliResult.bytes]: two fields because the CLI writes them through two sinks.
 *
 * A failure is a response like any other: `{"type": "Error", …}` on stdout,
 * with a non-zero exit code. So the **exit code decides what stdout is**: a
 * document command that exited non-zero wrote an error record, not a partial
 * document, and a caller must look at the code before parsing what it got.
 *
 * The command tree is the shipped one, assembled by `umweltCli`, and its HTTP
 * client is the shipped one too, and this class supplies **nothing at all**:
 * the string it is handed is the whole command line, and it is passed through
 * as the argv a shell would have produced.
 *
 * That includes `--api-base=<url>`, which every sequence driving this suite's
 * daemon types for itself, on every invocation. There is deliberately no
 * constructor parameter for it. A harness that carried the address would let
 * a sequence read as a command nobody could type, and — worse — would make the
 * suite pass against a CLI that had quietly learned to prefer whatever was
 * listening on loopback, since nothing would ever exercise the unnamed
 * default. With nothing given the CLI talks to the hosted service at
 * https://umwe.lt, which is the real default and what `DeploymentSequenceTest`
 * is about; every other sequence names the daemon the way any caller on a
 * local one has to, exactly as it passes `-s` on every session command.
 *
 * There is no per-test session state to supply either, because the CLI keeps
 * none.
 *
 * ## The environment a run sees
 *
 * The same rule governs [umwelt]'s `env`: it is a parameter of the
 * *invocation*, defaulting to nothing, and it is deliberately not a field of
 * this class. `export UMWELT_API_BASE=…` is a line a user types, so a sequence
 * that passes it still reads as a transcript; a harness that *carried* the
 * variable would be `--api-base` hidden in the constructor all over again,
 * with every sequence quietly ceasing to name the deployment it drives.
 *
 * Clikt's environment here is an injected one, which is what keeps the
 * developer's own shell from changing what a test asserts — but it also means
 * a run never reads the real environment. What an `env` covers is the CLI's
 * option-from-environment plumbing reaching a real server; what no in-process
 * harness can cover is `getenv` in the shipped binary.
 *
 * What it deliberately does **not** cover, because the process boundary is
 * absent: real argv splitting by a shell, real exit codes, stdout as an OS
 * stream, and the platform's own environment. `CliTester` in `umwelt-cli` pins
 * what it can of those.
 */
class UmweltCli {

    /**
     * Runs one `umwelt` command line, optionally in an [env] — the whole
     * environment the run sees, since nothing falls back to the real one.
     */
    suspend fun umwelt(
        commandLine: String,
        env: Map<String, String> = emptyMap(),
    ): CliResult {
        val out = StringBuilder()
        // one buffer per invocation, handed out fresh: the writer closes what
        // it is given, and closing a Buffer is a no-op that keeps its content
        val bytes = Buffer()
        var exitCode = 0
        val command = umweltCli(
            out = { out.append(it) },
            bytesOut = { bytes },
            // never the system browser: a `focus` without `--no-open` would
            // put a watch page in front of whoever runs the suite. An opener
            // that declines leaves the handover a LINK, which is what the
            // record then says
            openBrowser = { false },
        )
        val captured = command.test(
            argv = tokenize(commandLine),
            // the CLI reads UMWELT_API_BASE / UMWELT_API_KEY / UMWELT_INSECURE
            // from the environment, and this is the whole of what it sees:
            // empty unless the sequence set something, so the developer's own
            // shell can never change what a test asserts
            envvars = env,
        ) { argv ->
            // the shipped runner, which is what turns a failure into the
            // `{"type": "Error"}` record on stdout — going through Clikt's own
            // `parse` here would leave the error on stderr and test nothing
            exitCode = command.runUmweltCommand(argv)
        }
        // the one place the "nothing is ever written to stderr" rule is
        // asserted; every sequence below relies on it rather than repeating it
        assertEquals(
            "", captured.stderr,
            "`umwelt $commandLine` wrote to stderr, which the CLI must never do"
        )
        return CliResult(
            exitCode = exitCode,
            out = out.toString(),
            bytes = bytes.readByteArray(),
        )
    }

    /**
     * Best-effort cleanup, for an `@AfterTest` that must not itself fail.
     *
     * [apiBase] is required rather than defaulted: `session close --all` with
     * no deployment named goes to the hosted service and closes sessions
     * belonging to whoever is signed in there.
     */
    fun closeEverything(apiBase: String) {
        runBlocking {
            runCatching { umwelt("--api-base=$apiBase session close --all") }
        }
    }

}

/**
 * Splits a command line the way a shell would, honouring double quotes, so a
 * sequence can be written as one readable string rather than a list of
 * fragments. Everything these tests pass is either a bare token or a
 * double-quoted string; no escaping, no single quotes, no globbing.
 */
internal fun tokenize(commandLine: String): List<String> {
    val tokens = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    var started = false
    for (c in commandLine) {
        when {
            c == '"' -> { quoted = !quoted; started = true }
            c.isWhitespace() && !quoted -> {
                if (started) { tokens += current.toString(); current.clear(); started = false }
            }
            else -> { current.append(c); started = true }
        }
    }
    if (started) tokens += current.toString()
    return tokens
}

// --- reading refs out of a dump, the way an agent has to ------------------

private val ACTIONABLE_TAG = Regex("""<[^>]*\bref="(\d+)"[^>]*>""")

private val REF_LINK = Regex("""\[([^\]]*)]\(ref:(\d+):""")

/**
 * The ref of the first actionable tag whose rendered form contains [marker] —
 * `dump.tagRef("aria-label=\"query\"")`.
 *
 * Refs are assigned per dump and are not predictable, so a sequence has to read
 * them back out of the Markdown exactly as an agent does. Doing it any other
 * way — pinning a number, or asking the daemon — would test something no caller
 * can actually do.
 */
fun String.tagRef(marker: String): String =
    ACTIONABLE_TAG.findAll(this).firstOrNull { marker in it.value }?.groupValues?.get(1)
        ?: error("no actionable element matching '$marker' in the dump:\n$this")

/** The ref of the first `[label](ref:N:…)` link whose label contains [label]. */
fun String.linkRef(label: String): String =
    REF_LINK.findAll(this)
        .firstOrNull { it.groupValues[1].contains(label, ignoreCase = true) }
        ?.groupValues?.get(2)
        ?: error("no link labelled '$label' in the dump:\n$this")
