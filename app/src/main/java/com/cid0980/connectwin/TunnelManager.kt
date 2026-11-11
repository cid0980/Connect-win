package com.cid0980.connectwin

import android.content.Context
import android.net.ConnectivityManager
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate

class TunnelManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onUrlChanged: (String?) -> Unit
) {
    private var loopJob: Job? = null
    @Volatile
    private var currentProcess: Process? = null

    private val quickUrlRegex = Regex("https://[a-z0-9-]+\\.trycloudflare\\.com")

    fun start() {
        stop()
        loopJob = scope.launch(Dispatchers.IO) {
            val caBundle = ensureCaBundle()
            while (isActive) {
                try {
                    runOnce(caBundle)
                } catch (t: Throwable) {
                    BridgeState.update {
                        it.copy(tunnelStatus = "Reconnecting...", lastError = t.message?.take(120))
                    }
                    BridgeState.log("Tunnel error: ${t.message}")
                }
                if (isActive) {
                    delay(3500L)
                }
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        runCatching { currentProcess?.destroyForcibly() }
        currentProcess = null
        onUrlChanged(null)
    }

    private fun resolveBinary(): File {
        val libDir = File(context.applicationInfo.nativeLibraryDir)
        val f = File(libDir, "libcloudflared.so")
        if (!f.exists()) {
            throw IllegalStateException("libcloudflared.so missing in ${libDir.absolutePath}")
        }
        return f
    }

    /**
     * Exports Android's trusted system CA certificates into a PEM bundle so
     * statically compiled Go `cloudflared` (SSL_CERT_FILE) verifies TLS cleanly
     * across Android 11–16 (including APEX conscrypt cert stores).
     */
    private fun ensureCaBundle(): File {
        val out = File(context.filesDir, "cacert.pem")
        if (out.exists() && out.length() > 10_000L) return out
        val sb = StringBuilder()
        val ks = KeyStore.getInstance("AndroidCAStore")
        ks.load(null, null)
        val aliases = ks.aliases()
        while (aliases.hasMoreElements()) {
            val alias = aliases.nextElement()
            val cert = ks.getCertificate(alias) as? X509Certificate ?: continue
            val b64 = Base64.encodeToString(cert.encoded, Base64.NO_WRAP)
            sb.append("-----BEGIN CERTIFICATE-----\n")
            b64.chunked(64).forEach { line ->
                sb.append(line).append('\n')
            }
            sb.append("-----END CERTIFICATE-----\n")
        }
        out.writeText(sb.toString())
        return out
    }

    private fun activeDnsServers(): String {
        val list = mutableListOf<String>()
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val lp = cm.getLinkProperties(cm.activeNetwork)
            lp?.dnsServers?.forEach { addr ->
                val host = addr.hostAddress ?: return@forEach
                // Prefer IPv4 DNS or bracketed IPv6
                if (!host.contains(":")) {
                    list.add("$host:53")
                }
            }
        }
        list.addAll(listOf("1.1.1.1:53", "8.8.8.8:53", "9.9.9.9:53"))
        return list.distinct().joinToString(",")
    }

    private fun runOnce(caBundle: File) {
        val bin = resolveBinary()
        val namedToken = BridgeState.getNamedTunnelToken(context)
        val customHost = BridgeState.getCustomHostname(context)
        val proto = BridgeState.getTunnelProtocol(context)

        val args = mutableListOf(
            bin.absolutePath,
            "tunnel",
            "--no-autoupdate",
            "--protocol", proto,
            "--edge-ip-version", "4"
        )

        if (namedToken.isNotBlank()) {
            args.addAll(listOf("run", "--token", namedToken))
            BridgeState.update {
                it.copy(
                    tunnelStatus = "Connecting (Named Tunnel)...",
                    tunnelUrl = customHost.ifBlank { "(Named Tunnel active — check CF Dashboard)" }
                )
            }
            if (customHost.isNotBlank()) onUrlChanged(customHost)
        } else {
            args.addAll(listOf("--url", "http://127.0.0.1:${BridgeState.HTTP_PORT}"))
            BridgeState.update { it.copy(tunnelStatus = "Requesting Quick Tunnel...", tunnelUrl = null) }
        }

        val pb = ProcessBuilder(args)
            .directory(context.filesDir)
            .redirectErrorStream(true)

        pb.environment().apply {
            put("HOME", context.filesDir.absolutePath)
            put("SSL_CERT_FILE", caBundle.absolutePath)
            put("CONNECTWIN_DNS", activeDnsServers())
            put("TUNNEL_METRICS", "127.0.0.1:0")
        }

        val proc = pb.start()
        currentProcess = proc
        BridgeState.log("cloudflared started (pid-alive=${proc.isAlive}, proto=$proto)")

        proc.inputStream.bufferedReader().useLines { lines ->
            for (line in lines) {
                val m = quickUrlRegex.find(line)
                if (m != null) {
                    val url = m.value
                    BridgeState.update {
                        it.copy(tunnelStatus = "Connected", tunnelUrl = url, lastError = null)
                    }
                    BridgeState.log("Tunnel LIVE -> $url")
                    onUrlChanged(url)
                } else if (namedToken.isNotBlank() && line.contains("Registered tunnel connection")) {
                    BridgeState.update {
                        it.copy(
                            tunnelStatus = "Connected (Named)",
                            lastError = null
                        )
                    }
                    BridgeState.log("Named tunnel registered")
                } else if (line.contains("ERR ") || line.contains("error=")) {
                    BridgeState.log("cf: ${line.take(140)}")
                }
            }
        }

        val rc = proc.waitFor()
        BridgeState.log("cloudflared exited (rc=$rc)")
    }
}
