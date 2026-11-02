package com.example.phone_number_lookup

suspend fun runTest() {
    val report = GoogleMetadataExtractor.extract("damonkirby86@gmail.com")
    println(report)
}
