package com.example.phone_number_lookup

data class SiteIssue(
    val site: String,
    val reason: String,
    val url: String = "",
    val retryable: Boolean = true
)
