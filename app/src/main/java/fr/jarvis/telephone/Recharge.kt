package fr.jarvis.telephone

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import org.json.JSONObject

/**
 * Rappel de recharge (écouteurs, montre…) au bon moment : l'appli signale à Jarvis quand le prochain réveil change
 * (n'importe quelle appli d'horloge, réveils Jarvis compris) et quand la batterie passe sous le seuil hors charge.
 * Jarvis décide (le soir seulement, pas deux fois en 3 h, texte du rappel) et envoie la notification.
 */
object Recharge {
    /** Seuil donné par Jarvis dans sa réponse (défaut 30 %), et marge avant de pouvoir re-signaler. */
    private const val MARGE = 5

    fun enCharge(ctx: Context): Boolean = ctx.getSystemService(BatteryManager::class.java)?.isCharging == true

    private fun signaler(ctx: Context, evenement: JSONObject) {
        if (Prefs.jarvis(ctx).isEmpty()) return
        Thread {
            runCatching { Jarvis.evenement(ctx, evenement) }.onSuccess { r ->
                if (r.has("seuil_batterie")) Prefs.seuilBatterie(ctx, r.getInt("seuil_batterie"))
                if (r.optBoolean("envoye")) Prefs.ajouterHistorique(ctx, "🔌 Rappel de recharge (${evenement.getString("type")})")
            }
        }.start()
    }

    /** Le prochain réveil a changé : seulement s'il existe et qu'il est nouveau. */
    fun reveilChange(ctx: Context) {
        val prochain = ctx.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.triggerTime ?: return
        if (prochain == Prefs.dernierReveilVu(ctx)) return
        Prefs.dernierReveilVu(ctx, prochain)
        signaler(ctx, JSONObject().put("type", "reveil_regle").put("heure", prochain).put("en_charge", enCharge(ctx)))
    }

    /** À chaque changement de batterie (reçu par le service) : une fois par passage sous le seuil. */
    fun batterie(ctx: Context, intent: Intent) {
        val niveau = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val echelle = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (niveau < 0 || echelle <= 0) return
        val pct = niveau * 100 / echelle
        val branche = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val seuil = Prefs.seuilBatterie(ctx)
        if (branche || pct >= seuil + MARGE) {
            Prefs.batterieSignalee(ctx, false)
        } else if (pct < seuil && !Prefs.batterieSignalee(ctx)) {
            Prefs.batterieSignalee(ctx, true)
            signaler(ctx, JSONObject().put("type", "batterie_basse").put("niveau", pct).put("en_charge", false))
        }
    }
}

/** android.app.action.NEXT_ALARM_CLOCK_CHANGED : un réveil vient d'être réglé, déplacé ou supprimé. */
class ReveilChangeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED) Recharge.reveilChange(ctx)
    }
}
