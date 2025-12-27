@file:Suppress("PackageDirectoryMismatch")

package com.faforever.coopmapdeployer

import com.faforever.CHECKSUMS_FILENAME
import com.faforever.FafDatabase
import com.faforever.GitRepo
import com.faforever.Log
import com.faforever.extractChecksumsFromZip
import com.faforever.generateChecksums
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.walk

private val log = LoggerFactory.getLogger("coop-maps-updater")

data class CoopMap(
    val folderName: String,
    val mapId: Int,
    val mapType: Int
) {
    fun zipName(version: Int) =
        "${folderName.lowercase()}.v${version.toString().padStart(4, '0')}.zip"

    fun folderName(version: Int) =
        "${folderName.lowercase()}.v${version.toString().padStart(4, '0')}"
}

private val coopMaps = listOf(
    CoopMap("X1CA_Coop_001", 1, 0),
    CoopMap("X1CA_Coop_002", 3, 0),
    CoopMap("X1CA_Coop_003", 4, 0),
    CoopMap("X1CA_Coop_004", 5, 0),
    CoopMap("X1CA_Coop_005", 6, 0),
    CoopMap("X1CA_Coop_006", 7, 0),

    CoopMap("SCCA_Coop_A01", 8, 1),
    CoopMap("SCCA_Coop_A02", 9, 1),
    CoopMap("SCCA_Coop_A03", 10, 1),
    CoopMap("SCCA_Coop_A04", 11, 1),
    CoopMap("SCCA_Coop_A05", 12, 1),
    CoopMap("SCCA_Coop_A06", 13, 1),

    CoopMap("SCCA_Coop_R01", 20, 2),
    CoopMap("SCCA_Coop_R02", 21, 2),
    CoopMap("SCCA_Coop_R03", 22, 2),
    CoopMap("SCCA_Coop_R04", 23, 2),
    CoopMap("SCCA_Coop_R05", 24, 2),
    CoopMap("SCCA_Coop_R06", 25, 2),

    CoopMap("SCCA_Coop_E01", 14, 3),
    CoopMap("SCCA_Coop_E02", 15, 3),
    CoopMap("SCCA_Coop_E03", 16, 3),
    CoopMap("SCCA_Coop_E04", 17, 3),
    CoopMap("SCCA_Coop_E05", 18, 3),
    CoopMap("SCCA_Coop_E06", 19, 3),

    CoopMap("FAF_Coop_Prothyon_16", 26, 4),
    CoopMap("FAF_Coop_Fort_Clarke_Assault", 27, 4),
    CoopMap("FAF_Coop_Theta_Civilian_Rescue", 28, 4),
    CoopMap("FAF_Coop_Novax_Station_Assault", 31, 4),
    CoopMap("FAF_Coop_Operation_Tha_Atha_Aez", 32, 4),
    CoopMap("FAF_Coop_Havens_Invasion", 33, 4),
    CoopMap("FAF_Coop_Operation_Rescue", 35, 4),
    CoopMap("FAF_Coop_Operation_Uhthe_Thuum_QAI", 36, 4),
    CoopMap("FAF_Coop_Operation_Yath_Aez", 37, 4),
    CoopMap("FAF_Coop_Operation_Ioz_Shavoh_Kael", 38, 4),
    CoopMap("FAF_Coop_Operation_Trident", 39, 4),
    CoopMap("FAF_Coop_Operation_Blockade", 40, 4),
    CoopMap("FAF_Coop_Operation_Golden_Crystals", 41, 4),
    CoopMap("FAF_Coop_Operation_Holy_Raid", 42, 4),
    CoopMap("FAF_Coop_Operation_Tight_Spot", 45, 4),
    CoopMap("FAF_Coop_Operation_Overlord_Surth_Velsok", 47, 4),
    CoopMap("FAF_Coop_Operation_Rebels_Rest", 48, 4),
    CoopMap("FAF_Coop_Operation_Red_Revenge", 49, 4),
)

data class CoopMapDatabase(
    val dryRun: Boolean
) : FafDatabase() {
    fun getLatestVersion(map: CoopMap): Int {
        createStatement().use { st ->
            st.executeQuery("SELECT version FROM coop_map WHERE id=${map.mapId}")
                .use { rs ->
                    if (!rs.next()) error("Map ${map.mapId} not found")
                    return rs.getInt(1)
                }
        }
    }

    fun update(map: CoopMap, version: Int) {
        val sql = """
        UPDATE coop_map
        SET version=$version,
            filename='maps/${map.zipName(version)}'
        WHERE id=${map.mapId}
    """.trimIndent()

        createStatement().use { it.executeUpdate(sql) }
    }
}

private fun processCoopMap(
    db: CoopMapDatabase,
    map: CoopMap,
    simulate: Boolean,
    gitDir: String,
    mapsDir: String
) {
    log.info("Processing $map")

    val tmp = Files.createTempDirectory("coop-map")
    try {
        Files.walk(Path.of(gitDir, map.folderName)).forEach {
            val target = tmp.resolve(Path.of(gitDir, map.folderName).relativize(it))
            if (it.isDirectory()) target.createDirectories()
            else it.copyTo(target)
        }

        val files = tmp.walk()
            .filter { it.isRegularFile() }
            // Files.walk does not guarantee fixed order, but we need it
            .sortedBy { tmp.relativize(it).toString() }
            .toList()
        val currentVersion = db.getLatestVersion(map)

        // Generate checksums for the new content (with path rewriting applied)
        val newChecksums = generateChecksumsForMap(map, currentVersion, files, tmp)

        // Compare with checksums from existing ZIP
        val currentZip = Path.of(mapsDir, map.zipName(currentVersion))
        val oldChecksums = extractChecksumsFromZip(currentZip)

        val changed = currentVersion == 0 || newChecksums != oldChecksums

        if (!changed) {
            log.info("$map unchanged")
            return
        }

        val newVersion = currentVersion + 1
        log.info("$map updated → v$newVersion")

        if (!simulate) {
            val finalZip = Path.of(mapsDir, map.zipName(newVersion))
            createZip(map, newVersion, files, tmp, finalZip)
            db.update(map, newVersion)
        }
    } finally {
        tmp.toFile().deleteRecursively()
    }
}

/**
 * Generate checksums for map files with path rewriting applied to text files.
 * This is used for content-based change detection.
 */
private fun generateChecksumsForMap(
    map: CoopMap,
    version: Int,
    files: List<Path>,
    base: Path
): String {
    return generateChecksums(files, base) { file ->
        getFileContent(file, map, version)
    }
}

/**
 * Get file content with path rewriting for text files.
 */
private fun getFileContent(file: Path, map: CoopMap, version: Int): ByteArray {
    return if (file.isTextFile()) {
        file.readText()
            .replace(
                "/maps/${map.folderName}/",
                "/maps/${map.folderName(version)}/",
            )
            .toByteArray()
    } else {
        file.readBytes()
    }
}

private fun createZip(
    map: CoopMap,
    version: Int,
    files: List<Path>,
    base: Path,
    out: Path
) {
    ZipArchiveOutputStream(out.toFile()).use { zip ->
        zip.setMethod(ZipArchiveEntry.DEFLATED)

        // Generate and write checksums.md5 as first entry
        val checksums = generateChecksumsForMap(map, version, files, base)
        val checksumBytes = checksums.toByteArray()
        val checksumEntry = ZipArchiveEntry(CHECKSUMS_FILENAME).apply {
            size = checksumBytes.size.toLong()
        }
        zip.putArchiveEntry(checksumEntry)
        zip.write(checksumBytes)
        zip.closeArchiveEntry()

        // Write all files at root level
        files.forEach { file ->
            val rel = base.relativize(file).toString().replace("\\", "/")
            val bytes = getFileContent(file, map, version)

            val entry = ZipArchiveEntry(rel).apply {
                size = bytes.size.toLong()
            }

            zip.putArchiveEntry(entry)
            zip.write(bytes)
            zip.closeArchiveEntry()
        }

        zip.finish()
    }
}

private fun Path.isTextFile() = listOf(".md", ".lua", ".json", ".txt").any { toString().endsWith(it) }

fun main(args: Array<String>) {
    Log.init()

    val MAP_DIR = System.getenv("MAP_DIR") ?: "/opt/faf/data/faf-coop-maps"
    val PATCH_VERSION = System.getenv("PATCH_VERSION") ?: error("PATCH_VERSION required")
    val REPO_URL = System.getenv("GIT_REPO_URL") ?: "https://github.com/FAForever/faf-coop-maps"
    val GIT_REF = System.getenv("GIT_REF") ?: "v$PATCH_VERSION"
    val WORKDIR = System.getenv("GIT_WORKDIR") ?: "/tmp/faf-coop-maps"
    val DRYRUN = (System.getenv("DRY_RUN") ?: "false").lowercase() in listOf("1", "true", "yes")

    log.info("=== Kotlin Coop Map Deployer v{} ===", PATCH_VERSION)

    Files.createDirectories(Paths.get(MAP_DIR))

    GitRepo(
        workDir = Paths.get(WORKDIR),
        repoUrl = REPO_URL,
        gitRef = GIT_REF,
    ).checkout()

    CoopMapDatabase(dryRun = DRYRUN).use { db ->
        coopMaps.forEach {
            try {
                processCoopMap(db, it, DRYRUN, WORKDIR, MAP_DIR)
            } catch (e: Exception) {
                log.warn("Failed processing $it", e)
            }
        }
    }
}
