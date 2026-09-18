package ro.allview.cityzen

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Bluetooth CLASIC, pentru unitatile din bord care n-au Low Energy.
 *
 * Unitatea din Allview Cityzen n-are BLE, iar PunteBle vorbeste numai BLE, prin
 * GATT. Un adaptor ELM327 clasic nu se leaga de ea nici daca il cumperi. De
 * aceea exista fisierul asta.
 *
 * PARTEA IMPORTANTA: pagina nu afla nimic. punte.js ii da un navigator.bluetooth
 * cu servicii si caracteristici, fiindca asa arata Web Bluetooth. Bluetooth-ul
 * clasic n-are asa ceva — are un singur tub de octeti, ca un cablu serial. Deci
 * puntea asta INVENTEAZA un serviciu si o caracteristica, cu aceleasi UUID-uri
 * pe care le cunoaste deja pagina, si le leaga la tub. index.html ramane
 * neschimbat, si la fel harta, ecranele si toate formulele verificate in masina.
 *
 * Trei lucruri pe care le face altfel decat varianta BLE:
 *
 *  - NU SCANEAZA. La Bluetooth clasic, adaptorul trebuie imperecheat o data din
 *    setarile Androidului, cu codul 1234 sau 0000. Dupa aceea apare in lista.
 *    O scanare clasica dureaza doisprezece secunde si cere permisiuni in plus,
 *    fara niciun castig: adaptorul e oricum imperecheat.
 *
 *  - CITESTE PE FIRUL LUI. Socketul e blocant: citirea asteapta pana vin octeti.
 *    De aceea are fir propriu, iar ce citeste trimite in pagina exact ca o
 *    notificare BLE, ca sa nu se schimbe nimic mai sus.
 *
 *  - NU SE UITA LA LISTA DE SERVICII. Unitatea nu ruleaza stiva Bluetooth
 *    obisnuita: cand persist.sys.bt.custom.stack e adevarat, apelurile pleaca
 *    spre o stiva proprie a producatorului. Aceea nu raspunde la interogarea de
 *    servicii, deci getUuids() intoarce gol MEREU, si pe un adaptor bun, si pe
 *    unul stricat. O verificare pe lista aia ar refuza exact aparatele care
 *    merg. Se incearca direct deschiderea tubului, si se vede ce iese.
 *
 *  - SCRIE DIRECT, FARA COADA. La BLE, o scriere trebuie sa astepte confirmarea
 *    celei dinainte, altfel stiva le amesteca. Pe un socket serial nu exista
 *    problema asta: octetii intra in ordine. Coada, MTU-ul si ritmul rapid din
 *    PunteBle dispar aici, si odata cu ele jumatate din ce se putea strica.
 */
@SuppressLint("MissingPermission")
class PunteSpp(
    private val activitate: AppCompatActivity,
    private val web: WebView
) {

    companion object {
        private const val TAG = "PunteSpp"
        private const val PREF = "cityzen"
        private const val CHEIE_ULTIM = "ultimAdaptorSpp"

        /** Serial Port Profile — tubul de octeti al oricarui ELM327 clasic. */
        private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /* Serviciul si caracteristica inventate. UUID-urile sunt cele pe care
           pagina le cauta oricum in lista ei, deci nu trebuie schimbat nimic
           in HTML. */
        private const val SERVICIU = "0000fff0-0000-1000-8000-00805f9b34fb"
        private const val CARACTERISTICA = "0000fff1-0000-1000-8000-00805f9b34fb"
    }

    private val principal = android.os.Handler(android.os.Looper.getMainLooper())
    private var socket: BluetoothSocket? = null
    private var iesire: OutputStream? = null
    private var firCitire: Thread? = null
    private var dispozitiv: BluetoothDevice? = null
    @Volatile private var inchis = false

    private fun adaptor(): BluetoothAdapter? =
        (activitate.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun ok(id: Int, arg: String?) = ruleaza("window.__punte.ok($id, ${arg ?: "null"})")
    private fun eroare(id: Int, mesaj: String) =
        ruleaza("window.__punte.eroare($id, ${JSONObject.quote(mesaj)})")
    private fun ruleaza(js: String) { web.post { web.evaluateJavascript(js, null) } }

    // ------------------------------------------------- ce cheama pagina la noi

    /**
     * Lista de ales. La clasic nu se scaneaza: se arata ce e imperecheat.
     * Daca nu e nimic imperecheat, mesajul spune exact ce are de facut omul —
     * altfel se uita la o lista goala si crede ca s-a stricat aplicatia.
     */
    @JavascriptInterface
    fun cere_dispozitiv(id: Int) {
        principal.post {
            val a = adaptor()
            if (a == null) { eroare(id, "Bluetooth indisponibil pe unitatea asta"); return@post }
            if (!a.isEnabled) { eroare(id, "Bluetooth-ul e oprit"); return@post }

            val imperecheate = try { a.bondedDevices?.toList() ?: emptyList() } catch (e: Exception) { emptyList() }
            JurnalBt.scrie("listă cerută · ${imperecheate.size} dispozitive împerecheate: " +
                imperecheate.joinToString(", ") { (it.name ?: "fără nume") + "/" + it.address })

            /* Cautarea. Pe unitatea din Cityzen ecranul de Bluetooth din Setari
               nu imperecheaza nimic: cand apesi pe un aparat trimite AT#CC,
               adica "leaga handsfree si muzica". Un adaptor OBD n-are niciunul,
               cautarea de servicii pica cu SDC_SEARCH_FAILED, legatura se rupe,
               si lista de imperecheri ramane goala. Deci nu ne mai putem baza pe
               ea — cautam noi aparatele din aer si ne legam direct la adresa.
               Legatura la un aparat neimperecheat merge: in jurnalul unitatii se
               vede cum se deschide fara probleme (dm_acl_hci_connect_success). */
            cautaSiAlege(id, a, imperecheate)
        }
    }

    /** Cauta 12 secunde, aduna ce gaseste peste cele imperecheate, apoi intreaba. */
    private fun cautaSiAlege(id: Int, a: BluetoothAdapter, imperecheate: List<BluetoothDevice>) {
        val gasite = LinkedHashMap<String, BluetoothDevice>()
        imperecheate.forEach { gasite[it.address] = it }

        val dialogCautare = AlertDialog.Builder(activitate)
            .setTitle("Caut adaptoare")
            .setMessage("Caut în jur… 12 secunde.\nAdaptorul trebuie să fie alimentat și neconectat la telefon.")
            .setCancelable(false)
            .create()
        dialogCautare.show()

        var ascultator: BroadcastReceiver? = null
        var gata = false

        val incheie = Runnable {
            if (gata) return@Runnable
            gata = true
            try { a.cancelDiscovery() } catch (e: Exception) { }
            try { ascultator?.let { activitate.unregisterReceiver(it) } } catch (e: Exception) { }
            try { dialogCautare.dismiss() } catch (e: Exception) { }

            val lista = gasite.values.toList()
            JurnalBt.scrie("căutare terminată · ${lista.size} aparate: " +
                lista.joinToString(", ") { (it.name ?: "fără nume") + "/" + it.address })

            if (lista.isEmpty()) {
                eroare(id, "n-am găsit niciun adaptor. Verifică dacă e alimentat, " +
                        "dacă nu e legat la telefon, și încearcă din nou.")
                return@Runnable
            }

            val nume = lista.map { d ->
                val marcaj = if (d.bondState == BluetoothDevice.BOND_BONDED) " · împerecheat" else ""
                (d.name ?: "fără nume") + marcaj + "\n" + d.address
            }.toTypedArray()

            AlertDialog.Builder(activitate)
                .setTitle("Alege adaptorul OBD")
                .setItems(nume) { _, i ->
                    val d = lista[i]
                    dispozitiv = d
                    ok(id, JSONObject()
                        .put("nume", d.name ?: JSONObject.NULL)
                        .put("id", d.address).toString())
                }
                .setNegativeButton("Renunț") { _, _ -> eroare(id, "nu s-a ales niciun adaptor") }
                .setOnCancelListener { eroare(id, "nu s-a ales niciun adaptor") }
                .show()
        }

        ascultator = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val d = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        if (d != null && !gasite.containsKey(d.address)) {
                            gasite[d.address] = d
                            JurnalBt.scrie("  găsit: " + (d.name ?: "fără nume") + " / " + d.address)
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> principal.post(incheie)
                }
            }
        }

        val filtru = IntentFilter()
        filtru.addAction(BluetoothDevice.ACTION_FOUND)
        filtru.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        try { activitate.registerReceiver(ascultator, filtru) } catch (e: Exception) { }

        try { a.cancelDiscovery() } catch (e: Exception) { }
        val pornit = try { a.startDiscovery() } catch (e: Exception) { false }
        JurnalBt.scrie("căutare pornită: $pornit")

        /* Plasa de siguranta: daca unitatea nu trimite niciodata
           ACTION_DISCOVERY_FINISHED — se intampla pe stivele proprii — incheiem
           noi dupa 12 secunde, cu ce am adunat pana atunci. */
        principal.postDelayed(incheie, 12000)
    }

    @JavascriptInterface
    fun dispozitive_cunoscute(id: Int) {
        principal.post {
            val a = adaptor()
            if (a == null) { eroare(id, "Bluetooth indisponibil"); return@post }
            val lista = JSONArray()
            try {
                a.bondedDevices?.forEach {
                    lista.put(JSONObject()
                        .put("nume", it.name ?: JSONObject.NULL)
                        .put("id", it.address))
                }
            } catch (e: Exception) { }
            ok(id, lista.toString())
        }
    }

    @JavascriptInterface
    fun conecteaza(id: Int, adresa: String) {
        Thread {
            try {
                val a = adaptor() ?: throw Exception("Bluetooth indisponibil")
                val d = dispozitiv?.takeIf { it.address.equals(adresa, true) }
                    ?: a.getRemoteDevice(adresa.uppercase())
                dispozitiv = d
                JurnalBt.scrie("conectare cerută către ${d.name ?: "fără nume"} / ${d.address}")
                JurnalBt.scrie("  tip radio: ${
                    when (d.type) { 1 -> "clasic"; 2 -> "numai LE"; 3 -> "clasic și LE"; else -> "necunoscut" }
                } · împerecheat: ${d.bondState == android.bluetooth.BluetoothDevice.BOND_BONDED}")
                /* Lista de servicii se scrie doar in jurnal, ca sa se vada ce
                   raspunde unitatea. NU se ia nicio decizie pe ea: pe unitatile
                   cu stiva proprie e goala mereu, desi legatura se poate face. */
                try {
                    val u = d.uuids?.joinToString(", ") { it.uuid.toString() } ?: "niciunul anunțat"
                    JurnalBt.scrie("  servicii anunțate: $u  (informativ, nu blochează)")
                } catch (e: Exception) { JurnalBt.scrie("  serviciile nu s-au putut citi: ${e.message}") }
                inchideTacut()
                try { a.cancelDiscovery() } catch (e: Exception) { }

                val s = conecteazaSocket(d)

                socket = s
                iesire = s.outputStream
                inchis = false
                activitate.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putString(CHEIE_ULTIM, d.address).apply()
                porneisteCitirea(s.inputStream)
                JurnalBt.scrie("LEGAT la ${d.name} / ${d.address}")
                ok(id, null)
            } catch (e: Exception) {
                JurnalBt.scrie("CONECTARE EȘUATĂ: ${e.javaClass.simpleName}: ${e.message}")
                inchideTacut()
                eroare(id, "nu m-am putut lega: " + (e.message ?: "motiv necunoscut"))
            }
        }.start()
    }

    /**
     * Serviciul si caracteristica inventate. Pagina crede ca a descoperit un
     * aparat BLE obisnuit, cu o caracteristica pe care se poate scrie si care
     * notifica — exact ce ii trebuie ca sa lucreze cu ELM327.
     */
    @JavascriptInterface
    fun descopera_servicii(id: Int) {
        principal.post {
            if (socket == null) { eroare(id, "nu exista legatura"); return@post }
            val c = JSONObject()
                .put("uuid", CARACTERISTICA)
                .put("read", false).put("write", true)
                .put("writeNoResp", true).put("notify", true).put("indicate", false)
            val srv = JSONObject()
                .put("uuid", SERVICIU)
                .put("caracteristici", JSONArray().put(c))
            ok(id, JSONArray().put(srv).toString())
        }
    }

    /** Firul de citire merge oricum; aici doar confirmam. */
    @JavascriptInterface
    fun porneste_notificari(id: Int, uuid: String) {
        principal.post {
            if (socket == null) eroare(id, "nu exista legatura") else ok(id, null)
        }
    }

    @JavascriptInterface
    fun opreste_notificari(id: Int, uuid: String) { principal.post { ok(id, null) } }

    /**
     * Scrierea. Fara coada si fara asteptare: pe un tub serial octetii intra in
     * ordine, iar adaptorul raspunde cand e gata. Toata complicatia din varianta
     * BLE — coada, confirmarile, reincercarile la "ocupat" — nu-si are rostul.
     */
    @JavascriptInterface
    fun scrie(id: Int, uuid: String, base64: String, cuRaspuns: Boolean) {
        Thread {
            val o = iesire
            if (o == null) { eroare(id, "legatura e inchisa"); return@Thread }
            try {
                val date = Base64.decode(base64, Base64.NO_WRAP)
                o.write(date)
                o.flush()
                ok(id, null)
            } catch (e: Exception) {
                Log.w(TAG, "scriere esuata: ${e.message}")
                pierdut()
                eroare(id, "scriere eșuată: " + (e.message ?: ""))
            }
        }.start()
    }

    @JavascriptInterface
    fun deconecteaza() { Thread { inchideTacut() }.start() }

    @JavascriptInterface
    fun este_conectat(): Boolean = socket?.isConnected == true

    /** La BLE cerea ritm rapid. Aici n-are ce cere: socketul n-are ritm. */
    @JavascriptInterface
    fun revigoreaza() { }

    /** Ce fel de punte e, ca sa se vada in pagina. */
    @JavascriptInterface
    fun fel(): String = "clasic"

    /** Radiografia aparatului plus jurnalul, pentru descărcat din Setări. */
    @JavascriptInterface
    fun raport(): String = JurnalBt.raport(activitate, "clasic")

    @JavascriptInterface
    fun goleste_jurnal() = JurnalBt.goleste()

    /**
     * Alegerea, fortata de om. Recunoasterea automata se inseala uneori: sunt
     * unitati care declara BLE si au si scanner, dar pe care BLE-ul nu merge de
     * fapt. Fara butonul asta, omul ramane blocat si n-are nicio cale sa iasa
     * fara sa recompileze aplicatia.
     */
    @JavascriptInterface
    fun alege_clasic(da: Boolean) {
        activitate.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean("fortatClasic", da).apply()
    }

    // ------------------------------------------------------------ dedesubt

    /**
     * Deschiderea tubului. Trei incercari, toate pe acelasi UUID.
     *
     * De ce numai pe UUID: unitatea din Cityzen nu ruleaza stiva Bluetooth
     * obisnuita a Androidului. Cand persist.sys.bt.custom.stack e adevarat,
     * libbluetooth_jni.so comuta pe o stiva proprie ("blink"), iar aceea
     * alege firul de tratare DUPA UUID-ul cerut, dintr-o lista inchisa de
     * patru: 1101 (SPP), fcfb (CarLink), fe35 si fe36 (HiCar). Orice altceva
     * cade mut, in ramura "cur uuid unknow, so skip process".
     *
     * De aceea a disparut rezerva pe canalul 1: un socket deschis pe canal nu
     * poarta niciun UUID, deci pe unitatea asta nu are cum sa fie rutat. Pe
     * telefoane obisnuite mergea; aici doar ascundea motivul adevarat al
     * esecului si manca timp.
     *
     * A treia incercare exista fiindca stiva face interogarea de servicii abia
     * la prima conectare. Daca adaptorul nu apucase sa raspunda, o a doua
     * cerere dupa o pauza scurta prinde canalul deja aflat.
     */
    private fun conecteazaSocket(d: BluetoothDevice): BluetoothSocket {
        var ultima: Exception? = null

        val incercari = listOf<Pair<String, () -> BluetoothSocket>>(
            "securizat pe 1101" to { d.createRfcommSocketToServiceRecord(SPP) },
            "nesecurizat pe 1101" to { d.createInsecureRfcommSocketToServiceRecord(SPP) },
            "securizat pe 1101, a doua oară" to { d.createRfcommSocketToServiceRecord(SPP) }
        )

        for ((i, incercare) in incercari.withIndex()) {
            val (nume, fabrica) = incercare
            if (i == 2) {
                JurnalBt.scrie("  pauză de o secundă și jumătate, apoi mai încerc o dată")
                try { Thread.sleep(1500) } catch (e: InterruptedException) { }
            }
            JurnalBt.scrie("  încerc $nume")
            var s: BluetoothSocket? = null
            try {
                s = fabrica()
                s.connect()
                JurnalBt.scrie("  a mers: $nume")
                return s
            } catch (e: Exception) {
                ultima = e
                JurnalBt.scrie("  $nume a eșuat: ${e.javaClass.simpleName}: ${e.message}")
                /* Socketul picat trebuie inchis, altfel ramane un canal pe
                   jumatate deschis si incercarea urmatoare pica si ea. */
                try { s?.close() } catch (e2: Exception) { }
            }
        }

        throw ultima ?: Exception("nu s-a putut deschide niciun socket")
    }

    private fun porneisteCitirea(intrare: InputStream) {
        firCitire?.interrupt()
        firCitire = Thread {
            val tampon = ByteArray(1024)
            try {
                while (!inchis) {
                    val n = intrare.read(tampon)
                    if (n < 0) break
                    if (n == 0) continue
                    val b64 = Base64.encodeToString(tampon.copyOf(n), Base64.NO_WRAP)
                    /* Se trimite ca notificare BLE, pe caracteristica inventata:
                       pagina nu are de unde sti ca dedesubt e alt fel de radio. */
                    ruleaza("window.__punte.notificare(" +
                            "${JSONObject.quote(CARACTERISTICA)}, ${JSONObject.quote(b64)})")
                }
            } catch (e: Exception) {
                if (!inchis) Log.w(TAG, "citirea s-a oprit: ${e.message}")
            }
            if (!inchis) pierdut()
        }
        firCitire?.isDaemon = true
        firCitire?.start()
    }

    /** Legatura a picat singura: se anunta pagina, ca sa nu mai scrie in gol. */
    private fun pierdut() {
        if (inchis) return
        inchis = true
        try { socket?.close() } catch (e: Exception) { }
        socket = null; iesire = null
        ruleaza("window.__punte.deconectat()")
    }

    private fun inchideTacut() {
        inchis = true
        try { firCitire?.interrupt() } catch (e: Exception) { }
        try { socket?.close() } catch (e: Exception) { }
        socket = null; iesire = null; firCitire = null
    }
}
