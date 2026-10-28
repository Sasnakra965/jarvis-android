package fr.jarvis.telephone

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lecture à voix haute : la voix Piper de Jarvis (fichier audio téléchargé, champ « audio » des messages),
 * sinon la synthèse vocale d'Android (voix française). Écran verrouillé compris, depuis le
 * service de premier plan. Sortie « média » : le système l'envoie aux écouteurs Bluetooth s'ils sont
 * connectés, sinon au haut-parleur ; le mode silencieux ne coupe pas le son média.
 * Alerte (départ, trafic, rappel important) : musique en pause, volume média au maximum le temps de la
 * lecture, puis volume remis comme avant et musique relancée. Briefing : musique seulement baissée.
 */
object Lecteur {
    private var tts: TextToSpeech? = null
    private var pret = false
    private val enAttente = mutableListOf<Triple<String, Boolean, (() -> Unit)?>>()
    private var focus: AudioFocusRequest? = null
    private var focusPause = false
    private val ui = Handler(Looper.getMainLooper())
    private var volumeAvant: Int? = null
    private val numero = AtomicInteger()
    private val aVenir = AtomicInteger() // alertes programmées pas encore lancées
    /** Actions à lancer quand une lecture commence (accusé de lecture pour mesurer le délai). */
    private val auDebut = ConcurrentHashMap<String, () -> Unit>()
    /** Voix Piper : lecteur en cours et fichiers qui attendent leur tour (alertes à la suite). */
    private var media: MediaPlayer? = null
    private val fichiers = ArrayDeque<Triple<File, Boolean, (() -> Unit)?>>()

    private val attributs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /**
     * Lit un texte. alerte : à la suite (sans couper) et volume au maximum ; auDemarrage : appelé au début.
     * audio : adresse de la voix Piper (WAV) ; si le téléchargement échoue, la voix d'Android lit le texte.
     */
    fun lire(ctx: Context, texte: String, alerte: Boolean = false, auDemarrage: (() -> Unit)? = null, audio: String? = null) {
        if (texte.isBlank() && audio == null) return
        val app = ctx.applicationContext
        if (audio != null) {
            Thread {
                val f = telecharger(app, audio)
                ui.post { if (f != null) lireFichier(app, f, alerte, auDemarrage) else lireTexte(app, texte, alerte, auDemarrage) }
            }.start()
            return
        }
        lireTexte(app, texte, alerte, auDemarrage)
    }

    /** La voix Piper (WAV) : 20 s au plus, sinon null (et la voix d'Android prend le relais). */
    private fun telecharger(ctx: Context, url: String): File? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        try {
            if (c.responseCode != 200 || c.contentType?.startsWith("audio/") != true) return@runCatching null
            val f = File(ctx.cacheDir, "voix-${numero.incrementAndGet()}.wav")
            c.inputStream.use { entree -> f.outputStream().use { entree.copyTo(it) } }
            f
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    /** Joue un fichier audio de la voix de Jarvis (briefing : coupe ce qui est en cours ; alerte : à la suite). */
    @Synchronized
    fun lireFichier(ctx: Context, f: File, alerte: Boolean = true, auDemarrage: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        if (!alerte) {
            tts?.stop()
            media?.release()
            media = null
            fichiers.clear()
        }
        if (media != null || tts?.isSpeaking == true || aVenir.get() > 0) {
            fichiers.addLast(Triple(f, alerte, auDemarrage))
            return
        }
        demarrer(app, f, alerte, auDemarrage)
    }

    private fun demarrer(ctx: Context, f: File, alerte: Boolean, auDemarrage: (() -> Unit)?) {
        val am = ctx.getSystemService(AudioManager::class.java)
        prendreFocus(am, alerte)
        val mp = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(attributs)
                setDataSource(f.path)
                setOnCompletionListener { suivant(ctx, f) }
                setOnErrorListener { _, _, _ -> suivant(ctx, f); true }
                prepare()
            }
        }.getOrNull() ?: return suivant(ctx, f)
        media = mp
        // Alerte : 0,2 s pour que la musique se mette en pause, puis volume au maximum (comme la voix d'Android).
        aVenir.incrementAndGet()
        ui.postDelayed({
            aVenir.decrementAndGet()
            if (alerte) {
                if (volumeAvant == null) volumeAvant = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
            }
            auDemarrage?.invoke()
            mp.start()
        }, if (alerte) 200 else 0)
    }

    @Synchronized
    private fun suivant(ctx: Context, fait: File) {
        media?.release()
        media = null
        fait.delete()
        fin(ctx, null)
    }

    @Synchronized
    private fun lireTexte(app: Context, texte: String, alerte: Boolean, auDemarrage: (() -> Unit)?) {
        if (texte.isBlank()) return
        if (tts == null) {
            pret = false
            tts = TextToSpeech(app) { statut ->
                synchronized(this) {
                    pret = statut == TextToSpeech.SUCCESS
                    if (pret) {
                        tts?.language = Locale.FRANCE
                        tts?.setAudioAttributes(attributs)
                        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(id: String?) {
                                id?.let { auDebut.remove(it)?.invoke() }
                            }
                            override fun onDone(id: String?) = fin(app, id)
                            @Deprecated("API 21") override fun onError(id: String?) = fin(app, id)
                        })
                        enAttente.forEach { (t, a, d) -> parler(app, t, a, d) }
                    } else {
                        Prefs.ajouterHistorique(app, "🔇 Synthèse vocale indisponible (installe une voix française)")
                    }
                    enAttente.clear()
                }
            }
        }
        if (pret) parler(app, texte, alerte, auDemarrage) else enAttente.add(Triple(texte, alerte, auDemarrage))
    }

    /** Alerte : la musique se met en PAUSE (focus exclusif) ; briefing : elle est seulement baissée. */
    private fun prendreFocus(am: AudioManager, alerte: Boolean) {
        if (focus == null || (alerte && !focusPause)) {
            focus?.let { am.abandonAudioFocusRequest(it) }
            val type = if (alerte) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            focus = AudioFocusRequest.Builder(type).setAudioAttributes(attributs).build()
            focusPause = alerte
            am.requestAudioFocus(focus!!)
        }
    }

    private fun parler(ctx: Context, texte: String, alerte: Boolean, auDemarrage: (() -> Unit)?) {
        val am = ctx.getSystemService(AudioManager::class.java)
        prendreFocus(am, alerte)
        val id = "jarvis-${numero.incrementAndGet()}"
        auDemarrage?.let { auDebut[id] = it }
        if (!alerte) {
            tts?.speak(texte, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
            return
        }
        // Laisse 0,2 s au lecteur de musique pour se mettre en pause, puis volume au maximum et lecture.
        aVenir.incrementAndGet()
        ui.postDelayed({
            aVenir.decrementAndGet()
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (volumeAvant == null) volumeAvant = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
            tts?.speak(texte, TextToSpeech.QUEUE_ADD, Bundle(), id)
        }, 200)
    }

    /** Fin d'une lecture : rend le volume et la musique quand plus rien n'est en cours. */
    private fun fin(ctx: Context, id: String?) {
        id?.let { auDebut.remove(it) }
        if (tts?.isSpeaking == true || aVenir.get() > 0 || media != null) return // une alerte attend encore son tour
        synchronized(this) {
            fichiers.removeFirstOrNull()?.let { (f, a, d) ->
                ui.post { demarrer(ctx.applicationContext, f, a, d) }
                return
            }
        }
        val am = ctx.getSystemService(AudioManager::class.java)
        volumeAvant?.let { am.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) }
        volumeAvant = null
        // Rendre le focus relance la musique mise en pause.
        focus?.let { am.abandonAudioFocusRequest(it) }
        focus = null
        focusPause = false
    }

    @Synchronized
    fun stop(ctx: Context) {
        enAttente.clear()
        tts?.stop()
        fichiers.forEach { it.first.delete() }
        fichiers.clear()
        media?.release()
        media = null
        fin(ctx.applicationContext, null)
    }
}

/** Texte lisible à voix haute : sans emojis ni symboles décoratifs (hors de Lecteur : testable sans Android). */
fun pourLaVoix(texte: String): String = texte
    .replace(Regex("[\\p{So}\\p{Cs}\\uFE0F\\u200D]"), "")
    .replace(Regex("\\s+"), " ")
    .trim()
