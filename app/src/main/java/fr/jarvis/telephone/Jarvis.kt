package fr.jarvis.telephone

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Échanges directs avec Jarvis : appairage par QR code, puis envoi de photos et vidéos vers la télé. */
object Jarvis {
    class Erreur(message: String) : Exception(message)

    /** Lien du QR code de Jarvis (https://…/pair?token=…) : adresse de Jarvis et jeton d'appairage. */
    fun lireQr(lien: String): Pair<String, String>? {
        val i = lien.indexOf("/pair?")
        if (i <= 0 || !lien.startsWith("http")) return null
        val token = Regex("[?&]token=([A-Za-z0-9_-]+)").find(lien)?.groupValues?.get(1) ?: return null
        return lien.substring(0, i) to token
    }

    /** Ce qu'on envoie : les photos (diaporama si plusieurs), sinon une seule vidéo. Second : nombre laissé de côté. */
    fun <T> choisir(elements: List<T>, estVideo: (T) -> Boolean): Pair<List<T>, Int> {
        val photos = elements.filterNot(estVideo)
        if (photos.isNotEmpty()) return photos to elements.size - photos.size
        return elements.take(1) to (elements.size - 1).coerceAtLeast(0)
    }

    /** À appeler hors du fil principal. */
    fun lier(ctx: Context, lien: String) {
        val (url, token) = lireQr(lien) ?: throw Erreur("Ce n'est pas le QR code de Jarvis (Jarvis › Téléphone, sur le PC).")
        val corps = JSONObject().put("token", token).put("nom", Build.MODEL).toString().toByteArray()
        val r = appel("$url/api/pair/app", "POST", null, "application/json", corps.size.toLong()) { it.write(corps) }
        Prefs.lierJarvis(ctx, url, r.getString("jeton"))
    }

    fun ecrans(ctx: Context): List<String> {
        val r = appel("${Prefs.jarvis(ctx)}/api/ecran", "GET", Prefs.jetonJarvis(ctx))
        val liste = r.getJSONArray("ecrans")
        return (0 until liste.length()).map { liste.getString(it) }
    }

    /** Envoie un fichier à l'écran (paramètres : ecran, sorte, lot, index, total, secondes…). */
    fun envoyer(ctx: Context, params: Map<String, String>, taille: Long, ouvrir: () -> InputStream) {
        val q = params.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        appel("${Prefs.jarvis(ctx)}/api/ecran/envoi?$q", "POST", Prefs.jetonJarvis(ctx), "application/octet-stream", taille) { sortie ->
            ouvrir().use { it.copyTo(sortie, 1 shl 16) }
        }
    }

    private fun appel(
        url: String,
        methode: String,
        jeton: String?,
        type: String? = null,
        taille: Long = 0,
        ecrire: ((OutputStream) -> Unit)? = null,
    ): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = methode
            c.connectTimeout = 20_000
            c.readTimeout = 600_000 // grosse vidéo : l'afficheur répond une fois tout reçu
            if (jeton != null) c.setRequestProperty("Authorization", "Bearer $jeton")
            if (ecrire != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", type)
                c.setFixedLengthStreamingMode(taille)
                c.outputStream.use(ecrire)
            }
            val code = c.responseCode
            val texte = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            val json = runCatching { JSONObject(texte) }.getOrDefault(JSONObject())
            if (code == 401) throw Erreur("L'appli n'est plus liée à Jarvis : ouvre l'appli Jarvis › « Lier à Jarvis ».")
            if (code >= 400) throw Erreur(json.optString("erreur").ifEmpty { "Jarvis a répondu $code" })
            return json
        } catch (e: java.io.IOException) {
            throw Erreur("Jarvis injoignable (Internet ?) : ${e.message}")
        } finally {
            c.disconnect()
        }
    }
}
