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

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlin.random.Random

/**
 * The multiplatform sliver of `java.io.File` this suite actually used —
 * kotlinx-io, the same filesystem API the CLI itself writes `-o` targets with.
 */

/**
 * A path in the system temp directory that nothing has created yet — the
 * `File.createTempFile(…).also { it.delete() }` this replaces, without the
 * create-then-delete dance kotlinx-io gives no reason for.
 *
 * The name is randomized rather than sequential because the whole suite shares
 * one temp directory and a rerun must not pick up the leftovers of the last.
 */
fun tempPath(prefix: String, suffix: String): Path = Path(
    SystemTemporaryDirectory,
    "$prefix-${Random.nextLong().toULong().toString(radix = 16)}$suffix"
)

fun Path.readText(): String =
    SystemFileSystem.source(this).buffered().use { it.readString() }

fun Path.deleteIfExists() {
    if (SystemFileSystem.exists(this)) SystemFileSystem.delete(this)
}

/**
 * Removes a directory and everything below it.
 *
 * kotlinx-io has no equivalent of `File.deleteRecursively`, and
 * `SystemFileSystem.delete` refuses a non-empty directory, so the walk is
 * ours. [SystemFileSystem.list] throws on anything that is not a directory,
 * hence the metadata probe rather than a `runCatching`.
 *
 * Entries are removed with [deleteEntry], not `SystemFileSystem.delete`:
 * Chrome's `SingletonLock`, `SingletonCookie` and `RunningChromeVersion` in a
 * `--user-data-dir` are symlinks to targets that do not exist, and kotlinx-io
 * guards its delete with an `exists` check that follows the link — so it
 * silently skipped them, the directory stayed non-empty, and its own delete
 * failed. That broke [UmweltDaemon.start] in exactly the case its wipe exists
 * for: a profile Chrome did not clean up after.
 */
fun Path.deleteRecursively() {
    if (SystemFileSystem.metadataOrNull(this)?.isDirectory == true) {
        SystemFileSystem.list(this).forEach { it.deleteRecursively() }
    }
    deleteEntry()
}

/**
 * Removes this one file, symlink or empty directory without following a
 * symlink, and does nothing if there is nothing at this path. kotlinx-io has
 * no call that can do this for a dangling symlink — see [deleteRecursively].
 */
expect fun Path.deleteEntry()

fun Path.readBytes(): ByteArray =
    SystemFileSystem.source(this).buffered().use { it.readByteArray() }
