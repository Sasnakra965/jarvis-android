package fr.jarvis.telephone

import android.app.Activity
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout

/**
 * Kanban du briefing en plein écran (cartes en cours et en attente), même téléphone verrouillé :
 * page /briefing/kanban de Jarvis, avec le jeton de l'appli. Boutons Relire / Stop / Fermer.
 */
class KanbanActivity : Activity() {
    companion object {
        fun intention(ctx: Context): Intent =
            Intent(ctx, KanbanActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        fun enAttente(ctx: Context): PendingIntent =
            PendingIntent.getActivity(ctx, 11, intention(ctx), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        /**
         * Téléphone déverrouillé : ouvert tout de suite si Android le permet (service d'accessibilité Jarvis
         * actif). Verrouillé : c'est la notification du briefing (écran plein) qui l'ouvre.
         */
        fun ouvrir(ctx: Context) {
            if (Prefs.kanban(ctx).isEmpty()) return
            val verrouille = ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked
            if (!verrouille && Envois.serviceActif(ctx)) runCatching { ctx.startActivity(intention(ctx)) }
        }
    }

    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val dp = resources.displayMetrics.density
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#0B1F3A")) }
        web = WebView(this).apply {
            webViewClient = WebViewClient()
            settings.javaScriptEnabled = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        col.addView(web)
        val barre = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun bouton(t: String, quoi: () -> Unit) = Button(this).apply {
            text = t
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(0, (64 * dp).toInt(), 1f)
            setOnClickListener { quoi() }
        }
        barre.addView(bouton("Relire") { Briefings.relire(this) })
        barre.addView(bouton("Stop") { Lecteur.stop(this) })
        barre.addView(bouton("Fermer") { finish() })
        col.addView(barre)
        setContentView(col)
        charger()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        charger()
    }

    private fun charger() {
        val url = Prefs.kanban(this)
        if (url.isNotEmpty()) web.loadUrl(url, mapOf("Authorization" to "Bearer ${Prefs.jetonJarvis(this)}"))
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
