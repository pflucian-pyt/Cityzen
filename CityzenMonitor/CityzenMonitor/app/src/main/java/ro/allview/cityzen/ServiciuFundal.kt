package ro.allview.cityzen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * Serviciu de prim-plan.
 *
 * Rostul lui: fara notificare permanenta, Android opreste procesul la prima
 * strangere de memorie, iar legatura cu adaptorul se rupe in mijlocul drumului.
 * Cu el, procesul are prioritate de aplicatie vizibila.
 *
 * ATENTIE, limita reala: serviciul tine procesul in viata, dar NU porneste
 * ceasurile din pagina. Cand WebView-ul nu e vizibil, Android incetineste
 * temporizatoarele din JavaScript, deci interogarea se rareste. De asta ecranul
 * e tinut aprins. Inregistrarea cu ecranul stins cere mutarea buclei de
 * interogare din HTML in Kotlin — pasul urmator, nu acesta.
 */
class ServiciuFundal : Service() {

    companion object {
        private const val CANAL = "cityzen_monitor"
        private const val ID_NOTIF = 42
    }

    private var trezire: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        creeazaCanal()
        porneste()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        trezire = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AvatrMonitor:citire").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)   // cel mai lung drum plauzibil
        }
    }

    private fun creeazaCanal() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val c = NotificationChannel(CANAL, "Citire din masina", NotificationManager.IMPORTANCE_LOW)
        c.setShowBadge(false)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(c)
    }

    private fun porneste() {
        val deschide = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = NotificationCompat.Builder(this, CANAL)
            .setContentTitle("Avatr Monitor")
            .setContentText("Citesc date din masina")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(deschide)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        // De la Android 14, un serviciu de tip "dispozitiv conectat" porneste
        // numai daca aplicatia DETINE deja o permisiune Bluetooth. Daca omul
        // n-a apucat sa raspunda la cerere, sistemul arunca SecurityException
        // si aplicatia moare. Asa ca incercam intai cu tipul cerut, si daca nu
        // se poate pornim fara tip: notificarea si prioritatea procesului
        // raman, doar eticheta lipseste.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(ID_NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(ID_NOTIF, n)
            }
        } catch (e: Throwable) {
            Jurnal.scrie("serviciul de prim-plan cu tip a esuat, incerc fara", e)
            try {
                startForeground(ID_NOTIF, n)
            } catch (x: Throwable) {
                Jurnal.scrie("serviciul de prim-plan nu a putut porni deloc", x)
                stopSelf()   // mai bine fara serviciu decat cu aplicatia oprita
            }
        }
    }

    override fun onStartCommand(i: Intent?, steaguri: Int, id: Int): Int = START_STICKY

    override fun onBind(i: Intent?): IBinder? = null

    override fun onDestroy() {
        trezire?.let { if (it.isHeld) it.release() }
        trezire = null
        super.onDestroy()
    }
}
