package fr.jarvis.telephone

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer

/**
 * Messages envoyés à la demande de Jarvis (outil envoyer_message) :
 * - SMS : contact cherché par nom (plusieurs → la liste repart à Jarvis), envoi par SmsManager, même téléphone
 *   verrouillé ; l'accusé (envoyé / échec) part quand le réseau a pris le SMS en charge.
 * - WhatsApp : conversation ouverte par le lien wa.me avec le texte pré-rempli, puis le service d'accessibilité
 *   appuie sur Envoyer. Téléphone verrouillé (ou service non activé) : notification « message WhatsApp prêt ».
 */
object Envois {
    const val SMS_ENVOYE = "fr.jarvis.telephone.SMS_ENVOYE"
    private const val ID_NOTIF_WHATSAPP = 4

    /** Accusé à Jarvis (POST JSON), en arrière-plan. */
    fun accuser(url: String, json: JSONObject) {
        Thread {
            runCatching {
                val c = URL(url).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.connectTimeout = 15_000
                c.readTimeout = 15_000
                c.outputStream.use { it.write(json.toString().toByteArray()) }
                c.responseCode
                c.disconnect()
            }
        }.start()
    }

    private fun echec(ctx: Context, e: Message.Envoi, pourquoi: String) {
        Prefs.ajouterHistorique(ctx, "✉️ Message à ${e.destinataire} non envoyé : $pourquoi")
        accuser(e.ack, JSONObject().put("statut", "echec").put("detail", pourquoi))
    }

    fun traiter(ctx: Context, e: Message.Envoi) {
        val contacts = if (estNumero(e.destinataire)) listOf(e.destinataire to e.destinataire) else {
            if (!autorise(ctx, Manifest.permission.READ_CONTACTS)) return echec(ctx, e, "l'appli Jarvis n'a pas accès aux contacts (Autorisations)")
            chercher(ctx, e.destinataire)
        }
        when {
            contacts.isEmpty() -> return echec(ctx, e, "aucun contact « ${e.destinataire} » dans le téléphone")
            contacts.size > 1 -> {
                val choix = JSONArray()
                contacts.take(10).forEach { (nom, num) -> choix.put(JSONObject().put("nom", nom).put("numero", num)) }
                accuser(e.ack, JSONObject().put("statut", "choix").put("choix", choix))
                return
            }
        }
        val (nom, numero) = contacts.first()
        if (e.canal == "whatsapp") whatsapp(ctx, e, nom, numero) else sms(ctx, e, nom, numero)
    }

    // --- SMS -------------------------------------------------------------------------------------

    private fun sms(ctx: Context, e: Message.Envoi, nom: String, numero: String) {
        if (!autorise(ctx, Manifest.permission.SEND_SMS)) return echec(ctx, e, "l'appli Jarvis n'a pas le droit d'envoyer des SMS (Autorisations)")
        val sm = ctx.getSystemService(SmsManager::class.java) ?: @Suppress("DEPRECATION") SmsManager.getDefault()
        val parties = sm.divideMessage(e.texte)
        // Accusé sur la dernière partie seulement : le SMS est parti quand elle l'est.
        val envoye = PendingIntent.getBroadcast(
            ctx, e.id.hashCode(),
            Intent(ctx, SmsResultat::class.java).setAction(SMS_ENVOYE)
                .putExtra("ack", e.ack).putExtra("numero", numero).putExtra("nom", nom),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val intents = ArrayList<PendingIntent?>(List(parties.size) { i -> if (i == parties.size - 1) envoye else null })
        runCatching { sm.sendMultipartTextMessage(numero, null, parties, intents, null) }
            .onFailure { return echec(ctx, e, "l'envoi du SMS a échoué (${it.message})") }
        Prefs.ajouterHistorique(ctx, "✉️ SMS à $nom ($numero) : ${e.texte}")
    }

    // --- WhatsApp --------------------------------------------------------------------------------

    /** Numéro au format international sans « + » pour wa.me (06… → 336…). */
    fun international(numero: String): String {
        val n = numero.filter { it.isDigit() || it == '+' }
        return when {
            n.startsWith("+") -> n.drop(1)
            n.startsWith("00") -> n.drop(2)
            n.startsWith("0") && n.length == 10 -> "33" + n.drop(1)
            else -> n
        }
    }

    private fun whatsapp(ctx: Context, e: Message.Envoi, nom: String, numero: String) {
        val lien = Uri.parse("https://wa.me/${international(numero)}?text=${URLEncoder.encode(e.texte, "UTF-8").replace("+", "%20")}")
        val ouvrir = Intent(Intent.ACTION_VIEW, lien).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val verrouille = ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        // Le service appuiera sur Envoyer dès que la conversation sera ouverte (pendant 30 min au plus).
        Prefs.whatsappEnAttente(ctx, e.ack, nom, System.currentTimeMillis() + 30 * 60_000)
        if (!verrouille && serviceActif(ctx)) {
            runCatching { ctx.startActivity(ouvrir) }.onSuccess { return }
        }
        // Verrouillé (ou service pas activé) : la personne valide en touchant la notification.
        val n = Notification.Builder(ctx, Notifs.CANAL_IMPORTANT)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("💬 Message WhatsApp prêt pour $nom")
            .setContentText(e.texte)
            .setStyle(Notification.BigTextStyle().bigText("${e.texte}\n\nTouche pour l'envoyer."))
            .setContentIntent(PendingIntent.getActivity(ctx, 9, ouvrir, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true)
            .build()
        Notifs.nm(ctx).notify(ID_NOTIF_WHATSAPP, n)
        Prefs.ajouterHistorique(ctx, "💬 WhatsApp prêt pour $nom : ${e.texte}")
        accuser(
            e.ack,
            JSONObject().put("statut", "pret").put("numero", numero)
                .put("detail", if (serviceActif(ctx)) "téléphone verrouillé : à valider sur la notification" else "touche la notification (service d'accessibilité Jarvis non activé : appuie aussi sur Envoyer)"),
        )
    }

    fun serviceActif(ctx: Context): Boolean {
        val actifs = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return actifs.split(':').any { ComponentName.unflattenFromString(it)?.className == WhatsappService::class.java.name }
    }

    // --- Contacts --------------------------------------------------------------------------------

    fun estNumero(s: String): Boolean = s.count { it.isDigit() } >= 6 && s.all { it.isDigit() || it in "+ .-()" }

    private fun sansAccents(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()

    /** Contacts dont le nom contient tous les mots demandés ; un seul numéro par numéro (sans doublon). */
    fun filtrer(tous: List<Pair<String, String>>, demande: String): List<Pair<String, String>> {
        val mots = sansAccents(demande).split(Regex("\\s+")).filter { it.isNotBlank() }
        if (mots.isEmpty()) return emptyList()
        val trouves = tous.filter { (nom, _) -> sansAccents(nom).let { n -> mots.all { n.contains(it) } } }
        // Nom exact prioritaire (« Paul » ne doit pas proposer « Paulette » si « Paul » existe).
        val exacts = trouves.filter { sansAccents(it.first) == mots.joinToString(" ") }
        return (exacts.ifEmpty { trouves }).distinctBy { it.second.filter { c -> c.isDigit() }.takeLast(9) }
    }

    fun chercher(ctx: Context, nom: String): List<Pair<String, String>> {
        val tous = mutableListOf<Pair<String, String>>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                tous.add(n to num)
            }
        }
        return filtrer(tous, nom)
    }

    fun autorise(ctx: Context, permission: String) = ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}

/** Résultat de l'envoi d'un SMS (pris en charge par le réseau, ou refusé). */
class SmsResultat : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val ack = intent.getStringExtra("ack") ?: return
        val numero = intent.getStringExtra("numero") ?: ""
        val nom = intent.getStringExtra("nom") ?: numero
        val ok = resultCode == android.app.Activity.RESULT_OK
        val pourquoi = when (resultCode) {
            SmsManager.RESULT_ERROR_NO_SERVICE -> "pas de réseau"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "mode avion"
            SmsManager.RESULT_ERROR_NULL_PDU, SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "refusé par l'opérateur ou le téléphone"
            else -> "erreur $resultCode"
        }
        Prefs.ajouterHistorique(ctx, if (ok) "✅ SMS envoyé à $nom" else "❌ SMS à $nom : $pourquoi")
        Envois.accuser(ack, JSONObject().put("statut", if (ok) "envoye" else "echec").put("numero", numero).apply { if (!ok) put("detail", pourquoi) })
    }
}

/**
 * Service d'accessibilité : quand Jarvis a préparé un message WhatsApp, appuie sur « Envoyer » dans la
 * conversation ouverte, une seule fois. Il n'agit que sur WhatsApp et seulement dans ce cas-là.
 */
class WhatsappService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (!pkg.startsWith("com.whatsapp")) return
        val attente = Prefs.whatsappEnAttente(this) ?: return
        val racine = rootInActiveWindow ?: return
        val bouton = racine.findAccessibilityNodeInfosByViewId("$pkg:id/send").firstOrNull()
            ?: trouver(racine) { it.contentDescription?.toString()?.lowercase() in setOf("envoyer", "send") }
            ?: return
        if (!bouton.isEnabled || !bouton.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        Prefs.oublierWhatsapp(this)
        Notifs.nm(this).cancel(4)
        Prefs.ajouterHistorique(this, "✅ WhatsApp envoyé à ${attente.second}")
        Envois.accuser(attente.first, JSONObject().put("statut", "envoye"))
    }

    private fun trouver(n: AccessibilityNodeInfo, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (test(n)) return n
        for (i in 0 until n.childCount) n.getChild(i)?.let { c -> trouver(c, test)?.let { return it } }
        return null
    }

    override fun onInterrupt() = Unit
}
