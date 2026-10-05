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
import com.xemantic.kotlin.test.sameAsMarkdown
import com.xemantic.kotlin.test.should
import com.xemantic.umwelt.e2e.harness.UmweltCli
import com.xemantic.umwelt.e2e.harness.UmweltUnderTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.SystemFileSystem
import kotlin.test.AfterTest
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * Which deployment a command actually lands on — the two ends of it, each
 * reached for real.
 *
 * A freshly installed `umwelt` talks to the **hosted service** at
 * `https://umwe.lt`, and reaching anything else — including a daemon on this
 * very machine — takes `--api-base`. Every other sequence in this module hands
 * that flag to the harness once, which makes the default the one thing they
 * cannot show: they would all pass against a CLI that quietly preferred
 * whatever was listening on loopback. So this class exercises both ends,
 * against real servers: the hosted one over the public internet, and the
 * suite's own daemon at its ephemeral address.
 *
 * There is **one CLI here, supplied with nothing**, and the runs that mean the
 * local daemon say so themselves — `--api-base=<url>` on the command line, or,
 * in the one sequence that is about it, `UMWELT_API_BASE` in the environment
 * that invocation is handed. That is the whole point of this file: every
 * invocation below reads exactly as it would in a terminal, so the difference
 * between the two deployments is visible in the command rather than hidden in
 * how the harness was constructed.
 *
 * `CliDeploymentTest` in `umwelt-cli` pins the same rule against a mock
 * transport, where the *address chosen* can be asserted exactly and no service
 * has to be up. That one is about the decision; this one is about the fact that
 * the decision reaches a server.
 *
 * ## Rules for touching the hosted service from a test
 *
 * - **Read-only, always.** `profiles`, a session lookup that is expected to
 *   miss, and the anonymous `read` of a public page — nothing else. A sequence
 *   here must never open a session on umwe.lt: that starts a real browser in
 *   somebody's infrastructure, on somebody's account, for a test that had a
 *   local daemon available all along. The anonymous read is admitted because
 *   it leaves nothing behind: its session is the daemon's own, opened for the
 *   one response and closed with it, and it is the command a fresh install
 *   exists to answer. Nothing here can change the hosted deployment either
 *   way: there is no configuration API to call, on that deployment or any
 *   other.
 * - **Never [UmweltCli.closeEverything] here**, for the same reason turned up
 *   to eleven: on a CLI supplied with nothing, `session close --all` goes to
 *   umwe.lt and closes sessions belonging to whoever is signed in. The harness
 *   refuses it outright; [cleanUp] names the daemon instead.
 * - **Do not pin payloads that belong to a deployment this repo does not own.**
 *   Its profile list is its own business and will change; what is asserted is
 *   the shape, the exit code, and that the answer is *not* this machine's. A
 *   page read *through* it is another matter: example.com belongs to IANA, not
 *   to umwe.lt, and is pinned whole like every other read in this module.
 *
 * ## Expected to fail until the service exists
 *
 * umwe.lt is not deployed yet, so the hosted sequences currently fail on the
 * transport (exit `ExitCode.UNREACHABLE`, `cannot reach an umwelt server`).
 * Once it is up they will report what it really answers — including, if it
 * turns out to require a key for `profiles`, a rejected-key exit rather than a
 * list, which is itself worth learning from a test rather than from a user.
 * This is the only class in the repo whose result depends on a service outside
 * it; if that becomes noise, the remedy is its own opt-in property beside
 * `-Pumwelt.e2eTest`, not a mock.
 */
class DeploymentSequenceTest {

    private val site = UmweltUnderTest.site.baseUrl

    /** This suite's own daemon, at the ephemeral address it came up on. */
    private val daemonUrl = UmweltUnderTest.daemon.baseUrl

    /**
     * One CLI, with nothing supplied — a freshly installed `umwelt`, which
     * talks to the hosted service. Every invocation below is therefore the
     * command line as a user types it, `--api-base=<url>` included on the runs
     * that mean the local daemon.
     */
    private val cli = UmweltCli()

    @AfterTest
    fun cleanUp() {
        // named, and never `closeEverything()`: on this CLI that would send
        // `session close --all` to the hosted service
        runBlocking {
            runCatching { cli.umwelt("--api-base=$daemonUrl session close --all") }
        }
    }

    @Test
    @Ignore
    fun `should reach the hosted service when no api base is given`() = runTest {

        // no `--api-base` anywhere, and the harness hands every run an empty
        // environment, so this is the command as a user types it an hour after
        // installing: it goes to umwe.lt because there is nowhere else it could
        // have learned to go
        cli.umwelt("profiles") should {
            have(exitCode == 0)
            have(type == "ProfileList")
        }
    }

    @Test
    @Ignore
    fun `should read a bare host through the hosted service`() = runTest {

        // `umwelt read example.com` - the first command a user types, whole:
        // no flag, no environment, no scheme. `TargetUrlTest` pins what the
        // CLI makes of the bare host and `CliDeploymentTest` pins where it
        // sends it; this is the one run in which that https target reaches a
        // real browser on the deployment nothing had to name, and the only
        // one that meets a TLS site at all - the fixture site the other
        // sequences read is plaintext on loopback, so the https half of the
        // scheme rule is not something they could ever show. The page is
        // IANA's, pinned whole because it changes about once a decade
        cli.umwelt("read example.com") should {
            have(exitCode == 0)
            out sameAsMarkdown """
                ---
                lang: en
                title: Example Domain
                status: 200
                ---
                
                # Example Domain
                
                This domain is for use in documentation examples without needing permission. Avoid use in operations.
                
                [Learn more](https://iana.org/domains/example)
                
            """.trimIndent()
        }
    }

    @Test
    fun `should reach the local daemon when it is named`() = runTest {

        // the same command, one flag different — and now the answer is this
        // machine's configuration directory, read by this machine's daemon:
        // one entry per file in it, named after the file, in the order an `ls`
        // would show them, each carrying the description that file was written
        // with — words only this suite's own configuration contains
        cli.umwelt("--api-base=$daemonUrl profiles") should {
            have(exitCode == 0)
            out sameAsJson """
                {
                  "type": "ProfileList",
                  "profiles": [
                    {
                      "name": "e2e",
                      "description": "${UmweltUnderTest.E2E_DESCRIPTION}",
                      "persistent": true,
                      "default": true
                    },
                    {
                      "name": "e2e-scraper",
                      "description": "${UmweltUnderTest.E2E_SCRAPER_DESCRIPTION}",
                      "persistent": false,
                      "default": false
                    }
                  ]
                }
            """.trimIndent()
        }

        // and the directory is what those names came from — the proof of *whose*
        // files answered
        val onDisk = SystemFileSystem.list(UmweltUnderTest.daemon.profilesDir)
            .map { it.name }
            .filter { it.endsWith(".yaml") }
            .map { it.removeSuffix(".yaml") }
            .sorted()
        assert(onDisk == listOf("e2e", "e2e-scraper"))
    }

    @Test
    @Ignore
    fun `should apply the local configuration only to the daemon it belongs to`() = runTest {

        // the whole point of the flag, in one comparison: a configuration file
        // on this machine configures the daemon on this machine, and says
        // nothing about where a bare command lands. A CLI that read the file —
        // or that preferred a daemon it found listening — would answer these
        // two invocations identically, and an agent would have no way to tell
        // which deployment its next command was driving.
        val hostedProfiles = cli.umwelt("profiles") should {
            have(exitCode == 0)
            have(type == "ProfileList")
        }
        val localProfiles = cli.umwelt("--api-base=$daemonUrl profiles") should {
            have(exitCode == 0)
            have(type == "ProfileList")
        }

        assert(hostedProfiles.out != localProfiles.out)
    }

    @Test
    @Ignore
    fun `should not resolve a local session id on the hosted service`() = runTest {

        // why the moving default would be a bug rather than a convenience: a
        // session lives in the daemon that opened it, so a default that drifted
        // mid-loop would leave an agent naming ids that no longer exist — with
        // a command line that had not changed at all
        val sid = (cli.umwelt("--api-base=$daemonUrl session new") should { have(exitCode == 0) }).sessionId

        // a session that has not navigated has no page to report, so the tab is
        // sent somewhere before it is asked where it is
        cli.umwelt("--api-base=$daemonUrl goto -s $sid $site/article.html") should { have(exitCode == 0) }
        cli.umwelt("--api-base=$daemonUrl status -s $sid") should { have(exitCode == 0) }

        // the record is pinned whole although the deployment is somebody
        // else's: `SessionNotFound` is umwelt's own contract, which any umwelt
        // answers, so the wording is not umwe.lt's to choose. Pinning the exit
        // code alone would have been green on the transport failure the hosted
        // sequences currently produce — a test claiming to prove a refusal
        // while proving only that nothing answered.
        cli.umwelt("status -s $sid") should {
            have(exitCode == 1)
            out sameAsJson """
                {
                  "type": "Error",
                  "code": 1,
                  "message": "no session with id '$sid'",
                  "error": {
                    "type": "SessionNotFound",
                    "id": "$sid",
                    "message": "no session with id '$sid'"
                  }
                }
            """.trimIndent()
        }
    }

    @Test
    fun `should drive a whole loop from an exported api base`() = runTest {

        // the other half of the one knob, as the skill tells a user to reach
        // for it: `export UMWELT_API_BASE=…` once, then bare commands for the
        // life of the loop. `CliDeploymentTest` pins which address that
        // resolves to; what is left to show is that a run configured by
        // nothing but its environment really drives a browser on the deployment
        // the variable named — the flag is not the only way in.
        //
        // The environment here is an injected one, so this covers the CLI's
        // option-from-environment plumbing and not the platform's `getenv`:
        // the shipped binary reading a real shell's environment is past the
        // process boundary this suite does not cross.
        val env = mapOf("UMWELT_API_BASE" to daemonUrl)

        // What proves which deployment answered is the page, not a listing:
        // `profiles` is pinned against the flag two tests above, and repeating
        // its record here would tie this sequence to a shape that is still
        // pending rather than to the variable it is about.
        val sid = (cli.umwelt("session new", env) should { have(exitCode == 0) }).sessionId

        // a page only this machine can serve: the hosted service could not
        // have answered this, so the loop is provably on the local daemon
        cli.umwelt("goto -s $sid $site/article.html", env) should {
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

        cli.umwelt("dump -s $sid", env) should {
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

        // and the id resolves for as long as the variable is set, which is the
        // property the export buys: one address for the whole loop
        cli.umwelt("session close $sid", env) should {
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

}
