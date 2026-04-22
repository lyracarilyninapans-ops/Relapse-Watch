package com.example.relapse_watch.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeoReminderRepositoryImplTest {

    @Test
    fun `extractCoordinates reads direct latitude longitude numbers`() {
        val data = mapOf<String, Any>(
            "latitude" to 12.34,
            "longitude" to 56.78
        )

        val result = GeoReminderRepositoryImpl.extractCoordinatesForTest(data)

        assertEquals(12.34, result?.first)
        assertEquals(56.78, result?.second)
    }

    @Test
    fun `extractCoordinates reads lat lng string values`() {
        val data = mapOf<String, Any>(
            "lat" to "12.34",
            "lng" to "56.78"
        )

        val result = GeoReminderRepositoryImpl.extractCoordinatesForTest(data)

        assertEquals(12.34, result?.first)
        assertEquals(56.78, result?.second)
    }

    @Test
    fun `extractRadiusMeters falls back and clamps minimum`() {
        val fallback = GeoReminderRepositoryImpl.extractRadiusMetersForTest(emptyMap())
        val clamped = GeoReminderRepositoryImpl.extractRadiusMetersForTest(mapOf("radiusMeters" to 0))
        val parsed = GeoReminderRepositoryImpl.extractRadiusMetersForTest(mapOf("radius" to "85"))

        assertEquals(100, fallback)
        assertEquals(1, clamped)
        assertEquals(85, parsed)
    }

    @Test
    fun `extractMediaUrls prefers mediaItems values`() {
        val data = mapOf<String, Any>(
            "mediaItems" to listOf(
                mapOf("type" to "photo", "cloudUrl" to "https://cdn/photo.jpg"),
                mapOf("type" to "audio", "cloudUrl" to "https://cdn/audio.m4a"),
                mapOf("type" to "video", "cloudUrl" to "https://cdn/video.mp4")
            ),
            "imageUrl" to "https://legacy/photo.jpg",
            "audioUrl" to "https://legacy/audio.m4a",
            "videoUrl" to "https://legacy/video.mp4"
        )

        val (imageUrl, audioUrl, videoUrl) = GeoReminderRepositoryImpl.extractMediaUrls(data)

        assertEquals("https://cdn/photo.jpg", imageUrl)
        assertEquals("https://cdn/audio.m4a", audioUrl)
        assertEquals("https://cdn/video.mp4", videoUrl)
    }

    @Test
    fun `extractMediaUrls falls back to legacy direct fields`() {
        val data = mapOf<String, Any>(
            "imageUrl" to "https://legacy/photo.jpg",
            "audioUrl" to "https://legacy/audio.m4a",
            "videoUrl" to "https://legacy/video.mp4"
        )

        val (imageUrl, audioUrl, videoUrl) = GeoReminderRepositoryImpl.extractMediaUrls(data)

        assertEquals("https://legacy/photo.jpg", imageUrl)
        assertEquals("https://legacy/audio.m4a", audioUrl)
        assertEquals("https://legacy/video.mp4", videoUrl)
    }

    @Test
    fun `extractMediaUrls handles missing and blank values`() {
        val data = mapOf<String, Any>(
            "mediaItems" to listOf(
                mapOf("type" to "audio", "cloudUrl" to "")
            ),
            "audioUrl" to ""
        )

        val (imageUrl, audioUrl, videoUrl) = GeoReminderRepositoryImpl.extractMediaUrls(data)

        assertNull(imageUrl)
        assertNull(audioUrl)
        assertNull(videoUrl)
    }
}
