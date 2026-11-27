package org.fossify.calendar.dialogs

import android.app.Activity
import android.app.AlertDialog
import android.widget.EditText
import android.widget.Toast
import ch.rosenweg4303.cloud.KontoSpeicher
import ch.rosenweg4303.cloud.dav.DavArbeit
import ch.rosenweg4303.cloud.dav.KalenderAbgleich
import org.fossify.calendar.helpers.RosenwegNextcloud
import org.fossify.commons.helpers.ensureBackgroundThread

/**
 * Rosenweg: Nextcloud-Kalender anlegen, umbenennen und loeschen - direkt in der Nextcloud
 * (MKCALENDAR/PROPPATCH/DELETE), danach abgleichen. Farbe aendern geht wie bei jedem Kalender
 * ueber "Kalender verwalten" der App (wird per PROPPATCH uebernommen). Bei freigegebenen Kalendern
 * heisst Loeschen: die Freigabe fuer mich beenden.
 */
object NextcloudKalenderDialog {
    private val FARBEN = intArrayOf(0xFF0082C9.toInt(), 0xFFE9322D.toInt(), 0xFF2D8C3C.toInt(), 0xFFF18F01.toInt(),
        0xFF795AAB.toInt(), 0xFF1E3A8A.toInt())

    fun zeigen(a: Activity, fertig: () -> Unit) {
        ensureBackgroundThread {
            val liste = KalenderAbgleich.kalender(a)
            a.runOnUiThread {
                val namen = (listOf("+ Neuer Kalender …") +
                    liste.map { it.name + if (it.nurLesen) " (nur lesen)" else "" }).toTypedArray()
                AlertDialog.Builder(a).setTitle("Nextcloud-Kalender")
                    .setItems(namen) { _, i -> if (i == 0) anlegen(a, fertig) else kalender(a, liste[i - 1], fertig) }
                    .setNegativeButton("Schliessen", null).show()
            }
        }
    }

    private fun eingabe(a: Activity, titel: String, vorgabe: String, ok: (String) -> Unit) {
        val feld = EditText(a).apply { setText(vorgabe); setSelection(vorgabe.length); setSingleLine() }
        AlertDialog.Builder(a).setTitle(titel).setView(feld)
            .setPositiveButton("OK") { _, _ -> feld.text.toString().trim().takeIf { it.isNotEmpty() }?.let(ok) }
            .setNegativeButton("Abbrechen", null).show()
    }

    private fun anlegen(a: Activity, fertig: () -> Unit) = eingabe(a, "Neuer Kalender", "") { name ->
        val farbe = FARBEN[Math.floorMod(name.hashCode(), FARBEN.size)]
        im(a, "Kalender „$name“ angelegt", fertig) { it.kalenderAnlegen(name, farbe) }
    }

    private fun kalender(a: Activity, k: KalenderAbgleich.Companion.Kalender, fertig: () -> Unit) {
        AlertDialog.Builder(a).setTitle(k.name)
            .setItems(arrayOf("Umbenennen", "Löschen")) { _, i ->
                if (i == 0) eingabe(a, "Umbenennen", k.name) { neu ->
                    im(a, "Umbenannt", fertig) { it.kalenderAendern(k.id, neu, k.farbe) }
                } else AlertDialog.Builder(a).setTitle("„${k.name}“ löschen?")
                    .setMessage(
                        if (k.nurLesen) "Die Freigabe für dich wird beendet."
                        else "Der Kalender wird in der Nextcloud mit allen Terminen gelöscht " +
                            "(Papierkorb der Nextcloud)."
                    )
                    .setPositiveButton("Löschen") { _, _ -> im(a, "Gelöscht", fertig) { it.kalenderLoeschen(k.id) } }
                    .setNegativeButton("Abbrechen", null).show()
            }.setNegativeButton("Abbrechen", null).show()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun im(a: Activity, erfolg: String, fertig: () -> Unit, tun: (KalenderAbgleich) -> Unit) {
        val app = a.applicationContext
        ensureBackgroundThread {
            val meldung = try {
                val konto = KontoSpeicher(app).laden() ?: error("nicht mit der Nextcloud verbunden")
                DavArbeit.exklusiv(RosenwegNextcloud.PLAN.name) {
                    val ab = KalenderAbgleich(app, konto, RosenwegNextcloud.APP)
                    tun(ab)
                    RosenwegNextcloud.abgleichenJetzt(app, konto)
                }
                erfolg
            } catch (e: Exception) {
                "Nicht möglich: ${e.message}"
            }
            a.runOnUiThread { Toast.makeText(a, meldung, Toast.LENGTH_LONG).show(); fertig() }
        }
    }
}
