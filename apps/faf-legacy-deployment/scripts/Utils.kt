package com.faforever

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import org.eclipse.jgit.api.Git
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.Statement

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

