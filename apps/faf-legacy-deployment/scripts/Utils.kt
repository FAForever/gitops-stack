package com.faforever

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import org.apache.commons.compress.archivers.zip.ZipFile
import org.eclipse.jgit.api.Git
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.Statement
import kotlin.io.path.inputStream

object Log {
    fun init() {
        val level = System.getenv("LOG_LEVEL") ?: "INFO"
        val root = LoggerFactory
            .getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        root.level = Level.toLevel(level, Level.INFO)
    }
}

data class GitRepo(
    val workDir: Path,
    val repoUrl: String,
    val gitRef: String,
) {
    private val log = LoggerFactory.getLogger(GitRepo::class.simpleName)

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

abstract class FafDatabase : AutoCloseable {
    private val host = System.getenv("DATABASE_HOST") ?: "localhost"
    private val database = System.getenv("DATABASE_NAME") ?: "faf"
    private val username = System.getenv("DATABASE_USERNAME") ?: "root"
    private val password = System.getenv("DATABASE_PASSWORD") ?: "banana"

    private val connection: Connection =
        DriverManager.getConnection(
            "jdbc:mariadb://$host/$database?useSSL=false&serverTimezone=UTC",
            username,
            password
        )

    fun createStatement(): Statement = connection.createStatement()

    fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)

    override fun close() {
        connection.close()
    }
}

const val CHECKSUMS_FILENAME = "checksums.md5"

/**
 * Compute MD5 hash of a file.
 */
fun Path.md5(): String {
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

/**
 * Compute MD5 hash of a byte array.
 */
fun ByteArray.md5(): String {
    val md = MessageDigest.getInstance("MD5")
    md.update(this)
    return md.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Generate checksums.md5 content for a list of files.
 * Format: `<md5>  <relative-path>` per line, sorted alphabetically by path.
 *
 * @param files List of files to include
 * @param base Base path to compute relative paths from
 * @param contentProvider Optional function to provide content bytes for a file (for cases where
 *                        the actual content differs from the file on disk, e.g., path rewriting)
 */
fun generateChecksums(
    files: List<Path>,
    base: Path,
    contentProvider: ((Path) -> ByteArray)? = null
): String {
    return files
        .sortedBy { base.relativize(it).toString().replace("\\", "/") }
        .map { file ->
            val relativePath = base.relativize(file).toString().replace("\\", "/")
            val hash = if (contentProvider != null) {
                contentProvider(file).md5()
            } else {
                file.md5()
            }
            "$hash  $relativePath"
        }
        .joinToString("\n")
}

/**
 * Extract checksums.md5 content from an existing ZIP file.
 * Returns null if the file doesn't exist or doesn't contain checksums.md5.
 */
fun extractChecksumsFromZip(zipPath: Path): String? {
    if (!Files.exists(zipPath)) return null

    return try {
        ZipFile.builder().setPath(zipPath).get().use { zip ->
            val entry = zip.getEntry(CHECKSUMS_FILENAME) ?: return null
            zip.getInputStream(entry).bufferedReader().readText()
        }
    } catch (e: Exception) {
        null
    }
}

