package com.example.relapse_watch.services

import org.junit.Assert.assertTrue
import org.junit.Test

class MediaUrlValidatorTest {

    @Test
    fun validate_rejectsNonHttpsUrl() {
        val result = MediaUrlValidator.validate("http://firebasestorage.googleapis.com/file")
        assertTrue(result.isFailure)
    }

    @Test
    fun validate_rejectsNonAllowlistedHost() {
        val result = MediaUrlValidator.validate("https://example.com/file")
        assertTrue(result.isFailure)
    }

    @Test
    fun validate_acceptsAllowlistedHttpsHost() {
        val result = MediaUrlValidator.validate("https://firebasestorage.googleapis.com/v0/b/example/o/file")
        assertTrue(result.isSuccess)
    }
}
