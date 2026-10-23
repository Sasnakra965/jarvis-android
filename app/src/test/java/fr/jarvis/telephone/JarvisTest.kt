package fr.jarvis.telephone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JarvisTest {
    @Test
    fun lienDuQrCode() {
        assertEquals(
            "https://jarvis.tail1234.ts.net" to "Ab-c_12",
            Jarvis.lireQr("https://jarvis.tail1234.ts.net/pair?token=Ab-c_12"),
        )
        assertNull(Jarvis.lireQr("https://exemple.fr/autre?token=x"))
        assertNull(Jarvis.lireQr("bonjour"))
    }

    @Test
    fun photosAvantVideos() {
        // true = vidéo
        assertEquals(listOf(false, false) to 1, Jarvis.choisir(listOf(false, true, false)) { it })
        assertEquals(listOf(true) to 2, Jarvis.choisir(listOf(true, true, true)) { it })
        assertEquals(emptyList<Boolean>() to 0, Jarvis.choisir(emptyList<Boolean>()) { it })
    }
}
