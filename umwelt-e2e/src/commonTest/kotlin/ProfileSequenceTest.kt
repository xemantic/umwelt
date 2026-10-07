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
import com.xemantic.kotlin.test.sameAsJson
import com.xemantic.kotlin.test.sameAsMarkdown
import com.xemantic.kotlin.test.should
import com.xemantic.umwelt.e2e.harness.UmweltCli
import com.xemantic.umwelt.e2e.harness.UmweltUnderTest
import com.xemantic.umwelt.e2e.harness.visitsPage
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Profiles — the browsers a session can be opened in, and the one concept a
 * caller names to say *where* and *as whom* it runs.
 *
 * A profile is a browsing identity with a name, a description, and a place it
 * runs: the daemon's own Chrome today, a remote browser provider once one is
 * configured. As in Chrome itself, a profile is **persistent** — locally a
 * Chrome `--user-data-dir` of its own, which is what makes a session opened in
 * it arrive already signed in, and what makes `HandoffSequenceTest`'s
 * handover worth performing — and an **ephemeral** session is the incognito
 * window opened *from* it: the same browser, a fresh context, nothing carried
 * in and nothing kept. `session new --ephemeral` asks for one. A profile that
 * must never keep anything (the hosted service's, a headless scraper's) says
 * `persistent: false`, and every session in it is ephemeral whether asked or
 * not. Every deployment has at least one profile and exactly one
 * **default**, which is where a session lands when it names none — and it
 * lands there persistently, since the default profile of a daemon on the
 * user's machine is the one they signed into, in a directory of umwelt's own.
 *
 * There used to be a second concept beside it: a *backend*, the place a
 * browser runs, carrying the profiles that lived on it and named on its own by
 * an ephemeral session. On the wire it was nothing but a name, and the one
 * rule connecting the two was a refusal (`ProfileFixesBackend`) whose sole job
 * was to stop a caller from naming the same thing twice. An agent never chose
 * by either of the two: it chose by purpose — "signed in as the user",
 * "anonymous", "not from my address" — which neither record stated. That
 * purpose is now the profile's `description`, "anonymous" is `--ephemeral`
 * on whichever profile's browser, and where a profile runs is its own
 * business, so the wire and the CLI know profile names and nothing else.
 *
 * ## One file per profile
 *
 * Profiles are **not** a list inside `config.yaml`. Each is its own file in a
 * `profiles/` directory beside it, and **the file name is the profile's name**:
 * `profiles/e2e.yaml` is the profile called `e2e`, and nothing inside the file
 * says so. The directory is derived from wherever the configuration file is —
 * `--config <path>` moves both together — so a dev server and this suite can
 * never read the user's own profiles. A persistent local profile keeps its
 * data beside its file, in `profiles/<name>/`, unless the file points
 * elsewhere.
 *
 * Three things follow, and they are why the layout is worth the extra file:
 *
 * - **Duplicate profile names are impossible.** A directory cannot hold two
 *   entries with one name, so the check that a list would need does not exist.
 *   The filesystem is the namespace.
 * - **A profile is added, removed and compared as a file.** Dropping one in is
 *   the whole act, and `diff profiles/personal.yaml profiles/work.yaml` is how
 *   two of them are read side by side, which a nested list inside a larger
 *   document does not allow.
 * - **Only `*.yaml` is read**, so renaming one to `work.yaml.disabled` turns it
 *   off without deleting anybody's logins — while a file whose stem is not a
 *   usable profile name is refused rather than skipped, since a silently
 *   ignored profile is a session landing somewhere the caller did not mean.
 *
 * What stays in `config.yaml` is what is not a profile: the server, the session
 * defaults, and `defaultProfile` — the one setting that points *across* files,
 * and therefore the one an edit elsewhere can leave dangling.
 *
 * **The configuration files are the entire management surface.** There is no
 * `profiles new` and no `profiles delete`: a profile *is* its file, and the
 * directory behind a persistent one is created by Chrome on the first session.
 * Three reasons that is the shape:
 *
 * - a command that creates a profile can only write to the directory the user
 *   also edits, which gives the daemon two sources of truth for the same list;
 * - a blank persistent profile does nothing that an ephemeral session does
 *   not, so the act worth having is the human signing into it — a handoff the
 *   agent has to arrange anyway, and the natural moment for the edit;
 * - `profiles delete` is `rm -rf` over somebody's logins, reachable from a tool
 *   call. Taking a profile away stays a human's edit of a human's file.
 *
 * **The daemon exposes no configuration API at all** — no `config`, no reload,
 * nothing that reports a path. Editing these files means being on the machine
 * that holds them, and an agent that is on it can find them the way it finds
 * any other file; one that is not has no business being told a filesystem
 * layout by a daemon whose `/health` answers the network. Where to look is the
 * skill's to explain, not a command's to report.
 *
 * ## How a change takes effect: a restart
 *
 * There is **no `config reload`**. Profiles change rarely enough that stopping
 * the daemon and starting it again is the whole mechanism, and a reload
 * endpoint costs more than it saves: it has to answer a question a restart
 * never raises — which edits a live daemon can adopt, and which it can only
 * report as pending — and it is a second path to a running configuration, so
 * every refusal would need to exist twice.
 *
 * The configuration is therefore read and validated **exactly once, at
 * startup**, and a daemon that refuses it does not start: the process exits
 * with the error naming the file, which is where an agent sees a refusal.
 *
 * Nor is the restart itself a command. `umwelt` is a client that talks to an
 * address and never manages a process; stopping and starting `umwelt-server`
 * is ordinary process management, which the skill describes and an agent does
 * with the shell, on the user's say-so. A `umwelt up`/`umwelt down`/`umwelt
 * consent` trio was specified for it and dropped: each would wrap a one-off
 * operation on the user's machine in a command — and consent in a config key —
 * where asking the user in the conversation is both simpler and the point.
 *
 * The cost is worth stating, because an agent has to plan around it: a restart
 * closes every open session. Adding a profile in the middle of a task discards
 * the tab the task was in, so it belongs between tasks — and a persistent
 * profile is worth nothing until a human has signed into it anyway, which is a
 * handoff that has to be arranged rather than slipped in.
 *
 * ## The rules these sequences pin
 *
 * - **The listing says what each profile is**: its `description` — the words
 *   the deployment's owner wrote for the agent choosing between them — whether
 *   it can keep anything (`persistent`), and which one is the `default`. The whole record is
 *   asserted once, here, since every other file would only re-pin it.
 * - **An unnamed session lands on the default profile, persistently, and
 *   every session reports both the profile it is in and whether it is
 *   `ephemeral`.** `profile` is never `null`: an ephemeral session is a
 *   session in some profile's browser, not a session in none.
 * - **Persistent means shared and kept; ephemeral means apart and discarded.**
 *   Two persistent sessions in one profile see each other's cookies; an
 *   ephemeral session sees none of them, not even its own profile's, and a
 *   profile that keeps nothing opens nothing else.
 * - **A name that is not there fails**, typed, carrying the name — and no
 *   command could have created it, so the failure is the only answer left.
 *
 * ## Deliberately not written here
 *
 * **Every rule about *loading* the directory** — the name taken from the file
 * name, `*.yaml` only, files read in sorted order, and the refusals: a stem
 * that is not a usable profile name, a `defaultProfile` naming no file, and a
 * directory with nothing in it. Each needs a **daemon per case**, which this
 * suite deliberately does not have: one daemon per process is what keeps it to
 * thirty seconds, and the unit of isolation is a session. They live in
 * `umwelt-server`'s `UmweltConfigTest`, over `loadUmweltConfig` against a temp
 * directory — no daemon, no browser, one refusal per test.
 *
 * What a *hosted* listing must hold — at least one profile, exactly one
 * default — is not here either: reaching it means naming no deployment, which
 * is `DeploymentSequenceTest`'s subject, so it is asserted there.
 */
class ProfileSequenceTest {

    private val site = UmweltUnderTest.site.baseUrl
    private val daemonUrl = UmweltUnderTest.daemon.baseUrl
    private val cli = UmweltCli()

    @AfterTest
    fun cleanUp() {
        // sessions only: the profile list is fixed at startup, so no sequence
        // can disturb it
        cli.closeEverything(daemonUrl)
    }

    @Test
    fun `should list the profiles the daemon was configured with`() = runTest {

        // one entry per file in `profiles/`, named after the file and in sorted
        // order — the same order the directory was read in, so a listing and an
        // `ls` agree. The description is the file's own words, relayed verbatim:
        // it is what an agent reads to choose, so the daemon adds nothing to it.
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
    }

    @Test
    fun `should open a session in a configured profile`() = runTest {

        // a persistent profile is its own Chrome process, with its own
        // user-data-dir; the record says which profile the session is in and
        // that it keeps its state, so an agent never has to remember what it
        // asked for
        val session = cli.umwelt("--api-base=$daemonUrl session new --profile e2e") should {
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

        cli.umwelt("--api-base=$daemonUrl goto -s $sid $site/article.html") should {
            have(exitCode == 0)
            out sameAsJson """
                {
                  "type": "Navigation",
                  "navigation": {
                    "url": "$site/article.html",
                    "status": 200,
                    "settled": true,
                    "title": "The Article",
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
                title: The Article
                status: 200
                ---
                
                # The Article
                
                Umwelt is the world as an organism perceives it.
                
                [the report as data](ref:1:/report.csv)
                
                [download the table](ref:2:/attachment.csv)
                
            """.trimIndent()
        }
    }

    @Test
    fun `should say where each session landed and whether it keeps anything`() = runTest {

        // nothing named: the default profile, persistently — the one the user
        // signed into on their own machine
        val unnamed = cli.umwelt("--api-base=$daemonUrl session new") should {
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

        // the incognito window of the same profile: its browser, none of its
        // cookies
        val incognito = cli.umwelt("--api-base=$daemonUrl session new --ephemeral") should {
            have(exitCode == 0)
            out sameAsJson """
                {
                  "type": "SessionOpened",
                  "sessionId": "$sessionId",
                  "profile": "e2e",
                  "ephemeral": true
                }
            """.trimIndent()
        }

        // a profile that keeps nothing opens nothing but ephemeral sessions —
        // asked or not, and the record says so rather than leaving the caller
        // to assume what it did not ask about
        val scraped = cli.umwelt("--api-base=$daemonUrl session new --profile e2e-scraper") should {
            have(exitCode == 0)
            out sameAsJson """
                {
                  "type": "SessionOpened",
                  "sessionId": "$sessionId",
                  "profile": "e2e-scraper",
                  "ephemeral": true
                }
            """.trimIndent()
        }

        // which profile each session runs in, and whether it keeps its state,
        // is what an agent driving several of them has to be able to read back
        cli.umwelt("--api-base=$daemonUrl session list") should {
            have(exitCode == 0)
            out sameAsJson """
                {
                  "type": "SessionList",
                  "sessions": [
                    {
                      "id": "${unnamed.sessionId}",
                      "profile": "e2e",
                      "ephemeral": false
                    },
                    {
                      "id": "${incognito.sessionId}",
                      "profile": "e2e",
                      "ephemeral": true
                    },
                    {
                      "id": "${scraped.sessionId}",
                      "profile": "e2e-scraper",
                      "ephemeral": true
                    }
                  ]
                }
            """.trimIndent()
        }
    }

    @Test
    fun `should share state within a persistent profile and keep ephemeral sessions apart`() = runTest {

        // what `persistent` and `ephemeral` mean, observed rather than
        // declared: the fixture counts visits in a cookie, so two persistent
        // sessions in one profile continue one count, while every ephemeral
        // session starts at one — a fresh context each, with nothing carried
        // in, not even from the profile whose browser it runs in
        val first = (cli.umwelt("--api-base=$daemonUrl session new --profile e2e") should {
            have(exitCode == 0)
        }).sessionId
        cli.umwelt("--api-base=$daemonUrl goto -s $first $site/visits.html") should { have(exitCode == 0) }
        val firstDump = cli.umwelt("--api-base=$daemonUrl dump -s $first")
        firstDump should { have(exitCode == 0) }
        val visits = firstDump.out.visitCount()

        val second = (cli.umwelt("--api-base=$daemonUrl session new --profile e2e") should {
            have(exitCode == 0)
        }).sessionId
        cli.umwelt("--api-base=$daemonUrl goto -s $second $site/visits.html") should { have(exitCode == 0) }
        cli.umwelt("--api-base=$daemonUrl dump -s $second") should {
            have(exitCode == 0)
            out sameAsMarkdown visitsPage(visits + 1)
        }

        // the same page, ephemerally: the persistent profile's own incognito
        // session, twice, and the profile that keeps nothing
        listOf(
            "session new --profile e2e --ephemeral",
            "session new --profile e2e --ephemeral",
            "session new --profile e2e-scraper",
        ).forEach { command ->
            val sid = (cli.umwelt("--api-base=$daemonUrl $command") should {
                have(exitCode == 0)
            }).sessionId
            cli.umwelt("--api-base=$daemonUrl goto -s $sid $site/visits.html") should { have(exitCode == 0) }
            cli.umwelt("--api-base=$daemonUrl dump -s $sid") should {
                have(exitCode == 0)
                out sameAsMarkdown visitsPage(1)
            }
        }

        // and nothing an ephemeral session did reached the profile: its count
        // continues from the persistent sessions alone
        val third = (cli.umwelt("--api-base=$daemonUrl session new --profile e2e") should {
            have(exitCode == 0)
        }).sessionId
        cli.umwelt("--api-base=$daemonUrl goto -s $third $site/visits.html") should { have(exitCode == 0) }
        cli.umwelt("--api-base=$daemonUrl dump -s $third") should {
            have(exitCode == 0)
            out sameAsMarkdown visitsPage(visits + 2)
        }
    }

    @Test
    fun `should fail on a profile name that does not exist`() = runTest {

        // a typo must never land in a blank profile mistaken for a signed-in
        // one, so this fails rather than creating it — and with no command that
        // could have created it, the failure is the *only* answer left. The
        // error is typed because the offending name is what the agent needs
        // next: an opaque 502 (which is what the bare IllegalArgument from
        // umwelt-core used to become) says nothing about which profile was
        // wrong.
        cli.umwelt("--api-base=$daemonUrl session new --profile no-such-profile") should {
            have(exitCode == 1)
            out sameAsJson """
                {
                  "type": "Error",
                  "code": 1,
                  "message": "no browser profile named 'no-such-profile'",
                  "error": {
                    "type": "ProfileNotFound",
                    "name": "no-such-profile",
                    "message": "no browser profile named 'no-such-profile'"
                  }
                }
            """.trimIndent()
        }
    }

    /** The count the visits fixture printed, read out of its dump. */
    private fun String.visitCount(): Int =
        Regex("visit number (\\d+)").find(this)?.groupValues?.get(1)?.toInt()
            ?: error("no visit count in:\n$this")

}
