import com.google.protobuf.gradle.*
import java.net.URI
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

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

// The one version number. AutoUpdater reads it from the generated version.properties.
val lyrenneVersion = "2.15.0"

// Compose packages for the OS it runs on, so per-OS build steps key off the host.
val hostOs = System.getProperty("os.name").orEmpty()
val isWindowsHost = hostOs.startsWith("Windows")

// Include shared module sources directly (they are Android library modules but pure Kotlin/JVM code)
sourceSets {
    main {
        kotlin.srcDir("${project.rootDir}/innertube/src/main/kotlin")
        kotlin.srcDir("${project.rootDir}/lrclib/src/main/kotlin")
        kotlin.srcDir("${project.rootDir}/betterlyrics/src/main/kotlin")
        kotlin.srcDir("${project.rootDir}/kugou/src/main/kotlin")
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
    // version.properties carries lyrenneVersion into the app, so it is defined exactly once.
    val version = lyrenneVersion
    inputs.property("version", version)
    filesMatching("version.properties") { expand("version" to version) }
}

dependencies {
    testImplementation(libs.junit)
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
    implementation(libs.extractor) { exclude(group = "com.google.protobuf") }
    implementation(libs.ktor.client.cio) // Used by lrclib

    // Image loading for desktop
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")

    // Audio playback - VLC bindings
    implementation("uk.co.caprica:vlcj:4.8.3")
    implementation("net.java.dev.jna:jna:5.17.0")
    implementation("net.java.dev.jna:jna-platform:5.17.0")

    // OS media session: Windows SMTC, Linux MPRIS (Now Playing, media buttons, timeline)
    implementation("dev.toastbits:mediasession:0.1.1") {
        // The D-Bus runtime (dbus-java + jnr) is only used for MPRIS. Compose packages for the
        // host OS, so a Windows build has no use for it and the portable ZIP stays lean.
        if (isWindowsHost) exclude(group = "com.github.hypfvieh")
    }

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
        mainClass = "com.lyrenne.desktop.MainKt"

        // Without an explicit ceiling the JVM takes a quarter of physical RAM as max heap, 8 GB
        // on a 32 GB machine. GC then has no reason to work, and a measured install sat at 455 MB
        // RSS for a music player. 512 MB is comfortably above what playback, the library and the
        // image cache actually need; Coil's caches are bounded separately in Main.kt so they no
        // longer scale off this number.
        jvmArgs += listOf("-Xmx512m")

        nativeDistributions {
            // No installer formats on purpose. Windows ships the portable ZIP built from
            // createDistributable (packagePortableZip); Linux .deb/.rpm are built from the same
            // distributable by nfpm (packaging/linux/nfpm.yaml), because Compose's own packages
            // cannot declare the VLC dependency. Do not add Msi/Exe here.

            // Bundled VLC/ffmpeg: Compose picks resources/<os>-<arch>/ for the host, and
            // resources/linux-x64/ is empty — Linux uses the distro's VLC and ffmpeg.
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))

            // Include required JVM modules in the custom runtime. jdk.security.auth: dbus-java
            // (MPRIS on Linux) reads the Unix uid through it for D-Bus authentication.
            modules("java.sql", "java.naming", "java.net.http", "jdk.unsupported", "jdk.security.auth")

            packageName = "Lyrenne"
            packageVersion = lyrenneVersion
            description = "Lyrenne, a YouTube Music player for your desktop"
            vendor = "Lyrenne"

            windows {
                // jpackage embeds this into Lyrenne.exe (verified via RT_GROUP_ICON).
                iconFile.set(project.file("src/main/resources/icon.ico"))
            }
            linux {
                iconFile.set(project.file("src/main/resources/icon.png"))
            }
        }

        buildTypes.release {
            proguard {
                isEnabled = false
            }
        }
    }
}

/**
 * Fetch the bundled ffmpeg used by car/USB export.
 *
 * Not committed: the binary is ~114 MB and GitHub rejects files over 100 MB, so the repo would
 * need Git LFS purely to carry a build input. Downloading it on demand keeps the checkout light
 * while the release ZIP still ships it, so export works with no user setup.
 *
 * BtbN's LGPL build is the smallest official one that still carries what CarExport needs —
 * verified present: libmp3lame, loudnorm (EBU R128), aformat, pan, and aac/opus/mp3/flac/vorbis
 * decoders.
 */
tasks.register("fetchFfmpeg") {
    val target = layout.projectDirectory.file("resources/windows-x64/ffmpeg/ffmpeg.exe").asFile
    val downloadUrl =
        "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-lgpl.zip"
    outputs.file(target)
    // Windows only: Linux uses ffmpeg from PATH (a recommended package dependency).
    val windowsHost = isWindowsHost
    onlyIf { windowsHost && !target.exists() }

    doLast {
        logger.lifecycle("Downloading ffmpeg (~147 MB) — one time, cached at ${target.absolutePath}")
        target.parentFile.mkdirs()
        val tmpZip = File(temporaryDir, "ffmpeg.zip")
        URI(downloadUrl).toURL().openStream().use { input ->
            tmpZip.outputStream().use { input.copyTo(it) }
        }

        ZipFile(tmpZip).use { zip ->
            val entry = zip.entries().asSequence()
                .firstOrNull { it.name.endsWith("bin/ffmpeg.exe") }
                ?: throw GradleException("ffmpeg.exe not found inside $downloadUrl")
            zip.getInputStream(entry).use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
        tmpZip.delete()
        if (target.length() == 0L) throw GradleException("Failed to extract ffmpeg.exe")
        logger.lifecycle("ffmpeg ready: ${target.length() / 1024 / 1024} MB")
    }
}

// The distributable copies resources/ wholesale, so ffmpeg must exist before it runs. Matched
// lazily — the Compose plugin registers these tasks after this script body has run.
tasks.matching { it.name == "createDistributable" || it.name == "prepareAppResources" }
    .configureEach { dependsOn("fetchFfmpeg") }

/**
 * Build the release ZIP safely.
 *
 * Running the app from the distributable folder makes AppPaths write `data/` right next to
 * Lyrenne.exe — credentials.json, the library DB, preferences. Smoke-testing before zipping
 * therefore bakes real login cookies into the release artifact. This happened once (v2.6.0,
 * published to a PUBLIC repo) so it is now automated rather than left to memory:
 * purge runtime dirs, zip with 7z, then FAIL the build if anything sensitive is inside.
 */
tasks.register("packagePortableZip") {
    dependsOn("createDistributable")
    // Resolved at configuration time — doLast must not reference script/project objects
    // or the Gradle configuration cache refuses to serialize the task.
    val appDir = layout.buildDirectory.dir("compose/binaries/main/app").get().asFile
    val imageDir = File(appDir, "Lyrenne")
    val zipFile = File(appDir, "Lyrenne-$lyrenneVersion-portable.zip")
    val sevenZipCandidates = listOf(
        File("C:/Program Files/7-Zip/7z.exe"),
        File("C:/Program Files (x86)/7-Zip/7z.exe")
    )
    // The portable ZIP is the Windows release. Linux packages come from nfpm instead.
    val windowsHost = isWindowsHost
    onlyIf { windowsHost }

    doLast {
        // 1. Purge anything the app generated while it was run from this folder
        listOf("data", "Downloads", "updates").forEach { name ->
            val dir = File(imageDir, name)
            if (dir.exists()) {
                dir.deleteRecursively()
                logger.lifecycle("Purged runtime dir: $name")
            }
        }
        // The crash log sits next to the exe and records song titles and the account name,
        // so it is user data too — never ship one left behind by a smoke test.
        File(imageDir, "lyrenne.log").takeIf { it.exists() }?.let {
            it.delete()
            logger.lifecycle("Purged lyrenne.log")
        }

        // 1b. sqlite-jdbc ships native libraries for Linux, Android, musl, FreeBSD and macOS.
        // A Windows-only distributable needs none of them — ~9 MB of the 12.9 MB jar.
        File(imageDir, "app").listFiles { f -> f.name.startsWith("sqlite-jdbc") && f.extension == "jar" }
            ?.forEach { jar ->
                val before = jar.length()
                val stripped = File(jar.parentFile, "${jar.name}.stripped")
                ZipOutputStream(stripped.outputStream().buffered()).use { out ->
                    ZipFile(jar).use { zip ->
                        zip.entries().asSequence()
                            .filter {
                                !it.name.startsWith("org/sqlite/native/") ||
                                    it.name.startsWith("org/sqlite/native/Windows/x86_64/")
                            }
                            .forEach { entry ->
                                out.putNextEntry(ZipEntry(entry.name))
                                if (!entry.isDirectory) zip.getInputStream(entry).use { it.copyTo(out) }
                                out.closeEntry()
                            }
                    }
                }
                jar.delete()
                stripped.renameTo(jar)
                logger.lifecycle(
                    "Stripped foreign sqlite natives: ${before / 1024 / 1024} MB -> ${jar.length() / 1024 / 1024} MB"
                )
            }

        // 2. Always start from a fresh archive — `7z a` ADDS to an existing zip, which would
        //    silently retain entries that were just purged from disk.
        if (zipFile.exists()) zipFile.delete()

        val sevenZip = sevenZipCandidates.firstOrNull { it.exists() }
            ?: throw GradleException("7-Zip not found. Never use Compress-Archive — it writes backslash entries that break Java's ZipEntry.isDirectory().")

        val exit = ProcessBuilder(
            sevenZip.absolutePath, "a", "-tzip", zipFile.absolutePath, "Lyrenne/*", "-mx5"
        ).directory(appDir).redirectErrorStream(true).start().let { p ->
            p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor()
        }
        if (exit != 0) throw GradleException("7z failed with exit code $exit")

        // 3. Refuse to hand over an artifact containing secrets or a broken entry format
        val forbidden = Regex(
            "(?i)(^|/)(data/|credentials|preferences\\.properties|lyrenne\\.db|login-profile|lyrenne\\.log)"
        )
        val offenders = mutableListOf<String>()
        var backslashEntries = 0
        ZipFile(zipFile).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                if (forbidden.containsMatchIn(entry.name)) offenders += entry.name
                if (entry.name.contains('\\')) backslashEntries++
            }
        }
        if (offenders.isNotEmpty()) {
            zipFile.delete()
            throw GradleException("REFUSING TO SHIP: zip contains user data — ${offenders.joinToString()}")
        }
        if (backslashEntries > 0) {
            zipFile.delete()
            throw GradleException("REFUSING TO SHIP: $backslashEntries backslash entries — Java cannot extract this zip")
        }

        logger.lifecycle("Portable zip verified clean: ${zipFile.absolutePath} (${zipFile.length() / 1024 / 1024} MB)")
    }
}

/**
 * Update signing. The private key never goes in the repo or on GitHub: a compromised account
 * must not be able to ship code, and that only holds while the key is somewhere else (offline).
 * Location: -PupdateSigningKey=<path>, else $LYRENNE_SIGNING_KEY, else ~/.lyrenne/update-signing.key.
 */
fun signingKeyPath(): String =
    (findProperty("updateSigningKey") as String?)
        ?: System.getenv("LYRENNE_SIGNING_KEY")
        ?: "${System.getProperty("user.home")}/.lyrenne/update-signing.key"

/** One-time: creates the Ed25519 pair and prints the public key for AutoUpdater.UPDATE_PUBLIC_KEY. */
tasks.register("generateUpdateSigningKey") {
    val keyFile = File(signingKeyPath())
    doLast {
        if (keyFile.exists()) throw GradleException("Refusing to overwrite ${keyFile.absolutePath}")
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        keyFile.parentFile.mkdirs()
        keyFile.writeText(Base64.getEncoder().encodeToString(pair.private.encoded))
        logger.lifecycle("Private key written to ${keyFile.absolutePath}. Back it up offline.")
        logger.lifecycle("Paste into AutoUpdater.UPDATE_PUBLIC_KEY:")
        logger.lifecycle(Base64.getEncoder().encodeToString(pair.public.encoded))
    }
}

/**
 * Writes Lyrenne-X.Y.Z-portable.zip.sig: Ed25519 over the ZIP's SHA-256, base64. Upload it to
 * the release next to the ZIP. AutoUpdater.verifySignature is the other half.
 */
tasks.register("signPortableZip") {
    dependsOn("packagePortableZip")
    val zipFile = layout.buildDirectory.file("compose/binaries/main/app/Lyrenne-$lyrenneVersion-portable.zip").get().asFile
    val keyFile = File(signingKeyPath())
    doLast {
        if (!keyFile.exists()) throw GradleException("No signing key at ${keyFile.absolutePath}")
        val key = KeyFactory.getInstance("Ed25519").generatePrivate(
            PKCS8EncodedKeySpec(Base64.getDecoder().decode(keyFile.readText().trim()))
        )
        val md = MessageDigest.getInstance("SHA-256")
        zipFile.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        val sig = Signature.getInstance("Ed25519").run { initSign(key); update(md.digest()); sign() }
        val sigFile = File(zipFile.absolutePath + ".sig")
        sigFile.writeText(Base64.getEncoder().encodeToString(sig))
        logger.lifecycle("Signed: ${sigFile.absolutePath}")
    }
}

// CI (-Pci) skips the smoke tests: they call real YouTube or real OS APIs, and a runner's network
// being blocked is not a Lyrenne failure. Run them locally before a release.
tasks.withType<Test>().configureEach {
    if (project.hasProperty("ci")) exclude("**/*SmokeTest*")
}

sqldelight {
    databases {
        create("LyrenneDatabase") {
            packageName.set("com.lyrenne.desktop.db")
        }
    }
}
