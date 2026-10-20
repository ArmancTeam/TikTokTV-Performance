package com.example.tiktokxsleppify

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceQueryTest {

    @Test
    fun turkish() {
        assertEquals("futbol", VoiceQuery.clean("futbol videoları"))
        assertEquals("futbol", VoiceQuery.clean("bana futbol videoları göster"))
        assertEquals("komik kediler", VoiceQuery.clean("TikTok'ta komik kediler ara"))
        assertEquals("futbol", VoiceQuery.clean("futbol ile ilgili videolar"))
    }

    @Test
    fun english() {
        assertEquals("football", VoiceQuery.clean("show me football videos"))
        assertEquals("funny cats", VoiceQuery.clean("search for funny cats"))
    }

    @Test
    fun spanish() {
        assertEquals("fútbol", VoiceQuery.clean("muéstrame videos de fútbol"))
        assertEquals("recetas de pasta", VoiceQuery.clean("busca recetas de pasta"))
    }

    @Test
    fun keepsOriginalWhenEverythingIsNoise() {
        assertEquals("videolar", VoiceQuery.clean("videolar"))
        assertEquals("show me", VoiceQuery.clean("show me"))
    }

    @Test
    fun keepsMeaningfulWords() {
        assertEquals("Tom ile Jerry", VoiceQuery.clean("Tom ile Jerry"))
        assertEquals("real madrid goller", VoiceQuery.clean("real madrid goller"))
    }
}
