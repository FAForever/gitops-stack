@file:Suppress("PackageDirectoryMismatch")

package com.faforever

import org.slf4j.LoggerFactory
import java.awt.Image
import java.awt.Point
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.awt.image.Raster
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import kotlin.io.path.listDirectoryEntries
import kotlin.math.sqrt

private val log = LoggerFactory.getLogger("coop-map-previews")

/**
 * Map preview images for the co-op missions.
 *
 * The API mints a thumbnail URL for every co-op mission out of the zip name alone
 * (`CoopMapEnricher`): `maps/<folder>.vNNNN.zip` becomes
 * `content.faforever.com/maps/previews/{small,large}/<folder>.vNNNN.png`. It never checks
 * that the file is there, and nothing ever wrote it. The vault upload path generates
 * previews in `MapService.generatePreview`, but co-op missions do not go through it, they
 * come through this deployer. So every client asked for an image that had never been
 * created and showed a hole where the mission preview belongs. Measured against the live
 * CDN on 2026-08-21: of 42 co-op mission folders, zero had the PNG the API points at.
 *
 * What clients fall back to is whatever art happens to sit in the installed map folder,
 * which is why some missions show a preview, some show none, and Coalition's Red Revenge
 * shows a different map: its folder carries a `.png` that does not match its own terrain.
 * The image written here comes out of the map file itself and cannot drift like that.
 *
 * ## Why this does not call the API's generator
 *
 * `com.faforever.commons.map.PreviewGenerator` is the obvious candidate, and it does not
 * survive contact with the co-op missions. Run over all 44 of them, all 44 fail, for two
 * independent reasons:
 *
 * - It overlays mass, hydrocarbon and army markers read from resources under
 *   `images/map_markers`, and the published `com.faforever.commons:data` artifact contains
 *   no images at all, so `ImageIO.read` is handed a null stream: "input == null!". Those
 *   files live in the API's own resources, so the generator only works inside the API.
 * - The marker positions come from evaluating the mission's `_save.lua`, and a third of
 *   the campaign missions fail that outright ("attempt to index ? (a nil value)"): a co-op
 *   save file is a script, not the data table a vault map ships.
 *
 * Both are about the marker overlay, not about the picture. So this takes the picture and
 * leaves the overlay: no marker art to ship, no Lua to evaluate, nothing that can fail per
 * mission. The trade is that a co-op preview shows terrain without the mass and start
 * markers a vault map's preview carries, which is the right way round for a campaign map
 * anyway, where "start positions" are a dozen scripted AI spawns.
 *
 * ## The picture
 *
 * Every `.scmap` embeds its own preview, uncompressed, right behind the header. The layout
 * is the one [ScmapPathFixResult]'s rewriter walks and CI verifies byte for byte: 16 bytes
 * of header, 14 of map size and padding, then a sized chunk holding a 128 byte DDS header
 * followed by raw BGRA pixels.
 */

/** Matches `FafApiProperties.Map.previewSizeSmall`, so co-op and vault previews are one size. */
private const val PREVIEW_SIZE_SMALL = 128

/** Matches `FafApiProperties.Map.previewSizeLarge`. */
private const val PREVIEW_SIZE_LARGE = 512

/** Where the preview chunk's length sits: 16 bytes of header plus 14 of size and padding. */
private const val PREVIEW_CHUNK_OFFSET = 30

/** The DDS header in front of the pixels. Fixed size, and carries nothing we need. */
private const val DDS_HEADER_BYTES = 128

/**
 * Write both preview sizes for one mission.
 *
 * `baseName` is the zip name without its extension, which is the whole of the API's
 * contract: same lower casing, same `.vNNNN` suffix. Deriving it from the zip rather than
 * assembling it again is deliberate, because the CDN is case sensitive where the game
 * engine is not, and a preview under the wrong spelling is a 404 nobody notices.
 *
 * `mapFolder` is the unpacked mission as it goes into the zip. The `.scmap` there is the
 * pre-rewrite copy, which does not matter: the path fixer only touches the internal
 * `/maps/...` strings, and the preview sits in front of them.
 */
fun writeMapPreviews(mapFolder: Path, mapsDir: Path, baseName: String): PreviewSource {
    val (preview, source) = readPreview(mapFolder)
    if (preview != null) {
        writePreview(preview, previewPath(mapsDir, "small", baseName), PREVIEW_SIZE_SMALL)
        writePreview(preview, previewPath(mapsDir, "large", baseName), PREVIEW_SIZE_LARGE)
        return source
    }
    return copyStockMapPreview(mapFolder, mapsDir, baseName)
}

/** Where a mission's preview came from, for the deployment log and the CI check. */
enum class PreviewSource { MAP_FILE, SIDECAR_DDS, STOCK_MAP, NONE }

/**
 * The preview of the stock map a mission is built on, for the one mission that has no
 * picture of its own anywhere.
 *
 * A mission whose `.scmap` is a stand in plays on a map that ships with the game, and it
 * says which one in its `_scenario.lua`: Novax Station Assault names `/maps/X1MP_012`.
 * Those maps are in the vault, so their previews already sit in the directory this writes
 * into, under the lower cased name the CDN insists on. Copying one is not a fallback in
 * the sense of "something rather than nothing": it is the same terrain the mission is
 * played on, from the same generator, produced by the vault instead of here.
 *
 * Only reached when the mission has neither an embedded preview nor a sidecar, which today
 * is exactly one mission of forty four.
 */
private fun copyStockMapPreview(mapFolder: Path, mapsDir: Path, baseName: String): PreviewSource {
    val scenario = mapFolder.listDirectoryEntries()
        .firstOrNull { it.fileName.toString().endsWith("_scenario.lua", ignoreCase = true) }
        ?: return PreviewSource.NONE

    val own = mapFolder.fileName.toString().lowercase()
    val referenced = MAP_REFERENCE.findAll(Files.readString(scenario))
        .map { it.groupValues[1].lowercase() }
        .filter { it != own }
        .distinct()
        .toList()

    for (name in referenced) {
        // Re-rendered, not copied. The oldest previews in the vault are 100 by 100 and 256
        // by 256, from before the current sizes, and copying one of those would leave a
        // co-op mission the odd one out at a size nothing else uses. The large one is the
        // source for both because it has the most pixels to start from.
        val source = listOf("large", "small")
            .map { previewPath(mapsDir, it, name) }
            .firstOrNull { Files.exists(it) }
            ?: continue

        val image = ImageIO.read(source.toFile()) ?: continue
        writePreview(image, previewPath(mapsDir, "small", baseName), PREVIEW_SIZE_SMALL)
        writePreview(image, previewPath(mapsDir, "large", baseName), PREVIEW_SIZE_LARGE)
        log.info("{} has no image of its own; rendered from the preview of {}", baseName, name)
        return PreviewSource.STOCK_MAP
    }
    return PreviewSource.NONE
}

/** `/maps/<name>` as the mission's own scenario spells it. */
private val MAP_REFERENCE = Regex("""/maps/([A-Za-z0-9_]+)""")

/**
 * The best available picture for a mission, and where it came from.
 *
 * The map file wins whenever there is one, because it is the only source that cannot
 * disagree with the terrain being played. The sidecar `.dds` is for the thirteen missions
 * whose map is not in this repository at all: it is the same 256 by 256 BGRA image in the
 * same layout, just kept beside the map instead of inside it.
 *
 * The order matters and is the whole point. Both missions with wrong art in this repository
 * have a *matching pair* of wrong sidecars: Tha Atha Aez's `.dds` and `.png` are from 2017
 * while its map was replaced in 2024, and Red Revenge arrived with art from an earlier draft
 * of its own terrain. Taking the sidecar when a map file exists would keep exactly those two
 * bugs. Taking it when no map file exists costs nothing, because there is nothing to be
 * wrong against.
 */
private fun readPreview(mapFolder: Path): Pair<BufferedImage?, PreviewSource> {
    readEmbeddedPreview(mapFolder)?.let { return it to PreviewSource.MAP_FILE }
    readSidecarPreview(mapFolder)?.let { return it to PreviewSource.SIDECAR_DDS }
    return null to PreviewSource.NONE
}

/**
 * The `.dds` sitting next to the map, decoded the same way as an embedded preview.
 *
 * Twelve of the thirteen missions without a map file ship one, and it is the only thing
 * that stands between them and no preview at all. It is 256 by 256, so those missions end
 * up at the same resolution as everyone else rather than at the 100 by 100 of the `.png`
 * beside it, which is a size no part of FAF asks for any more.
 */
private fun readSidecarPreview(mapFolder: Path): BufferedImage? {
    val dds = mapFolder.listDirectoryEntries()
        .firstOrNull { it.fileName.toString().endsWith(".dds", ignoreCase = true) }
        ?: return null

    val bytes = Files.readAllBytes(dds)
    return runCatching { decodeBgra(bytes, DDS_HEADER_BYTES, bytes.size - DDS_HEADER_BYTES) }
        .onFailure { log.warn("{} is not a usable preview: {}", dds.fileName, it.message) }
        .getOrNull()
}

/**
 * Write the previews only if they are not already there.
 *
 * This is what backfills the missions deployed before previews existed. An unchanged
 * mission is never rezipped, so without this the previews would appear one at a time, each
 * waiting for its mission to be edited. Checking two file names costs nothing on a run
 * where everything is already in place.
 */
fun ensureMapPreviews(mapFolder: Path, mapsDir: Path, baseName: String) {
    val missing = listOf("small", "large")
        .map { previewPath(mapsDir, it, baseName) }
        .filterNot { Files.exists(it) }
    if (missing.isEmpty()) return

    log.info("Backfilling {} preview(s) for {}", missing.size, baseName)
    val source = writeMapPreviews(mapFolder, mapsDir, baseName)
    if (source == PreviewSource.NONE) {
        log.warn("{} has neither a map file nor a sidecar image: no preview", baseName)
    }
}

private fun previewPath(mapsDir: Path, size: String, baseName: String): Path =
    mapsDir.resolve("previews").resolve(size).resolve("$baseName.png")

/**
 * The preview image embedded in the mission's `.scmap`, or `null` when the mission has no
 * map file to take one from.
 *
 * Thirteen of the forty four missions ship an eighteen byte stand in reading "test fake
 * map file" instead of a map: their terrain is part of the game install rather than of
 * this repository, so there is nothing here to render and never will be. The path fixer
 * skips the same files for the same reason. That is a `null`, not a failure.
 *
 * Everything else is a hard failure. A mission that has a map file but cannot produce a
 * picture must stop its own deployment rather than publish something plausible looking,
 * because a wrong image that looks right is what this whole change exists to get rid of.
 */
internal fun readEmbeddedPreview(mapFolder: Path): BufferedImage? {
    val mapFile = mapFolder.listDirectoryEntries()
        .firstOrNull { it.fileName.toString().endsWith(".scmap", ignoreCase = true) }
        ?: error("no .scmap in $mapFolder")

    val bytes = Files.readAllBytes(mapFile)
    if (!bytes.isScmap()) {
        log.info("{} is a placeholder, not a map file: no preview to generate", mapFile.fileName)
        return null
    }

    val chunkSize = bytes.littleEndianInt(PREVIEW_CHUNK_OFFSET)
    return decodeBgra(bytes, PREVIEW_CHUNK_OFFSET + 4 + DDS_HEADER_BYTES, chunkSize - DDS_HEADER_BYTES)
}

/**
 * A square, uncompressed BGRA image out of `length` bytes at `start`.
 *
 * Shared by the two places a co-op preview can come from, because they hold the same thing:
 * a 128 byte DDS header this skips, then one byte per channel per pixel. Everything that can
 * be checked is, because a wrong offset does not fail, it produces a sheared or shifted
 * picture that still looks like an image.
 */
private fun decodeBgra(bytes: ByteArray, start: Int, length: Int): BufferedImage {
    check(length > 0) { "no preview image: $length bytes of pixel data" }
    check(start + length <= bytes.size) {
        "preview claims $length bytes at $start but the file is only ${bytes.size} bytes"
    }

    val side = sqrt(length / 4.0)
    check(side == side.toInt().toDouble() && side > 0) {
        "$length bytes of pixel data is not a square BGRA image"
    }

    val pixels = bytes.copyOfRange(start, start + length)
    bgraToAbgr(pixels)

    val image = BufferedImage(side.toInt(), side.toInt(), BufferedImage.TYPE_4BYTE_ABGR)
    image.data = Raster.createRaster(image.sampleModel, DataBufferByte(pixels, pixels.size), Point())
    return image
}

/**
 * The engine stores the preview as BGRA while `TYPE_4BYTE_ABGR` wants the alpha first, so
 * every pixel rotates one byte to the right. Same conversion the API's generator does.
 */
private fun bgraToAbgr(buffer: ByteArray) {
    var i = 0
    while (i < buffer.size) {
        val a = buffer[i + 3]
        buffer[i + 3] = buffer[i + 2]
        buffer[i + 2] = buffer[i + 1]
        buffer[i + 1] = buffer[i]
        buffer[i] = a
        i += 4
    }
}

private fun ByteArray.littleEndianInt(at: Int): Int =
    (this[at].toInt() and 0xFF) or
        ((this[at + 1].toInt() and 0xFF) shl 8) or
        ((this[at + 2].toInt() and 0xFF) shl 16) or
        ((this[at + 3].toInt() and 0xFF) shl 24)

private fun scale(source: BufferedImage, size: Int): BufferedImage {
    val scaled = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val graphics = scaled.createGraphics()
    try {
        graphics.drawImage(source.getScaledInstance(size, size, Image.SCALE_SMOOTH), 0, 0, null)
    } finally {
        graphics.dispose()
    }
    return scaled
}

/**
 * Render one preview and publish it atomically.
 *
 * Same `.part` plus `ATOMIC_MOVE` dance the zip publishing uses, and for the same reason:
 * this directory is served straight to the internet, so a reader must see either the old
 * image or the new one, never a half written PNG.
 */
private fun writePreview(source: BufferedImage, target: Path, size: Int) {
    Files.createDirectories(target.parent)

    val partial = target.resolveSibling("${target.fileName}.part")
    try {
        Files.newOutputStream(partial).use { output ->
            check(ImageIO.write(scale(source, size), "png", output)) {
                "no PNG writer available for $target"
            }
        }
        Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE)
    } finally {
        Files.deleteIfExists(partial)
    }
}
