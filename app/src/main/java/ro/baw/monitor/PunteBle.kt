package ro.baw.monitor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.UUID

/**
 * Bluetooth Low Energy nativ, pus la dispozitia paginii HTML.
 *
 * Pagina nu stie ca s-a schimbat ceva: punte.js ii da un "navigator.bluetooth"
 * care ajunge aici. Toata logica de interogare, formulele si ecranele rămân in
 * HTML, unde au fost deja verificate in masina.
 *
 * Reguli respectate din experienta de pe HTML:
 *  - o singura scriere pe rand, in coada, ca sa nu se amestece raspunsurile
 *  - MTU cerut la 247, altfel raspunsul cu 120 de celule vine in prea multe bucati
 *  - deconectarea se anunta imediat in pagina, ca sa nu scrie in gol
 */
@SuppressLint("MissingPermission")
class PunteBle(
    private val activitate: AppCompatActivity,
    private val web: WebView
) {

    companion object {
        private const val TAG = "PunteBle"
        private const val DURATA_SCANARE_MS = 7000L
        /** sub pragul de 1600 ms al paginii, ca sa apuce coada sa mearga mai departe */
        private const val RABDARE_SCRIERE_MS = 1200L
        /** cat asteptam cand stiva zice "ocupat", si de cate ori reincercam */
        private const val PAUZA_OCUPAT_MS = 25L
        private const val REINCERCARI_OCUPAT = 24   // 24 x 25ms = 600ms, sub pragul de 1600ms al paginii
        /** dupa ce plasa de siguranta intra, lasam stiva sa se goleasca */
        private const val RACIRE_MS = 120L
        private const val PREF = "baw"
        private const val CHEIE_ULTIM = "ultimAdaptor"
        private const val CHEIE_MERS = "ultimAdaptorAMers"
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val principal = Handler(Looper.getMainLooper())

    private var dispozitiv: BluetoothDevice? = null
    private var gatt: BluetoothGatt? = null

    /** uuid mic-scris -> caracteristica, ca sa gasim rapid ce cere pagina */
    private val dupaUuid = HashMap<String, BluetoothGattCharacteristic>()

    private var idConectare = 0
    private var incercare = 0
    private var idDescoperire = 0
    private var idMtu = 0

    /** o scriere pe rand */
    private class Scriere(val id: Int, val c: BluetoothGattCharacteristic,
                          val date: ByteArray, val cuRaspuns: Boolean) {
        /** creste la fiecare incercare, ca o plasa veche sa nu taie una noua */
        var incercari = 0
    }

    private val coada = ArrayDeque<Scriere>()
    private var scriereInCurs: Scriere? = null

    /** ce se schimba cu descriptorul de notificare, pe rand */
    private val cozaNotificari = ArrayDeque<Pair<Int, BluetoothGattCharacteristic>>()
    private var notificareInCurs: Pair<Int, BluetoothGattCharacteristic>? = null

    // ---------------------------------------------------------------- catre JS

    private fun ok(id: Int, jsonSauNull: String?) {
        val arg = if (jsonSauNull == null) "null" else JSONObject.quote(jsonSauNull)
        ruleaza("window.__punte.ok($id, $arg)")
    }

    private fun eroare(id: Int, mesaj: String) {
        ruleaza("window.__punte.eroare($id, ${JSONObject.quote(mesaj)})")
    }

    private fun ruleaza(js: String) {
        web.post { web.evaluateJavascript(js, null) }
    }

    // ------------------------------------------------- ce apeleaza pagina la noi

    @JavascriptInterface
    fun cere_dispozitiv(id: Int) {
        principal.post { scaneaza(id) }
    }

    @JavascriptInterface
    fun conecteaza(id: Int, adresa: String) {
        principal.post {
            // adresa poate veni de la un adaptor scos din dispozitive_cunoscute,
            // pe care nu l-a ales nimeni din lista in sesiunea asta
            val d = daMiDispozitivul(adresa)
            if (d == null) { eroare(id, "nu s-a ales niciun adaptor"); return@post }
            dispozitiv = d
            idConectare = id
            incercare = 1
            dupaUuid.clear()
            coada.clear(); scriereInCurs = null
            cozaNotificari.clear(); notificareInCurs = null
            leaga(d, false)
        }
    }

    /**
     * Cere ritm rapid pe legatura.
     *
     * Lipsea, si costa mult. Android tine implicit un ritm de economie, la
     * vreo 30-50 ms intre schimburi, si il poate rari si mai mult cand radioul
     * e disputat de Wi-Fi si de celular — adica exact in mers. Cu un raspuns
     * lung, taiat in mai multe cadre, un dus-intors ajunge sa treaca de pragul
     * de asteptare al paginii. Apoi telefonul revine la ritm bun si totul merge
     * iar. De aici tiparul: merge douazeci de secunde, tace treizeci, revine.
     *
     * Ritmul rapid coboara intervalul la vreo 11-15 ms. Consuma ceva mai mult,
     * ceea ce in masina nu conteaza — telefonul e in incarcator.
     */
    private fun ritmRapid() {
        val g = gatt ?: return
        val reusit = try {
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        } catch (e: Exception) { false }
        Log.i(TAG, "ritm rapid cerut: $reusit")
    }

    /** pagina o cheama cand banuieste ca legatura a incetinit */
    @JavascriptInterface
    fun revigoreaza() {
        principal.post { ritmRapid() }
    }

    /** adaptoarele pe care le stie telefonul, ca pagina sa se relege singura */
    @JavascriptInterface
    fun dispozitive_cunoscute(id: Int) {
        principal.post {
            val adaptor = (activitate.getSystemService(Context.BLUETOOTH_SERVICE)
                    as? BluetoothManager)?.adapter
            if (adaptor == null) { eroare(id, "Bluetooth indisponibil"); return@post }

            val lista = JSONArray()
            val vazute = HashSet<String>()
            val ultim = activitate.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(CHEIE_ULTIM, null)

            val adauga = { d: BluetoothDevice ->
                if (vazute.add(d.address)) {
                    lista.put(JSONObject()
                        .put("nume", d.name ?: JSONObject.NULL)
                        .put("id", d.address))
                }
            }

            try { adaptor.bondedDevices?.forEach(adauga) } catch (e: Exception) { }
            if (ultim != null && !vazute.contains(ultim)) {
                try { adauga(adaptor.getRemoteDevice(ultim)) } catch (e: Exception) { }
            }
            Log.i(TAG, "adaptoare cunoscute: $lista")
            ok(id, lista.toString())
        }
    }

    private fun daMiDispozitivul(adresa: String): BluetoothDevice? {
        if (adresa.isBlank()) return dispozitiv
        dispozitiv?.let { if (it.address.equals(adresa, true)) return it }
        val adaptor = (activitate.getSystemService(Context.BLUETOOTH_SERVICE)
                as? BluetoothManager)?.adapter ?: return dispozitiv
        return try { adaptor.getRemoteDevice(adresa.uppercase()) }
               catch (e: Exception) { Log.w(TAG, "adresa nevalida: $adresa"); dispozitiv }
    }

    @JavascriptInterface
    fun descopera_servicii(id: Int) {
        principal.post {
            val g = gatt
            if (g == null) { eroare(id, "nu exista legatura"); return@post }
            val gata = g.services
            if (gata != null && gata.isNotEmpty()) { raportServicii(id); return@post }
            idDescoperire = id
            if (!g.discoverServices()) eroare(id, "descoperirea serviciilor a esuat")
        }
    }

    @JavascriptInterface
    fun porneste_notificari(id: Int, uuid: String) {
        principal.post {
            val c = dupaUuid[uuid.lowercase()]
            if (c == null) { eroare(id, "caracteristica $uuid lipseste"); return@post }
            cozaNotificari.add(id to c)
            urmatoareaNotificare()
        }
    }

    @JavascriptInterface
    fun opreste_notificari(id: Int, uuid: String) {
        principal.post {
            val c = dupaUuid[uuid.lowercase()]
            val g = gatt
            if (c != null && g != null) g.setCharacteristicNotification(c, false)
            ok(id, null)
        }
    }

    @JavascriptInterface
    fun scrie(id: Int, uuid: String, base64: String, cuRaspuns: Boolean) {
        principal.post {
            val c = dupaUuid[uuid.lowercase()]
            if (c == null) { eroare(id, "caracteristica $uuid lipseste"); return@post }
            if (gatt == null) { eroare(id, "legatura e inchisa"); return@post }
            val date = try {
                Base64.decode(base64, Base64.NO_WRAP)
            } catch (e: Exception) {
                eroare(id, "date nevalide"); return@post
            }
            coada.add(Scriere(id, c, date, cuRaspuns))
            urmatoareaScriere()
        }
    }

    @JavascriptInterface
    fun deconecteaza() {
        principal.post { inchide() }
    }

    @JavascriptInterface
    fun este_conectat(): Boolean = gatt != null

    /**
     * O incercare de legatura.
     *
     * "autoConnect" schimba felul in care lucreaza Android. Cu false cere
     * legatura imediat si renunta repede — bine pentru un adaptor care emite
     * reclame chiar acum. Cu true pune o cerere care asteapta pana adaptorul se
     * arata — singurul lucru care merge cu un adaptor imperecheat si adormit.
     * Incercam intai varianta rapida, si daca da 133 trecem la cea rabdatoare.
     */
    private fun leaga(d: BluetoothDevice, rabdator: Boolean) {
        gatt?.let { try { it.close() } catch (e: Exception) { } }
        Log.i(TAG, "conectare la ${d.address}, incercarea $incercare, autoConnect=$rabdator")
        gatt = d.connectGatt(activitate, rabdator, apel, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) {
            val id = idConectare; idConectare = 0
            if (id != 0) eroare(id, "connectGatt a returnat null")
        }
    }

    // ------------------------------------------------------------------ scanare

    private fun scaneaza(id: Int) {
        val mgr = activitate.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adaptor = mgr?.adapter
        if (adaptor == null || !adaptor.isEnabled) {
            eroare(id, "Bluetooth-ul e oprit"); return
        }
        val scanner = adaptor.bluetoothLeScanner
        if (scanner == null) { eroare(id, "scanner BLE indisponibil"); return }

        val gasite = LinkedHashMap<String, BluetoothDevice>()

        // Punctul in care varianta din browser reusea si aplicatia nu.
        // Un adaptor imperecheat in aplicatia OBDLink nu mai emite reclame
        // continuu, iar daca aplicatia aceea ii ține legatura nu emite deloc.
        // O lista facuta doar din scanare nu-l va cuprinde niciodata. Asa ca
        // pornim de la ce e deja imperecheat in telefon.
        val imperecheate = try { adaptor.bondedDevices ?: emptySet() } catch (e: Exception) { emptySet() }
        for (d in imperecheate) {
            gasite[d.address] = d
            Log.i(TAG, "imperecheat: ${d.name ?: "fara nume"} / ${d.address}")
        }

        val pref = activitate.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val ultim = pref.getString(CHEIE_ULTIM, null)
        // il luam automat numai daca ultima conectare cu el a chiar reusit;
        // altfel aplicatia ar reincerca tacut un dispozitiv greșit la infinit
        val ultimAMers = pref.getBoolean(CHEIE_MERS, false)
        var terminat = false

        val setari = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // declarat inainte de functia care il opreste: Kotlin nu accepta
        // referinte inainte spre variabile locale
        var apelScanare: ScanCallback? = null

        fun opreste(alesDirect: BluetoothDevice?) {
            if (terminat) return
            terminat = true
            apelScanare?.let { try { scanner.stopScan(it) } catch (e: Exception) { } }
            if (alesDirect != null) { alege(id, alesDirect); return }
            if (gasite.isEmpty()) {
                eroare(id, "niciun adaptor gasit si niciunul imperecheat. " +
                        "Verifica daca Bluetooth-ul e pornit si daca adaptorul e alimentat.")
                return
            }
            arataLista(id, gasite.values.toList())
        }

        apelScanare = object : ScanCallback() {
            override fun onScanResult(tip: Int, rez: ScanResult) {
                val d = rez.device ?: return
                if (!gasite.containsKey(d.address))
                    Log.i(TAG, "gasit: ${d.name ?: "fara nume"} / ${d.address}")
                gasite[d.address] = d
                if (ultim != null && ultimAMers && d.address == ultim) opreste(d)
            }
            override fun onScanFailed(cod: Int) {
                if (terminat) return
                terminat = true
                eroare(id, "scanarea a esuat, cod $cod")
            }
        }

        // adaptorul dovedit si deja imperecheat: mergem direct la el, fara
        // sa mai asteptam o reclama care poate nu vine niciodata
        val dovedit = if (ultim != null && ultimAMers) gasite[ultim] else null
        if (dovedit != null) {
            Log.i(TAG, "merg direct la ${dovedit.address}, a mers ultima data")
            alege(id, dovedit)
            return
        }

        scanner.startScan(null, setari, apelScanare!!)
        principal.postDelayed({ opreste(null) }, DURATA_SCANARE_MS)
    }

    private fun arataLista(id: Int, lista: List<BluetoothDevice>) {
        // adaptoarele cu nume primele; OBDLink chiar in varf
        val sortate = lista.sortedWith(compareBy(
            { !(it.name ?: "").contains("OBD", ignoreCase = true) },
            { it.name == null },
            { it.name ?: it.address }
        ))
        val etichete = sortate.map {
            val stare = if (it.bondState == BluetoothDevice.BOND_BONDED) " · împerecheat" else ""
            (it.name ?: "fara nume") + "\n" + it.address + stare
        }.toTypedArray()
        AlertDialog.Builder(activitate)
            .setTitle("Alege adaptorul OBD")
            .setItems(etichete) { _, i -> alege(id, sortate[i]) }
            .setOnCancelListener { eroare(id, "alegerea a fost anulata") }
            .show()
    }

    private fun alege(id: Int, d: BluetoothDevice) {
        dispozitiv = d
        // reusita se marcheaza abia la descoperirea serviciilor, nu acum
        activitate.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(CHEIE_ULTIM, d.address).putBoolean(CHEIE_MERS, false).apply()
        Log.i(TAG, "ales: ${d.name ?: "fara nume"} / ${d.address}, " +
                "imperecheat=${d.bondState == BluetoothDevice.BOND_BONDED}")
        val j = JSONObject()
            .put("nume", d.name ?: JSONObject.NULL)
            .put("id", d.address)
        ok(id, j.toString())
    }

    // --------------------------------------------------------------- apeluri BLE

    private val apel = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, stare: Int, nou: Int) {
            principal.post { starePeFirulPrincipal(stare, nou) }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, stare: Int) {
            principal.post {
                Log.i(TAG, "MTU = $mtu")
                ritmRapid()
                val id = idConectare; idConectare = 0
                if (id != 0) ok(id, null)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, stare: Int) {
            principal.post {
                val id = idDescoperire; idDescoperire = 0
                if (id == 0) return@post
                if (stare != BluetoothGatt.GATT_SUCCESS) {
                    eroare(id, "descoperirea a esuat, stare $stare"); return@post
                }
                raportServicii(id)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, stare: Int) {
            principal.post {
                val s = scriereInCurs ?: return@post
                scriereInCurs = null
                if (stare == BluetoothGatt.GATT_SUCCESS) ok(s.id, null)
                else eroare(s.id, "scriere respinsa, stare $stare")
                urmatoareaScriere()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, stare: Int) {
            principal.post {
                val n = notificareInCurs ?: return@post
                notificareInCurs = null
                if (stare == BluetoothGatt.GATT_SUCCESS) ok(n.first, null)
                else eroare(n.first, "activarea notificarilor a esuat, stare $stare")
                urmatoareaNotificare()
            }
        }

        // Folosim intentionat varianta veche: e apelata pe toate versiunile de
        // Android, de la 8 pana la 15. Cu ambele suprascrise, pe Android 13+
        // acelasi cadru ar ajunge de doua ori in pagina si ar corupe tamponul.
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val date = c.value ?: return
            val b64 = Base64.encodeToString(date, Base64.NO_WRAP)
            ruleaza("window.__punte.notificare(${JSONObject.quote(c.uuid.toString().lowercase())}, " +
                    "${JSONObject.quote(b64)})")
        }
    }

    /**
     * Starea legaturii, mutata pe firul principal.
     *
     * Android cheama toate raspunsurile astea pe un fir al lui, nu pe al nostru.
     * Coada de scrieri se atinge si din "scrie()", care vine din pagina, adica de
     * pe firul principal. Doua fire pe aceeasi coada inseamna ca, la o suprapunere,
     * "scriereInCurs" ramane nenul pentru totdeauna: comenzile se aseaza la rand
     * si nu mai pleaca niciuna, desi legatura GATT arata ridicata. Simptomul e o
     * muchie curata — merge, si apoi dintr-o data toate interogarile ies "fara
     * raspuns". Asa ca tot ce vine de la Android intra intai pe firul principal,
     * si coada are un singur stapan.
     */
    private fun starePeFirulPrincipal(stare: Int, nou: Int) {
        run {
            if (nou == BluetoothProfile.STATE_CONNECTED) {
                // MTU mare inainte de orice: raspunsul de 241 de octeti vine altfel
                // in 12 bucati de cate 20, si se pierd cadre pe drum
                idMtu = idConectare
                val g = gatt
                if (g == null || !g.requestMtu(247)) {
                    val id = idConectare; idConectare = 0
                    if (id != 0) ok(id, null)
                }
            } else if (nou == BluetoothProfile.STATE_DISCONNECTED) {
                val id = idConectare
                if (id == 0) {
                    // legatura buna s-a rupt in mers
                    ruleaza("window.__punte.deconectat()")
                    principal.post { inchide() }
                    return
                }

                val d = dispozitiv
                /*
                 * 133 inseamna de obicei "nu l-am gasit acum". A doua incercare,
                 * rabdatoare, prinde adaptoarele imperecheate care nu emiteau.
                 *
                 * 147, 8, 19 si 22 inseamna altceva: legatura a existat si a
                 * murit, sau telefonul a refuzat-o. Cu ecranul stins, pe multe
                 * telefoane — Honor si Huawei mai ales — asta se intampla la
                 * fiecare cateva minute, iar o incercare nerabdatoare cu
                 * autoConnect=false cade imediat, fiindca cere o legatura acum,
                 * pe loc. Cu autoConnect=true, cererea rasmane in controlerul
                 * Bluetooth si se prinde singura cand adaptorul se aude iar,
                 * chiar cu ecranul stins. Deci pentru astea reincercam
                 * rabdator, si nu uitam adaptorul: el e bun, doar telefonul
                 * doarme.
                 */
                if (stare == 133 && incercare == 1 && d != null) {
                    incercare = 2
                    principal.postDelayed({ leaga(d, true) }, 600)
                    return
                }
                if (stare in intArrayOf(147, 8, 19, 22, 62) && incercare < 6 && d != null) {
                    incercare++
                    val peste = 800L * incercare        // ne rarim, ca sa nu ardem bateria
                    Jurnal.scrie("legatura a picat (stare $stare), reiau rabdator " +
                            "in ${peste} ms, incercarea $incercare", null)
                    principal.postDelayed({ leaga(d, true) }, peste)
                    return
                }

                idConectare = 0
                // adaptorul asta nu merge: uitam ca a fost bun, ca la urmatoarea
                // apasare sa apara iar lista si sa poti alege altul
                activitate.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putBoolean(CHEIE_MERS, false).apply()
                eroare(id, if (stare == 133)
                    "nu m-am putut lega (133). Cel mai des inseamna ca alta " +
                    "aplicatie ține adaptorul ocupat — inchide aplicatia OBDLink " +
                    "de tot si incearca din nou."
                else "conectarea a esuat (stare $stare)")
                principal.post { inchide() }
            }
        }
    }

    private fun raportServicii(id: Int) {
        val g = gatt
        if (g == null) { eroare(id, "legatura s-a inchis"); return }
        dupaUuid.clear()
        val lista = JSONArray()
        for (srv in g.services) {
            val cs = JSONArray()
            for (c in srv.characteristics) {
                val p = c.properties
                val uuid = c.uuid.toString().lowercase()
                dupaUuid[uuid] = c
                cs.put(JSONObject()
                    .put("uuid", uuid)
                    .put("read", p and BluetoothGattCharacteristic.PROPERTY_READ != 0)
                    .put("write", p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
                    .put("writeNoResp", p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                    .put("notify", p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                    .put("indicate", p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0))
            }
            lista.put(JSONObject()
                .put("uuid", srv.uuid.toString().lowercase())
                .put("caracteristici", cs))
        }
        Log.i(TAG, "servicii: $lista")
        ritmRapid()
        // abia acum adaptorul e dovedit bun, deci merita luat automat data viitoare
        activitate.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(CHEIE_MERS, true).apply()
        ok(id, lista.toString())
    }

    private fun urmatoareaScriere() {
        if (scriereInCurs != null) return
        val s = coada.poll() ?: return
        val g = gatt
        if (g == null) { eroare(s.id, "legatura e inchisa"); urmatoareaScriere(); return }

        scriereInCurs = s
        s.incercari++
        val incercareaAsta = s.incercari

        // Plasa de siguranta: unele telefoane pierd din cand in cand confirmarea
        // unei scrieri, si fara asta coada s-ar opri definitiv acolo.
        // "incercareaAsta" conteaza: fara el, o plasa pusa la o incercare veche
        // ar taia o reincercare pornita intre timp.
        principal.postDelayed({
            if (scriereInCurs === s && s.incercari == incercareaAsta) {
                Log.w(TAG, "confirmarea scrierii nu a venit in $RABDARE_SCRIERE_MS ms")
                scriereInCurs = null
                eroare(s.id, "confirmarea scrierii nu a venit")
                // Racire inainte de urmatoarea. Scrierea asta poate fi inca in
                // stiva; daca trimitem imediat alta, stiva raspunde "ocupat" si
                // pornim o cascada de esecuri instantanee.
                principal.postDelayed({ urmatoareaScriere() }, RACIRE_MS)
            }
        }, RABDARE_SCRIERE_MS)

        val tip = if (s.cuRaspuns) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                  else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

        val pornit: Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(s.c, s.date, tip) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                s.c.writeType = tip
                s.c.value = s.date
                g.writeCharacteristic(s.c)
            }
        }

        if (pornit) return

        // "Ocupat" nu e o defectiune, e contra-presiune: tamponul stivei e plin
        // si cere o clipa de rabdare. Inainte renuntam pe loc, iar pagina vedea
        // comanda esuata in 3 ms — fara ca ea sa fi plecat vreodata pe radio.
        scriereInCurs = null
        if (s.incercari < REINCERCARI_OCUPAT) {
            Log.i(TAG, "stiva ocupata, reincerc (${s.incercari}/$REINCERCARI_OCUPAT)")
            coada.addFirst(s)     // isi pastreaza randul, nu trece dupa altele
            principal.postDelayed({ urmatoareaScriere() }, PAUZA_OCUPAT_MS)
        } else {
            Log.w(TAG, "stiva ocupata dupa $REINCERCARI_OCUPAT incercari, renunt")
            eroare(s.id, "stiva Bluetooth ocupata")
            principal.postDelayed({ urmatoareaScriere() }, RACIRE_MS)
        }
    }

    private fun urmatoareaNotificare() {
        if (notificareInCurs != null) return
        val n = cozaNotificari.poll() ?: return
        val g = gatt
        if (g == null) { eroare(n.first, "legatura e inchisa"); urmatoareaNotificare(); return }
        val c = n.second
        if (!g.setCharacteristicNotification(c, true)) {
            eroare(n.first, "nu pot activa notificarile"); urmatoareaNotificare(); return
        }
        val d = c.getDescriptor(CCCD)
        if (d == null) {
            // unele adaptoare nu expun CCCD; notificarile merg oricum
            ok(n.first, null); urmatoareaNotificare(); return
        }
        notificareInCurs = n
        principal.postDelayed({
            if (notificareInCurs === n) {
                Log.w(TAG, "confirmarea descriptorului nu a venit, merg mai departe")
                notificareInCurs = null
                ok(n.first, null)     // notificarile merg des si fara CCCD confirmat
                urmatoareaNotificare()
            }
        }, RABDARE_SCRIERE_MS)

        val val0 = if (c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        else
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

        val pornit: Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, val0) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run { d.value = val0; g.writeDescriptor(d) }
        }
        if (!pornit) {
            notificareInCurs = null
            eroare(n.first, "scrierea descriptorului a esuat")
            urmatoareaNotificare()
        }
    }

    fun inchide() {
        coada.clear(); scriereInCurs = null
        cozaNotificari.clear(); notificareInCurs = null
        dupaUuid.clear()
        gatt?.let {
            try { it.disconnect() } catch (e: Exception) { }
            try { it.close() } catch (e: Exception) { }
        }
        gatt = null
    }
}
