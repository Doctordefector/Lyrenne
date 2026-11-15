package com.lyrenne.desktop.sound.listenbrainz

import com.lyrenne.desktop.AppPaths
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

/**
 * Listens ListenBrainz has not confirmed yet, on disk. A listen is written here before the first
 * attempt to send it and removed only once ListenBrainz answered 200, so a dropped connection, a
 * crash or quitting mid-request never loses one. Sending one twice is harmless: ListenBrainz
 * keeps one listen per user, timestamp and track.
 */
class ListenQueue(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(PendingListen.serializer())
    private val items = mutableListOf<PendingListen>()

    init {
        if (file.exists()) {
            runCatching { items += json.decodeFromString(serializer, file.readText()) }
                .onFailure { e ->
                    // Kept aside rather than overwritten: it may still hold listens worth recovering.
                    Timber.w("ListenBrainz queue unreadable, setting it aside: ${e.message}")
                    runCatching { file.renameTo(File(file.parentFile, file.name + ".broken")) }
                }
        }
    }

    val size: Int @Synchronized get() = items.size

    @Synchronized
    fun add(listen: PendingListen) {
        items += listen
        // A runaway backlog (a token left dead for years) is capped, oldest first.
        while (items.size > MAX_ITEMS) items.removeAt(0)
        save()
    }

    /** The oldest [max] listens, in the order they were heard. */
    @Synchronized
    fun peek(max: Int): List<PendingListen> = items.take(max)

    @Synchronized
    fun remove(sent: List<PendingListen>) {
        sent.forEach { items.remove(it) }
        save()
    }

    private fun save() {
        try {
            if (items.isEmpty()) file.delete() else AppPaths.writeAtomic(file, json.encodeToString(serializer, items))
        } catch (e: Exception) {
            Timber.e("ListenBrainz queue not saved: ${e.message}")
        }
    }

    companion object {
        const val MAX_ITEMS = 10_000
    }
}

/**
 * Sends what [queue] holds. One listen goes as `single`, a backlog as `import` batches.
 *
 * - Accepted: removed from the queue.
 * - Rejected (HTTP 400): a batch is retried one listen at a time so one bad listen cannot hold
 *   the others back; a single listen ListenBrainz refuses is dropped, because sending it again
 *   would be refused again.
 * - Unauthorized: everything stays, and nothing more is sent until the token changes.
 * - TryLater: everything stays, and [flush] reports how long to wait.
 */
class ListenSender(
    private val api: ListenBrainzApi,
    private val queue: ListenQueue,
    private val clientVersion: String,
) {
    sealed class Result {
        data object Done : Result()
        data object Unauthorized : Result()
        data class Wait(val reason: String, val retryAfterMs: Long?) : Result()
    }

    fun flush(token: String): Result {
        while (true) {
            val batch = queue.peek(BATCH)
            if (batch.isEmpty()) return Result.Done
            val type = if (batch.size == 1) ListenType.SINGLE else ListenType.IMPORT
            when (val outcome = api.submit(token, type, batch, clientVersion)) {
                SubmitOutcome.Accepted -> queue.remove(batch)
                SubmitOutcome.Unauthorized -> return Result.Unauthorized
                is SubmitOutcome.TryLater -> return Result.Wait(outcome.reason, outcome.retryAfterMs)
                is SubmitOutcome.Rejected -> {
                    if (batch.size == 1) {
                        Timber.w("ListenBrainz refused a listen, dropping it: ${outcome.message} (${batch[0].artist} - ${batch[0].track})")
                        queue.remove(batch)
                    } else {
                        // Find the bad one: each on its own, which keeps the good ones
                        for (listen in batch) {
                            when (val one = api.submit(token, ListenType.SINGLE, listOf(listen), clientVersion)) {
                                SubmitOutcome.Accepted -> queue.remove(listOf(listen))
                                is SubmitOutcome.Rejected -> {
                                    Timber.w("ListenBrainz refused a listen, dropping it: ${one.message}")
                                    queue.remove(listOf(listen))
                                }
                                SubmitOutcome.Unauthorized -> return Result.Unauthorized
                                is SubmitOutcome.TryLater -> return Result.Wait(one.reason, one.retryAfterMs)
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        /** ListenBrainz takes up to 1000 per request; smaller batches keep a 400 cheap to bisect. */
        const val BATCH = 100
    }
}

/** Retry delay after [failures] failed attempts in a row: 15 s, doubling, capped at 30 minutes. */
fun retryBackoffMs(failures: Int, serverHintMs: Long? = null): Long {
    val exp = 15_000L shl (failures - 1).coerceIn(0, 7)
    return maxOf(exp.coerceAtMost(30 * 60_000L), serverHintMs ?: 0L)
}
