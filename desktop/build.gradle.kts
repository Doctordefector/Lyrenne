import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    alias(libs.plugins.kotlin.serialization)
    id("app.cash.sqldelight") version "2.0.2"
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
    }
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

    // JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    // Logging
    implementation("org.slf4j:slf4j-simple:2.0.16")

    // SQLDelight for local database
    implementation("app.cash.sqldelight:sqlite-driver:2.0.2")
    implementation("app.cash.sqldelight:coroutines-extensions:2.0.2")
}

compose.desktop {
    application {
        mainClass = "com.metrolist.music.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)

            packageName = "Metrolist"
            packageVersion = "1.0.0"
            description = "YouTube Music Desktop Client"
            vendor = "Metrolist"

            windows {
                menuGroup = "Metrolist"
                upgradeUuid = "b5e74c38-1c2d-4e8f-9a7b-6d5e4f3c2a1b"
                // iconFile.set(project.file("src/main/resources/icon.ico"))
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

sqldelight {
    databases {
        create("MetrolistDatabase") {
            packageName.set("com.metrolist.music.desktop.db")
        }
    }
}
