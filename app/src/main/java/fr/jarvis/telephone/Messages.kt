package fr.jarvis.telephone

import org.json.JSONObject
import java.util.Calendar

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
 * Un message qui n'est pas du JSON (ex. envoyé depuis la page ntfy) s'affiche comme une notification.
 */
sealed class Message {
    data class Notif(val titre: String, val texte: String, val priorite: Int, val url: String? = null) : Message()
    data class Reveil(val id: String, val quand: Long, val libelle: String) : Message()
    data class AnnulerReveil(val id: String?) : Message()

    companion object {
        const val NORMALE = 3

        fun priorite(nom: String?): Int = when (nom?.lowercase()) {
            "basse" -> 2
            "haute" -> 4
            "urgente" -> 5
            else -> NORMALE
        }

        /** Lit un événement du flux JSON de ntfy ; null si ce n'est pas un message. */
        fun lire(ev: JSONObject): Message? {
            if (ev.optString("event") != "message") return null
            val corps = ev.optString("message", "")
            val envoi = ev.optLong("time", System.currentTimeMillis() / 1000) * 1000
            val json = corps.trim().takeIf { it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (json == null || !json.has("type")) {
                return Notif(ev.optString("title").ifEmpty { "Jarvis" }, corps, ev.optInt("priority", NORMALE))
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
                else -> Notif(
                    json.optString("titre").ifEmpty { ev.optString("title").ifEmpty { "Jarvis" } },
                    json.optString("message"),
                    priorite(json.optString("priorite")),
                    json.optString("url").ifEmpty { null },
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
