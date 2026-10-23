package fr.jarvis.telephone

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Réveils : vraie alarme système (setAlarmClock), sonnerie en écran plein, Arrêter / Snooze. */
object Alarms {
    const val SONNER = "fr.jarvis.telephone.SONNER"
    const val ARRETER = "fr.jarvis.telephone.ARRETER"
    const val SNOOZE = "fr.jarvis.telephone.SNOOZE"
    const val EXTRA_ID = "id"
    const val EXTRA_LIBELLE = "libelle"
    const val SNOOZE_MINUTES = 10
    private const val SONNERIE_MAX_MS = 10 * 60_000L

    private fun am(ctx: Context) = ctx.getSystemService(AlarmManager::class.java)

    fun heure(ms: Long): String {
        val jour = SimpleDateFormat("yyyyMMdd", Locale.FRANCE)
        val prefixe = if (jour.format(Date(ms)) == jour.format(Date())) "" else
            SimpleDateFormat("EEEE ", Locale.FRANCE).format(Date(ms))
        return prefixe + SimpleDateFormat("HH'h'mm", Locale.FRANCE).format(Date(ms))
    }

    private fun declencheur(ctx: Context, id: String, libelle: String): PendingIntent = PendingIntent.getBroadcast(
        ctx, id.hashCode(),
        Intent(ctx, AlarmReceiver::class.java).setAction(SONNER)
            .putExtra(EXTRA_ID, id).putExtra(EXTRA_LIBELLE, libelle),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun peutProgrammer(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am(ctx).canScheduleExactAlarms()

    /** Programme (ou reprogramme, même id) un réveil. Renvoie false si l'heure est déjà passée. */
    fun programmer(ctx: Context, id: String, quand: Long, libelle: String): Boolean {
        if (quand <= System.currentTimeMillis()) return false
        Prefs.garderReveil(ctx, Prefs.ReveilProgramme(id, quand, libelle))
        val op = declencheur(ctx, id, libelle)
        if (peutProgrammer(ctx)) {
            am(ctx).setAlarmClock(AlarmManager.AlarmClockInfo(quand, Notifs.ouvrirAppli(ctx)), op)
        } else {
            // Sans l'autorisation « alarmes exactes » : peut sonner avec un peu de retard.
            am(ctx).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quand, op)
        }
        return true
    }

    /** Annule un réveil (ou tous si id est null). Renvoie le nombre de réveils annulés. */
    fun annuler(ctx: Context, id: String?): Int {
        val cibles = Prefs.reveils(ctx).filter { id == null || it.id == id }
        for (r in cibles) {
            am(ctx).cancel(declencheur(ctx, r.id, r.libelle))
            Prefs.oublierReveil(ctx, r.id)
        }
        return cibles.size
    }

    /** Après un redémarrage du téléphone : les alarmes système sont perdues, on les remet. */
    fun reprogrammerTout(ctx: Context) {
        for (r in Prefs.reveils(ctx)) {
            if (!programmer(ctx, r.id, r.quand, r.libelle)) Prefs.oublierReveil(ctx, r.id)
        }
    }

    fun sonner(ctx: Context, id: String, libelle: String) {
        Prefs.oublierReveil(ctx, id)
        Prefs.reveilEnCours(ctx, true)
        Prefs.ajouterHistorique(ctx, "⏰ Réveil : $libelle")
        val ecran = PendingIntent.getActivity(
            ctx, 1,
            Intent(ctx, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                .putExtra(EXTRA_ID, id).putExtra(EXTRA_LIBELLE, libelle),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(ctx, Notifs.CANAL_REVEIL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("⏰ $libelle")
            .setContentText("Il est ${heure(System.currentTimeMillis())}")
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setFullScreenIntent(ecran, true)
            .setContentIntent(ecran)
            .setOngoing(true)
            .setTimeoutAfter(SONNERIE_MAX_MS)
            .addAction(Notification.Action.Builder(null as Icon?, "Arrêter", action(ctx, ARRETER, id, libelle)).build())
            .addAction(
                Notification.Action.Builder(null as Icon?, "Snooze $SNOOZE_MINUTES min", action(ctx, SNOOZE, id, libelle)).build()
            )
            .build()
        n.flags = n.flags or Notification.FLAG_INSISTENT // la sonnerie boucle jusqu'à Arrêter / Snooze
        Notifs.nm(ctx).notify(Notifs.ID_REVEIL, n)
    }

    private fun action(ctx: Context, quoi: String, id: String, libelle: String): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, quoi.hashCode(),
            Intent(ctx, AlarmReceiver::class.java).setAction(quoi)
                .putExtra(EXTRA_ID, id).putExtra(EXTRA_LIBELLE, libelle),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun arreter(ctx: Context) {
        Prefs.reveilEnCours(ctx, false)
        Notifs.nm(ctx).cancel(Notifs.ID_REVEIL)
        ctx.sendBroadcast(Intent(AlarmActivity.FERMER).setPackage(ctx.packageName))
    }

    fun snooze(ctx: Context, id: String, libelle: String) {
        arreter(ctx)
        val quand = System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L
        programmer(ctx, id.substringBefore("~snooze") + "~snooze", quand, libelle)
        Prefs.ajouterHistorique(ctx, "💤 Snooze : $libelle à ${heure(quand)}")
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra(Alarms.EXTRA_ID) ?: return
        val libelle = intent.getStringExtra(Alarms.EXTRA_LIBELLE) ?: "Réveil"
        when (intent.action) {
            Alarms.SONNER -> Alarms.sonner(ctx, id, libelle)
            Alarms.ARRETER -> {
                Alarms.arreter(ctx)
                Briefings.apresReveil(ctx)
            }
            Alarms.SNOOZE -> Alarms.snooze(ctx, id, libelle)
        }
    }
}
