package fr.jarvis.telephone

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import org.json.JSONObject

/**
 * L'appli signale à Jarvis quand le prochain réveil change (n'importe quelle appli d'horloge, réveils Jarvis
 * compris ; « aucun » aussi) et quand la batterie passe sous le seuil hors charge. Jarvis s'en sert pour :
 * - caler le briefing du matin sur le réveil (et l'envoyer dès que le réveil est arrêté) ;
 * - le rappel de recharge (écouteurs, montre…), le soir ou batterie basse.
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
                if (r.optBoolean("briefing")) Prefs.ajouterHistorique(ctx, "☀️ Réveil arrêté : briefing demandé à Jarvis")
            }
        }.start()
    }

    /** Le prochain réveil a changé (0 : plus aucun) : signalé à Jarvis s'il est différent du dernier signalé. */
    fun reveilChange(ctx: Context) {
        val prochain = ctx.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.triggerTime ?: 0L
        if (prochain == Prefs.dernierReveilVu(ctx)) return
        Prefs.dernierReveilVu(ctx, prochain)
        val heure: Any = if (prochain > 0) prochain else JSONObject.NULL
        signaler(ctx, JSONObject().put("type", "reveil_regle").put("heure", heure).put("en_charge", enCharge(ctx)))
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
