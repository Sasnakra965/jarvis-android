package fr.jarvis.telephone

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Handler
import android.os.Looper

/**
 * Briefing du matin : notification (toucher → Kanban) avec Relire / Stop / Plus de détails, lu à voix haute.
 * Si un réveil sonne (ou va sonner : attendre_reveil), la lecture attend qu'on arrête l'alarme.
 */
object Briefings {
    const val RELIRE = "fr.jarvis.telephone.RELIRE"
    const val STOP = "fr.jarvis.telephone.STOP_LECTURE"
    const val ID_NOTIF = 3
    private const val ATTENTE_MAX_MS = 20 * 60_000L
    private val ui = Handler(Looper.getMainLooper())

    fun recevoir(ctx: Context, b: Message.Briefing) {
        Prefs.garderBriefing(ctx, b.parole)
        afficher(ctx, b)
        Prefs.ajouterHistorique(ctx, "☀️ ${b.titre} : ${b.texte}")
        if (b.attendreReveil || Prefs.reveilEnCours(ctx)) {
            Prefs.briefingALire(ctx, true)
            // Réveil annulé ou jamais arrêté : on lit quand même au bout de 20 min.
            ui.postDelayed({ if (Prefs.briefingALire(ctx) && !Prefs.reveilEnCours(ctx)) lireEnAttente(ctx) }, ATTENTE_MAX_MS)
        } else {
            Lecteur.lire(ctx, b.parole)
        }
    }

    /** Appelé quand on arrête le réveil (pas au snooze). */
    fun apresReveil(ctx: Context) {
        if (Prefs.briefingALire(ctx)) lireEnAttente(ctx)
    }

    private fun lireEnAttente(ctx: Context) {
        Prefs.briefingALire(ctx, false)
        Lecteur.lire(ctx, Prefs.dernierBriefing(ctx))
    }

    private fun action(ctx: Context, quoi: String, label: String) = Notification.Action.Builder(
        null as Icon?, label,
        PendingIntent.getBroadcast(
            ctx, quoi.hashCode(), Intent(ctx, BriefingReceiver::class.java).setAction(quoi),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ),
    ).build()

    private fun afficher(ctx: Context, b: Message.Briefing) {
        val ouvrir = b.url?.let {
            PendingIntent.getActivity(
                ctx, 7, Intent(Intent.ACTION_VIEW, Uri.parse(it)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        } ?: Notifs.ouvrirAppli(ctx)
        val n = Notification.Builder(ctx, Notifs.CANAL_IMPORTANT)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(b.titre)
            .setContentText(b.texte)
            .setStyle(Notification.BigTextStyle().bigText(b.texte))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(ouvrir)
            .addAction(action(ctx, RELIRE, "Relire"))
            .addAction(action(ctx, STOP, "Stop"))
            .addAction(
                Notification.Action.Builder(
                    null as Icon?, "Plus de détails",
                    PendingIntent.getActivity(
                        ctx, 8, Intent(ctx, DetailsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                ).build()
            )
            .build()
        Notifs.nm(ctx).notify(ID_NOTIF, n)
    }

    /** L'appli Claude (pour parler en mode vocal), sinon claude.ai. */
    fun ouvrirClaude(ctx: Context) {
        val appli = ctx.packageManager.getLaunchIntentForPackage("com.anthropic.claude")
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://claude.ai/new"))
        ctx.startActivity(appli.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** « Plus de détails » : coupe la lecture et ouvre Claude (un bouton de notification ne peut ouvrir une appli qu'ainsi). */
class DetailsActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        Lecteur.stop(this)
        Notifs.nm(this).cancel(Briefings.ID_NOTIF)
        Briefings.ouvrirClaude(this)
        finish()
    }
}

class BriefingReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Briefings.RELIRE -> Lecteur.lire(ctx, Prefs.dernierBriefing(ctx))
            Briefings.STOP -> Lecteur.stop(ctx)
        }
    }
}
