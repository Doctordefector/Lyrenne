package com.metrolist.music.desktop.ui.screens

import java.net.UnknownHostException
import java.net.ConnectException
import java.net.SocketTimeoutException

/**
 * Converts raw exception messages into user-friendly error strings.
 */
fun friendlyErrorMessage(throwable: Throwable?, fallback: String = "Something went wrong"): String {
    return when (throwable) {
        is UnknownHostException,
        is ConnectException -> "No internet connection. Please check your network and try again."
        is SocketTimeoutException -> "Connection timed out. Please try again."
        else -> {
            val msg = throwable?.message?.lowercase() ?: return fallback
            when {
                "no such host" in msg || "unable to resolve" in msg || "network" in msg ->
                    "No internet connection. Please check your network and try again."
                "timeout" in msg -> "Connection timed out. Please try again."
                "403" in msg || "forbidden" in msg -> "Access denied. You may need to sign in again."
                "404" in msg || "not found" in msg -> "Content not found. It may have been removed."
                "401" in msg || "unauthorized" in msg -> "Session expired. Please sign in again."
                "429" in msg || "rate limit" in msg -> "Too many requests. Please wait a moment."
                "500" in msg || "internal server" in msg -> "Server error. Please try again later."
                else -> fallback
            }
        }
    }
}
