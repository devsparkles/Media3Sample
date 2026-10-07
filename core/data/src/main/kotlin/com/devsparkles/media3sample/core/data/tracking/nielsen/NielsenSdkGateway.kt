package com.devsparkles.media3sample.core.data.tracking.nielsen

/**
 * RÔLE : frontière entre notre code et le SDK Nielsen (Nielsen App SDK, classe `AppSdk`).
 *
 * ⚠️ LE SDK NIELSEN RÉEL N'EST PAS INCLUS DANS CE PROJET. Il est propriétaire : Nielsen fournit
 * le binaire et un `appid` à chaque client lors de l'onboarding. Ici, [LoggingNielsenSdkGateway]
 * se contente de journaliser chaque appel dans Logcat (tag « Nielsen »).
 *
 * Pourquoi une interface (pattern Gateway / Adapter) ?
 *  - les règles d'appel (quand faire stop, end, loadMetadata...) vivent dans [NielsenTracker],
 *    en Kotlin pur, testables en JVM sans le SDK ni Android ;
 *  - brancher le vrai SDK = écrire `AppSdkNielsenGateway` (module Android) qui convertit les
 *    Map en `org.json.JSONObject` et délègue à `AppSdk`. Rien d'autre ne change.
 *
 * Noms et signatures calqués sur l'API Android documentée (Engineering Portal Nielsen) :
 *  - `public void play(JSONObject channelInfo);`         https://engineeringportal.nielsen.com/wiki/play()
 *  - `public void loadMetadata(JSONObject jsonMetadata);` https://engineeringportal.nielsen.com/wiki/loadMetadata()
 *  - `public void setPlayheadPosition(long position)`, `stop()`, `end()`
 *    https://engineeringportal.nielsen.com/wiki/Android_SDK_API_Reference
 * Les métadonnées sont des Map<String, String> (et non des JSONObject) car org.json est une API
 * Android : un module Kotlin/JVM pur ne peut pas l'utiliser dans ses tests.
 */
interface NielsenSdkGateway {
    /** Ouvre la mesure d'un flux. Clés : `channelName` (optionnel en DCR), `mediaURL` (optionnel). */
    fun play(channelInfo: Map<String, String>)

    /** Métadonnées de l'asset courant (contenu OU pub, jamais mélangées). */
    fun loadMetadata(metadata: Map<String, String>)

    /** VOD : secondes depuis le début de l'asset. Live : heure Unix UTC en secondes. */
    fun setPlayheadPosition(positionSeconds: Long)

    fun stop()

    fun end()

    /**
     * Côté SDK réel : `AppLaunchMeasurementManager.appInBackground(context)`, ou détection
     * automatique via LifecycleObserver (approche recommandée à partir du SDK 7.1.0.0, d'après
     * https://engineeringportal.nielsen.com/wiki/DCR_Video_Android_SDK). Le gateway réel choisit.
     */
    fun appInBackground()

    fun appInForeground()

    /** Libère l'instance du SDK (`appSdk.close()`), à la fermeture de l'application uniquement. */
    fun close()
}

/**
 * Implémentation de démonstration : journalise chaque appel, sans rien envoyer.
 * @param log branché sur `Log.d("Nielsen", ...)` par AppContainer (ce module est Kotlin pur).
 */
class LoggingNielsenSdkGateway(private val log: (String) -> Unit) : NielsenSdkGateway {
    override fun play(channelInfo: Map<String, String>) = log("play($channelInfo)")
    override fun loadMetadata(metadata: Map<String, String>) = log("loadMetadata($metadata)")
    override fun setPlayheadPosition(positionSeconds: Long) = log("setPlayheadPosition($positionSeconds)")
    override fun stop() = log("stop()")
    override fun end() = log("end()")
    override fun appInBackground() = log("appInBackground()")
    override fun appInForeground() = log("appInForeground()")
    override fun close() = log("close()")
}
