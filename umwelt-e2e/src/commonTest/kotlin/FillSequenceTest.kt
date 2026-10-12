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
import com.xemantic.umwelt.e2e.harness.tagRef
import kotlinx.coroutines.test.runTest
import org.intellij.lang.annotations.Language
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * The `fill` command: a batch of `type` operations over multiple plain-text
 * fields in one round-trip, without re-dumping between them.
 *
 * `type` on a plain-text input does not change the DOM — the refs it touched
 * stay valid, and re-dumping before the next `type` only to get the same refs
 * back wastes a round-trip. `fill` takes a list of `ref=value` pairs and
 * applies them all server-side within a single lock acquisition, so no
 * re-dump is needed between plain-field fills.
 *
 * This is not a replacement for `type`: autocomplete fields, date pickers, and
 * React-controlled inputs that open a dropdown on keystroke still need the
 * individual type → dump → pick cycle. `fill` is for the common case of a
 * static contact/search form where every field is a plain text box.
 */
class FillSequenceTest {

    private val site = UmweltUnderTest.site.baseUrl
    private val daemonUrl = UmweltUnderTest.daemon.baseUrl
    private val cli = UmweltCli()

    @AfterTest
    fun cleanUp() {
        cli.closeEverything(daemonUrl)
    }

    /** The contact page as an agent perceives it, with the current field values. */
    @Language("markdown")
    @Suppress("HtmlUnknownAttribute")
    private fun contactPage(
        firstName: String = "",
        lastName: String = "",
        email: String = "",
    ) = """
        ---
        lang: en
        title: Contact
        status: 200
        ---

        # Contact

        <form action="/results" method="get">
        <input id="first" type="text" name="first"${if (firstName.isNotEmpty()) " value=\"$firstName\"" else ""} aria-label="first name" ref="1">
        <input id="last" type="text" name="last"${if (lastName.isNotEmpty()) " value=\"$lastName\"" else ""} aria-label="last name" ref="2">
        <input id="email" type="email" name="email"${if (email.isNotEmpty()) " value=\"$email\"" else ""} aria-label="email" ref="3">
        <input type="submit" value="Submit" ref="4">
        </form>

    """.trimIndent()

    /**
     * All three fields filled in one command with no dump in between — the
     * raison d'être of `fill`. A single dump after confirms all values landed.
     */
    @Test
    fun `should fill multiple fields in a single round-trip`() = runTest {

        val sid = (cli.umwelt("--api-base=$daemonUrl session new") should { have(exitCode == 0) }).sessionId
        cli.umwelt("--api-base=$daemonUrl goto -s $sid $site/contact.html") should { have(exitCode == 0) }

        val form = cli.umwelt("--api-base=$daemonUrl dump -s $sid")
        form should {
            have(exitCode == 0)
            out sameAsMarkdown contactPage()
        }
        val firstName = form.out.tagRef("aria-label=\"first name\"")
        val lastName = form.out.tagRef("aria-label=\"last name\"")
        val email = form.out.tagRef("aria-label=\"email\"")

        // one invocation fills all three — no dump in between
        cli.umwelt("""--api-base=$daemonUrl fill -s $sid "$firstName=John" "$lastName=Smith" "$email=john@example.com"""") should {
            have(exitCode == 0)
            out sameAsJson """
                {
                  "type": "Filled",
                  "refs": [
                    "$firstName",
                    "$lastName",
                    "$email"
                  ]
                }
            """.trimIndent()
        }

        // re-perceive: all three carry the values fill put there
        cli.umwelt("--api-base=$daemonUrl dump -s $sid") should {
            have(exitCode == 0)
            out sameAsMarkdown contactPage(
                firstName = "John",
                lastName = "Smith",
                email = "john@example.com"
            )
        }
    }

    /**
     * An unknown ref in the middle of a fill fails the whole call — the first
     * bad ref terminates the batch and none of the fields after it are touched.
     * Exit `4` (ReferenceNotFound), the same as `type` on a stale ref.
     */
    @Test
    fun `should refuse a fill that names an unknown ref`() = runTest {

        val sid = (cli.umwelt("--api-base=$daemonUrl session new") should { have(exitCode == 0) }).sessionId
        cli.umwelt("--api-base=$daemonUrl goto -s $sid $site/contact.html") should { have(exitCode == 0) }

        val form = cli.umwelt("--api-base=$daemonUrl dump -s $sid")
        form should { have(exitCode == 0) }
        val firstName = form.out.tagRef("aria-label=\"first name\"")

        // second entry names ref 99 which does not exist on this page
        cli.umwelt("""--api-base=$daemonUrl fill -s $sid "$firstName=John" "99=bad"""") should {
            have(exitCode == 4)
            out sameAsJson """
                {
                  "type": "Error",
                  "code": 4,
                  "message": "no actionable element with ref '99' on the current page",
                  "error": {
                    "type": "ReferenceNotFound",
                    "ref": "99",
                    "message": "no actionable element with ref '99' on the current page"
                  }
                }
            """.trimIndent()
        }
    }

}
