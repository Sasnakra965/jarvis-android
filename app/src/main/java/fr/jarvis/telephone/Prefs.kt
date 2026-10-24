package fr.jarvis.telephone

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Réglages, historique et réveils programmés, rangés dans les préférences de l'appli. */
object Prefs {
    private const val MAX_HISTORIQUE = 100
    private const val MAX_IDS = 200

    private fun p(ctx: Context): SharedPreferences = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    fun serveur(ctx: Context): String = p(ctx).getString("serveur", null)?.trimEnd('/') ?: "https://ntfy.sh"
    fun sujet(ctx: Context): String = p(ctx).getString("sujet", "")!!.trim()

    fun enregistrer(ctx: Context, serveur: String, sujet: String) {
        p(ctx).edit()
            .putString("serveur", serveur.trim().trimEnd('/').ifEmpty { "https://ntfy.sh" })
            .putString("sujet", sujet.trim())
            .remove("dernier_id") // nouveau sujet : on repart des nouveaux messages
            .apply()
    }

    // --- Lien avec Jarvis (partage vers la télé) ---

    /** Adresse de Jarvis (ex. https://jarvis.tailXXXX.ts.net), vide si l'appli n'est pas liée. */
    fun jarvis(ctx: Context): String = p(ctx).getString("jarvis_url", "")!!
    fun jetonJarvis(ctx: Context): String = p(ctx).getString("jarvis_jeton", "")!!

    fun lierJarvis(ctx: Context, url: String, jeton: String) =
        p(ctx).edit().putString("jarvis_url", url.trimEnd('/')).putString("jarvis_jeton", jeton).apply()

    /** Clé de déchiffrement des messages de Jarvis (base64url), vide tant qu'elle n'est pas récupérée. */
    fun cleNtfy(ctx: Context): String = p(ctx).getString("ntfy_cle", "")!!
    fun garderCleNtfy(ctx: Context, cle: String) = p(ctx).edit().putString("ntfy_cle", cle).apply()

    fun ecran(ctx: Context): String = p(ctx).getString("ecran", "")!!
    fun secondes(ctx: Context): Int = p(ctx).getInt("secondes", 5)
    fun garderChoix(ctx: Context, ecran: String, secondes: Int) =
        p(ctx).edit().putString("ecran", ecran).putInt("secondes", secondes).apply()

    // --- Messages déjà traités (le serveur peut renvoyer les messages manqués) ---

    fun dernierId(ctx: Context): String? = p(ctx).getString("dernier_id", null)

    /** Vrai si le message est nouveau (et le note comme vu). */
    @Synchronized
    fun nouveauMessage(ctx: Context, id: String): Boolean {
        val vus = JSONArray(p(ctx).getString("ids_vus", "[]"))
        for (i in 0 until vus.length()) if (vus.getString(i) == id) return false
        vus.put(id)
        while (vus.length() > MAX_IDS) vus.remove(0)
        p(ctx).edit().putString("ids_vus", vus.toString()).putString("dernier_id", id).apply()
        return true
    }

    // --- Historique affiché dans l'appli ---

    @Synchronized
    fun ajouterHistorique(ctx: Context, texte: String) {
        val h = JSONArray(p(ctx).getString("historique", "[]"))
        h.put(JSONObject().put("t", System.currentTimeMillis()).put("texte", texte))
        while (h.length() > MAX_HISTORIQUE) h.remove(0)
        p(ctx).edit().putString("historique", h.toString()).apply()
    }

    /** Du plus récent au plus ancien : (heure en ms, texte). */
    fun historique(ctx: Context): List<Pair<Long, String>> {
        val h = JSONArray(p(ctx).getString("historique", "[]"))
        return (h.length() - 1 downTo 0).map { h.getJSONObject(it).let { o -> o.getLong("t") to o.getString("texte") } }
    }

    fun effacerHistorique(ctx: Context) = p(ctx).edit().remove("historique").apply()

    // --- Message WhatsApp préparé : le service d'accessibilité appuiera sur Envoyer ---

    fun whatsappEnAttente(ctx: Context, ack: String, nom: String, jusqua: Long) =
        p(ctx).edit().putString("wa_ack", ack).putString("wa_nom", nom).putLong("wa_jusqua", jusqua).apply()

    /** (adresse d'accusé, nom) du message WhatsApp en attente, null s'il n'y en a pas ou s'il a expiré. */
    fun whatsappEnAttente(ctx: Context): Pair<String, String>? {
        val ack = p(ctx).getString("wa_ack", null) ?: return null
        if (p(ctx).getLong("wa_jusqua", 0) < System.currentTimeMillis()) return null
        return ack to p(ctx).getString("wa_nom", "")!!
    }

    fun oublierWhatsapp(ctx: Context) = p(ctx).edit().remove("wa_ack").remove("wa_nom").remove("wa_jusqua").apply()

    // --- Lecture vocale des alertes (départ, trafic, rappels importants) ---

    fun lectureVocale(ctx: Context): Boolean = p(ctx).getBoolean("lecture_vocale", true)
    fun lectureVocale(ctx: Context, oui: Boolean) = p(ctx).edit().putBoolean("lecture_vocale", oui).apply()

    // --- Briefing du matin (texte à relire) et réveil en train de sonner ---

    fun garderBriefing(ctx: Context, parole: String) = p(ctx).edit().putString("briefing", parole).apply()
    fun garderKanban(ctx: Context, url: String) = p(ctx).edit().putString("briefing_kanban", url).apply()
    /** Page Kanban du briefing ; à défaut, celle de l'adresse de Jarvis liée. */
    fun kanban(ctx: Context): String = p(ctx).getString("briefing_kanban", null) ?: jarvis(ctx).takeIf { it.isNotEmpty() }?.let { "$it/briefing/kanban" } ?: ""
    fun dernierBriefing(ctx: Context): String = p(ctx).getString("briefing", "")!!

    /** Briefing reçu pendant (ou juste avant) un réveil : à lire quand on l'arrête. */
    fun briefingALire(ctx: Context): Boolean = p(ctx).getBoolean("briefing_a_lire", false)
    fun briefingALire(ctx: Context, oui: Boolean) = p(ctx).edit().putBoolean("briefing_a_lire", oui).apply()

    fun reveilEnCours(ctx: Context): Boolean = p(ctx).getBoolean("reveil_en_cours", false)
    fun reveilEnCours(ctx: Context, oui: Boolean) = p(ctx).edit().putBoolean("reveil_en_cours", oui).apply()

    // --- Réveils programmés (pour les reprogrammer après un redémarrage) ---

    data class ReveilProgramme(val id: String, val quand: Long, val libelle: String)

    @Synchronized
    fun reveils(ctx: Context): List<ReveilProgramme> {
        val o = JSONObject(p(ctx).getString("reveils", "{}"))
        return o.keys().asSequence().map { id ->
            val r = o.getJSONObject(id)
            ReveilProgramme(id, r.getLong("quand"), r.optString("libelle", "Réveil"))
        }.sortedBy { it.quand }.toList()
    }

    @Synchronized
    fun garderReveil(ctx: Context, r: ReveilProgramme) {
        val o = JSONObject(p(ctx).getString("reveils", "{}"))
        o.put(r.id, JSONObject().put("quand", r.quand).put("libelle", r.libelle))
        p(ctx).edit().putString("reveils", o.toString()).apply()
    }

    @Synchronized
    fun oublierReveil(ctx: Context, id: String) {
        val o = JSONObject(p(ctx).getString("reveils", "{}"))
        o.remove(id)
        p(ctx).edit().putString("reveils", o.toString()).apply()
    }
}
