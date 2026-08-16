package com.matteomigliore.pcmanager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

/**
 * Blocco siti a livello di RETE, per coprire anche ciò che la barra degli indirizzi non mostra:
 * pagine aperte dentro altre app (webview dei social), app non-browser, download.
 *
 * Come funziona: si alza una VPN locale che NON instrada tutto il traffico — sarebbe pesante e
 * rischioso — ma soltanto le richieste DNS. Ogni richiesta viene letta:
 *  · dominio vietato dal profilo → si risponde "non esiste" (NXDOMAIN) e il sito non si apre
 *  · dominio permesso            → si inoltra a un DNS pubblico e si restituisce la risposta
 * Tutto il resto del traffico non passa di qui: se questo servizio cade, la navigazione continua.
 *
 * Limite noto: i browser che usano DNS-over-HTTPS aggirano il DNS di sistema. Per questo il blocco
 * via Accessibilità (SiteGuard) resta attivo: legge l'indirizzo e non dipende dal DNS. I due
 * meccanismi si coprono a vicenda.
 */
class SiteVpnService : VpnService() {

    companion object {
        const val AZIONE_STOP = "com.matteomigliore.pcmanager.VPN_STOP"
        private const val VPN_IP = "10.111.222.1"
        private const val DNS_FINTO = "10.111.222.2"
        private val DNS_VERI = listOf("1.1.1.1", "8.8.8.8")
        @Volatile
        var attiva = false
            private set
    }

    private var tun: ParcelFileDescriptor? = null
    private val pool = Executors.newFixedThreadPool(4)

    @Volatile
    private var vivo = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == AZIONE_STOP) { stopSelf(); return START_NOT_STICKY }
        avviaNotifica()
        if (!vivo) { vivo = true; Thread { ciclo() }.start() }
        return START_STICKY
    }

    private fun avviaNotifica() {
        val ch = "pcm_vpn"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(ch, "Filtro siti", NotificationManager.IMPORTANCE_MIN))
        }
        val n = Notification.Builder(this, ch)
            .setContentTitle("PC Manager")
            .setContentText("Filtro siti attivo")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .build()
        startForeground(42, n)
    }

    private fun ciclo() {
        try {
            val b = Builder()
                .addAddress(VPN_IP, 32)
                .addDnsServer(DNS_FINTO)
                .addRoute(DNS_FINTO, 32)          // SOLO il DNS passa dal tunnel
                .setSession("PC Manager")
                .setBlocking(true)
            if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
            // la nostra app non deve filtrare sé stessa (aggiornamenti, WebSocket verso il cloud)
            try { b.addDisallowedApplication(packageName) } catch (_: Exception) {}
            tun = b.establish() ?: return
            attiva = true

            val inp = FileInputStream(tun!!.fileDescriptor)
            val out = FileOutputStream(tun!!.fileDescriptor)
            val buf = ByteArray(32767)
            while (vivo) {
                val n = try { inp.read(buf) } catch (_: Exception) { -1 }
                if (n <= 0) { if (!vivo) break else continue }
                val pacchetto = buf.copyOf(n)
                pool.execute { try { gestisci(pacchetto, out) } catch (_: Exception) {} }
            }
        } catch (_: Exception) {
        } finally {
            attiva = false
            try { tun?.close() } catch (_: Exception) {}
        }
    }

    /** Estrae la domanda DNS, decide, e riscrive la risposta dentro il tunnel. */
    private fun gestisci(pkt: ByteArray, out: FileOutputStream) {
        if (pkt.size < 28) return
        if ((pkt[0].toInt() shr 4 and 0xF) != 4) return           // solo IPv4
        val ihl = (pkt[0].toInt() and 0xF) * 4
        if (pkt[9].toInt() != 17) return                           // solo UDP
        if (pkt.size < ihl + 8) return
        val dstPort = ((pkt[ihl + 2].toInt() and 0xFF) shl 8) or (pkt[ihl + 3].toInt() and 0xFF)
        if (dstPort != 53) return
        val dns = pkt.copyOfRange(ihl + 8, pkt.size)
        val host = nomeQuery(dns) ?: return

        val motivo = try { SiteRules.motivoBlocco(this, host) } catch (_: Exception) { null }
        registra(host)
        val risposta = if (motivo != null) rispostaNegata(dns) else (inoltra(dns) ?: return)
        synchronized(this) {
            out.write(costruisciIpUdp(pkt, ihl, risposta))
            out.flush()
        }
    }

    /** Nome della prima domanda (formato DNS: etichette precedute dalla loro lunghezza). */
    private fun nomeQuery(dns: ByteArray): String? {
        try {
            if (dns.size < 13) return null
            var i = 12
            val sb = StringBuilder()
            while (i < dns.size) {
                val len = dns[i].toInt() and 0xFF
                if (len == 0) break
                if (len > 63 || i + len >= dns.size) return null
                if (sb.isNotEmpty()) sb.append('.')
                sb.append(String(dns, i + 1, len, Charsets.US_ASCII))
                i += len + 1
            }
            val h = sb.toString().lowercase()
            return if (h.contains(".")) h.removePrefix("www.") else null
        } catch (_: Exception) {
            return null
        }
    }

    /** Risposta "dominio inesistente": il sito non si apre affatto. */
    private fun rispostaNegata(query: ByteArray): ByteArray {
        val r = query.copyOf()
        r[2] = 0x81.toByte()          // QR=1 (risposta), RD copiato
        r[3] = 0x83.toByte()          // RA=1, RCODE=3 (NXDOMAIN)
        r[6] = 0; r[7] = 0            // nessun record di risposta
        return r
    }

    /** Inoltra al DNS pubblico con socket PROTETTO: senza protect() rientrerebbe nel tunnel. */
    private fun inoltra(query: ByteArray): ByteArray? {
        for (server in DNS_VERI) {
            try {
                DatagramSocket().use { s ->
                    protect(s)
                    s.soTimeout = 3000
                    s.send(DatagramPacket(query, query.size, InetAddress.getByName(server), 53))
                    val buf = ByteArray(4096)
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    return buf.copyOf(p.length)
                }
            } catch (_: Exception) { /* provo il server successivo */ }
        }
        return null
    }

    /** Rimette la risposta in un pacchetto IP/UDP con mittente e destinatario scambiati. */
    private fun costruisciIpUdp(orig: ByteArray, ihl: Int, payload: ByteArray): ByteArray {
        val totale = ihl + 8 + payload.size
        val o = ByteArray(totale)
        System.arraycopy(orig, 0, o, 0, ihl + 8)
        System.arraycopy(payload, 0, o, ihl + 8, payload.size)
        o[2] = (totale shr 8).toByte(); o[3] = totale.toByte()
        for (k in 0 until 4) { val t = o[12 + k]; o[12 + k] = o[16 + k]; o[16 + k] = t }
        for (k in 0 until 2) { val t = o[ihl + k]; o[ihl + k] = o[ihl + 2 + k]; o[ihl + 2 + k] = t }
        val udpLen = 8 + payload.size
        o[ihl + 4] = (udpLen shr 8).toByte(); o[ihl + 5] = udpLen.toByte()
        o[ihl + 6] = 0; o[ihl + 7] = 0     // checksum UDP a 0: su IPv4 significa "non calcolato"
        o[10] = 0; o[11] = 0
        var somma = 0
        var i = 0
        while (i < ihl) { somma += ((o[i].toInt() and 0xFF) shl 8) or (o[i + 1].toInt() and 0xFF); i += 2 }
        while (somma shr 16 != 0) somma = (somma and 0xFFFF) + (somma shr 16)
        val ck = somma.inv() and 0xFFFF
        o[10] = (ck shr 8).toByte(); o[11] = ck.toByte()
        return o
    }

    /** Ogni dominio richiesto finisce in "Siti visitati": qui si vede anche ciò che sta nelle app. */
    private fun registra(host: String) {
        try {
            val sp = getSharedPreferences("pcm", MODE_PRIVATE)
            val o = org.json.JSONObject(sp.getString("sitequeue", "{}") ?: "{}")
            o.put(host, o.optInt(host, 0) + 1)
            sp.edit().putString("sitequeue", o.toString()).apply()
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        vivo = false
        attiva = false
        try { tun?.close() } catch (_: Exception) {}
        pool.shutdownNow()
        super.onDestroy()
    }
}
