package fr.jarvis.telephone

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Lecture à voix haute (synthèse vocale d'Android, voix française) : écran verrouillé compris, sur le
 * haut-parleur ou les écouteurs connectés (sortie « média », que le système envoie au bon appareil).
 */
object Lecteur {
    private var tts: TextToSpeech? = null
    private var pret = false
    private var enAttente: String? = null
    private var focus: AudioFocusRequest? = null

    private val attributs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    @Synchronized
    fun lire(ctx: Context, texte: String) {
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
                            override fun onStart(id: String?) = Unit
                            override fun onDone(id: String?) = rendreFocus(app)
                            @Deprecated("API 21") override fun onError(id: String?) = rendreFocus(app)
                        })
                        enAttente?.let { parler(app, it) }
                    } else {
                        Prefs.ajouterHistorique(app, "🔇 Synthèse vocale indisponible (installe une voix française)")
                    }
                    enAttente = null
                }
            }
        }
        if (pret) parler(app, texte) else enAttente = texte
    }

    private fun parler(ctx: Context, texte: String) {
        val am = ctx.getSystemService(AudioManager::class.java)
        // Baisse la musique le temps de la lecture, puis la rend.
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributs).build()
        am.requestAudioFocus(focus!!)
        tts?.speak(texte, TextToSpeech.QUEUE_FLUSH, Bundle(), "briefing")
    }

    private fun rendreFocus(ctx: Context) {
        focus?.let { ctx.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focus = null
    }

    @Synchronized
    fun stop(ctx: Context) {
        enAttente = null
        tts?.stop()
        rendreFocus(ctx.applicationContext)
    }
}
