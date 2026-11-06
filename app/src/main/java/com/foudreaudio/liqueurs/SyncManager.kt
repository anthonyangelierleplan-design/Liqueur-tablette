package com.foudreaudio.liqueurs

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Synchronisation directe entre deux téléphones avec Nearby Connections
 * (Bluetooth + Wi-Fi direct, aucun accès Internet).
 *
 * Déroulé : les deux téléphones cherchent et s'annoncent en même temps. Celui dont
 * l'identifiant est le plus petit demande la connexion, pour éviter les doublons.
 * Chacun confirme le même code à 4 chiffres, puis envoie son fichier de données.
 * La page fusionne ce qu'elle reçoit, puis on se déconnecte.
 */
class SyncManager(
    private val activity: Activity,
    private val dataFile: File,
    private val js: (String) -> Unit
) {
    companion object {
        private const val SERVICE_ID = "com.foudreaudio.liqueurs.sync"
        private const val CHUNK = 30_000
        private const val T_HEADER: Byte = 1
        private const val T_CHUNK: Byte = 2
    }

    private val client by lazy { Nearby.getConnectionsClient(activity) }
    private val main = Handler(Looper.getMainLooper())
    private val myId: String by lazy {
        val prefs = activity.getSharedPreferences("sync", Activity.MODE_PRIVATE)
        prefs.getString("id", null) ?: UUID.randomUUID().toString().take(8).also { prefs.edit().putString("id", it).apply() }
    }
    private val myName get() = "$myId|${Build.MODEL}"

    private var active = false
    private var pendingEndpoint: String? = null
    private var peer: String? = null
    private var expected = -1
    private var chunks: Array<ByteArray?> = emptyArray()
    private val outgoing = mutableSetOf<Long>()
    private var sentAll = false
    private var merged = false
    private var finished = false

    private fun status(state: String, extra: JSONObject = JSONObject()) {
        extra.put("state", state)
        main.post { js("window.onSyncStatus && onSyncStatus($extra)") }
    }

    fun start() {
        stop(silent = true)
        active = true; finished = false
        // P2P_CLUSTER : le mode le plus tolérant (Bluetooth, BLE et Wi-Fi), largement suffisant pour nos données.
        val strategy = Strategy.P2P_CLUSTER
        client.startAdvertising(myName, SERVICE_ID, lifecycle, AdvertisingOptions.Builder().setStrategy(strategy).build())
            .addOnSuccessListener { status("visible") }
            .addOnFailureListener { fail(it, "annonce") }
        client.startDiscovery(SERVICE_ID, discovery, DiscoveryOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { fail(it, "recherche") }
        status("searching")
    }

    fun stop(silent: Boolean = false) {
        active = false
        try {
            client.stopAdvertising(); client.stopDiscovery(); client.stopAllEndpoints()
        } catch (_: Exception) {}
        pendingEndpoint = null; peer = null; expected = -1; chunks = emptyArray()
        outgoing.clear(); sentAll = false; merged = false
        if (!silent) status("stopped")
    }

    fun accept() {
        val ep = pendingEndpoint ?: return
        client.acceptConnection(ep, payloads).addOnFailureListener { fail(it, "acceptation") }
        status("waiting_other")
    }

    fun reject() {
        pendingEndpoint?.let { client.rejectConnection(it) }
        stop()
    }

    /** La page a fusionné les données reçues. */
    fun mergedDone() { merged = true; maybeFinish() }

    private fun codeOf(e: Exception) = (e as? ApiException)?.statusCode ?: -1

    private fun fail(e: Exception, step: String = "") {
        if (!active) return
        val code = codeOf(e)
        status("error", JSONObject().put("step", step).put("code", code)
            .put("detail", if (code >= 0) ConnectionsStatusCodes.getStatusCodeString(code) else (e.message ?: "")))
        stop(silent = true)
    }

    private fun request(endpointId: String) {
        if (!active || peer != null || pendingEndpoint != null) return
        client.requestConnection(myName, endpointId, lifecycle).addOnFailureListener { e ->
            when (codeOf(e)) {
                // L'autre téléphone a lancé la connexion en même temps : on laisse la sienne aboutir.
                ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT,
                ConnectionsStatusCodes.STATUS_OUT_OF_ORDER_API_CALL -> {}
                // Liaison radio ratée : on retente une fois un peu plus tard.
                ConnectionsStatusCodes.STATUS_ENDPOINT_IO_ERROR,
                ConnectionsStatusCodes.STATUS_RADIO_ERROR -> main.postDelayed({ request(endpointId) }, 2500)
                else -> fail(e, "connexion")
            }
        }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (!active || peer != null || pendingEndpoint != null) return
            val otherId = info.endpointName.substringBefore("|")
            status("found", JSONObject().put("name", info.endpointName.substringAfter("|")))
            // Le plus petit identifiant demande tout de suite ; l'autre prend le relais après 5 s si rien ne se passe
            // (utile quand un seul des deux téléphones a détecté l'autre).
            if (myId < otherId) request(endpointId) else main.postDelayed({ request(endpointId) }, 5000)
        }
        override fun onEndpointLost(endpointId: String) {}
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            if (!active || (pendingEndpoint != null && pendingEndpoint != endpointId)) { client.rejectConnection(endpointId); return }
            pendingEndpoint = endpointId
            status("confirm", JSONObject()
                .put("code", info.authenticationDigits)
                .put("name", info.endpointName.substringAfter("|")))
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (!result.status.isSuccess) {
                if (active) { status("refused"); stop(silent = true) }
                return
            }
            peer = endpointId
            client.stopAdvertising(); client.stopDiscovery()
            status("transfer")
            sendData(endpointId)
        }

        override fun onDisconnected(endpointId: String) {
            if (!active || finished) return
            if (merged) { finished = true; stop(silent = true); status("done") }   // données de l'autre déjà reçues et fusionnées
            else { status("lost"); stop(silent = true) }
        }
    }

    private fun sendData(endpointId: String) {
        val bytes = try { if (dataFile.exists()) dataFile.readBytes() else "{}".toByteArray() } catch (e: Exception) { "{}".toByteArray() }
        val n = maxOf(1, (bytes.size + CHUNK - 1) / CHUNK)
        val header = ByteBuffer.allocate(9).put(T_HEADER).putInt(n).putInt(bytes.size).array()
        val all = mutableListOf(Payload.fromBytes(header))
        for (i in 0 until n) {
            val from = i * CHUNK; val to = minOf(bytes.size, from + CHUNK)
            val part = ByteBuffer.allocate(5 + (to - from)).put(T_CHUNK).putInt(i).put(bytes, from, to - from).array()
            all.add(Payload.fromBytes(part))
        }
        all.forEach { outgoing.add(it.id) }
        all.forEach { client.sendPayload(endpointId, it).addOnFailureListener { e -> fail(e, "envoi") } }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val b = payload.asBytes() ?: return
            val buf = ByteBuffer.wrap(b)
            when (buf.get()) {
                T_HEADER -> { expected = buf.int; chunks = arrayOfNulls(expected) }
                T_CHUNK -> {
                    val i = buf.int
                    if (i in chunks.indices) chunks[i] = b.copyOfRange(5, b.size)
                    if (expected > 0 && chunks.all { it != null }) {
                        val out = java.io.ByteArrayOutputStream(); chunks.forEach { out.write(it!!) }
                        val text = out.toString("UTF-8")
                        chunks = emptyArray(); expected = -1
                        main.post { js("window.onSyncData && onSyncData(${JSONObject.quote(text)})") }
                    }
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.payloadId !in outgoing) return
            when (update.status) {
                PayloadTransferUpdate.Status.SUCCESS -> {
                    outgoing.remove(update.payloadId)
                    if (outgoing.isEmpty()) { sentAll = true; maybeFinish() }
                }
                PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED -> {
                    if (active) { status("lost"); stop(silent = true) }
                }
            }
        }
    }

    private fun maybeFinish() {
        if (!sentAll || !merged || finished) return
        finished = true
        // Laisse le temps à l'autre téléphone de confirmer la réception avant de couper.
        main.postDelayed({ stop(silent = true); status("done") }, 800)
    }
}
