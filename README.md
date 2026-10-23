# Jarvis — appli Android

Petite appli Android qui reçoit les notifications et les réveils de [Jarvis](https://github.com/Azhabel/jarvis-assistant)
via [ntfy](https://ntfy.sh), sans MacroDroid ni Chrome :

- connexion permanente (service au premier plan), démarre avec le téléphone, se reconnecte seule et récupère les messages manqués ;
- notifications immédiates, même écran éteint ;
- **briefing du matin lu à voix haute** (synthèse vocale d'Android), relu après l'arrêt du réveil ;
- vrais réveils (`AlarmManager.setAlarmClock`) : écran plein avec sonnerie, boutons **Arrêter** et **Snooze 10 min**, annulation à distance ;
- écran unique : serveur, sujet, autorisations, tests, réveils programmés, historique.
- **partage vers la télé** : dans Google Photos (ou la galerie), « Partager › Jarvis (télé) » envoie une photo,
  plusieurs photos (diaporama, vitesse au choix) ou une vidéo sur l'écran choisi. L'appli se lie une fois à Jarvis
  avec « Lier à Jarvis » en scannant le QR code de Jarvis › Téléphone (elle reçoit son propre jeton d'appareil).

## Installer

Sur le téléphone, ouvrir **https://github.com/Sasnakra965/jarvis-android/releases/latest/download/jarvis.apk**,
installer (autoriser « sources inconnues » pour le navigateur la première fois), ouvrir Jarvis,
renseigner le **sujet** (le même que `NTFY_TOPIC` dans le `.env` de Jarvis) puis accorder les autorisations listées.

## Format des messages

Corps d'un message publié sur le sujet ntfy (`POST https://ntfy.sh/<sujet>`), en JSON :

| type | champs | effet |
|---|---|---|
| `notif` | `titre`, `message`, `priorite` : `basse` \| `normale` \| `haute` \| `urgente`, `url` (facultatif) | notification (haute/urgente : canal « Alertes importantes ») ; toucher ouvre `url` |
| `reveil` | `id`, `timestamp` (secondes) **ou** `heure` + `minute`, `libelle` | programme une alarme ; même `id` = remplace |
| `annuler_reveil` | `id` (facultatif) | annule ce réveil, ou tous sans `id` |
| `choix` | `titre`, `message`, `url` (facultatif), `choix` : `[{libelle, url}]` (3 au plus) | notification à boutons : chaque bouton envoie un `POST` à son `url` sans ouvrir l'appli, puis affiche « ✅ Noté » ; toucher ouvre `url` |
| `briefing` | `titre`, `message`, `parole` (texte lu), `url` (facultatif), `attendre_reveil` (facultatif) | notification lue à voix haute (voix française, écran verrouillé, haut-parleur ou écouteurs) ; boutons **Relire**, **Stop**, **Plus de détails** (ouvre l'appli Claude) ; toucher ouvre `url` (le Kanban). Pendant un réveil, ou avec `attendre_reveil`, la lecture attend l'arrêt de l'alarme (20 min au plus) |

```json
{"type":"notif","titre":"Jarvis","message":"Sauvegarde ratée","priorite":"haute"}
{"type":"reveil","id":"r-1761198600","heure":7,"minute":30,"timestamp":1761198600,"libelle":"Chantier Dupont"}
{"type":"annuler_reveil","id":"r-1761198600"}
```

`timestamp` prime sur `heure`/`minute` ; sans lui, c'est la prochaine fois qu'il est `heure:minute` (heure du
téléphone) après l'envoi. Un réveil dont l'heure est passée est ignoré. Un message qui n'est pas du JSON
s'affiche tel quel comme notification (titre et priorité ntfy).

Le sujet fait office de mot de passe : quiconque le connaît peut envoyer des messages. Choisir un nom long et aléatoire.

**Chiffrement de bout en bout** : une fois l'appli liée à Jarvis (« Lier à Jarvis »), elle récupère une clé
(`GET /api/ntfy/cle`, avec le serveur et le sujet) et Jarvis chiffre chaque message : corps
`{"chiffre": base64(iv 12 octets | texte | tag 16 octets)}`, AES-256-GCM. ntfy.sh ne voit plus le contenu.

## Développer

Kotlin, API Android seules, plus le lecteur de QR code des services Google (`play-services-code-scanner`). `gradle assembleRelease` (Gradle 8.11, JDK 17, SDK 35).
Chaque push sur `main` compile, teste, signe et publie une Release (`.github/workflows/apk.yml`) ;
secrets du dépôt : `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD`, `KEY_ALIAS`.
