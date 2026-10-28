package fr.jarvis.telephone

import android.app.Notification
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Briefing du matin : lu à voix haute comme une alerte importante (volume au max, musique en pause), Kanban
 * affiché en plein écran (même téléphone verrouillé), notification avec Relire / Stop / Plus de détails.
 * Si un réveil sonne (ou va sonner : attendre_reveil), la lecture attend qu'on arrête l'alarme ; couper le
 * réveil demande aussi à Jarvis le briefing du jour s'il n'est pas encore parti.
 * « Ouvrir dans Claude » : nouvelle conversation avec « Jarvis, donne-moi le briefing » déjà écrit ; Claude
 * appelle l'outil briefing de Jarvis et présente lui-même la journée, on lui répond dans la même conversation.
 */
object Briefings {
    const val RELIRE = "fr.jarvis.telephone.RELIRE"
    const val STOP = "fr.jarvis.telephone.STOP_LECTURE"
    const val ID_NOTIF = 3
    private const val ATTENTE_MAX_MS = 20 * 60_000L
    private val ui = Handler(Looper.getMainLooper())

    const val DEMANDE = "Jarvis, donne-moi le briefing"
    private const val APPLI_CLAUDE = "com.anthropic.claude"

    /** Lien « nouvelle conversation » avec le message déjà écrit (claude.ai : ouvert par l'appli Claude si elle est là). */
    fun lienClaude(texte: String): String = "https://claude.ai/new?q=" + Uri.encode(texte)

    fun recevoir(ctx: Context, b: Message.Briefing) {
        Prefs.garderBriefing(ctx, b.parole, b.audio)
        b.url?.let { Prefs.garderKanban(ctx, it) }
        afficher(ctx, b)
        KanbanActivity.ouvrir(ctx)
        Prefs.ajouterHistorique(ctx, "☀️ ${b.titre} : ${b.texte}")
        if (b.attendreReveil || Prefs.reveilEnCours(ctx)) {
            Prefs.briefingALire(ctx, true)
            // Réveil annulé ou jamais arrêté : on lit quand même au bout de 20 min.
            ui.postDelayed({ if (Prefs.briefingALire(ctx) && !Prefs.reveilEnCours(ctx)) lireEnAttente(ctx) }, ATTENTE_MAX_MS)
        } else {
            Lecteur.lire(ctx, b.parole, alerte = true, audio = b.audio)
        }
    }

    /** Appelé quand on arrête le réveil (pas au snooze). */
    fun apresReveil(ctx: Context) {
        if (Prefs.briefingALire(ctx)) {
            lireEnAttente(ctx)
            KanbanActivity.ouvrir(ctx)
        }
        // Le premier des deux : l'heure prévue ou l'arrêt du réveil (Jarvis n'en envoie qu'un par jour).
        Thread { runCatching { Jarvis.reveilCoupe(ctx) } }.start()
    }

    private fun lireEnAttente(ctx: Context) {
        Prefs.briefingALire(ctx, false)
        relire(ctx)
    }

    /** Relit le dernier briefing (voix Piper si elle est encore disponible). */
    fun relire(ctx: Context) = Lecteur.lire(ctx, Prefs.dernierBriefing(ctx), alerte = true, audio = Prefs.briefingAudio(ctx))

    private fun action(ctx: Context, quoi: String, label: String) = Notification.Action.Builder(
        null as Icon?, label,
        PendingIntent.getBroadcast(
            ctx, quoi.hashCode(), Intent(ctx, BriefingReceiver::class.java).setAction(quoi),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ),
    ).build()

    private fun afficher(ctx: Context, b: Message.Briefing) {
        // Toucher la notification (ou téléphone verrouillé : écran plein) ouvre le Kanban du briefing.
        val ouvrir = if (Prefs.kanban(ctx).isNotEmpty()) KanbanActivity.enAttente(ctx) else Notifs.ouvrirAppli(ctx)

        val n = Notification.Builder(ctx, Notifs.CANAL_IMPORTANT)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(b.titre)
            .setContentText(b.texte)
            .setStyle(Notification.BigTextStyle().bigText(b.texte))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(ouvrir)
            .setFullScreenIntent(ouvrir, true)
            .addAction(action(ctx, RELIRE, "Relire"))
            .addAction(action(ctx, STOP, "Stop"))
            .addAction(
                Notification.Action.Builder(
                    null as Icon?, "Ouvrir dans Claude",
                    PendingIntent.getActivity(
                        ctx, 8, Intent(ctx, DetailsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                ).build()
            )
            .build()
        Notifs.nm(ctx).notify(ID_NOTIF, n)
    }

    /**
     * Nouvelle conversation Claude avec `texte` déjà écrit, dans cet ordre :
     * 1. l'appli Claude, par son lien profond (claude.ai/new?q=…, lien vérifié de l'appli) ;
     * 2. sinon le site claude.ai avec le même lien, dans le navigateur ;
     * 3. sinon (ou réglage « copier-coller ») : message copié, Claude ouvert, « Collez et envoyez ».
     */
    fun ouvrirClaudeAvec(ctx: Context, texte: String) {
        if (Prefs.bilanParLien(ctx)) {
            val vue = Intent(Intent.ACTION_VIEW, Uri.parse(lienClaude(texte))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val appli = Intent(vue).setPackage(APPLI_CLAUDE)
            if (appli.resolveActivity(ctx.packageManager) != null && runCatching { ctx.startActivity(appli) }.isSuccess) {
                Prefs.ajouterHistorique(ctx, "💬 Claude ouvert : « $texte »")
                return
            }
            if (runCatching { ctx.startActivity(vue) }.isSuccess) {
                Prefs.ajouterHistorique(ctx, "💬 claude.ai ouvert dans le navigateur : « $texte »")
                return
            }
        }
        ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Jarvis", texte))
        runCatching { ouvrirClaude(ctx) }
        Toast.makeText(ctx, "📋 Collez et envoyez", Toast.LENGTH_LONG).show()
        Prefs.ajouterHistorique(ctx, "💬 « $texte » copié pour Claude")
    }

    /** L'appli Claude (pour parler en mode vocal), sinon claude.ai. */
    fun ouvrirClaude(ctx: Context) {
        val appli = ctx.packageManager.getLaunchIntentForPackage(APPLI_CLAUDE)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://claude.ai/new"))
        ctx.startActivity(appli.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * « Ouvrir dans Claude » : coupe la lecture et ouvre une nouvelle conversation Claude avec « Jarvis, donne-moi
 * le briefing » (un bouton de notification ne peut ouvrir une appli qu'ainsi).
 */
class DetailsActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        Lecteur.stop(this)
        Notifs.nm(this).cancel(Briefings.ID_NOTIF)
        Briefings.ouvrirClaudeAvec(this, Briefings.DEMANDE)
        finish()
    }
}

class BriefingReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Briefings.RELIRE -> Briefings.relire(ctx)
            Briefings.STOP -> Lecteur.stop(ctx)
        }
    }
}
