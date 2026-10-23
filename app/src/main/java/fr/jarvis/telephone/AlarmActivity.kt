package fr.jarvis.telephone

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Écran plein qui s'affiche quand le réveil sonne, même téléphone verrouillé. */
class AlarmActivity : Activity() {
    companion object {
        const val FERMER = "fr.jarvis.telephone.FERMER_REVEIL"
    }

    private val fermer = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val id = intent.getStringExtra(Alarms.EXTRA_ID) ?: ""
        val libelle = intent.getStringExtra(Alarms.EXTRA_LIBELLE) ?: "Réveil"
        val dp = resources.displayMetrics.density

        val colonne = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0B1F3A"))
            setPadding((24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt())
        }
        fun texte(t: String, taille: Float) = TextView(this).apply {
            text = t
            textSize = taille
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        fun bouton(t: String, fond: String, quoi: () -> Unit) = Button(this).apply {
            text = t
            textSize = 22f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor(fond))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (72 * dp).toInt()).apply {
                topMargin = (16 * dp).toInt()
            }
            setOnClickListener { quoi(); finish() }
        }
        colonne.addView(texte(Alarms.heure(System.currentTimeMillis()), 64f))
        colonne.addView(texte(libelle, 26f))
        colonne.addView(bouton("Arrêter", "#C62828") {
            Alarms.arreter(this)
            Briefings.apresReveil(this)
        })
        colonne.addView(bouton("Snooze ${Alarms.SNOOZE_MINUTES} min", "#1565C0") { Alarms.snooze(this, id, libelle) })
        setContentView(colonne)

        registerReceiver(fermer, IntentFilter(FERMER), Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        unregisterReceiver(fermer)
        super.onDestroy()
    }

    @Deprecated("Retour : on ne quitte pas sans choisir Arrêter ou Snooze")
    override fun onBackPressed() = Unit
}
