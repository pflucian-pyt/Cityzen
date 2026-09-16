package ro.allview.cityzen

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat

/**
 * Fereastra aplicatiei din bordul Allview Cityzen.
 *
 * Mult mai scurta decat cea din Avatr Monitor, si asta e intentionat. Acolo,
 * MainActivity mai citea din pagina vreo cincizeci de campuri ca sa le dea
 * ecranului de Android Auto, tinea alarme si trimitea pe server. Aici nu exista
 * Android Auto: aplicatia RULEAZA pe ecranul masinii, nu proiecteaza pe el.
 * Deci tot lantul acela pica, si cu el si dependenta de androidx.car.app —
 * partea cea mai fragila a compilarii.
 *
 * Ce a ramas sunt patru lucruri:
 *
 *  - WebView-ul, servit de pe o origine https falsa. Nu e cosmetic: Chromium
 *    refuza tacut geolocatia si alte lucruri pe file://, deci pagina ar parea
 *    ca merge si totusi jumatate din ea n-ar functiona.
 *  - PunteBle, care da paginii un navigator.bluetooth peste Bluetooth-ul
 *    nativ. WebView-ul Android nu are Web Bluetooth deloc.
 *  - Ceasul batut din Kotlin, pentru cand Android incetineste
 *    temporizatoarele din JavaScript.
 *  - Ecranul tinut aprins, fiindca un ecran stins inseamna citire pierduta.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val GAZDA = "appassets.androidplatform.net"
        private const val PORNIRE = "https://$GAZDA/assets/index.html"
        private const val CERERE_PERMISIUNI = 1
        private const val CERERE_GPS = 2

        /** Cat de demult a bifat pagina ca e vie. */
        private const val VERIFICA_BUCLA =
            "(function(){ return String(Date.now() - (window.__ultimaBataie||0)); })()"

        /** Bate ceasurile pe care pagina le-a lasat in window.AV.ceasuri. */
        private const val BATE_CEASURI = """
            (function(){
              window.__ultimaBataie = Date.now();
              try { window.__tic && window.__tic(); } catch(e) {}
              var c = (window.AV && window.AV.ceasuri) || [];
              for (var i = 0; i < c.length; i++) { try { c[i](); } catch(e) {} }
              return "ok";
            })()
        """
    }

    private lateinit var web: WebView
    private var punteBle: PunteBle? = null
    private var punteSpp: PunteSpp? = null
    private var punteFisiere: PunteFisiere? = null

    /*
     * Fereastra de sistem „unde salvez”. Se inregistreaza inainte de onCreate,
     * asa cere Android; altfel arunca la prima folosire. De aici se poate alege
     * si stickul USB, ceea ce e chiar rostul ei: pe unitatea din masina,
     * folderul Descarcari e greu de gasit si imposibil de scos afara.
     */
    private val alegeLocul: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            punteFisiere?.scrieLa(uri)
        }
    private val mana = Handler(Looper.getMainLooper())
    private var origineGps: String? = null
    private var apelGps: GeolocationPermissions.Callback? = null

    private fun are(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun areLocatie() = are(Manifest.permission.ACCESS_FINE_LOCATION)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        Jurnal.porneste(this)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WebView.setWebContentsDebuggingEnabled(true)

        val incarcator = WebViewAssetLoader.Builder()
            .setDomain(GAZDA)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setGeolocationEnabled(true)
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_NO_CACHE   // versiunea noua se vede imediat
        }

        web.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                vedere: WebView, cerere: WebResourceRequest
            ): WebResourceResponse? = incarcator.shouldInterceptRequest(cerere.url)
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origine: String?, apel: GeolocationPermissions.Callback?
            ) {
                if (areLocatie()) { apel?.invoke(origine, true, true); return }
                origineGps = origine
                apelGps = apel
                ActivityCompat.requestPermissions(
                    this@MainActivity,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    CERERE_GPS
                )
            }

            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                android.util.Log.i("Pagina", m.message() + " (linia " + m.lineNumber() + ")")
                return true
            }
        }

        /*
         * AMANDOUA PUNTILE, pornite deodata.
         *
         * Prima incercare alegea una la pornire si atat. Dar recunoasterea se
         * inseala: unitatea din Cityzen declara Bluetooth Low Energy, are si
         * scanner, si totusi nu gaseste nimic — iar omul ramanea cu un buton
         * care cerea repornirea aplicatiei ca sa schimbe ceva ce oricum nu era
         * sigur ca ajuta.
         *
         * Acum sunt inregistrate amandoua, sub nume diferite, iar pagina alege
         * la incarcare care dintre ele devine "Punte". Comutarea inseamna un
         * singur reload, fara repornire si fara sa se piarda nimic.
         */
        punteBle = PunteBle(this, web)
        punteSpp = PunteSpp(this, web)
        web.addJavascriptInterface(punteBle!!, "PunteBle")
        web.addJavascriptInterface(punteSpp!!, "PunteSpp")
        web.addJavascriptInterface(Comutator(), "Comutator")

        punteFisiere = PunteFisiere(this, web) { nume -> alegeLocul.launch(nume) }
        web.addJavascriptInterface(punteFisiere!!, "Fisiere")

        setContentView(web)
        ecranComplet()
        cerePermisiuni()
        porneisteServiciul()

        if (Jurnal.areCaderi(this)) {
            android.widget.Toast.makeText(
                this, "S-a salvat o eroare în Descărcări: cityzen_erori.txt",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }

        web.loadUrl(PORNIRE)
        batePeriodic()
    }

    /*
     * Cand WebView-ul nu e in fata, Android incetineste setInterval pana la o
     * bataie la cateva secunde. Pagina isi lasa ceasurile in window.AV.ceasuri
     * tocmai ca sa poata fi batute din afara. Se bate numai daca pagina chiar
     * a tacut, ca sa nu dublam munca atunci cand totul merge normal.
     */
    /**
     * Ce punte foloseste pagina. Aici nu mai e nimic de ales: unitatea din
     * Cityzen merge NUMAI pe Bluetooth clasic.
     *
     * Motivul, aflat din firmware: cand persist.sys.bt.custom.stack e adevarat,
     * unitatea nu ruleaza stiva Bluetooth obisnuita, ci una proprie. Aceea da
     * socketuri RFCOMM pe patru UUID-uri hardcodate, intre care 1101 (SPP), dar
     * nu da GATT folosibil pentru un adaptor ELM327. Deci Low Energy n-are cum
     * sa mearga aici, oricat ar declara unitatea ca are.
     *
     * Metodele au ramas pe loc, cu aceleasi nume, ca pagina veche sa nu cada
     * daca le cheama — doar ca raspunsul nu se mai schimba.
     */
    inner class Comutator {
        @android.webkit.JavascriptInterface
        fun clasic(): Boolean = true

        /** Radiografia, indiferent ce punte e activa. */
        @android.webkit.JavascriptInterface
        fun raport(): String = JurnalBt.raport(this@MainActivity, "clasic")

        @android.webkit.JavascriptInterface
        fun goleste_jurnal() = JurnalBt.goleste()

        /** Ramasa doar ca sa nu crape paginile vechi. Nu mai comuta nimic. */
        @android.webkit.JavascriptInterface
        fun pune(da: Boolean) {
            JurnalBt.scrie("cerere de comutare ignorată: aplicația merge numai pe Bluetooth clasic")
        }
    }

    private fun batePeriodic() {
        mana.postDelayed(object : Runnable {
            override fun run() {
                web.evaluateJavascript(VERIFICA_BUCLA) { r ->
                    val vechime = r?.trim('"')?.toLongOrNull() ?: 0L
                    if (vechime > 1500) web.evaluateJavascript(BATE_CEASURI, null)
                }
                mana.postDelayed(this, 1000)
            }
        }, 3000)
    }

    private fun cerePermisiuni() {
        val cerute = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            if (!are(Manifest.permission.BLUETOOTH_SCAN))
                cerute += Manifest.permission.BLUETOOTH_SCAN
            if (!are(Manifest.permission.BLUETOOTH_CONNECT))
                cerute += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (!areLocatie()) cerute += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= 33 && !are(Manifest.permission.POST_NOTIFICATIONS))
            cerute += Manifest.permission.POST_NOTIFICATIONS
        if (cerute.isNotEmpty())
            ActivityCompat.requestPermissions(this, cerute.toTypedArray(), CERERE_PERMISIUNI)
    }

    override fun onRequestPermissionsResult(
        cod: Int, permisiuni: Array<out String>, rezultate: IntArray
    ) {
        super.onRequestPermissionsResult(cod, permisiuni, rezultate)
        if (cod == CERERE_GPS) {
            apelGps?.invoke(origineGps, areLocatie(), true)
            apelGps = null; origineGps = null
            return
        }
        porneisteServiciul()
    }

    private fun porneisteServiciul() {
        val gata = if (Build.VERSION.SDK_INT >= 31)
            are(Manifest.permission.BLUETOOTH_CONNECT) else true
        if (!gata) return
        val i = Intent(this, ServiciuFundal::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    private fun ecranComplet() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    override fun onWindowFocusChanged(are: Boolean) {
        super.onWindowFocusChanged(are)
        if (are) ecranComplet()
    }

    override fun onDestroy() {
        mana.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
