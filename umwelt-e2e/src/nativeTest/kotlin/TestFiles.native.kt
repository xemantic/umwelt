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
import kotlinx.io.IOException
import kotlinx.io.files.Path
import platform.posix.ENOENT
import platform.posix.errno
import platform.posix.remove

/** POSIX `remove` unlinks a symlink itself, and `rmdir`s an empty directory. */
@OptIn(ExperimentalForeignApi::class)
actual fun Path.deleteEntry() {
    if (remove(toString()) != 0 && errno != ENOENT) {
        throw IOException("cannot delete $this (errno $errno)")
    }
}
