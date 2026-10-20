package com.example.tiktokxsleppify

/**
 * Turns what the user said ("futbol videoları göster", "show me football videos", "muéstrame videos de fútbol")
 * into a clean search term ("futbol", "football", "fútbol"). Pure Kotlin so it can be unit tested on the JVM.
 */
object VoiceQuery {

    private val fillerWords = setOf(
        // Turkish
        "göster", "gösterir", "misin", "mısın", "musun", "müsün", "ara", "aratır", "bul", "getir", "aç", "izle",
        "bana", "bize", "lütfen", "hakkında", "ilgili",
        // English
        "show", "me", "search", "for", "find", "play", "please", "watch", "open", "some", "about", "the",
        // Spanish
        "muestra", "muéstrame", "muestrame", "busca", "buscar", "quiero", "ver", "pon", "ponme", "sobre"
    )

    // "de" (Spanish) / "ile" (Turkish) are only noise right after "videos" / before "ilgili".
    fun clean(text: String): String {
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val kept = ArrayList<String>()
        var previousDropped = false
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            val lower = token.lowercase()
            val next = tokens.getOrNull(i + 1)?.lowercase()
            val drop = when {
                lower.startsWith("video") -> true            // video, videos, videoları, videolar, videosu ...
                lower.startsWith("tiktok") -> true           // tiktok'ta, tiktokta, TikTok
                lower in fillerWords -> true
                lower == "de" && previousDropped -> true
                lower == "ile" && next == "ilgili" -> true
                else -> false
            }
            if (!drop) kept.add(token)
            previousDropped = drop
            i++
        }
        val cleaned = kept.joinToString(" ").trim()
        return if (cleaned.isEmpty()) text.trim() else cleaned
    }
}
