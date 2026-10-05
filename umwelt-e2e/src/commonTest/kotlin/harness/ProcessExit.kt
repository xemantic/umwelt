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

/**
 * Runs [hook] when this test process exits.
 *
 * The suite shares one daemon and one fixture site across every test class
 * (a Chrome launch costs seconds), and `kotlin.test` has nowhere to hang that
 * teardown: common `kotlin.test` declares `Test`, `Ignore`, `BeforeTest` and
 * `AfterTest` and nothing else — no suite- or class-level hook. JUnit's
 * `@AfterAll` would only ever work on the JVM half of this module.
 *
 * So the lifetime of the shared resources is the lifetime of the *process*,
 * and this is the one platform-specific thing the e2e suite needs.
 * The order hooks run in is unspecified — the site and the daemon are
 * independent — but one that throws never strands the rest.
 *
 * Neither platform's hook survives `SIGKILL`; a run killed that hard leaks the
 * headful Chrome, which is why [UmweltDaemon.start] drops its profile
 * directory rather than trusting the run before it to have cleaned up.
 */
expect fun onProcessExit(hook: () -> Unit)
