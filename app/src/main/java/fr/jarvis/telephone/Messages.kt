package fr.jarvis.telephone

import org.json.JSONObject
import java.util.Base64
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * Format des messages (corps du message ntfy, en JSON) :
 *
 *   {"type":"notif", "titre":"Jarvis", "message":"…", "priorite":"normale", "url":"https://…"}
 *        priorite : basse | normale | haute | urgente (défaut : normale) ; url facultative (ouverte au toucher)
 *
 *   {"type":"reveil", "id":"r-123", "heure":7, "minute":30, "libelle":"Chantier Dupont", "timestamp":1761198600}
 *        timestamp (secondes, facultatif) prime sur heure/minute ; sinon prochaine fois qu'il est heure:minute
 *        après l'envoi du message. id facultatif (sert à l'annulation).
 *
 *   {"type":"annuler_reveil", "id":"r-123"}      sans id : annule tous les réveils
 *
 *   {"type":"briefing", "titre":"☀️ Briefing", "message":"…", "parole":"…", "url":"https://…/kanban",
 *    "attendre_reveil":false}
 *        notification lue à voix haute (parole, sinon message) ; attendre_reveil : lire à l'arrêt du réveil
 *
 *   {"type":"message", "id":"12", "destinataire":"Paul" | "0612345678", "texte":"…", "canal":"sms"|"whatsapp", "ack":"https://…"}
 *        SMS (ou WhatsApp) envoyé par le téléphone ; ack reçoit {statut: envoye|echec|choix|pret, detail?, numero?, choix?}
 *
 *   {"type":"appel", "id":"3", "action":"chercher"|"appeler", "destinataire":"Paul", "nom":"…", "numero":"…", "ack":"https://…"}
 *        chercher : contact cherché, sans appeler ; ack reçoit {statut: trouve|choix|echec, nom?, numero?, choix?}
 *        appeler : appel de numero (son vers le Bluetooth s'il est connecté) ; ack reçoit {statut: appel_lance|echec, audio?, detail?}
 *
 *   {"type":"choix", "titre":"…", "message":"…", "url":"https://…", "choix":[{"libelle":"Spam","url":"https://…"}]}
 *        notification à boutons (3 au plus) : chaque bouton envoie un POST à son url ; toucher ouvre url
 *
 * Un message qui n'est pas du JSON (ex. envoyé depuis la page ntfy) s'affiche comme une notification.
 *
 * Chiffré (appli liée à Jarvis) : {"chiffre": base64(iv 12 octets | texte | tag 16 octets)}, AES-256-GCM,
 * avec la clé récupérée auprès de Jarvis ; le serveur ntfy ne voit que ce charabia.
 */
sealed class Message {
    data class Notif(
        val titre: String,
        val texte: String,
        val priorite: Int,
        val url: String? = null,
        /** Marquée « vocal » (champ JSON ou tag ntfy) : à lire à voix haute. */
        val vocal: Boolean = false,
        /** Heure d'envoi (ms) et adresse d'accusé de lecture (mesure du délai, facultatif). */
        val envoye: Long? = null,
        val ack: String? = null,
    ) : Message() {
        /** À lire à voix haute : priorité ≥ 4 (haute ou urgente) ou marquée « vocal ». */
        val aLire get() = priorite >= 4 || vocal
    }
    data class Reveil(val id: String, val quand: Long, val libelle: String) : Message()
    data class AnnulerReveil(val id: String?) : Message()
    data class Envoi(val id: String, val destinataire: String, val texte: String, val canal: String, val ack: String) : Message()
    data class Appel(val id: String, val action: String, val destinataire: String, val nom: String?, val numero: String?, val ack: String) : Message()
    data class Choix(val titre: String, val texte: String, val url: String?, val boutons: List<Pair<String, String>>) : Message()
    data class Briefing(
        val titre: String,
        val texte: String,
        val parole: String,
        val url: String? = null,
        val attendreReveil: Boolean = false,
    ) : Message()

    companion object {
        const val NORMALE = 3

        /** Tag « vocal » posé sur le message ntfy (ex. envoi depuis la page ntfy ou un script). */
        private fun tagVocal(ev: JSONObject): Boolean {
            val tags = ev.optJSONArray("tags") ?: return false
            return (0 until tags.length()).any { tags.optString(it).equals("vocal", ignoreCase = true) }
        }

        fun priorite(nom: String?): Int = when (nom?.lowercase()) {
            "basse" -> 2
            "haute" -> 4
            "urgente" -> 5
            else -> NORMALE
        }

        /** Texte en clair d'un message chiffré, null si la clé manque ou ne convient pas. */
        fun dechiffrer(b64: String, cle: String): String? = runCatching {
            val b = Base64.getDecoder().decode(b64)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(Base64.getUrlDecoder().decode(cle), "AES"), GCMParameterSpec(128, b, 0, 12))
            String(c.doFinal(b, 12, b.size - 12), Charsets.UTF_8)
        }.getOrNull()

        /** Lit un événement du flux JSON de ntfy ; null si ce n'est pas un message. */
        fun lire(ev: JSONObject, cle: String = ""): Message? {
            if (ev.optString("event") != "message") return null
            var corps = ev.optString("message", "")
            val envoi = ev.optLong("time", System.currentTimeMillis() / 1000) * 1000
            var json = corps.trim().takeIf { it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (json != null && json.has("chiffre")) {
                corps = dechiffrer(json.getString("chiffre"), cle)
                    ?: return Notif("Jarvis", "Message chiffré illisible : ouvre l'appli Jarvis et touche « Lier à Jarvis ».", NORMALE)
                json = runCatching { JSONObject(corps) }.getOrNull()
            }
            if (json == null || !json.has("type")) {
                return Notif(ev.optString("title").ifEmpty { "Jarvis" }, corps, ev.optInt("priority", NORMALE), vocal = tagVocal(ev))
            }
            return when (json.getString("type")) {
                "reveil" -> {
                    val quand = if (json.has("timestamp")) json.getLong("timestamp") * 1000
                    else prochaine(json.getInt("heure"), json.optInt("minute", 0), envoi)
                    Reveil(
                        json.optString("id").ifEmpty { "r-$quand" },
                        quand,
                        json.optString("libelle").ifEmpty { "Réveil" },
                    )
                }
                "annuler_reveil" -> AnnulerReveil(json.optString("id").ifEmpty { null })
                "message" -> {
                    val ack = json.optString("ack")
                    if (!ack.startsWith("https://") && !ack.startsWith("http://")) return null
                    Envoi(
                        json.optString("id"),
                        json.optString("destinataire").trim(),
                        json.optString("texte"),
                        if (json.optString("canal") == "whatsapp") "whatsapp" else "sms",
                        ack,
                    )
                }
                "appel" -> {
                    val ack = json.optString("ack")
                    if (!ack.startsWith("https://") && !ack.startsWith("http://")) return null
                    Appel(
                        json.optString("id"),
                        if (json.optString("action") == "appeler") "appeler" else "chercher",
                        json.optString("destinataire").trim(),
                        json.optString("nom").trim().ifEmpty { null },
                        json.optString("numero").trim().ifEmpty { null },
                        ack,
                    )
                }
                "choix" -> {
                    val c = json.optJSONArray("choix")
                    Choix(
                        json.optString("titre").ifEmpty { "Jarvis" },
                        json.optString("message"),
                        json.optString("url").ifEmpty { null },
                        (0 until (c?.length() ?: 0)).mapNotNull { i ->
                            val b = c!!.optJSONObject(i) ?: return@mapNotNull null
                            val u = b.optString("url")
                            if (u.startsWith("https://") || u.startsWith("http://")) b.optString("libelle", "?") to u else null
                        }.take(3),
                    )
                }
                "briefing" -> Briefing(
                    json.optString("titre").ifEmpty { "Briefing" },
                    json.optString("message"),
                    json.optString("parole").ifEmpty { json.optString("message") },
                    json.optString("url").ifEmpty { null },
                    json.optBoolean("attendre_reveil", false),
                )
                else -> Notif(
                    json.optString("titre").ifEmpty { ev.optString("title").ifEmpty { "Jarvis" } },
                    json.optString("message"),
                    priorite(json.optString("priorite")),
                    json.optString("url").ifEmpty { null },
                    json.optBoolean("vocal", false) || tagVocal(ev),
                    json.optLong("envoye", 0).takeIf { it > 0 },
                    json.optString("ack").takeIf { it.startsWith("https://") || it.startsWith("http://") },
                )
            }
        }

        /** Prochaine fois qu'il est heure:minute (heure du téléphone) après `apres`. */
        fun prochaine(heure: Int, minute: Int, apres: Long): Long {
            val c = Calendar.getInstance().apply {
                timeInMillis = apres
                set(Calendar.HOUR_OF_DAY, heure)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (c.timeInMillis <= apres) c.add(Calendar.DAY_OF_YEAR, 1)
            return c.timeInMillis
        }
    }
}
