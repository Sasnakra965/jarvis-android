package fr.jarvis.telephone

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Service permanent : reste abonné au sujet ntfy (flux JSON, une ligne par événement),
 * se reconnecte seul (coupure, changement de réseau) et récupère les messages manqués.
 */
class ListenerService : Service() {
    companion object {
        @Volatile
        var etat: String = "Arrêté"
            private set

        fun demarrer(ctx: Context) {
            if (Prefs.sujet(ctx).isEmpty()) return
            ctx.startForegroundService(Intent(ctx, ListenerService::class.java))
        }

        fun redemarrer(ctx: Context) {
            ctx.stopService(Intent(ctx, ListenerService::class.java))
            demarrer(ctx)
        }
    }

    @Volatile private var actif = false
    @Volatile private var connexion: HttpURLConnection? = null
    private var fil: Thread? = null

    private val reseau = object : ConnectivityManager.NetworkCallback() {
        // Nouveau réseau (Wi-Fi ↔ 4G) : l'ancienne connexion est morte, on la coupe pour repartir aussitôt.
        override fun onAvailable(network: Network) {
            connexion?.disconnect()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifs.creerCanaux(this)
        val n = Notifs.service(this, "Connexion…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(Notifs.ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifs.ID_SERVICE, n)
        }
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(reseau)
        // Batterie (rappel de recharge) : ce signal ne s'écoute que depuis un service en marche.
        registerReceiver(batterie, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        // Réveil actuel (installation, redémarrage) : Jarvis cale le briefing dessus.
        Recharge.reveilChange(this)
    }

    private val batterie = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) = Recharge.batterie(ctx, intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (fil?.isAlive != true) {
            actif = true
            fil = Thread(::boucle, "jarvis-ntfy").apply { start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        actif = false
        connexion?.disconnect()
        fil?.interrupt()
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(reseau) }
        runCatching { unregisterReceiver(batterie) }
        changerEtat("Arrêté")
        super.onDestroy()
    }

    private fun changerEtat(e: String) {
        etat = e
        if (actif) Notifs.nm(this).notify(Notifs.ID_SERVICE, Notifs.service(this, e))
    }

    private fun boucle() {
        var attente = 5_000L
        while (actif) {
            val sujet = Prefs.sujet(this)
            try {
                val depuis = Prefs.dernierId(this)?.let { "?since=" + URLEncoder.encode(it, "UTF-8") } ?: ""
                val c = URL("${Prefs.serveur(this)}/${URLEncoder.encode(sujet, "UTF-8")}/json$depuis")
                    .openConnection() as HttpURLConnection
                c.connectTimeout = 20_000
                c.readTimeout = 100_000 // ntfy envoie un « keepalive » toutes les 45 s : au-delà, la connexion est morte
                connexion = c
                if (c.responseCode != 200) throw IllegalStateException("le serveur répond ${c.responseCode}")
                changerEtat("Connecté ✔")
                attente = 5_000L
                c.inputStream.bufferedReader().useLines { lignes ->
                    for (ligne in lignes) {
                        if (!actif) break
                        if (ligne.isBlank()) continue
                        runCatching { traiter(JSONObject(ligne)) }
                    }
                }
                changerEtat("Reconnexion…")
            } catch (e: Exception) {
                if (!actif) break
                changerEtat("Hors ligne, nouvel essai dans ${attente / 1000} s")
                try {
                    Thread.sleep(attente)
                } catch (_: InterruptedException) {
                }
                attente = (attente * 2).coerceAtMost(60_000L)
            } finally {
                connexion?.disconnect()
                connexion = null
            }
        }
    }

    /** Lit l'alerte (titre puis texte) ; si Jarvis le demande, lui renvoie l'heure du début de lecture. */
    private fun lireAlerte(msg: Message.Notif) {
        val recu = System.currentTimeMillis()
        val texte = pourLaVoix("${msg.titre}. ${msg.texte}")
        Lecteur.lire(this, texte, alerte = true, audio = msg.audio, auDemarrage = {
            val lu = System.currentTimeMillis()
            msg.envoye?.let { Prefs.ajouterHistorique(this, "🔊 Lu ${(lu - it) / 100 / 10.0} s après l'envoi") }
            msg.ack?.let { url ->
                Thread {
                    runCatching {
                        val c = URL(url).openConnection() as HttpURLConnection
                        c.requestMethod = "POST"
                        c.doOutput = true
                        c.setRequestProperty("Content-Type", "application/json")
                        c.connectTimeout = 15_000
                        c.outputStream.use { it.write("""{"lu":$lu,"recu":$recu}""".toByteArray()) }
                        c.responseCode
                        c.disconnect()
                    }
                }.start()
            }
        })
    }

    private fun traiter(ev: JSONObject) {
        val msg = Message.lire(ev, Prefs.cleNtfy(this)) ?: return
        if (!Prefs.nouveauMessage(this, ev.optString("id"))) return
        when (msg) {
            is Message.Notif -> {
                Notifs.afficher(this, msg.titre, msg.texte, msg.priorite, msg.url)
                Prefs.ajouterHistorique(this, "🔔 ${msg.titre} : ${msg.texte}")
                if (msg.aLire && Prefs.lectureVocale(this)) lireAlerte(msg)
            }
            is Message.Reveil -> {
                if (Alarms.programmer(this, msg.id, msg.quand, msg.libelle)) {
                    val quand = Alarms.heure(msg.quand)
                    Notifs.afficher(this, "⏰ Réveil programmé", "$quand · ${msg.libelle}", Message.NORMALE)
                    Prefs.ajouterHistorique(this, "⏰ Réveil programmé : $quand (${msg.libelle})")
                } else {
                    Prefs.ajouterHistorique(this, "⏰ Réveil ignoré (heure passée) : ${msg.libelle}")
                }
            }
            is Message.Briefing -> Briefings.recevoir(this, msg)
            is Message.Envoi -> Envois.traiter(this, msg)
            is Message.Appel -> Appels.traiter(this, msg)
            is Message.Choix -> {
                Choix.afficher(this, msg)
                Prefs.ajouterHistorique(this, "❓ ${msg.titre} : ${msg.texte}")
            }
            is Message.AnnulerReveil -> {
                val n = Alarms.annuler(this, msg.id)
                val quoi = if (n == 0) "aucun réveil à annuler" else "$n réveil(s) annulé(s)"
                Notifs.afficher(this, "⏰ Réveil annulé", quoi, Message.NORMALE)
                Prefs.ajouterHistorique(this, "⏰ Annulation : $quoi")
            }
        }
    }
}

/** Au démarrage du téléphone (et après une mise à jour de l'appli) : réveils et connexion. */
class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Notifs.creerCanaux(ctx)
        Alarms.reprogrammerTout(ctx)
        ListenerService.demarrer(ctx)
    }
}
