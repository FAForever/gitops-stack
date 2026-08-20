@file:Suppress("PackageDirectoryMismatch")

package com.faforever

import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream

private val log = LoggerFactory.getLogger("scmap-path-fixer")

/** Every .scmap file starts with these 16 bytes. */
private val SCMAP_HEADER = byteArrayOf(
    0x4D, 0x61, 0x70, 0x1A,                                      // "Map\x1A"
    0x02, 0x00, 0x00, 0x00,                                      // 2
    0xED.toByte(), 0xFE.toByte(), 0xEF.toByte(), 0xBE.toByte(),  // 0xBEEFFEED, little endian
    0x02, 0x00, 0x00, 0x00,                                      // 2
)

fun ByteArray.isScmap(): Boolean =
    size >= SCMAP_HEADER.size && SCMAP_HEADER.indices.all { this[it] == SCMAP_HEADER[it] }

/** Case insensitive raw byte search for an ASCII needle. */
fun ByteArray.containsAscii(needle: String): Boolean {
    val bytes = needle.lowercase().toByteArray(Charsets.ISO_8859_1)
    if (bytes.size > size) return false
    outer@ for (i in 0..size - bytes.size) {
        for (j in bytes.indices) {
            var b = this[i + j].toInt() and 0xFF
            if (b in 0x41..0x5A) b += 0x20                       // ASCII toLowerCase
            if (b != (bytes[j].toInt() and 0xFF)) continue@outer
        }
        return true
    }
    return false
}

/**
 * Whether the map references its own folder, so we only parse map files that actually need
 * the fix. Missions whose .scmap contains no such path (and the placeholder files that are
 * not maps at all) are passed through untouched.
 *
 * Deliberately without a trailing slash, unlike the leftover scan in [fixScmapPaths]. This
 * one has to match `/maps/<folder>.v0003/...` as well - a self reference that already
 * carries a version - so that such a file reaches the parser, where the old suffix is
 * stripped instead of a second one being stacked on top. The leftover scan wants the exact
 * opposite: it asserts that no *unversioned* reference survived, so it needs the trailing
 * slash to not match the versioned form it just wrote.
 */
fun ByteArray.referencesOwnMapFolder(folderName: String) = containsAscii("/maps/$folderName")

/** A .scmap only needs rewriting when it is one and it points into its own folder. */
fun ByteArray.needsPathFix(folderName: String) = isScmap() && referencesOwnMapFolder(folderName)

/**
 * Result of [fixScmapPaths].
 *
 * @property bytes the rewritten map file
 * @property rewritten every path that received the version, as written into [bytes]
 * @property skipped paths inside /maps that lead to another folder and were left alone
 */
data class ScmapPathFixResult(
    val bytes: ByteArray,
    val rewritten: List<String>,
    val skipped: List<String>,
)

/**
 * Rewrites the map folder segment of every path embedded in a .scmap so that it carries
 * the release version:
 *
 *     /maps/faf_coop_operation_blockade/env/layers/sand.dds
 *  -> /maps/faf_coop_operation_blockade.v0004/env/layers/sand.dds
 *
 * Only paths that point at [folderName] itself are touched. Paths pointing somewhere else
 * are left alone and reported in [ScmapPathFixResult.skipped] - several missions deliberately
 * reference a base game map
 * (`/maps/X1CA_001/X1CA_001.scmap`) or another map's texture, and versioning those would
 * break them.
 *
 * A .scmap is tightly packed with no field names, so every field has to be read and
 * written back in the exact same order. Most strings are null terminated; decal texture
 * paths are the exception and carry a leading int with their length. Arrays of structs
 * (decals, waves, props) start with an int count. Which sections exist depends on the map
 * file version - the coop missions use 53, 56 and 60.
 *
 * Ported from speed2's `sc_map_parser.gd` (`fix_paths`).
 *
 * @param version release version, or a negative value to copy the file without changes
 *                (used as a self check: the parser then has to reproduce the input byte
 *                for byte)
 * @throws IllegalArgumentException if the file is not a .scmap
 * @throws IllegalStateException if the result fails verification
 */

fun fixScmapPaths(bytes: ByteArray, folderName: String, version: Int): ScmapPathFixResult {
    require(bytes.isScmap()) { "$folderName: not a .scmap file, header mismatch" }

    val suffix = if (version >= 0) ".v%04d".format(version) else ""
    val rewriter = ScmapRewriter(bytes, folderName, suffix)
    val rewrittenBytes = rewriter.rewrite()
    val result = ScmapPathFixResult(rewrittenBytes, rewriter.rewritten.toList(), rewriter.skipped.distinct())

    if (suffix.isEmpty()) return result

    result.skipped.forEach {
        log.warn("$folderName: path leads outside the mission folder, left unchanged: $it")
    }
    log.info(
        "$folderName: rewrote {} path(s) in the map file, {} byte(s) added",
        result.rewritten.size, rewrittenBytes.size - bytes.size
    )

    // the file may only have grown by exactly what went into the paths - anything else means
    // the structural pass lost or invented bytes somewhere between them
    check(rewrittenBytes.size - bytes.size == rewriter.addedBytes) {
        "$folderName: byte delta ${rewrittenBytes.size - bytes.size} does not match the " +
            "${rewriter.addedBytes} byte(s) added to paths"
    }
    // no unversioned reference to the mission folder may survive. This is the one check that
    // catches a path the parser never recognised as a path in the first place - the delta
    // accounting and the round trip below are both blind to a rewrite that simply did not
    // happen, which is exactly the bug this whole class exists to fix.
    check(!rewrittenBytes.containsAscii("/maps/$folderName/")) {
        "$folderName: the rewritten .scmap still contains unversioned /maps/$folderName/ " +
            "path(s), refusing to ship it"
    }
    // the rewritten file has to parse again and come out byte identical
    val verified = ScmapRewriter(rewrittenBytes, folderName, "").rewrite()
    check(verified.contentEquals(rewrittenBytes)) {
        "$folderName: rewritten .scmap does not round trip, refusing to ship it"
    }
    return result
}

private class ScmapRewriter(
    private val src: ByteArray,
    folderName: String,
    private val suffix: String,
) {
    private val folder = folderName.lowercase()
    private val out = ByteArrayOutputStream(src.size + 1024)
    private var pos = 0

    val rewritten = mutableListOf<String>()
    val skipped = mutableListOf<String>()
    var addedBytes = 0
        private set

    fun rewrite(): ByteArray {
        copy(16)                                        // header
        copy(14)                                        // float size x, y + 6 padding bytes
        transferSizedChunk()                            // preview image

        val version = readInt()
        val width = readInt()
        val height = readInt()
        writeInt(version)
        writeInt(width)
        writeInt(height)
        copy(4)                                         // height scale

        copy((width + 1) * (height + 1) * 2)            // heightmap
        if (version >= 56) copy(1)                      // padding after the heightmap

        transferString()                                // shader name, not a path
        transferString(true)                            // editor background
        transferString(true)                            // skycube

        if (version >= 56) {
            val cubemaps = readInt()
            writeInt(cubemaps)
            repeat(cubemaps) {
                transferString()                        // name
                transferString(true)                    // path
            }
        } else {
            transferString(true)                        // old format has a single cubemap
        }

        copy(23 * 4)                                    // lighting
        copy(1 + 23 * 4)                                // water flag + water settings
        transferString(true)                            // water cubemap
        transferString(true)                            // water ramp
        copy(4 * 4)                                     // wave normal repeats
        repeat(4) {                                     // wave textures
            copy(8)                                     // movement
            transferString(true)
        }

        val waves = readInt()
        writeInt(waves)
        repeat(waves) {
            transferString()                            // texture name, not a path
            transferString()                            // ramp name, not a path
            copy(17 * 4)
        }

        if (version < 56) {
            transferString()                            // always "No Tileset"
            val tilesets = readInt()                    // always 6
            writeInt(tilesets)
            repeat(tilesets) {
                transferString(true)                    // albedo
                transferString(true)                    // normal
                copy(2 * 4)                             // scales
            }
        } else {
            copy(6 * 4)                                 // minimap
            if (version > 56) copy(4)                   // unknown
            repeat(19) {                                // 10 albedo + 9 normal
                transferString(true)
                copy(4)                                 // scale
            }
        }

        copy(8)                                         // 2 unknown values

        val decals = readInt()
        writeInt(decals)
        repeat(decals) {
            copy(8)                                     // id + type
            val textures = readInt()
            writeInt(textures)
            repeat(textures) { transferSizedString() }
            copy(12 * 4)                                // 11 floats + 1 int
        }

        val decalGroups = readInt()
        writeInt(decalGroups)
        repeat(decalGroups) {
            copy(4)                                     // id
            transferString()                            // name, not a path
            val ids = readInt()
            writeInt(ids)
            if (ids > 0) copy(ids * 4)
        }

        copy(8)                                         // int width + height

        val normalMaps = readInt()
        writeInt(normalMaps)
        repeat(normalMaps) { transferSizedChunk() }

        if (version < 56) copy(4)                       // unknown in the old format

        transferSizedChunk()                            // texture mask low
        if (version >= 56) transferSizedChunk()         // texture mask high

        val waterMaps = readInt()
        writeInt(waterMaps)
        repeat(waterMaps) { transferSizedChunk() }

        val halfSize = (width / 2) * (height / 2)
        repeat(3) { copy(halfSize) }                    // water foam, flatness, depth bias
        copy(width * height)                            // terrain types

        if (version <= 52) copy(2)                      // unknown in the oldest format

        if (version >= 60) {
            copy(16 * 4)                                // skybox settings
            transferString(true)                        // albedo
            transferString(true)                        // glow
            val planets = readInt()
            writeInt(planets)
            repeat(planets) { copy(10 * 4) }
            copy(3 + 4 * 4)                             // sky mid color + cirrus
            transferString(true)                        // cirrus texture
            val cirrusLayers = readInt()
            writeInt(cirrusLayers)
            repeat(cirrusLayers) { copy(5 * 4) }
            copy(4)                                     // one more setting
        }

        val props = readInt()
        writeInt(props)
        repeat(props) {
            transferString(true)                        // blueprint path
            copy(15 * 4)
        }

        if (pos < src.size) {
            log.warn("$folder: {} trailing byte(s) after the props, copied unchanged", src.size - pos)
            copy(src.size - pos)
        }
        return out.toByteArray()
    }

    /**
     * If [path] points into this mission's own folder inside /maps, the version is added to
     * the folder name. Only the `/maps/<folder>.vNNNN` head is lower cased; everything
     * after it keeps the casing it had.
     *
     * `fix_paths` lower cases the whole path, and this deliberately does not. Two of the
     * four affected missions reference their decals in exactly the casing the files carry
     * on disk - `env/decals/FAF_Coop_Operation_Golden_Crystals_nm.dds` and the three like
     * it - and lower casing those introduces a mismatch that is not in the source data.
     * The other 28 paths already point at lower case names while the files are mixed case,
     * so a lower cased tail buys nothing there either.
     *
     * Everything else is returned byte for byte as it came in, including paths under /maps
     * that lead to another folder. Those are shipped unparsed today and work, so there is
     * nothing to gain by touching them - and something to lose: this method is also handed
     * whatever a mis-framed [transferString] believes to be a string, and lower casing that
     * would silently flip bytes inside binary data without changing the length, which no
     * check in [fixScmapPaths] could catch.
     */
    private fun addVersion(path: String): String {
        if (suffix.isEmpty()) return path
        val match = MAP_PATH.matchEntire(path) ?: return path
        val (prefix, segment, rest) = match.destructured

        val bare = segment.lowercase().replace(VERSIONED, "")
        if (bare != folder) {
            skipped += path
            return path
        }

        val fixed = (prefix + bare + suffix).lowercase() + rest
        addedBytes += fixed.length - path.length
        rewritten += fixed
        return fixed
    }

    /** Null terminated string. [isPath] marks the ones that may need the version. */
    private fun transferString(isPath: Boolean = false) {
        val start = pos
        while (pos < src.size && src[pos] != 0.toByte()) pos++
        val raw = String(src, start, pos - start, Charsets.ISO_8859_1)
        pos++                                           // null terminator
        val value = if (isPath) addVersion(raw) else raw
        out.write(value.toByteArray(Charsets.ISO_8859_1))
        out.write(0)
    }

    /** Decal texture paths are not null terminated but prefixed with their length. */
    private fun transferSizedString() {
        val length = readInt()
        val raw = String(src, pos, length, Charsets.ISO_8859_1)
        pos += length
        val encoded = addVersion(raw).toByteArray(Charsets.ISO_8859_1)
        writeInt(encoded.size)
        out.write(encoded)
    }

    /** Chunk of bytes whose length is read from the file, used for embedded textures. */
    private fun transferSizedChunk() {
        val length = readInt()
        writeInt(length)
        if (length > 0) copy(length)
    }

    private fun readInt(): Int {
        val v = (src[pos].toInt() and 0xFF) or
            ((src[pos + 1].toInt() and 0xFF) shl 8) or
            ((src[pos + 2].toInt() and 0xFF) shl 16) or
            ((src[pos + 3].toInt() and 0xFF) shl 24)
        pos += 4
        return v
    }

    private fun writeInt(value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }

    private fun copy(length: Int) {
        out.write(src, pos, length)
        pos += length
    }

    private companion object {
        val VERSIONED = Regex("""\.v\d{4}$""")

        /**
         * `/maps/<folder>/<something>`, with the three parts captured separately.
         *
         * The leading slash is required, so this matches exactly what
         * [referencesOwnMapFolder] searches for. All 40 paths embedded in the missions carry
         * it; the slashless form was only ever accepted because `fix_paths` splits on `/`
         * discarding empty segments, which makes `maps/x/y` and `/maps/x/y` indistinguishable
         * to it. Accepting a form the gate does not detect means the gate can wave a file
         * through that the rewriter would have changed.
         */
        val MAP_PATH = Regex("""^(/maps/)([^/]+)(/.*)$""", RegexOption.IGNORE_CASE)
    }
}
