import com.google.protobuf.gradle.*
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    alias(libs.plugins.kotlin.serialization)
    id("app.cash.sqldelight") version "2.0.2"
    id("com.google.protobuf") version "0.9.4"
}

kotlin {
    jvmToolchain(21)
}

// Include shared module sources directly (they are Android library modules but pure Kotlin/JVM code)
sourceSets {
    main {
        kotlin.srcDir("${project.rootDir}/innertube/src/main/kotlin")
        kotlin.srcDir("${project.rootDir}/lrclib/src/main/kotlin")
        kotlin.srcDir("${project.rootDir}/kizzy/src/main/kotlin")
        kotlin.srcDir("${project.rootDir}/lastfm/src/main/kotlin")
        kotlin.srcDir("${project.rootDir}/shazamkit/src/main/kotlin")
        proto {
            srcDir("src/main/proto")
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.5"
    }
}

// Exclude proto files from resources (protobuf plugin already handles them)
tasks.named<ProcessResources>("processResources") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

dependencies {
    // Compose Desktop
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(compose.components.resources)

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")

    // Networking (used by innertube)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.encoding)

    // Shared module dependencies
    implementation(libs.brotli)
    implementation(libs.newpipeextractor)
    implementation(libs.ktor.client.cio) // Used by lrclib
    implementation(libs.json) // Used by kizzy (org.json)

    // Image loading for desktop
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")

    // Audio playback - VLC bindings
    implementation("uk.co.caprica:vlcj:4.8.3")
    implementation("net.java.dev.jna:jna:5.17.0")
    implementation("net.java.dev.jna:jna-platform:5.17.0")

    // JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    // Logging
    implementation("org.slf4j:slf4j-simple:2.0.16")

    // SQLDelight for local database
    implementation("app.cash.sqldelight:sqlite-driver:2.0.2")
    implementation("app.cash.sqldelight:coroutines-extensions:2.0.2")

    // Protocol Buffers (Listen Together)
    implementation("com.google.protobuf:protobuf-java:3.25.5")

    // OkHttp WebSocket (Listen Together) — already pulled by ktor-client-okhttp but declare explicitly
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

compose.desktop {
    application {
        mainClass = "com.metrolist.music.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)

            // Bundle VLC libraries with the app
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))

            // Include required JVM modules in the custom runtime
            modules("java.sql", "java.naming", "java.net.http", "jdk.unsupported")

            packageName = "Metrolist"
            packageVersion = "2.2.1"
            description = "YouTube Music Desktop Client"
            vendor = "Metrolist"

            windows {
                menuGroup = "Metrolist"
                upgradeUuid = "b5e74c38-1c2d-4e8f-9a7b-6d5e4f3c2a1b"
                iconFile.set(project.file("src/main/resources/icon.ico"))
                dirChooser = true
                shortcut = true
                menu = true
            }
        }

        buildTypes.release {
            proguard {
                isEnabled = false
            }
        }
    }
}

// Post-build task: patch icon into portable exe using Resource Hacker
// (Compose Desktop's iconFile only works for MSI, not createDistributable)
tasks.register("patchPortableIcon") {
    dependsOn("createDistributable")
    doLast {
        val exeFile = file("build/compose/binaries/main/app/Metrolist/Metrolist.exe")
        val iconFile = file("src/main/resources/icon.ico")
        val resourceHacker = file("C:/Temp/ResourceHacker/ResourceHacker.exe")

        if (!resourceHacker.exists()) {
            logger.warn("Resource Hacker not found at ${resourceHacker.absolutePath} — skipping icon patch")
            return@doLast
        }
        if (!exeFile.exists() || !iconFile.exists()) {
            logger.warn("Exe or icon not found — skipping icon patch")
            return@doLast
        }

        val patched = file("build/compose/binaries/main/app/Metrolist/Metrolist-patched.exe")
        val result = ProcessBuilder(
            resourceHacker.absolutePath,
            "-open", exeFile.absolutePath,
            "-save", patched.absolutePath,
            "-action", "addoverwrite",
            "-res", iconFile.absolutePath,
            "-mask", "ICONGROUP,MAINICON,"
        ).start().waitFor()
        logger.lifecycle("Resource Hacker exited with code $result")
        if (patched.exists()) {
            patched.copyTo(exeFile, overwrite = true)
            patched.delete()
            logger.lifecycle("Icon patched into portable exe successfully")
        }
    }
}

sqldelight {
    databases {
        create("MetrolistDatabase") {
            packageName.set("com.metrolist.music.desktop.db")
        }
    }
}
