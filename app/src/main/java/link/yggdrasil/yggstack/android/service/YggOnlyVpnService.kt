package link.yggdrasil.yggstack.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import hev.htproxy.TProxyService
import link.yggdrasil.yggstack.android.R
import java.io.File

/**
 * Android VPN adapter for Yggstack's local SOCKS5 listener.
 * Only the Yggdrasil IPv6 allocation is routed into the VPN. Android owns the
 * interface and supplies its file descriptor to hev; the `tun0` name in Hev's
 * config is therefore informational on Android (the descriptor is authoritative).
 */
class YggOnlyVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                stopSelf(startId)
            }
            ACTION_START, null -> {
                val saved = getSharedPreferences(PREFS, MODE_PRIVATE)
                val address = intent?.getStringExtra(EXTRA_ADDRESS)
                    ?: saved.getString(KEY_ADDRESS, "127.0.0.1")
                val port = intent?.getIntExtra(EXTRA_PORT, 0)?.takeIf { it > 0 }
                    ?: saved.getInt(KEY_PORT, 1080)
                val localYggAddress = intent?.getStringExtra(EXTRA_LOCAL_ADDRESS)
                    ?: saved.getString(KEY_LOCAL_ADDRESS, null)
                if (address.isNullOrBlank() || port !in 1..65535 || !isYggdrasilAddress(localYggAddress)) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                startTunnel(address, port, localYggAddress!!, startId)
            }
        }
        return START_STICKY
    }

    private fun startTunnel(socksAddress: String, socksPort: Int, localYggAddress: String, startId: Int) {
        if (tun != null) stopTunnel()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification())

        try {
            val builder = Builder()
                .setSession("Yggdrasil")
                .setMtu(1500)
                // Give apps the actual Yggdrasil address so they can bind to it.
                .addAddress(localYggAddress, 7)
                // Yggdrasil's 200::/7 allocation includes routed 300::/8 subnets.
                .addRoute("200::", 7)
                // Android DNS resolver AAAA-query compatibility route, also used
                // by yggdrasil-android; all other public IPv6 stays off this VPN.
                .addRoute("2000::", 128)
                // A VPN with IPv6 routes can otherwise block IPv6 destinations that
                // do not match a VPN route. Let ordinary IPv6 fall through to the
                // underlying network, just as IPv4 does; only Yggdrasil uses Hev.
                .allowFamily(OsConstants.AF_INET6)
                .allowFamily(OsConstants.AF_INET)
                .allowBypass()

            tun = builder.establish() ?: error("Android did not establish the VPN interface")

            val config = """
                tunnel:
                  name: tun0
                  mtu: 1500
                  ipv6: '$localYggAddress'
                  icmp: 'off'
                socks5:
                  address: '$socksAddress'
                  port: $socksPort
                  udp: 'udp'
                misc:
                  log-level: warn
            """.trimIndent()
            val configFile = File(filesDir, "ygg-only-hev.yml")
            configFile.writeText(config)

            check(TProxyService.TProxyStartService(configFile.absolutePath, tun!!.fd)) {
                "HevSocks5Tunnel did not start"
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_RUNNING, true)
                .putString(KEY_ADDRESS, socksAddress)
                .putInt(KEY_PORT, socksPort)
                .putString(KEY_LOCAL_ADDRESS, localYggAddress)
                .apply()
        } catch (e: Exception) {
            stopTunnel()
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, false).apply()
            stopSelf(startId)
        }
    }

    private fun stopTunnel() {
        runCatching { TProxyService.TProxyStopService() }
        runCatching { tun?.close() }
        tun = null
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, false).apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onRevoke() {
        stopTunnel()
        stopSelf()
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.ygg_vpn_title), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(getString(R.string.ygg_vpn_title))
        .setContentText(getString(R.string.ygg_vpn_running))
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    private fun isYggdrasilAddress(address: String?): Boolean = address != null && runCatching {
        val bytes = java.net.InetAddress.getByName(address).address
        bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0x02
    }.getOrDefault(false)

    companion object {
        const val ACTION_START = "link.yggdrasil.yggstack.android.plus.VPN_START"
        const val ACTION_STOP = "link.yggdrasil.yggstack.android.plus.VPN_STOP"
        const val EXTRA_ADDRESS = "socks_address"
        const val EXTRA_PORT = "socks_port"
        const val EXTRA_LOCAL_ADDRESS = "local_yggdrasil_address"
        const val PREFS = "ygg_only_vpn"
        const val KEY_ENABLED = "enabled"
        const val KEY_RUNNING = "running"
        private const val KEY_ADDRESS = "address"
        private const val KEY_PORT = "port"
        private const val KEY_LOCAL_ADDRESS = "local_yggdrasil_address"
        private const val CHANNEL_ID = "ygg_only_vpn"
        private const val NOTIFICATION_ID = 7012
    }
}
