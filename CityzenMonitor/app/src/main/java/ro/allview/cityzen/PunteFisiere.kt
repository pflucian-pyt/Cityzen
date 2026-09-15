package ro.allview.cityzen

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.widget.Toast
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
class PunteFisiere(private val activitate: AppCompatActivity) {

    companion object { private const val TAG = "PunteFisiere" }

    @JavascriptInterface
    fun salveaza(nume: String, base64: String, tip: String) {
        val octeti = try {
            Base64.decode(base64, Base64.DEFAULT)
        } catch (e: Exception) {
            spune("Export eșuat: date nevalide"); return
        }

        val curat = nume.replace(Regex("[/\\\\:*?\"<>|]"), "_").ifBlank { "export.json" }
        val kb = (octeti.size + 512) / 1024

        try {
            val unde = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                prinMediaStore(curat, octeti, tip)
            else
                directPeDisc(curat, octeti)
            spune("Salvat în Descărcări: $unde ($kb KB)")
            Log.i(TAG, "salvat $unde, ${octeti.size} octeti")
        } catch (e: Exception) {
            Log.e(TAG, "salvare esuata", e)
            spune("Export eșuat: ${e.message}. Copiază textul din casetă.")
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

    private fun spune(text: String) {
        activitate.runOnUiThread {
            Toast.makeText(activitate, text, Toast.LENGTH_LONG).show()
        }
    }
}
