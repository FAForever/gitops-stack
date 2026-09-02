@file:Suppress("PackageDirectoryMismatch")

package com.faforever

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * Renders a preview for every mission in a checkout of the co-op missions and fails if any
 * of them cannot be produced. Meant for CI, so a mission whose map file the generator
 * cannot read is caught here rather than by a hole on somebody's co-op tab.
 *
 * It also answers the question the deployment job cannot: the job runs headless in a
 * `gradle:9.4-jdk21` container, and preview generation is the first thing in this deployer
 * to touch Java2D and ImageIO. Rendering every mission in the same kind of environment is
 * what says that works.
 *
 * A rendered preview is only accepted if it is the requested size and not a single flat
 * colour. The second check is the one that matters: a mission whose embedded preview is
 * missing still yields an image, it is just uniformly black, and that would reach the CDN
 * looking exactly like a working file.
 *
 * Point MAPS_REPO at a checkout of https://github.com/FAForever/faf-coop-maps. Set
 * PREVIEW_OUT to keep the rendered images for inspection.
 */
fun main() {
    val repo = File(System.getenv("MAPS_REPO") ?: "/tmp/faf-coop-maps")
    require(repo.isDirectory) { "MAPS_REPO does not point at a directory: $repo" }
    val out = System.getenv("PREVIEW_OUT")?.let { Path.of(it) }
    // Stands in for the live maps directory, where the vault's own previews already are.
    // Without it the stock map fall back has nothing to find and cannot be exercised.
    System.getenv("PREVIEW_SEED")?.let { seed ->
        val from = Path.of(seed)
        val target = out ?: error("PREVIEW_SEED needs PREVIEW_OUT")
        Files.walk(from).filter { Files.isRegularFile(it) }.forEach {
            val to = target.resolve(from.relativize(it).toString())
            Files.createDirectories(to.parent)
            Files.copy(it, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    val missions = repo.listFiles()
        ?.filter { it.isDirectory && !it.name.startsWith(".") }
        ?.filter { folder -> folder.listFiles()?.any { it.name.lowercase().endsWith(".scmap") } == true }
        ?.sortedBy { it.name }
        ?: emptyList()
    require(missions.isNotEmpty()) { "no mission folder with a .scmap found below $repo" }

    val failures = mutableListOf<String>()
    val bySource = mutableMapOf<PreviewSource, MutableList<String>>()

    println("%-44s %-10s %-10s %s".format("mission", "source", "size", "colours"))

    missions.forEach { folder ->
        val target = out ?: Files.createTempDirectory("coop-preview")
        val baseName = folder.name.lowercase() + ".v0000"
        try {
            val source = writeMapPreviews(folder.toPath(), target, baseName)
            bySource.getOrPut(source) { mutableListOf() } += folder.name

            val small = target.resolve("previews/small/$baseName.png")
            if (source == PreviewSource.NONE) {
                println("%-44s %-10s %-10s %s".format(folder.name, "none", "-", "no image anywhere"))
                return@forEach
            }
            val image = ImageIO.read(small.toFile())
                ?: error("${folder.name}: the written file is not a readable image")

            val colours = buildSet {
                for (x in 0 until image.width step 4) {
                    for (y in 0 until image.height step 4) add(image.getRGB(x, y))
                }
            }.size
            if (image.width != 128 || image.height != 128) {
                failures += "${folder.name}: small preview is ${image.width}x${image.height}, expected 128x128"
            }
            if (colours < 2) {
                failures += "${folder.name}: preview is a single colour, the map file has no usable preview"
            }

            println("%-44s %-10s %-10s %s".format(
                folder.name,
                when (source) {
                    PreviewSource.MAP_FILE -> "map file"
                    PreviewSource.SIDECAR_DDS -> "sidecar"
                    else -> "stock map"
                },
                "${image.width}x${image.height}",
                colours,
            ))
        } catch (e: Exception) {
            failures += "${folder.name}: ${e.message}"
            println("%-44s %-10s %-10s %s".format(folder.name, "-", "-", "FAILED: ${e.message}"))
        } finally {
            if (out == null) target.toFile().deleteRecursively()
        }
    }

    val fromMap = bySource[PreviewSource.MAP_FILE].orEmpty()
    val fromSidecar = bySource[PreviewSource.SIDECAR_DDS].orEmpty()
    val fromStock = bySource[PreviewSource.STOCK_MAP].orEmpty()
    val none = bySource[PreviewSource.NONE].orEmpty()

    println()
    println("${fromMap.size + fromSidecar.size + fromStock.size} of ${missions.size} mission(s) have a preview")
    println("  ${fromMap.size} from their own map file")
    println("  ${fromSidecar.size} from the sidecar .dds, their map not being in this repository:")
    fromSidecar.forEach { println("      $it") }
    if (fromStock.isNotEmpty()) {
        println("  ${fromStock.size} from the stock map their scenario names:")
        fromStock.forEach { println("      $it") }
    }
    if (none.isNotEmpty()) {
        println("  ${none.size} with no image at all:")
        none.forEach { println("      $it") }
    }

    // A run that rendered nothing proves nothing, the same way the path fixer's check
    // refuses a run in which no path was rewritten.
    if (fromMap.isEmpty()) {
        failures += "no mission produced a preview from its own map file, so the part that " +
            "matters was never exercised"
    }
    if (out != null) println("images written below $out")

    if (failures.isNotEmpty()) {
        println()
        failures.forEach { println("FAIL  $it") }
        println("${failures.size} failure(s)")
        exitProcess(1)
    }
    println("all good")
}
