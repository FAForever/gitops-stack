@file:Suppress("PackageDirectoryMismatch")

package com.faforever

import java.io.File
import kotlin.system.exitProcess

/**
 * Runs [fixScmapPaths] over every map file in a checkout of the coop missions and fails if
 * anything is off. Meant for CI, so a change to the path rewriting cannot quietly break a
 * map file.
 *
 * Rewriting with a negative version has to reproduce the input byte for byte. That covers
 * every mission, not only the handful that need the fix, but it is a weaker statement than
 * it looks: with no suffix the rewriter is a byte copier by construction, so the identity
 * check really only says that no read ran off the end of the file. The checks that carry
 * weight are the ones [fixScmapPaths] makes on the rewritten output, and the detection
 * cross check below.
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
    val rewrittenFolders = mutableListOf<String>()
    val versions = mutableMapOf<Int, Int>()
    var placeholders = 0
    var identical = 0

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
        val version = formatVersion(bytes)
        versions[version] = (versions[version] ?: 0) + 1

        // The rewriter only knows the sections of the versions that are actually in this
        // repo. Nothing new will appear here - maps are written as v60, occasionally still
        // as v56 - so an unexpected version means a section boundary the rewriter has never
        // been run against, and it has to be looked at rather than parsed on a guess.
        if (version !in KNOWN_VERSIONS) {
            failures += "$folder: unexpected .scmap version $version, the rewriter only " +
                "covers ${KNOWN_VERSIONS.joinToString()}"
        }

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

        // referencesOwnMapFolder is both the gate and the thing under test here: if it ever
        // regressed to false every check below would be skipped and CI would still pass. So
        // it is compared against a second, independent implementation.
        val detected = bytes.referencesOwnMapFolder(folder)
        val expected = String(bytes, Charsets.ISO_8859_1).contains("/maps/$folder", ignoreCase = true)
        if (detected != expected) {
            failures += "$folder: referencesOwnMapFolder says $detected, a plain text search says $expected"
        }

        if (detected) {
            try {
                // delta accounting, leftover scan and round trip are asserted in fixScmapPaths
                val fix = fixScmapPaths(bytes, folder, 4)
                rewrittenFolders += folder
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

        println("%-44s %-8s %-10s %s".format(folder, "v$version", bytes.size, notes.joinToString(", ")))
    }

    // a run in which nothing at all was rewritten proves nothing about the rewriting
    if (rewrittenFolders.isEmpty()) {
        failures += "no map file references its own folder, so nothing was rewritten - " +
            "either the missions changed or the detection is broken"
    }

    println()
    println("$identical of ${maps.size - placeholders} map file(s) reproduced byte for byte")
    println("${rewrittenFolders.size} map file(s) reference their own folder and were rewritten:")
    rewrittenFolders.forEach { println("  $it") }
    println("$placeholders placeholder file(s) skipped")
    println("format versions: " + versions.toSortedMap().entries.joinToString { "v${it.key} x${it.value}" })

    if (failures.isNotEmpty()) {
        println()
        failures.forEach { println("FAIL  $it") }
        println("${failures.size} failure(s)")
        exitProcess(1)
    }
    println("all good")
}

private val DOUBLE_VERSION = Regex("""\.v\d{4}\.v\d{4}""")

/** The only .scmap format versions the coop missions contain. */
private val KNOWN_VERSIONS = setOf(53, 56, 60)

/**
 * Map file format version, stored behind the preview image. A valid header is only 16 bytes,
 * so a file can pass [isScmap] and still be too short to hold one - that is reported as an
 * unknown version rather than taking the whole run down.
 */
private fun formatVersion(bytes: ByteArray): Int {
    fun int(at: Int) = (bytes[at].toInt() and 0xFF) or
        ((bytes[at + 1].toInt() and 0xFF) shl 8) or
        ((bytes[at + 2].toInt() and 0xFF) shl 16) or
        ((bytes[at + 3].toInt() and 0xFF) shl 24)
    return runCatching { int(34 + int(30)) }.getOrDefault(-1)
}
