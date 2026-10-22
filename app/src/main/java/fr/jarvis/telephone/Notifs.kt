package fr.jarvis.telephone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import java.util.concurrent.atomic.AtomicInteger

/** Canaux et affichage des notifications. */
object Notifs {
    const val CANAL_MESSAGES = "messages"
    const val CANAL_IMPORTANT = "important"
    const val CANAL_SERVICE = "service"
    const val CANAL_REVEIL = "reveil_sonnerie"

    const val ID_SERVICE = 1
    const val ID_REVEIL = 2
    private val prochainId = AtomicInteger(100)

    fun nm(ctx: Context): NotificationManager = ctx.getSystemService(NotificationManager::class.java)

    fun creerCanaux(ctx: Context) {
        val nm = nm(ctx)
        nm.createNotificationChannel(
            NotificationChannel(CANAL_MESSAGES, "Messages de Jarvis", NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.createNotificationChannel(
            NotificationChannel(CANAL_IMPORTANT, "Alertes importantes", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CANAL_SERVICE, "Connexion à Jarvis", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Notification permanente qui garde Jarvis connecté"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CANAL_REVEIL, "Réveil", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 600, 800, 600)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    fun ouvrirAppli(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Lien à ouvrir au toucher (ex. navigation Waze d'une alerte de départ). */
    private fun lien(ctx: Context, url: String): PendingIntent = PendingIntent.getActivity(
        ctx, url.hashCode(), Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun afficher(ctx: Context, titre: String, texte: String, priorite: Int, url: String? = null) {
        val canal = if (priorite >= 4) CANAL_IMPORTANT else CANAL_MESSAGES
        val n = Notification.Builder(ctx, canal)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(titre)
            .setContentText(texte)
            .setStyle(Notification.BigTextStyle().bigText(texte))
            .setContentIntent(url?.let { lien(ctx, it) } ?: ouvrirAppli(ctx))
            .setAutoCancel(true)
            .build()
        nm(ctx).notify(prochainId.incrementAndGet(), n)
    }

    /** Notification permanente du service de connexion. */
    fun service(ctx: Context, etat: String): Notification =
        Notification.Builder(ctx, CANAL_SERVICE)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Jarvis")
            .setContentText(etat)
            .setContentIntent(ouvrirAppli(ctx))
            .setOngoing(true)
            .build()
}
