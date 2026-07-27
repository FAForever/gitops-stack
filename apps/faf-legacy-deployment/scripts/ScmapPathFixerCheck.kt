@file:Suppress("PackageDirectoryMismatch")

package com.faforever

import java.io.File
import kotlin.system.exitProcess

/**
 * Runs [fixScmapPaths] over every map file in a checkout of the coop missions and fails if
 * anything is off. Meant for CI, so a change to the path rewriting cannot quietly break a
 * map file.
 *
 * The important one is the identity check: rewriting with a negative version has to
 * reproduce the input byte for byte. It covers every mission, not only the handful that
 * need the fix, and therefore also the old file formats. Scoping a section to the wrong
 * file version breaks exactly those and nothing else.
 *
 * Point MAPS_REPO at a checkout of https://github.com/FAForever/faf-coop-maps.
 */
fun main() {
    val repo = File(System.getenv("MAPS_REPO") ?: "/tmp/faf-coop-maps")
    require(repo.isDirectory) { "MAPS_REPO does not point at a directory: $repo" }

    val maps = repo.walkTopDown()
        .filter { it.isFile && it.name.lowercase().endsWith(".scmap") }
        .sortedBy { it.path }
        .toList()
    require(maps.isNotEmpty()) { "no .scmap files found below $repo" }

    val failures = mutableListOf<String>()
    var placeholders = 0
    var identical = 0
    var rewrittenMaps = 0

    println("%-44s %-8s %-10s %s".format("mission", "format", "size", "result"))

    maps.forEach { file ->
        val folder = file.parentFile.name
        val bytes = file.readBytes()

        if (!bytes.isScmap()) {
            placeholders++
            println("%-44s %-8s %-10s %s".format(folder, "-", bytes.size, "not a map file, skipped"))
            return@forEach
        }

        val notes = mutableListOf<String>()

        try {
            val copy = fixScmapPaths(bytes, folder, -1).bytes
            if (copy.contentEquals(bytes)) {
                identical++
                notes += "identical"
            } else {
                failures += "$folder: copy is ${copy.size} bytes, input is ${bytes.size}"
                notes += "NOT IDENTICAL"
            }
        } catch (e: Exception) {
            failures += "$folder: ${e.message}"
            notes += "FAILED: ${e.message}"
        }

        if (bytes.referencesOwnMapFolder(folder)) {
            try {
                // delta accounting and the round trip are asserted inside fixScmapPaths
                val fix = fixScmapPaths(bytes, folder, 4)
                rewrittenMaps++
                notes += "${fix.rewritten.size} path(s) rewritten"

                fix.rewritten.filterNot { it.contains(".v0004/") }
                    .forEach { failures += "$folder: not versioned: $it" }
                fix.rewritten.filter { it != it.lowercase() }
                    .forEach { failures += "$folder: not lower cased: $it" }
                fix.rewritten.filter { DOUBLE_VERSION.containsMatchIn(it) }
                    .forEach { failures += "$folder: version added twice: $it" }
            } catch (e: Exception) {
                failures += "$folder: ${e.message}"
                notes += "FAILED: ${e.message}"
            }
        }

        println("%-44s %-8s %-10s %s".format(folder, "v" + formatVersion(bytes), bytes.size, notes.joinToString(", ")))
    }

    println()
    println("$identical of ${maps.size - placeholders} map file(s) reproduced byte for byte")
    println("$rewrittenMaps map file(s) reference their own folder and were rewritten")
    println("$placeholders placeholder file(s) skipped")

    if (failures.isNotEmpty()) {
        println()
        failures.forEach { println("FAIL  $it") }
        println("${failures.size} failure(s)")
        exitProcess(1)
    }
    println("all good")
}

private val DOUBLE_VERSION = Regex("""\.v\d{4}\.v\d{4}""")

/** Map file format version, stored behind the preview image. */
private fun formatVersion(bytes: ByteArray): Int {
    fun int(at: Int) = (bytes[at].toInt() and 0xFF) or
        ((bytes[at + 1].toInt() and 0xFF) shl 8) or
        ((bytes[at + 2].toInt() and 0xFF) shl 16) or
        ((bytes[at + 3].toInt() and 0xFF) shl 24)
    return int(34 + int(30))
}
