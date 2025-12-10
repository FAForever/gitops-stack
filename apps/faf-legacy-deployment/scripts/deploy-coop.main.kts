#!/usr/bin/env kotlin

@file:DependsOn("com.mysql:mysql-connector-j:9.5.0")
@file:DependsOn("org.eclipse.jgit:org.eclipse.jgit:7.4.0.202509020913-r")
@file:DependsOn("com.squareup.okio:okio:3.16.4")

import org.eclipse.jgit.api.Git
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.inputStream

// --------------------------- CONFIG ---------------------------

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
val VO_DOWNLOAD_TMP = Paths.get("/tmp", "vo_download_$PATCH_VERSION")

// --------------------------- UTILS ---------------------------

fun md5(path: Path): String {
    val md = MessageDigest.getInstance("MD5")
    path.inputStream().use { input ->
        val buf = ByteArray(4096)
        var r: Int
        while (input.read(buf).also { r = it } != -1) {
            md.update(buf, 0, r)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

fun Path.commonPath(other: Path): Path {
    val a = this.toAbsolutePath().normalize()
    val b = other.toAbsolutePath().normalize()
    val min = minOf(a.nameCount, b.nameCount)
    var i = 0
    while (i < min && a.getName(i) == b.getName(i)) i++
    return if (i == 0) a.root else a.root.resolve(a.subpath(0, i))
}

fun setPerm664(path: Path) {
    try {
        val perms = mutableSetOf<PosixFilePermission>().apply {
            add(PosixFilePermission.OWNER_READ); add(PosixFilePermission.OWNER_WRITE)
            add(PosixFilePermission.GROUP_READ); add(PosixFilePermission.GROUP_WRITE)
            add(PosixFilePermission.OTHERS_READ)
        }
        Files.setPosixFilePermissions(path, perms)
    } catch (e: Exception) {
        println("Warning: couldn't set perms on $path: ${e.message}")
    }
}

// --------------------------- ZIP with preserved hierarchy ---------------------------

fun zipPreserveStructure(sources: List<Path>, outputFile: Path, base: Path) {
    Files.createDirectories(outputFile.parent)
    ZipOutputStream(Files.newOutputStream(outputFile)).use { zos ->
        for (src in sources) {
            if (!Files.exists(src)) {
                // skip
                continue
            }
            if (Files.isDirectory(src)) {
                Files.walk(src).use { stream ->
                    stream.filter { Files.isRegularFile(it) }.forEach { file ->
                        val arcname = base.relativize(file).toString().replace("\\", "/")
                        val entry = ZipEntry(arcname)
                        // fix timestamp for determinism (not strictly necessary)
                        entry.time = 315532800000L // 1980-01-01 00:00:00 UTC in ms
                        zos.putNextEntry(entry)
                        Files.newInputStream(file).use { inp -> inp.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            } else {
                val arcname = base.relativize(src).toString().replace("\\", "/")
                val entry = ZipEntry(arcname)
                entry.time = 315532800000L
                zos.putNextEntry(entry)
                Files.newInputStream(src).use { inp -> inp.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }
}

// --------------------------- GIT ---------------------------

fun prepareRepo(): Path {
    val dir = Paths.get(WORKDIR)
    if (Files.exists(dir.resolve(".git"))) {
        println("Repo exists — fetching and checking out $GIT_REF...")
        Git.open(dir.toFile()).use { git ->
            git.fetch().call()
            git.checkout().setName(GIT_REF).call()
        }
    } else {
        println("Cloning $REPO_URL -> $dir ...")
        Git.cloneRepository()
            .setURI(REPO_URL)
            .setDirectory(dir.toFile())
            .call()
        Git.open(dir.toFile()).use { git ->
            git.checkout().setName(GIT_REF).call()
        }
    }
    return dir
}

// --------------------------- GITHUB RELEASE ASSET DOWNLOAD ---------------------------

fun httpGet(url: String): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.requestMethod = "GET"
    conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
    conn.connectTimeout = 15000
    conn.readTimeout = 15000
    val code = conn.responseCode
    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
    return stream.bufferedReader().use { it.readText() }
}

fun downloadFile(url: String, dest: Path) {
    val u = URL(url)
    val conn = u.openConnection() as HttpURLConnection
    conn.requestMethod = "GET"
    conn.connectTimeout = 20000
    conn.readTimeout = 20000
    conn.instanceFollowRedirects = true
    val code = conn.responseCode
    if (code !in 200..299) {
        throw IOException("Failed to download $url -> HTTP $code")
    }
    Files.createDirectories(dest.parent)
    conn.inputStream.use { input ->
        Files.newOutputStream(dest, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use { out ->
            input.copyTo(out)
        }
    }
}

fun findNx2UrlsFromReleaseJson(json: String): List<String> {
    // crude but effective: find all "browser_download_url": "..." and filter .nx2
    val regex = Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"")
    return regex.findAll(json).mapNotNull { it.groupValues.getOrNull(1) }.filter { it.endsWith(".nx2") }.toList()
}

fun downloadVoAssets(version: String, targetDir: Path): List<Path> {
    println("Downloading VO assets for version $version from GitHub releases...")
    Files.createDirectories(VO_DOWNLOAD_TMP)
    val apiUrl = "https://api.github.com/repos/FAForever/fa-coop/releases/tags/v$version"
    val json = try {
        httpGet(apiUrl)
    } catch (e: Exception) {
        println("Warning: failed to fetch release JSON: ${e.message}")
        return emptyList()
    }

    val urls = findNx2UrlsFromReleaseJson(json)
    if (urls.isEmpty()) {
        println("No .nx2 assets found in release v$version")
        return emptyList()
    }

    val downloaded = mutableListOf<Path>()
    for (u in urls) {
        val filename = Paths.get(URL(u).path).fileName.toString()
        val dst = VO_DOWNLOAD_TMP.resolve(filename)
        try {
            println("Downloading $u -> $dst")
            downloadFile(u, dst)
            // rename to include .v{version}.nx2 before .nx2
            val newName = filename.replace(Regex("\\.nx2$"), ".v$version.nx2")
            val newPath = VO_DOWNLOAD_TMP.resolve(newName)
            Files.move(dst, newPath, StandardCopyOption.REPLACE_EXISTING)
            downloaded.add(newPath)
        } catch (e: Exception) {
            println("Warning: failed to download $u: ${e.message}")
        }
    }

    // copy to target updates dir
    val outDir = targetDir.resolve("updates_coop_files")
    Files.createDirectories(outDir)
    for (p in downloaded) {
        val dest = outDir.resolve(p.fileName.toString())
        println("Copying VO $p -> $dest")
        try {
            if (!DRYRUN) {
                Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING)
                setPerm664(dest)
            } else {
                println("[DRYRUN] Would copy $p -> $dest")
            }
        } catch (e: Exception) {
            println("Warning: copying failed: ${e.message}")
        }
    }

    return downloaded.map { outDir.resolve(it.fileName) }
}

// --------------------------- DATABASE ---------------------------

fun dbConnection() =
    DriverManager.getConnection("jdbc:mysql://$DB_HOST/$DB_NAME?useSSL=false&serverTimezone=UTC", DB_USER, DB_PASS)

fun readExisting(conn: java.sql.Connection, mod: String): Map<Int, Pair<String?, String?>> {
    val sql = """
        SELECT uf.fileId, uf.name, uf.md5
        FROM (
            SELECT fileId, MAX(version) AS v
            FROM updates_${mod}_files
            GROUP BY fileId
        ) t
        JOIN updates_${mod}_files uf ON uf.fileId = t.fileId AND uf.version = t.v
    """.trimIndent()
    val out = mutableMapOf<Int, Pair<String?, String?>>()
    conn.prepareStatement(sql).use { stmt ->
        val rs = stmt.executeQuery()
        while (rs.next()) {
            out[rs.getInt(1)] = rs.getString(2) to rs.getString(3)
        }
    }
    return out
}

fun updateDb(conn: java.sql.Connection, mod: String, fileId: Int, version: Int, name: String, md5: String) {
    println("Updating DB: $name (fileId=$fileId, version=$version) md5=$md5")
    if (DRYRUN) {
        println("[DRYRUN] DB: would delete/insert for $fileId,$version")
        return
    }
    val del = "DELETE FROM updates_${mod}_files WHERE fileId=? AND version=?"
    val ins = "INSERT INTO updates_${mod}_files (fileId, version, name, md5, obselete) VALUES (?, ?, ?, ?, 0)"
    conn.prepareStatement(del).use {
        it.setInt(1, fileId)
        it.setInt(2, version)
        it.executeUpdate()
    }
    conn.prepareStatement(ins).use {
        it.setInt(1, fileId)
        it.setInt(2, version)
        it.setString(3, name)
        it.setString(4, md5)
        it.executeUpdate()
    }
}

// --------------------------- PROCESS FILES ---------------------------

fun processItem(
    conn: java.sql.Connection,
    mod: String,
    version: Int,
    fileId: Int,
    nameFmt: String,
    sources: List<Path>?,
    voDownloadedMap: Map<String, Path>
) {
    val name = nameFmt.format(version)
    val outDir = TARGET_DIR.resolve("updates_${mod}_files")
    Files.createDirectories(outDir)
    val target = outDir.resolve(name)

    println("Processing $name (fileId $fileId)")

    if (sources == null) {
        // VO file: look for downloaded file in target dir (name formatted)
        val expectedName = name // matches nameFmt.format(version)
        val candidate = outDir.resolve(expectedName)
        if (!Files.exists(candidate)) {
            println("Warning: VO file $expectedName not found in $outDir, skipping")
            return
        }
        val newMd5 = md5(candidate)
        val oldMd5 = readExisting(conn, mod)[fileId]?.second
        if (newMd5 != oldMd5) {
            updateDb(conn, mod, fileId, version, expectedName, newMd5)
        } else {
            println("VO $expectedName unchanged")
        }
        return
    }

    // sources present -> create zip or copy single file
    val existing = sources.filter { p -> Files.exists(p) }
    if (existing.isEmpty()) {
        println("Warning: no existing sources for $name, skipping")
        return
    }

    // if single source and it's a file, copy it directly (like init_coop.lua)
    if (existing.size == 1 && Files.isRegularFile(existing[0])) {
        val src = existing[0]
        println("Single file source for $name: copying $src -> $target")
        if (!DRYRUN) {
            Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING)
            setPerm664(target)
        } else {
            println("[DRYRUN] Would copy $src -> $target")
        }
        val newMd5 = md5(target)
        val oldMd5 = readExisting(conn, mod)[fileId]?.second
        if (newMd5 != oldMd5) {
            updateDb(conn, mod, fileId, version, name, newMd5)
        } else {
            println("$name unchanged")
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
    println("Zipping sources with base=$base -> $tmp")
    zipPreserveStructure(existing, tmp, base)
    println("Moving zip to $target")
    if (!DRYRUN) {
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        setPerm664(target)
    } else {
        println("[DRYRUN] Would move $tmp -> $target")
    }

    val newMd5 = md5(target)
    val oldMd5 = readExisting(conn, mod)[fileId]?.second
    if (newMd5 != oldMd5) {
        updateDb(conn, mod, fileId, version, name, newMd5)
    } else {
        println("$name unchanged")
    }
}

// --------------------------- MAIN ---------------------------

println("=== Kotlin Coop Deployer v$PATCH_VERSION ===")
val repo = prepareRepo()
println("Repo ready at $repo")

// Download VO assets first
val voFiles = downloadVoAssets(PATCH_VERSION, TARGET_DIR) // returns list of paths in target dir
val voMap = voFiles.associateBy { it.fileName.toString() } // name -> Path

val conn = dbConnection()
try {
    val existing = readExisting(conn, "coop")

    data class PatchFile(val id: Int, val fileTemplate: String, val includes: List<Path>?)

    val filesList = listOf(
        PatchFile(1, "init_coop.v%d.lua", listOf(repo.resolve("init_coop.lua"))),
        PatchFile(
            2, "lobby_coop_v%d.cop", listOf(
                repo.resolve("mods"),
                repo.resolve("units"),
                repo.resolve("mod_info.lua"),
                repo.resolve("readme.md"),
                repo.resolve("changelog.md")
            )
        ),

        // all VO files (no sources → already downloaded externally)
        PatchFile(3, "A01_VO.v%d.nx2", null),
        PatchFile(4, "A02_VO.v%d.nx2", null),
        PatchFile(5, "A03_VO.v%d.nx2", null),
        PatchFile(6, "A04_VO.v%d.nx2", null),
        PatchFile(7, "A05_VO.v%d.nx2", null),
        PatchFile(8, "A06_VO.v%d.nx2", null),
        PatchFile(9, "C01_VO.v%d.nx2", null),
        PatchFile(10, "C02_VO.v%d.nx2", null),
        PatchFile(11, "C03_VO.v%d.nx2", null),
        PatchFile(12, "C04_VO.v%d.nx2", null),
        PatchFile(13, "C05_VO.v%d.nx2", null),
        PatchFile(14, "C06_VO.v%d.nx2", null),
        PatchFile(15, "E01_VO.v%d.nx2", null),
        PatchFile(16, "E02_VO.v%d.nx2", null),
        PatchFile(17, "E03_VO.v%d.nx2", null),
        PatchFile(18, "E04_VO.v%d.nx2", null),
        PatchFile(19, "E05_VO.v%d.nx2", null),
        PatchFile(20, "E06_VO.v%d.nx2", null),
        PatchFile(21, "Prothyon16_VO.v%d.nx2", null),
        PatchFile(22, "A03_VO.v%d.nx2", null),
        PatchFile(23, "A03_VO.v%d.nx2", null),
        PatchFile(24, "A03_VO.v%d.nx2", null),
        PatchFile(25, "A03_VO.v%d.nx2", null),
        // … add the rest
    )

    for ((fileId, fmt, srcs) in filesList) {
        processItem(conn, "coop", PATCH_VERSION.toInt(), fileId, fmt, srcs?.map { it }, voMap)
    }
} finally {
    conn.close()
}

println("=== Done ===")
