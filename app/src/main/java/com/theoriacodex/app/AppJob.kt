package com.theoriacodex.app

/**
 * Every `JobScheduler` job the app schedules, with its ID. IDs are unique per app: scheduling one replaces any pending
 * or running job with the same ID, whatever its service. Persisted jobs keep their IDs across updates, so never renumber
 * or reuse one; give a new job the next free number here. Scripts that drive jobs by number name them too.
 */
internal enum class AppJob(val id: Int) {
    /** The periodic check for new chapters and episodes (`SeriesUpdateJobService`). */
    SERIES_UPDATE(3101),

    /** Chapter downloads the user asked for (`SeriesDownloadJobService`). */
    USER_DOWNLOAD(3102),

    /** Chapter downloads the check for new chapters queued (`SeriesDownloadJobService`). */
    AUTOMATIC_DOWNLOAD(3103),

    /** Hashing and tagging the Library's pictures from their stills (`LibraryPictureJobService`). */
    LIBRARY_PICTURES(3104),

    /** Reading playing posts' frames while the device is idle (`LibraryFrameJobService`). */
    LIBRARY_FRAMES(3105),
}
