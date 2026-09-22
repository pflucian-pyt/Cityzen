package ro.allview.cityzen

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageInfo
import android.os.Build
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Jurnalul Bluetooth, scris de amandoua puntile.
 *
 * De ce exista: in masina, unitatea vede adaptorul in lista de dispozitive
 * imperecheate, dar aplicatia nu se leaga. De pe dinafara nu se poate spune de
 * ce — mesajul ajuns in pagina e scurt si trece prin trei straturi pana acolo.
 * Aici se scrie fiecare pas, cu ceas, exact cum l-a vazut Androidul.
 *
 * Pe langa pasi, se ia si o radiografie a aparatului. Doua lucruri din ea
 * lamuresc de obicei totul:
 *
 *  - TIPUL dispozitivului imperecheat. Android il stie: CLASIC, LE sau AMANDOUA.
 *    Un adaptor care apare ca LE nu se leaga niciodata pe RFCOMM, oricat ai
 *    incerca, si invers.
 *  - SERVICIILE lui. Daca printre ele nu e 1101 — Serial Port Profile — atunci
 *    nu exista tub serial de deschis, si toata puntea clasica bate in perete.
 *
 * Amandoua se afla in doua secunde si scutesc zile de incercari.
 */
@SuppressLint("MissingPermission")
object JurnalBt {

    private const val MAX = 400
    private val randuri = ArrayList<String>()
    private val ceas = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun scrie(text: String) {
        val r = ceas.format(Date()) + "  " + text
        randuri.add(r)
        if (randuri.size > MAX) randuri.removeAt(0)
        Log.i("JurnalBt", text)
    }

    @Synchronized
    fun goleste() { randuri.clear(); scrie("jurnal golit") }

    @Synchronized
    private fun copie(): List<String> = ArrayList(randuri)

    /** Numele omenesc al tipului de radio pe care il are dispozitivul. */
    private fun tip(d: BluetoothDevice): String = try {
        when (d.type) {
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> "clasic"
            BluetoothDevice.DEVICE_TYPE_LE -> "numai LE"
            BluetoothDevice.DEVICE_TYPE_DUAL -> "clasic și LE"
            else -> "necunoscut"
        }
    } catch (e: Exception) { "nu s-a putut citi" }

    private fun legatura(d: BluetoothDevice): String = try {
        when (d.bondState) {
            BluetoothDevice.BOND_BONDED -> "împerecheat"
            BluetoothDevice.BOND_BONDING -> "se împerechează"
            else -> "NEîmperecheat"
        }
    } catch (e: Exception) { "necunoscut" }

    /**
     * Radiografia sistemului.
     *
     * Adunata la cerere, ca sa avem intr-un singur fisier tot ce s-ar putea
     * cauta doua ore prin meniuri. Trei lucruri de aici conteaza cel mai des:
     *
     *  - CAPABILITATILE declarate. Daca unitatea spune ca are bluetooth_le dar
     *    nu gaseste nimic, minciuna se vede aici, alaturi de faptul ca scanner-ul
     *    exista. E fix cazul din Cityzen.
     *  - PERMISIUNILE. Pe Android 12 si mai nou, fara BLUETOOTH_CONNECT lista de
     *    imperecheate vine goala si totul pare defect de Bluetooth.
     *  - APLICATIILE CARE CER OBD. Adaptorul accepta o singura legatura; daca o
     *    tine Car Scanner, aplicatia noastra nu poate intra, si nimic din
     *    jurnal n-ar spune de ce.
     */
    private fun radiografiaSistemului(c: Context): JSONObject {
        val o = JSONObject()

        val b = JSONObject()
        b.put("producator", Build.MANUFACTURER)
        b.put("model", Build.MODEL)
        b.put("aparat", Build.DEVICE)
        b.put("produs", Build.PRODUCT)
        b.put("placa", Build.BOARD)
        b.put("hardware", Build.HARDWARE)
        b.put("amprenta", Build.FINGERPRINT)
        b.put("android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")")
        b.put("compilare", Build.DISPLAY)
        if (Build.VERSION.SDK_INT >= 23) b.put("peticDeSecuritate", Build.VERSION.SECURITY_PATCH)
        o.put("aparat", b)

        /* Capabilitatile declarate de sistem. Se iau toate cele care ne pot
           interesa, nu doar Bluetooth: cand ceva lipseste, e bine sa se vada
           in acelasi loc. */
        val cap = JSONObject()
        listOf(
            "android.hardware.bluetooth" to PackageManager.FEATURE_BLUETOOTH,
            "android.hardware.bluetooth_le" to PackageManager.FEATURE_BLUETOOTH_LE,
            "android.hardware.location" to PackageManager.FEATURE_LOCATION,
            "android.hardware.location.gps" to PackageManager.FEATURE_LOCATION_GPS,
            "android.hardware.usb.host" to PackageManager.FEATURE_USB_HOST,
            "android.hardware.wifi" to PackageManager.FEATURE_WIFI,
            "android.hardware.touchscreen" to PackageManager.FEATURE_TOUCHSCREEN
        ).forEach { (nume, cheie) ->
            cap.put(nume, try { c.packageManager.hasSystemFeature(cheie) } catch (e: Exception) { false })
        }
        o.put("capabilitati", cap)

        /* Permisiunile noastre. Pe Android 12+, lipsa lui BLUETOOTH_CONNECT
           face lista de imperecheate sa vina goala, fara nicio eroare. */
        val perm = JSONObject()
        listOf(
            "BLUETOOTH", "BLUETOOTH_ADMIN", "BLUETOOTH_SCAN", "BLUETOOTH_CONNECT",
            "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "INTERNET", "POST_NOTIFICATIONS"
        ).forEach { n ->
            perm.put(n, try {
                c.packageManager.checkPermission("android.permission.$n", c.packageName) ==
                        PackageManager.PERMISSION_GRANTED
            } catch (e: Exception) { false })
        }
        o.put("permisiuni", perm)

        /* Setarile de sistem care ne privesc, inclusiv modul dezvoltator. */
        val set = JSONObject()
        fun glob(nume: String): String = try {
            Settings.Global.getString(c.contentResolver, nume) ?: "—"
        } catch (e: Exception) { "nu se poate citi" }
        set.put("bluetooth_on", glob("bluetooth_on"))
        set.put("development_settings_enabled", glob("development_settings_enabled"))
        set.put("adb_enabled", glob("adb_enabled"))
        set.put("airplane_mode_on", glob("airplane_mode_on"))
        set.put("wifi_on", glob("wifi_on"))
        o.put("setariSistem", set)

        /* WebView-ul: versiunea lui spune daca Web Bluetooth ar avea sanse.
           Pe unitate scria "Web Bluetooth is not supported on this platform" —
           asta nu tine de versiune, WebView-ul n-are deloc, dar versiunea
           ramane utila pentru orice altceva se poarta ciudat in pagina. */
        val wv = JSONArray()
        listOf("com.google.android.webview", "com.android.webview",
               "com.android.chrome", "org.chromium.webview_shell").forEach { pachet ->
            try {
                val pi: PackageInfo = c.packageManager.getPackageInfo(pachet, 0)
                wv.put(JSONObject().put("pachet", pachet).put("versiune", pi.versionName ?: "?"))
            } catch (e: Exception) { }
        }
        o.put("webview", wv)

        /* Aplicatii care s-ar putea bate pe adaptor. Adaptorul accepta o
           singura legatura: daca o tine alta aplicatie, a noastra nu intra, si
           nimic din jurnalul de conectare n-ar spune de ce. */
        val obd = JSONArray()
        try {
            val cuvinte = listOf("obd", "torque", "carscanner", "car_scanner",
                                 "elm", "scanmaster", "vgate", "piston")
            c.packageManager.getInstalledPackages(0).forEach { pi ->
                val n = pi.packageName.lowercase()
                if (cuvinte.any { n.contains(it) }) obd.put(pi.packageName)
            }
        } catch (e: Exception) {
            obd.put("lista de aplicații nu s-a putut citi: " + (e.message ?: ""))
        }
        o.put("aplicatiiOBD", obd)

        return o
    }

    /**
     * Radiografia. Se face la cerere, nu la pornire: lista de servicii a unui
     * dispozitiv se poate schimba dupa imperechere, si vrem starea de acum.
     */
    fun raport(c: Context, punteActiva: String): String {
        val j = JSONObject()
        j.put("cand", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        j.put("punteActiva", punteActiva)
        j.put("android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")")
        j.put("aparat", Build.MANUFACTURER + " " + Build.MODEL)

        j.put("sistem", radiografiaSistemului(c))

        val a: BluetoothAdapter? =
            (c.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (a == null) {
            j.put("adaptor", "lipsește cu totul")
        } else {
            val ad = JSONObject()
            ad.put("pornit", try { a.isEnabled } catch (e: Exception) { false })
            ad.put("nume", try { a.name ?: "" } catch (e: Exception) { "" })
            ad.put("adresa", try { a.address ?: "" } catch (e: Exception) { "" })
            ad.put("stare", try {
                when (a.state) {
                    BluetoothAdapter.STATE_ON -> "pornit"
                    BluetoothAdapter.STATE_OFF -> "oprit"
                    BluetoothAdapter.STATE_TURNING_ON -> "pornește"
                    BluetoothAdapter.STATE_TURNING_OFF -> "se oprește"
                    else -> "necunoscut"
                }
            } catch (e: Exception) { "necunoscut" })
            ad.put("scannerLe", try { a.bluetoothLeScanner != null } catch (e: Exception) { false })
            /* Capabilitatile radio, de la Android 5 incolo. Un aparat care
               raspunde "false" la toate, dar declara bluetooth_le, e chiar
               tiparul unei unitati care minte despre Low Energy. */
            if (Build.VERSION.SDK_INT >= 21) {
                ad.put("multipleAdvertisement",
                    try { a.isMultipleAdvertisementSupported } catch (e: Exception) { false })
                ad.put("offloadedFiltering",
                    try { a.isOffloadedFilteringSupported } catch (e: Exception) { false })
                ad.put("offloadedScanBatching",
                    try { a.isOffloadedScanBatchingSupported } catch (e: Exception) { false })
            }
            if (Build.VERSION.SDK_INT >= 26) {
                ad.put("le2MPhy", try { a.isLe2MPhySupported } catch (e: Exception) { false })
                ad.put("leCodedPhy", try { a.isLeCodedPhySupported } catch (e: Exception) { false })
                ad.put("leExtendedAdvertising",
                    try { a.isLeExtendedAdvertisingSupported } catch (e: Exception) { false })
            }
            /* Ce profiluri sunt ocupate chiar acum. Daca A2DP sau HEADSET sunt
               conectate, radioul e disputat — nu impiedica, dar explica
               incetinelile. */
            val prof = JSONObject()
            listOf("A2DP" to 2, "HEADSET" to 1, "GATT" to 7, "GATT_SERVER" to 8).forEach { (n, cod) ->
                prof.put(n, try {
                    when (a.getProfileConnectionState(cod)) {
                        0 -> "deconectat"; 1 -> "se conectează"
                        2 -> "CONECTAT"; 3 -> "se deconectează"; else -> "?"
                    }
                } catch (e: Exception) { "?" })
            }
            ad.put("profiluri", prof)
            j.put("adaptor", ad)

            val lista = JSONArray()
            try {
                a.bondedDevices?.forEach { d ->
                    val o = JSONObject()
                    o.put("nume", d.name ?: "")
                    o.put("adresa", d.address)
                    o.put("tip", tip(d))
                    o.put("legatura", legatura(d))
                    val u = JSONArray()
                    var areSpp = false
                    try {
                        d.uuids?.forEach {
                            val s = it.uuid.toString().lowercase()
                            u.put(s)
                            if (s.startsWith("00001101")) areSpp = true
                        }
                    } catch (e: Exception) { }
                    o.put("servicii", u)
                    o.put("areSerial", areSpp)
                    lista.put(o)
                }
            } catch (e: Exception) {
                j.put("eroareLista", e.message ?: "necunoscută")
            }
            j.put("imperecheate", lista)
        }

        val jr = JSONArray()
        copie().forEach { jr.put(it) }
        j.put("jurnal", jr)

        /* Concluzia, scrisa in cuvinte. Cine citeste fisierul peste o luna n-o
           mai poate reconstitui singur din patru campuri JSON. */
        val sfaturi = JSONArray()
        try {
            val d = a?.bondedDevices?.firstOrNull {
                (it.name ?: "").contains("OBD", true) || (it.name ?: "").contains("ELM", true) ||
                (it.name ?: "").contains("VLINK", true) || (it.name ?: "").contains("Vgate", true)
            }
            when {
                a == null -> sfaturi.put("Unitatea n-are adaptor Bluetooth deloc.")
                d == null -> sfaturi.put("Niciun dispozitiv împerecheat nu pare a fi un adaptor OBD. " +
                        "Împerechează-l din Setări → Bluetooth.")
                tip(d) == "numai LE" && punteActiva == "clasic" ->
                    sfaturi.put("Adaptorul „${d.name}” e numai Low Energy. Aplicația merge doar pe " +
                            "Bluetooth clasic, deci nu-l poate folosi. Ia un adaptor clasic — " +
                            "vLinker BM-Android e cel probat pe mașina asta.")
                tip(d) == "clasic" && punteActiva == "ble" ->
                    sfaturi.put("Adaptorul „${d.name}” e clasic, dar puntea e pe Low Energy. " +
                            "Treci pe Bluetooth clasic din Setări.")
                !d.uuids.orEmpty().any { it.uuid.toString().lowercase().startsWith("00001101") } &&
                        punteActiva == "clasic" ->
                    sfaturi.put("Adaptorul „${d.name}” nu anunță serviciul serial 1101. " +
                            "Nu e neapărat un semn rău: lista de servicii se umple abia după o " +
                            "împerechere reușită. Încearcă direct conectarea; dacă pică după zece " +
                            "secunde, verifică să fie codul PIN al unității 1234.")
                else -> sfaturi.put("Împerecherea și tipul par în regulă; citește jurnalul de mai jos.")
            }
        } catch (e: Exception) { }
        j.put("cePareSaFie", sfaturi)

        return j.toString(2)
    }
}
