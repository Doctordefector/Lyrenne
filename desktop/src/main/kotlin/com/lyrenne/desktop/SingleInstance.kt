package com.lyrenne.desktop

import timber.log.Timber
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import kotlin.concurrent.thread

/**
 * One running copy per data folder. A second launch pokes the first over a socket in the data
 * folder and exits, and the first brings its window back.
 *
 * Without it, a window hidden to a tray icon that does not answer clicks (Java's XEmbed icon
 * behind KDE's or GNOME's tray proxy) could only be "reopened" by launching again, which started
 * another copy, hidden to its own dead icon, on the same database.
 */
object SingleInstance {
    /** Set once the window exists. A poke before then is dropped: the window is opening anyway. */
    @Volatile var onActivate: (() -> Unit)? = null

    /** False when another copy answered and was asked to show itself; the caller should exit. */
    fun claim(): Boolean {
        val socket = File(AppPaths.dataDir, "instance.sock")
        val address = UnixDomainSocketAddress.of(socket.toPath())
        runCatching {
            SocketChannel.open(address).use { it.write(ByteBuffer.wrap(byteArrayOf(1))) }
            return false
        }
        // Nobody listening: first launch, or a socket file left by a crash.
        // ponytail: any failure here runs without the guard rather than refusing to start.
        runCatching {
            socket.delete()
            val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(address)
            socket.deleteOnExit()
            thread(isDaemon = true, name = "single-instance") {
                while (true) runCatching { server.accept().close(); onActivate?.invoke() }
            }
        }.onFailure { Timber.w("Single-instance guard unavailable: ${it.message}") }
        return true
    }
}
