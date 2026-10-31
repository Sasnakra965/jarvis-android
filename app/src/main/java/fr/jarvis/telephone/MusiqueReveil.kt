package fr.jarvis.telephone

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Musique de réveil : le lien (YouTube Music) s'ouvre dans l'appli YouTube Music, rien n'est téléchargé.
 * Le volume part bas et monte jusqu'au maximum en 30 s. Si rien ne joue au bout de 15 s (pas de réseau,
 * appli absente ou bloquée…), la sonnerie d'alarme du téléphone prend le relais : on ne rate jamais le réveil.
 * Arrêter : musique en pause, sonnerie coupée, volumes remis comme avant.
 *
 * Android n'ouvre une autre appli depuis l'arrière-plan qu'avec « Afficher par-dessus les autres applis » ;
 * sinon, l'appli est ouverte depuis l'écran du réveil (AlarmActivity), dès qu'il s'affiche.
 */
object MusiqueReveil {
    const val APPLI = "com.google.android.apps.youtube.music"
    private const val SECOURS_MS = 15_000L
    private const val MONTEE_MS = 30_000L
    private const val PAS_MS = 1_000L
    private const val DUREE_MAX_MS = 10 * 60_000L

    private val ui = Handler(Looper.getMainLooper())
    private var lien: String? = null
    private var lance = false
    private var secours: MediaPlayer? = null
    private var debut = 0L
    private var debutSecours = 0L
    private var volumeMusique = -1
    private var volumeAlarme = -1

    private fun audio(ctx: Context) = ctx.getSystemService(AudioManager::class.java)

    /** Le réveil sonne avec une musique : volume bas, appli ouverte si Android le permet, secours à 15 s. */
    fun demarrer(ctx: Context, url: String) {
        val app = ctx.applicationContext
        arreter(app) // restes d'un réveil précédent
        val a = audio(app)
        lien = url
        lance = false
        debut = SystemClock.elapsedRealtime()
        volumeMusique = a.getStreamVolume(AudioManager.STREAM_MUSIC)
        a.setStreamVolume(AudioManager.STREAM_MUSIC, max(1, a.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 8), 0)
        if (Settings.canDrawOverlays(app)) lancer(app)
        ui.post(object : Runnable {
            override fun run() {
                monter(app)
                if (SystemClock.elapsedRealtime() - debut < MONTEE_MS) ui.postDelayed(this, PAS_MS)
            }
        })
        ui.postDelayed({ verifier(app) }, SECOURS_MS)
        ui.postDelayed({ arreter(app) }, DUREE_MAX_MS) // comme la sonnerie : 10 min au plus
    }

    /** Ouvre le lien dans YouTube Music (une fois). Depuis l'écran du réveil, Android le permet toujours. */
    fun lancer(ctx: Context) {
        val url = lien ?: return
        if (lance) return
        lance = runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(APPLI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        Prefs.ajouterHistorique(ctx, if (lance) "🎵 Musique de réveil lancée" else "🎵 YouTube Music n'a pas pu s'ouvrir")
    }

    private fun fraction(depuis: Long) = ((SystemClock.elapsedRealtime() - depuis).toFloat() / MONTEE_MS).coerceIn(0.15f, 1f)

    private fun monter(ctx: Context) {
        val a = audio(ctx)
        if (lien != null && secours == null) {
            val maxi = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            a.setStreamVolume(AudioManager.STREAM_MUSIC, max(1, (maxi * fraction(debut)).roundToInt()), 0)
        }
        secours?.let { val f = fraction(debutSecours); runCatching { it.setVolume(f, f) } }
    }

    private fun verifier(ctx: Context) {
        if (lien == null) return
        if (lance && audio(ctx).isMusicActive) return
        Prefs.ajouterHistorique(ctx, "⏰ La musique ne joue pas : sonnerie d'alarme à la place")
        jouerSecours(ctx)
    }

    /** Sonnerie d'alarme du téléphone, en boucle, volume d'alarme au maximum, son qui monte. */
    private fun jouerSecours(ctx: Context) {
        val a = audio(ctx)
        val son = RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        volumeAlarme = a.getStreamVolume(AudioManager.STREAM_ALARM)
        a.setStreamVolume(AudioManager.STREAM_ALARM, a.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        debutSecours = SystemClock.elapsedRealtime()
        secours = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                )
                setDataSource(ctx, son)
                isLooping = true
                prepare()
                setVolume(0.15f, 0.15f)
                start()
            }
        }.getOrNull()
        // Même la sonnerie ne démarre pas : notification de réveil classique (son d'alarme du canal Réveil).
        if (secours == null) Alarms.sonnerieClassique(ctx)
        ui.post(object : Runnable {
            override fun run() {
                if (secours == null) return
                monter(ctx)
                ui.postDelayed(this, PAS_MS)
            }
        })
    }

    private fun pause(ctx: Context) {
        val a = audio(ctx)
        a.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
        a.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
    }

    /** Coupe tout (Arrêter, Snooze, 10 min sans réponse). Sans effet s'il n'y a pas de musique en cours. */
    fun arreter(ctx: Context) {
        val app = ctx.applicationContext
        ui.removeCallbacksAndMessages(null)
        secours?.let { runCatching { it.stop(); it.release() } }
        secours = null
        val a = audio(app)
        if (lance) {
            pause(app)
            // YouTube Music encore en train de charger : on remet la pause un peu plus tard.
            ui.postDelayed({ pause(app) }, 3_000)
            ui.postDelayed({ pause(app) }, 8_000)
        }
        if (volumeMusique >= 0) a.setStreamVolume(AudioManager.STREAM_MUSIC, volumeMusique, 0)
        if (volumeAlarme >= 0) a.setStreamVolume(AudioManager.STREAM_ALARM, volumeAlarme, 0)
        volumeMusique = -1
        volumeAlarme = -1
        lien = null
        lance = false
    }
}
