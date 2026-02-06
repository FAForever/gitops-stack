plugins {
    kotlin("jvm") version "2.3.0"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.7")
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.5.0.202512021534-r")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("ch.qos.logback:logback-classic:1.5.28")
}

// Use the root level for files
sourceSets {
    main {
        kotlin.srcDirs(".")
    }
}

tasks.register<JavaExec>("deployCoop") {
    group = "application"
    description = "Deploy coop"

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.faforever.coopdeployer.CoopDeployerKt")
}

tasks.register<JavaExec>("deployCoopMaps") {
    group = "application"
    description = "Deploy coop maps"

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.faforever.coopmapdeployer.CoopMapDeployerKt")
}