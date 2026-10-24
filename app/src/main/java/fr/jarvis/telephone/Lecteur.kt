package fr.jarvis.telephone

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lecture à voix haute (synthèse vocale d'Android, voix française) : écran verrouillé compris, depuis le
 * service de premier plan. Sortie « média » : le système l'envoie aux écouteurs Bluetooth s'ils sont
 * connectés, sinon au haut-parleur ; le mode silencieux ne coupe pas le son média.
 * Alerte (départ, trafic, rappel important) : mise à la suite d'une lecture en cours, volume média au
 * maximum le temps de la lecture, puis remis comme avant.
 */
object Lecteur {
    private var tts: TextToSpeech? = null
    private var pret = false
    private val enAttente = mutableListOf<Triple<String, Boolean, (() -> Unit)?>>()
    private var focus: AudioFocusRequest? = null
    private var volumeAvant: Int? = null
    private val numero = AtomicInteger()
    /** Actions à lancer quand une lecture commence (accusé de lecture pour mesurer le délai). */
    private val auDebut = ConcurrentHashMap<String, () -> Unit>()

    private val attributs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** Lit un texte. alerte : à la suite (sans couper) et volume au maximum ; auDemarrage : appelé au début. */
    @Synchronized
    fun lire(ctx: Context, texte: String, alerte: Boolean = false, auDemarrage: (() -> Unit)? = null) {
        if (texte.isBlank()) return
        val app = ctx.applicationContext
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

    private fun parler(ctx: Context, texte: String, alerte: Boolean, auDemarrage: (() -> Unit)?) {
        val am = ctx.getSystemService(AudioManager::class.java)
        if (alerte) {
            // Alerte prioritaire : volume média au maximum le temps de la lecture, puis remis comme avant.
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (volumeAvant == null) volumeAvant = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
        }
        // Baisse la musique le temps de la lecture, puis la rend.
        if (focus == null) {
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributs).build()
            am.requestAudioFocus(focus!!)
        }
        val id = "jarvis-${numero.incrementAndGet()}"
        auDemarrage?.let { auDebut[id] = it }
        tts?.speak(texte, if (alerte) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH, Bundle(), id)
    }

    /** Fin d'une lecture : rend le volume et la musique quand plus rien n'est en cours. */
    private fun fin(ctx: Context, id: String?) {
        id?.let { auDebut.remove(it) }
        if (tts?.isSpeaking == true) return
        val am = ctx.getSystemService(AudioManager::class.java)
        volumeAvant?.let { am.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) }
        volumeAvant = null
        focus?.let { am.abandonAudioFocusRequest(it) }
        focus = null
    }

    @Synchronized
    fun stop(ctx: Context) {
        enAttente.clear()
        tts?.stop()
        fin(ctx.applicationContext, null)
    }
}

/** Texte lisible à voix haute : sans emojis ni symboles décoratifs (hors de Lecteur : testable sans Android). */
fun pourLaVoix(texte: String): String = texte
    .replace(Regex("[\\p{So}\\p{Cs}\\uFE0F\\u200D]"), "")
    .replace(Regex("\\s+"), " ")
    .trim()
