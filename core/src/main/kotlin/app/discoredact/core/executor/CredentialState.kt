package app.discoredact.core.executor

/**
 * What is stored, as the Credentials screen needs to say it. The three cases are told apart so an unreadable set
 * (the phone's key was invalidated or removed, or the file is damaged) is not reported as "none": the fix differs.
 * It carries a capture time and an age, never a value.
 */
sealed interface CredentialState {
    /** No ciphertext file. */
    data object None : CredentialState

    /** A file exists and cannot be decrypted or decoded. A real run never starts on it (load() returns null). */
    data object Unreadable : CredentialState

    data class Present(val status: CredentialStatus) : CredentialState

    /** The line the screens show. [formatTime] renders an epoch-millisecond capture time. */
    fun describe(formatTime: (Long) -> String): String = when (this) {
        None -> "No credentials stored."
        Unreadable -> "Stored credentials cannot be read (the phone's key changed or the file is damaged). Wipe credentials, then capture again."
        is Present ->
            if (status.stale) {
                "Captured ${formatTime(status.capturedAt)}, ${status.ageDays} days ago: STALE (over 14 days; the client build in the headers no longer matches a live session). Capture again before a real run."
            } else {
                "Captured ${formatTime(status.capturedAt)}, ${status.ageDays} days ago."
            }
    }

    /** Whether the Wipe credentials button applies: something is stored, readable or not. */
    val wipeable: Boolean get() = this !is None

    /** Whether a real run could use it. */
    val usable: Boolean get() = this is Present
}
