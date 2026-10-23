package fr.jarvis.telephone

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class MessageTest {
    private fun ev(corps: String, titre: String? = null, time: Long = 1_761_120_000) = JSONObject()
        .put("event", "message").put("id", "x").put("time", time).put("message", corps)
        .apply { if (titre != null) put("title", titre) }

    @Test fun notifJson() {
        val m = Message.lire(ev("""{"type":"notif","titre":"Jarvis","message":"Salut","priorite":"haute"}"""))
        assertEquals(Message.Notif("Jarvis", "Salut", 4), m)
    }

    @Test fun notifAvecLien() {
        val m = Message.lire(ev("""{"type":"notif","titre":"Départ","message":"Go","url":"https://waze.com/ul?q=x"}"""))
        assertEquals(Message.Notif("Départ", "Go", 3, "https://waze.com/ul?q=x"), m)
    }

    @Test fun texteSimple() {
        assertEquals(Message.Notif("Test", "bonjour", 3), Message.lire(ev("bonjour", "Test")))
        assertEquals(Message.Notif("Jarvis", "{pas du json", 3), Message.lire(ev("{pas du json")))
    }

    @Test fun reveilTimestamp() {
        val m = Message.lire(ev("""{"type":"reveil","id":"r1","timestamp":1761198600,"libelle":"Chantier"}"""))
        assertEquals(Message.Reveil("r1", 1_761_198_600_000, "Chantier"), m)
    }

    @Test fun reveilHeureMinute() {
        val envoi = Calendar.getInstance().apply { set(2026, 9, 22, 22, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val m = Message.lire(ev("""{"type":"reveil","heure":7,"minute":30}""", time = envoi / 1000)) as Message.Reveil
        val c = Calendar.getInstance().apply { timeInMillis = m.quand }
        assertEquals(23, c.get(Calendar.DAY_OF_MONTH)) // 22h → 7h30 le lendemain
        assertEquals(7, c.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, c.get(Calendar.MINUTE))
        assertEquals("Réveil", m.libelle)
        assertTrue(m.id.startsWith("r-"))
    }

    @Test fun annulation() {
        assertEquals(Message.AnnulerReveil("r1"), Message.lire(ev("""{"type":"annuler_reveil","id":"r1"}""")))
        assertEquals(Message.AnnulerReveil(null), Message.lire(ev("""{"type":"annuler_reveil"}""")))
    }

    @Test fun keepaliveIgnore() {
        assertNull(Message.lire(JSONObject().put("event", "keepalive").put("id", "k")))
    }

    // Vecteur produit par Jarvis (Node, lib/ntfy.ts) : clé 32 × 0x07, iv 12 × 0x03.
    private val cle = "BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc"
    private val chiffre = "AwMDAwMDAwMDAwMDXtzXeipNfHhYLiwogjHdIM9IiZvG8LZKDZpEGJOEMsyTi19P/ojWIantaT+vjKIGbGFB5EoiHUYaQAzegB8qjZv2ahZsPQXgPaZsxg=="

    @Test fun messageChiffre() {
        val m = Message.lire(ev("""{"chiffre":"$chiffre"}"""), cle)
        assertEquals(Message.Notif("Départ", "BECKER à 16h", Message.NORMALE), m)
    }

    @Test fun messageChiffreSansCle() {
        val m = Message.lire(ev("""{"chiffre":"$chiffre"}"""), "") as Message.Notif
        assertTrue(m.texte.contains("Lier à Jarvis"))
    }
}
