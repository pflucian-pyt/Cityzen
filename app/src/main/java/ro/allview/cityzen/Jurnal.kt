package ro.allview.cityzen

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Jurnalul de erori al aplicatiei, scris pe telefon.
 *
 * De ce exista: cand ecranul din masina cade, se vede doar "aplicatia a
 * intampinat o eroare neasteptata". Motivul adevarat sta in jurnalul de sistem,
 * la care se ajunge de obicei cu telefonul legat prin cablu la un calculator —
 * imposibil cand cablul e ocupat de masina.
 *
 * Asa ca aplicatia isi prinde singura caderile si le scrie intr-un fisier text,
 * in Descarcari. Dupa o cadere, deschizi fisierul si se vede exact ce si unde
 * a crapat.
 *
 * Se prind doua feluri de necazuri:
 *  - caderile netratate, prin ascultatorul global
 *  - cele pe care codul nostru le prinde singur, dar care merita stiute
 */
object Jurnal {

    private const val TAG = "Jurnal"
    private const val NUME = "cityzen_erori.txt"
    private const val MAXIM = 60_000        // pastram doar ultimele necazuri

    private var context: Context? = null

    fun porneste(c: Context) {
        if (context != null) return
        context = c.applicationContext

        val vechiul = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { fir, e ->
            try { scrie("CADERE pe firul ${fir.name}", e) } catch (x: Throwable) { }
            // lasam sistemul sa-si faca treaba mai departe, ca sa nu ascundem nimic
            vechiul?.uncaughtException(fir, e)
        }
        scrie("pornire", null)
    }

    fun scrie(eticheta: String, e: Throwable?) {
        val c = context ?: return
        val ceas = SimpleDateFormat("dd-MM HH:mm:ss", Locale.US).format(Date())
        val sb = StringBuilder()
        sb.append("\n[$ceas] $eticheta\n")
        if (e != null) {
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            sb.append(sw.toString())
        }
        Log.w(TAG, sb.toString())

        try {
            val f = File(c.filesDir, NUME)
            if (f.exists() && f.length() > MAXIM) {
                // pastram coada, nu capul: ultimele necazuri conteaza
                val text = f.readText()
                f.writeText(text.takeLast(MAXIM / 2))
            }
            f.appendText(sb.toString())
            copiazaInDescarcari(c, f)
        } catch (x: Throwable) {
            Log.w(TAG, "nu pot scrie jurnalul: ${x.message}")
        }
    }

    /** aceeasi cale ca la exportul sesiunii, ca fisierul sa fie usor de gasit */
    private fun copiazaInDescarcari(c: Context, sursa: File) {
        val octeti = sursa.readBytes()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rez = c.contentResolver
            // stergem varianta veche, ca sa nu se adune "cityzen_erori(1).txt"
            try {
                rez.delete(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    "${MediaStore.Downloads.DISPLAY_NAME}=?", arrayOf(NUME)
                )
            } catch (x: Throwable) { }
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, NUME)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            }
            val uri = rez.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return
            rez.openOutputStream(uri).use { it?.write(octeti) }
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            File(dir, NUME).writeBytes(octeti)
        }
    }

    /** exista vreo cadere salvata de la ultima pornire? */
    fun areCaderi(c: Context): Boolean = try {
        File(c.filesDir, NUME).readText().contains("CADERE")
    } catch (e: Throwable) { false }
}
