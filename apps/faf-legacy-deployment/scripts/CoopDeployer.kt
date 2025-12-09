import org.apache.commons.compress.archivers.zip.Zip64Mode
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.eclipse.jgit.api.Git
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.inputStream

private val log = LoggerFactory.getLogger("CoopDeployer")

fun Path.setPerm664() {
    val perms = mutableSetOf<PosixFilePermission>().apply {
        add(PosixFilePermission.OWNER_READ); add(PosixFilePermission.OWNER_WRITE)
        add(PosixFilePermission.GROUP_READ); add(PosixFilePermission.GROUP_WRITE)
        add(PosixFilePermission.OTHERS_READ)
    }
    Files.setPosixFilePermissions(this, perms)
}

data class FeatureModGitRepo(
    val workDir: Path,
    val repoUrl: String,
    val gitRef: String,
) {
    fun checkout(): Path {
        if (Files.exists(workDir.resolve(".git"))) {
            log.info("Repo exists — fetching and checking out $gitRef...")
            Git.open(workDir.toFile()).use { git ->
                git.fetch().call()
                git.checkout().setName(gitRef).call()
            }
        } else {
            log.info("Cloning repository $repoUrl")
            Git.cloneRepository()
                .setURI(repoUrl)
                .setDirectory(workDir.toFile())
                .call()
            log.info("Checking out $gitRef")
            Git.open(workDir.toFile()).use { git ->
                git.checkout().setName(gitRef).call()
            }
        }

        return workDir
    }
}

data class GithubReleaseAssetDownloader(
    val repoOwner: String = "FAForever",
    val repoName: String,
    val suffix: String,
    val version: String,
    val dryRun: Boolean,
) {
    companion object {
        private const val API_URL = "https://api.github.com/repos/%s/%s/releases/tags/v%s"
        private val DOWNLOAD_URL_REGEX = Regex(""""browser_download_url"\s*:\s*"(?<url>[^"]+)"""")
    }

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun downloadAssets(targetDir: Path): List<Path> {
        log.info("Downloading assets for version $version from GitHub releases...")
        val tempDir = Paths.get("/tmp", "asset_download_$version")

        val urls = getAssetUrisBySuffix()
        if (urls.isEmpty()) {
            log.info("No .nx2 assets found in release v{}", version)
            return emptyList()
        }

        val downloaded = mutableListOf<Path>()
        Files.createDirectories(tempDir)
        for (u in urls) {
            val filename = Paths.get(u.toURL().path).fileName.toString()
            val dst = tempDir.resolve(filename)

            downloadFile(u, dst)
            // rename to include .v{version}.nx2 before .nx2
            val newName = filename.replace(Regex("\\.nx2$"), ".v$version.nx2")
            val newPath = tempDir.resolve(newName)
            Files.move(dst, newPath, StandardCopyOption.REPLACE_EXISTING)
            downloaded.add(newPath)
        }

        // copy to target updates dir
        val outDir = targetDir.resolve("updates_coop_files")
        Files.createDirectories(outDir)
        for (p in downloaded) {
            val dest = outDir.resolve(p.fileName.toString())
            log.info("Copying VO {} -> {}", p, dest)

            if (!dryRun) {
                Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING)
                dest.setPerm664()
            } else {
                log.info("[DRYRUN] Would copy {} -> {}", p, dest)
            }
        }

        return downloaded.map { outDir.resolve(it.fileName) }
    }

    private fun getAssetUrisBySuffix(): List<URI> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(API_URL.format(repoOwner, repoName, version)))
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "application/vnd.github.v3+json")
            .GET()
            .build()
        val apiResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body()

        return DOWNLOAD_URL_REGEX.findAll(apiResponse)
            .mapNotNull { it.groups["url"]?.value }
            .filter { it.endsWith(suffix, ignoreCase = true) }
            .map { URI.create(it) }
            .toList()
    }

    private fun downloadFile(source: URI, dest: Path) {
        log.debug("Downloading {} -> {}", source, dest)

        val request = HttpRequest.newBuilder()
            .uri(source)
            .GET()
            .build()

        Files.createDirectories(dest.parent)

        val response = httpClient.send(
            request,
            HttpResponse.BodyHandlers.ofFile(dest)
        )

        if (response.statusCode() !in 200..299) {
            Files.deleteIfExists(dest)
            throw IOException("Failed to download $source -> HTTP ${response.statusCode()}")
        }
    }

}

data class FafDatabase(
    val host: String,
    val database: String,
    val username: String,
    val password: String,
    val dryRun: Boolean
) : AutoCloseable {
    /**
     * Definition of an existing file in the database
     */
    data class PatchFile(val mod: String, val fileId: Int, val name: String, val md5: String, val version: Int)

    private val connection: Connection =
        DriverManager.getConnection(
            "jdbc:mariadb://$host/$database?useSSL=false&serverTimezone=UTC",
            username,
            password
        )

    fun getCurrentPatchFile(mod: String, fileId: Int): PatchFile? {
        val sql = """
        SELECT uf.fileId, uf.name, uf.md5, t.v
        FROM (
            SELECT fileId, MAX(version) AS v
            FROM updates_${mod}_files
            GROUP BY fileId
        ) t
        JOIN updates_${mod}_files uf ON uf.fileId = t.fileId AND uf.version = t.v
        WHERE uf.fileId = ?
    """.trimIndent()

        connection.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, fileId)
            val rs = stmt.executeQuery()
            while (rs.next()) {
                val fileId = rs.getInt(1)
                val name = rs.getString(2)
                val md5 = rs.getString(3)
                val version = rs.getInt(4)
                return PatchFile(mod = mod, fileId = fileId, name = name, md5 = md5, version = version)
            }
        }
        return null
    }

    fun insertOrReplace(mod: String, fileId: Int, version: Int, name: String, md5: String) {
        log.info("Updating DB: {} (fileId={}, version={}) md5={}", name, fileId, version, md5)
        if (dryRun) {
            log.info("[DRYRUN] DB: would delete/insert for {},{}", fileId, version)
            return
        }
        val del = "DELETE FROM updates_${mod}_files WHERE fileId=? AND version=?"
        val ins = "INSERT INTO updates_${mod}_files (fileId, version, name, md5, obselete) VALUES (?, ?, ?, ?, 0)"
        connection.prepareStatement(del).use {
            it.setInt(1, fileId)
            it.setInt(2, version)
            it.executeUpdate()
        }
        connection.prepareStatement(ins).use {
            it.setInt(1, fileId)
            it.setInt(2, version)
            it.setString(3, name)
            it.setString(4, md5)
            it.executeUpdate()
        }
    }

    override fun close() {
        connection.close()
    }
}

private const val MINIMUM_ZIP_DATE = 315532800000L // 1980-01-01
private val MINIMUM_ZIP_FILE_TIME = FileTime.fromMillis(MINIMUM_ZIP_DATE)

class Patcher(
    val patchVersion: Int,
    val targetDir: Path,
    val db: FafDatabase,
    val dryRun: Boolean,
) {
    /**
     * Definition of a file to create a patch for
     */
    data class PatchFile(val id: Int, val fileTemplate: String, val includes: List<Path>?, val mod: String = "coop")

    fun process(patchFile: PatchFile) {
        val mod: String = patchFile.mod
        val fileId: Int = patchFile.id
        val name = patchFile.fileTemplate.format(patchVersion)
        val outDir = targetDir.resolve("updates_${mod}_files")

        Files.createDirectories(outDir)
        val target = outDir.resolve(name)

        log.info("Processing {} (fileId {})", name, fileId)

        if (patchFile.includes == null) {
            // VO file: look for downloaded file in target dir (name formatted)
            val expectedName = name // matches nameFmt.format(version)
            val candidate = outDir.resolve(expectedName)
            if (!Files.exists(candidate)) {
                log.debug("VO file {} not found in {}, skipping", expectedName, outDir)
                return
            }

            val newMd5 = candidate.md5()
            val oldFile = db.getCurrentPatchFile(mod, fileId)
            if (newMd5 != oldFile?.md5) {
                db.insertOrReplace(mod, fileId, patchVersion, expectedName, newMd5)
            } else {
                log.info("VO {} unchanged from version {}", expectedName, oldFile.version)
            }
            return
        }

        // sources present -> create zip or copy single file
        val existing = patchFile.includes.filter(Files::exists)
        if (existing.isEmpty()) {
            log.info("Warning: no existing sources for {}, skipping", name)
            return
        }

        // if single source and it's a file, copy it directly (like init_coop.lua)
        if (existing.size == 1 && Files.isRegularFile(existing[0])) {
            val src = existing[0]
            log.info("Single file source for {}: copying {} -> {}", name, src, target)

            val newMd5 = src.md5()
            val oldFile = db.getCurrentPatchFile(mod, fileId)
            if (newMd5 == oldFile?.md5) {
                log.info("{} unchanged from version {}, skipping", name, oldFile.version)
                return
            }

            if (!dryRun) {
                Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING)
                target.setPerm664()
                db.insertOrReplace(mod, fileId, patchVersion, name, newMd5)
            } else {
                log.info("[DRYRUN] Would copy {} -> {}", src, target)
            }

            return
        }

        // multiple sources -> zip them; determine base as common parent of the directories (so top-level folders like 'mods'/'units' remain)
        // compute base as common path of all existing sources
        var base = existing[0].toAbsolutePath().normalize()
        for (i in 1 until existing.size) {
            base = base.commonPath(existing[i].toAbsolutePath().normalize())
        }

        val tmp = Files.createTempFile("coop", ".zip")
        log.info("Zipping sources with base={} -> {}", base, tmp)
        zipPreserveStructure(existing, tmp, base)

        val newMd5 = tmp.md5()
        val oldFile = db.getCurrentPatchFile(mod, fileId)

        if (newMd5 == oldFile?.md5) {
            log.info("{} unchanged from version {}, skipping", name, oldFile.version)
            return
        }

        if (!dryRun) {
            log.info("Moving zip to {}", target)
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            target.setPerm664()

            log.info("Writing fileId {} with version {} to database", fileId, patchVersion)
            db.insertOrReplace(mod, fileId, patchVersion, name, newMd5)
        } else {
            log.info("[DRYRUN] Would move {} -> {}", tmp, target)
        }
    }

    private fun Path.md5(): String {
        val md = MessageDigest.getInstance("MD5")
        this.inputStream().use { input ->
            val buf = ByteArray(4096)
            var r: Int
            while (input.read(buf).also { r = it } != -1) {
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun Path.commonPath(other: Path): Path {
        val a = toAbsolutePath().normalize()
        val b = other.toAbsolutePath().normalize()

        val commonCount = (0 until minOf(a.nameCount, b.nameCount))
            .takeWhile { a.getName(it) == b.getName(it) }
            .count()

        return if (commonCount == 0) a.root
        else a.root.resolve(a.subpath(0, commonCount))
    }

    private fun zipPreserveStructure(sources: List<Path>, outputFile: Path, base: Path) {
        Files.createDirectories(outputFile.parent)

        // Never pass a stream here; this will cause extended local headers to be used, making it incompatible to FA!
        ZipArchiveOutputStream(outputFile.toFile()).use { zos ->
            zos.setMethod(ZipArchiveEntry.DEFLATED)

            for (src in sources) {
                if (!Files.exists(src)) {
                    // skip
                    log.warn("Could not find path {}", src)
                    continue
                }
                if (Files.isDirectory(src)) {
                    Files.walk(src).use { stream ->
                        stream
                            .filter { Files.isRegularFile(it) }
                            .forEach { zos.pushNormalizedFile(base, it) }
                    }
                } else {
                    zos.pushNormalizedFile(base, src)
                }
            }
        }
    }

    private fun ZipArchiveOutputStream.pushNormalizedFile(base: Path, path: Path) {
        require(Files.isRegularFile(path)) { "Path $path is not a regular file" }

        val archiveName = base.relativize(path).toString().replace("\\", "/")

        // Use the same constructor as the FAF API:
        val entry = ZipArchiveEntry(path.toFile(), archiveName).apply {
            // Ensure deterministic times
            setTime(MINIMUM_ZIP_FILE_TIME)
            setCreationTime(MINIMUM_ZIP_FILE_TIME)
            setLastModifiedTime(MINIMUM_ZIP_FILE_TIME)
            setLastAccessTime(MINIMUM_ZIP_FILE_TIME)
        }

        this.putArchiveEntry(entry)
        Files.newInputStream(path).use { inp -> inp.copyTo(this) }
        this.closeArchiveEntry()
    }

}

fun main() {
    val PATCH_VERSION = System.getenv("PATCH_VERSION") ?: error("PATCH_VERSION required")
    val REPO_URL = System.getenv("GIT_REPO_URL") ?: "https://github.com/FAForever/fa-coop.git"
    val GIT_REF = System.getenv("GIT_REF") ?: "v$PATCH_VERSION"
    val WORKDIR = System.getenv("GIT_WORKDIR") ?: "/tmp/fa-coop-kt"
    val DRYRUN = (System.getenv("DRY_RUN") ?: "false").lowercase() in listOf("1", "true", "yes")

    val DB_HOST = System.getenv("DATABASE_HOST") ?: "localhost"
    val DB_NAME = System.getenv("DATABASE_NAME") ?: "faf"
    val DB_USER = System.getenv("DATABASE_USERNAME") ?: "root"
    val DB_PASS = System.getenv("DATABASE_PASSWORD") ?: "banana"

    val TARGET_DIR = Paths.get("./legacy-featured-mod-files")

    log.info("=== Kotlin Coop Deployer v{} ===", PATCH_VERSION)

    val repo = FeatureModGitRepo(
        workDir = Paths.get(WORKDIR),
        repoUrl = REPO_URL,
        gitRef = GIT_REF
    ).checkout()

    // Download VO assets first
    GithubReleaseAssetDownloader(
        repoName = "fa-coop",
        suffix = ".nx2",
        version = PATCH_VERSION,
        dryRun = DRYRUN
    ).downloadAssets(TARGET_DIR)

    val filesList = listOf(
        Patcher.PatchFile(1, "init_coop.v%d.lua", listOf(repo.resolve("init_coop.lua"))),
        Patcher.PatchFile(
            2, "lobby_coop_v%d.cop", listOf(
                repo.resolve("mods"),
                repo.resolve("units"),
                repo.resolve("mod_info.lua"),
                repo.resolve("readme.md"),
                repo.resolve("changelog.md")
            )
        ),

        // all VO files (no sources → already downloaded externally)
        Patcher.PatchFile(3, "A01_VO.v%d.nx2", null),
        Patcher.PatchFile(4, "A02_VO.v%d.nx2", null),
        Patcher.PatchFile(5, "A03_VO.v%d.nx2", null),
        Patcher.PatchFile(6, "A04_VO.v%d.nx2", null),
        Patcher.PatchFile(7, "A05_VO.v%d.nx2", null),
        Patcher.PatchFile(8, "A06_VO.v%d.nx2", null),
        Patcher.PatchFile(9, "C01_VO.v%d.nx2", null),
        Patcher.PatchFile(10, "C02_VO.v%d.nx2", null),
        Patcher.PatchFile(11, "C03_VO.v%d.nx2", null),
        Patcher.PatchFile(12, "C04_VO.v%d.nx2", null),
        Patcher.PatchFile(13, "C05_VO.v%d.nx2", null),
        Patcher.PatchFile(14, "C06_VO.v%d.nx2", null),
        Patcher.PatchFile(15, "E01_VO.v%d.nx2", null),
        Patcher.PatchFile(16, "E02_VO.v%d.nx2", null),
        Patcher.PatchFile(17, "E03_VO.v%d.nx2", null),
        Patcher.PatchFile(18, "E04_VO.v%d.nx2", null),
        Patcher.PatchFile(19, "E05_VO.v%d.nx2", null),
        Patcher.PatchFile(20, "E06_VO.v%d.nx2", null),
        Patcher.PatchFile(21, "Prothyon16_VO.v%d.nx2", null),
        Patcher.PatchFile(22, "TCR_VO.v%d.nx2", null),
        Patcher.PatchFile(23, "SCCA_Briefings.v%d.nx2", null),
        Patcher.PatchFile(24, "SCCA_FMV.v%d.nx2", null),
        Patcher.PatchFile(25, "FAF_Coop_Operation_Tight_Spot_VO.v%d.nx2", null),
    )

    FafDatabase(
        host = DB_HOST,
        database = DB_NAME,
        username = DB_USER,
        password = DB_PASS,
        dryRun = DRYRUN
    ).use { db ->
        val patcher = Patcher(
            patchVersion = PATCH_VERSION.toInt(),
            targetDir = TARGET_DIR,
            db = db,
            dryRun = DRYRUN,
        )
        filesList.forEach(patcher::process)
    }

    log.info("=== Deployment complete ===")
}
