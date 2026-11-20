package com.paradown.app.data.model

/**
 * Estados persistentes de um download.
 */
enum class DownloadStatus {
    QUEUED,
    PROBING,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELED;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == CANCELED

    val isRunning: Boolean
        get() = this == PROBING || this == DOWNLOADING

    val canPause: Boolean
        get() = isRunning

    val canResume: Boolean
        get() = this == PAUSED || this == FAILED || this == QUEUED
}
