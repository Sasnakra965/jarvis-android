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

    @Test fun briefing() {
        val m = Message.lire(ev("""{"type":"briefing","titre":"☀️ Briefing","message":"☀️ Bonjour boss","parole":"Bonjour boss","url":"https://j/kanban","attendre_reveil":true}"""))
        assertEquals(Message.Briefing("☀️ Briefing", "☀️ Bonjour boss", "Bonjour boss", "https://j/kanban", true), m)
        val sansParole = Message.lire(ev("""{"type":"briefing","titre":"B","message":"Salut"}""")) as Message.Briefing
        assertEquals("Salut", sansParole.parole)
        assertEquals(false, sansParole.attendreReveil)
    }

    @Test fun choix() {
        val m = Message.lire(ev("""{"type":"choix","titre":"Nouvel expéditeur","message":"Jean","url":"https://j/mail/x","choix":[{"libelle":"Spam","url":"https://j/api/mails/choix?j=x&c=spam"},{"libelle":"Pas important","url":"https://j/a"},{"libelle":"Important","url":"https://j/b"},{"libelle":"Trop","url":"https://j/c"},{"libelle":"Bizarre","url":"javascript:x"}]}""")) as Message.Choix
        assertEquals("Nouvel expéditeur", m.titre)
        assertEquals(3, m.boutons.size)
        assertEquals("Spam" to "https://j/api/mails/choix?j=x&c=spam", m.boutons[0])
    }

    @Test fun lectureVocale() {
        val urgent = Message.lire(ev("""{"type":"notif","titre":"Départ","message":"Go","priorite":"urgente","vocal":true,"envoye":1761120000123,"ack":"https://j/api/notif/ack?j=x"}""")) as Message.Notif
        assertTrue(urgent.aLire)
        assertEquals(1761120000123L, urgent.envoye)
        assertEquals("https://j/api/notif/ack?j=x", urgent.ack)
        val normale = Message.lire(ev("""{"type":"notif","titre":"Info","message":"Rien"}""")) as Message.Notif
        assertEquals(false, normale.aLire)
        val haute = Message.lire(ev("""{"type":"notif","titre":"A","message":"B","priorite":"haute"}""")) as Message.Notif
        assertTrue(haute.aLire)
        // Message brut envoyé avec le tag ntfy « vocal »
        val brut = Message.lire(ev("Pars maintenant", "Jarvis").put("tags", org.json.JSONArray().put("vocal"))) as Message.Notif
        assertTrue(brut.aLire)
        assertEquals("Départ Bonjour", pourLaVoix("🚗 Départ 👋 Bonjour"))
    }

    @Test fun envoiMessage() {
        val m = Message.lire(ev("""{"type":"message","id":"7","destinataire":" Paul ","texte":"J'arrive","canal":"whatsapp","ack":"https://j/api/messages/ack?j=x"}""")) as Message.Envoi
        assertEquals(Message.Envoi("7", "Paul", "J'arrive", "whatsapp", "https://j/api/messages/ack?j=x"), m)
        assertEquals("sms", (Message.lire(ev("""{"type":"message","id":"8","destinataire":"Paul","texte":"x","ack":"https://j/a"}""")) as Message.Envoi).canal)
        assertNull(Message.lire(ev("""{"type":"message","id":"9","destinataire":"Paul","texte":"x","ack":"javascript:x"}""")))
    }

    @Test fun briefingRecap() {
        val b = Message.lire(ev("""{"type":"briefing","titre":"B","message":"Salut","recap":"https://j/api/briefing/recap?j=x"}""")) as Message.Briefing
        assertEquals("https://j/api/briefing/recap?j=x", b.recap)
        assertNull((Message.lire(ev("""{"type":"briefing","titre":"B","message":"Salut","recap":"javascript:x"}""")) as Message.Briefing).recap)
        assertNull((Message.lire(ev("""{"type":"briefing","titre":"B","message":"Salut"}""")) as Message.Briefing).recap)
    }

    @Test fun appel() {
        val c = Message.lire(ev("""{"type":"appel","id":"3","action":"chercher","destinataire":" Paul ","ack":"https://j/api/appels/ack?j=x"}""")) as Message.Appel
        assertEquals(Message.Appel("3", "chercher", "Paul", null, null, "https://j/api/appels/ack?j=x"), c)
        val a = Message.lire(ev("""{"type":"appel","id":"4","action":"appeler","destinataire":"Paul","nom":"Paul Martin","numero":"06 12","ack":"https://j/a"}""")) as Message.Appel
        assertEquals("appeler", a.action)
        assertEquals("06 12", a.numero)
        assertEquals("chercher", (Message.lire(ev("""{"type":"appel","id":"5","destinataire":"Paul","ack":"https://j/a"}""")) as Message.Appel).action)
        assertNull(Message.lire(ev("""{"type":"appel","id":"6","destinataire":"Paul","ack":"javascript:x"}""")))
    }

    @Test fun contactsEtNumeros() {
        assertEquals("33612345678", Envois.international("06 12 34 56 78"))
        assertEquals("33612345678", Envois.international("+33 6 12 34 56 78"))
        assertEquals("33612345678", Envois.international("0033612345678"))
        assertTrue(Envois.estNumero("06 12 34 56 78"))
        assertEquals(false, Envois.estNumero("Paul"))
        val tous = listOf("Paul Martin" to "06 11 11 11 11", "Paulette" to "06 22 22 22 22", "Paul" to "06 33 33 33 33", "Paul" to "+33 6 33 33 33 33", "Hélène Ribot" to "06 44")
        assertEquals(listOf("Paul" to "06 33 33 33 33"), Envois.filtrer(tous, "paul"))
        assertEquals(listOf("Paul Martin" to "06 11 11 11 11"), Envois.filtrer(tous, "Paul Martin"))
        assertEquals(listOf("Hélène Ribot" to "06 44"), Envois.filtrer(tous, "helene"))
        assertEquals(2, Envois.filtrer(listOf("Jo Dupont" to "1", "Jo Durand" to "2"), "jo").size)
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
