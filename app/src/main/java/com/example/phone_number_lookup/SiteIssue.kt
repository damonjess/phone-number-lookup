package com.example.phone_number_lookup

/** One site the scan could not check automatically. [profileUrl] lets the user check it by hand. */
data class SiteIssue(
    val site: String,
    val profileUrl: String,
    val reason: ProbeFailure,
    val detail: String = "",
) {
    /** Worth another automatic attempt. Bot checks and "no clear answer" give the same result again. */
    val retryable: Boolean
        get() = reason == ProbeFailure.NETWORK || reason == ProbeFailure.RATE_LIMITED ||
            reason == ProbeFailure.SERVER_ERROR || reason == ProbeFailure.FORBIDDEN
}
