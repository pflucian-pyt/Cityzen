package ro.allview.cityzen

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.widget.Toast
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * Salvarea fisierelor pe disc.
 *
 * De ce e nevoie: WebView-ul nu descarca nimic singur. Pagina creeaza un blob
 * si apasa pe o legatura invizibila — intr-un browser asta produce un fisier in
 * Descarcari, in WebView nu se intampla absolut nimic, fara nici o eroare.
 * punte.js prinde apasarea si trimite octetii aici.
 *
 * Fisierul ajunge in Descarcari, adica exact unde spune si mesajul din pagina.
 */
class PunteFisiere(
    private val activitate: AppCompatActivity,
    private val web: WebView,
    /** deschide fereastra de sistem „unde salvez”, cu numele propus */
    private val alegeLocul: (String) -> Unit
) {

    companion object { private const val TAG = "PunteFisiere" }

    /** ce asteapta sa fie scris, dupa ce omul alege locul */
    private var inAsteptare: ByteArray? = null
    private var numeInAsteptare: String = "export.json"

    /**
     * Salvare cu alegerea locului.
     *
     * Varianta de mai jos scrie in Descarcari si spune printr-un Toast unde a
     * pus fisierul. Toastul dispare in trei secunde, iar pe unitatea din masina
     * folderul Descarcari nu e usor de gasit — omul ramane cu impresia ca nu s-a
     * salvat nimic. Aici se deschide fereastra de sistem: alegi tu folderul,
     * inclusiv stickul USB, si vezi negru pe alb unde a ajuns.
     */
    @JavascriptInterface
    fun salveazaUnde(nume: String, base64: String, tip: String) {
        val octeti = try { Base64.decode(base64, Base64.DEFAULT) }
        catch (e: Exception) { raporteaza(false, "date nevalide"); return }
        inAsteptare = octeti
        numeInAsteptare = nume.replace(Regex("[/\\\\:*?\"<>|]"), "_").ifBlank { "export.json" }
        activitate.runOnUiThread { alegeLocul(numeInAsteptare) }
    }

    /** chemata din MainActivity dupa ce omul a ales locul */
    fun scrieLa(uri: Uri?) {
        val octeti = inAsteptare
        inAsteptare = null
        if (uri == null) { raporteaza(false, "ai renunțat la salvare"); return }
        if (octeti == null) { raporteaza(false, "nu mai era nimic de scris"); return }
        try {
            activitate.contentResolver.openOutputStream(uri).use { it!!.write(octeti) }
            val kb = (octeti.size + 512) / 1024
            raporteaza(true, "$numeInAsteptare · $kb KB · " + (uri.lastPathSegment ?: uri.toString()))
        } catch (e: Exception) {
            Log.e(TAG, "scriere esuata", e)
            raporteaza(false, e.message ?: "motiv necunoscut")
        }
    }

    /**
     * Rezultatul se spune SI in pagina, nu numai prin Toast. Un Toast care
     * dispare in trei secunde e ca si cum n-ai spune nimic: cand ceva a esuat,
     * omul cauta apoi o ora un fisier care nu exista.
     */
    private fun raporteaza(reusit: Boolean, text: String) {
        activitate.runOnUiThread {
            Toast.makeText(activitate, (if (reusit) "Salvat: " else "Nesalvat: ") + text,
                Toast.LENGTH_LONG).show()
            val js = "window.__salvare && window.__salvare(" + reusit + ", " +
                     org.json.JSONObject.quote(text) + ")"
            web.evaluateJavascript(js, null)
        }
    }

    @JavascriptInterface
    fun salveaza(nume: String, base64: String, tip: String) {
        val octeti = try {
            Base64.decode(base64, Base64.DEFAULT)
        } catch (e: Exception) {
            raporteaza(false, "date nevalide"); return
        }

        val curat = nume.replace(Regex("[/\\\\:*?\"<>|]"), "_").ifBlank { "export.json" }
        val kb = (octeti.size + 512) / 1024

        try {
            val unde = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                prinMediaStore(curat, octeti, tip)
            else
                directPeDisc(curat, octeti)
            raporteaza(true, "Descărcări / $unde ($kb KB)")
            Log.i(TAG, "salvat $unde, ${octeti.size} octeti")
        } catch (e: Exception) {
            Log.e(TAG, "salvare esuata", e)
            raporteaza(false, e.message ?: "motiv necunoscut")
        }
    }

    /** Android 10 si mai nou: fara permisiuni, prin colectia de Descarcari */
    private fun prinMediaStore(nume: String, octeti: ByteArray, tip: String): String {
        val valori = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, nume)
            put(MediaStore.Downloads.MIME_TYPE, if (tip.isBlank()) "application/json" else tip)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val rez = activitate.contentResolver
        val uri = rez.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, valori)
            ?: throw IllegalStateException("nu pot crea fisierul")
        rez.openOutputStream(uri).use { it!!.write(octeti) }
        valori.clear()
        valori.put(MediaStore.Downloads.IS_PENDING, 0)
        rez.update(uri, valori, null, null)
        return nume
    }

    /** Android 9 si mai vechi: scriere directa, cu permisiunea de stocare */
    private fun directPeDisc(nume: String, octeti: ByteArray): String {
        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, nume)
        f.writeBytes(octeti)
        return f.name
    }

}
