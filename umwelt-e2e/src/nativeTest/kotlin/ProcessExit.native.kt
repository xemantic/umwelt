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

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import platform.posix.SIGINT
import platform.posix.SIGTERM
import platform.posix.atexit
import platform.posix.exit
import platform.posix.signal

// `atexit` takes a C function pointer, and `staticCFunction` only accepts a
// reference to a top-level function that captures nothing — so the hooks
// themselves have to live in a global the drain reads back. Native tests run
// single-threaded on the main thread, which is also the thread that exits, so
// no synchronization is needed here.
private val hooks = mutableListOf<() -> Unit>()

private var installed = false

private fun drain() {
    // last registered, first stopped — and a hook that throws must not strand
    // the ones after it, or a failure to stop the site would leak Chrome
    hooks.asReversed().forEach { runCatching { it() } }
    hooks.clear()
}

/**
 * A `SIGINT`/`SIGTERM` handler, because `atexit` fires only on a *normal*
 * exit — unlike a JVM shutdown hook, which the JVM also runs on a signal.
 * Without this a Ctrl-C'd native run would leave Chrome and its user-data-dir
 * behind. Turning the signal into an `exit()` is what gets the `atexit`
 * handler above run, with the conventional 128 + signal status.
 */
private fun onSignal(signalNumber: Int) {
    exit(128 + signalNumber)
}

@OptIn(ExperimentalForeignApi::class)
actual fun onProcessExit(hook: () -> Unit) {
    hooks += hook
    if (!installed) {
        installed = true
        atexit(staticCFunction(::drain))
        signal(SIGINT, staticCFunction(::onSignal))
        signal(SIGTERM, staticCFunction(::onSignal))
    }
}
