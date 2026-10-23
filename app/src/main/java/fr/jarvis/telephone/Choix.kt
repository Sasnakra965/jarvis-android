package fr.jarvis.telephone

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * Question de Jarvis à boutons (ex. nouvel expéditeur de mail : Spam / Pas important / Important).
 * Un bouton envoie un POST à son adresse (qui contient un jeton secret) sans ouvrir l'appli,
 * puis la notification affiche « ✅ Noté ».
 */
object Choix {
    const val REPONDRE = "fr.jarvis.telephone.REPONDRE"
    private val prochainId = AtomicInteger(500)

    fun afficher(ctx: Context, c: Message.Choix) {
        val id = prochainId.incrementAndGet()
        val b = Notification.Builder(ctx, Notifs.CANAL_IMPORTANT)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(c.titre)
            .setContentText(c.texte)
            .setStyle(Notification.BigTextStyle().bigText(c.texte))
            .setContentIntent(
                c.url?.let {
                    PendingIntent.getActivity(
                        ctx, id, Intent(Intent.ACTION_VIEW, Uri.parse(it)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                } ?: Notifs.ouvrirAppli(ctx)
            )
            .setAutoCancel(true)
        c.boutons.forEachIndexed { i, (libelle, url) ->
            val intent = Intent(ctx, ChoixReceiver::class.java).setAction(REPONDRE)
                .putExtra("url", url).putExtra("libelle", libelle).putExtra("notif", id).putExtra("titre", c.titre)
            b.addAction(
                Notification.Action.Builder(
                    null as Icon?, libelle,
                    PendingIntent.getBroadcast(ctx, id * 10 + i, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                ).build()
            )
        }
        Notifs.nm(ctx).notify(id, b.build())
    }

    /** POST vers l'adresse du bouton ; vrai si Jarvis a répondu OK. */
    fun envoyer(url: String): Boolean = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.doOutput = true
        c.outputStream.use { }
        val ok = c.responseCode in 200..299
        c.disconnect()
        ok
    }.getOrDefault(false)
}

class ChoixReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val url = intent.getStringExtra("url") ?: return
        val libelle = intent.getStringExtra("libelle") ?: ""
        val id = intent.getIntExtra("notif", 0)
        val titre = intent.getStringExtra("titre") ?: "Jarvis"
        val fin = goAsync()
        Thread {
            val ok = Choix.envoyer(url)
            val texte = if (ok) "✅ Noté : $libelle" else "⚠️ Jarvis n'a pas pu être joint, réessaie plus tard."
            Notifs.nm(ctx).notify(
                id,
                Notification.Builder(ctx, Notifs.CANAL_MESSAGES).setSmallIcon(R.drawable.ic_notif)
                    .setContentTitle(titre).setContentText(texte).setAutoCancel(true).setTimeoutAfter(if (ok) 8_000 else 0).build(),
            )
            Prefs.ajouterHistorique(ctx, "✉️ $titre : $texte")
            fin.finish()
        }.start()
    }
}
