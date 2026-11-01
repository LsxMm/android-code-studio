/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.utils

/**
 * Validates the name of a project directory.
 *
 * Project names are **not** restricted to ASCII any more: a project may be called `我的应用` or `App
 * 演示` just as well as `MyApp`. Only characters that cannot appear in a file name (on any of the
 * platforms the IDE runs on) and a couple of pathological cases are rejected.
 */
object ProjectNameValidator {

  /** Maximum length of a project name. Keeps generated paths well below file system limits. */
  const val MAX_LENGTH = 64

  /** Characters that cannot be used in a directory name. */
  private val ILLEGAL_CHARS = charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')

  /**
   * Returns `true` if [name] can be used as a project (directory) name.
   *
   * Note that the name is expected to be trimmed by the caller.
   */
  fun isValid(name: String): Boolean {
    if (name.isEmpty() || name.length > MAX_LENGTH) return false

    // Windows does not allow a trailing dot, and it would break path handling anyway
    if (name.endsWith(".")) return false

    // Avoid "." / ".." and control characters
    if (name == "." || name == "..") return false

    return name.none { it in ILLEGAL_CHARS || it.isISOControl() }
  }
}
