package org.fossify.calendar.helpers

import android.app.Activity
import android.util.Log
import ch.rosenweg4303.cloud.KontoSpeicher
import ch.rosenweg4303.cloud.dav.KalenderAbgleich
import org.fossify.calendar.extensions.calDAVHelper
import org.fossify.calendar.extensions.config
import org.fossify.calendar.extensions.eventsHelper
import org.fossify.calendar.models.CalendarEntity
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.helpers.PERMISSION_READ_CALENDAR
import org.fossify.commons.helpers.PERMISSION_WRITE_CALENDAR
import org.fossify.commons.helpers.ensureBackgroundThread

/**
 * Rosenweg (Stefan 26.11.2026): die Kalender der Rosenweg-Nextcloud direkt per CalDAV, ohne DAVx5.
 *
 * Zugang: KontoSpeicher aus commons-rosenweg - auf geteilten Telefonen von Rosenweg Geraet, auf
 * dem eigenen Handy nach dem Anmeldeschirm (Rosenweg-Karte oder Rosenweg-Konto) ueber die Anlage
 * (POST /api/telefon/privat/nextcloud). Der Abgleich (commons: KalenderAbgleich) legt die
 * Nextcloud-Kalender als Systemkalender an; diese App zeigt und bearbeitet sie wie jeden
 * "CalDAV"-Kalender. Aenderungen gehen beim naechsten Abgleich hoch (beim Oeffnen, hoechstens alle
 * 10 Minuten, und nach der Anmeldung).
 */
object RosenwegNextcloud {
    private const val TAG = "RosenwegNextcloud"
    private const val ABSTAND_MS = 10 * 60 * 1000L
    private const val APP = "Rosenweg Kalender (Android)"
    const val BESCHREIBUNG = "Deine Kalender aus der Rosenweg-Nextcloud erscheinen hier. Was du hier " +
        "einträgst oder änderst, geht zurück in die Nextcloud."

    @Volatile private var zuletzt = 0L
    @Volatile private var laeuft = false

    /** Im Hintergrund abgleichen; [fertig] im UI-Thread (true = etwas geaendert, neu anzeigen). */
    fun abgleichen(a: Activity, sofort: Boolean = false, fertig: (Boolean) -> Unit = {}) {
        if (laeuft || (!sofort && System.currentTimeMillis() - zuletzt < ABSTAND_MS)) return
        if (!a.hasPermission(PERMISSION_WRITE_CALENDAR) || !a.hasPermission(PERMISSION_READ_CALENDAR)) return
        laeuft = true
        zuletzt = System.currentTimeMillis()
        ensureBackgroundThread {
            var geaendert = false
            try {
                val konto = KontoSpeicher(a).laden()
                if (konto == null) {
                    geaendert = entfernen(a)
                } else {
                    val e = KalenderAbgleich(a, konto, APP).abgleichen()
                    einbinden(a, KalenderAbgleich.kalenderIds(a).map { it.toInt() })
                    geaendert = e.hoch + e.herunter + e.geloescht > 0
                }
            } catch (e: Exception) {
                Log.w(TAG, "Abgleich: ${e.javaClass.simpleName} ${e.message}")
            } finally {
                laeuft = false
            }
            a.runOnUiThread { fertig(geaendert) }
        }
    }

    /** Nextcloud-Kalender in die Auswahl der App ("CalDAV-Synchronisation") aufnehmen und einlesen. */
    private fun einbinden(a: Activity, ids: List<Int>) {
        if (ids.isEmpty()) return
        val config = a.config
        config.caldavSyncedCalendarIds = (config.getSyncedCalendarIdsAsList() + ids).distinct().joinToString(",")
        config.caldavSync = true
        val bekannt = a.eventsHelper.getCalendarsSync().map { it.caldavCalendarId }.toSet()
        a.calDAVHelper.getCalDAVCalendars(ids.joinToString(","), false).filter { it.id !in bekannt }.forEach {
            a.eventsHelper.insertOrUpdateCalendarSync(
                CalendarEntity(null, it.displayName, it.color, it.id, it.displayName, it.accountName)
            )
        }
        a.calDAVHelper.refreshCalendars(showToasts = false, scheduleNextSync = true) {}
    }

    /** Abgemeldet: Nextcloud-Kalender samt Terminen weg (die Daten bleiben auf dem Server). */
    private fun entfernen(a: Activity): Boolean {
        val ids = KalenderAbgleich.kalenderIds(a).map { it.toInt() }
        if (ids.isEmpty()) return false
        val config = a.config
        ids.forEach { id ->
            a.calDAVHelper.deleteCalDAVCalendarEvents(id.toLong())
            a.eventsHelper.getCalendarWithCalDAVCalendarId(id)?.let { a.eventsHelper.deleteCalendars(arrayListOf(it), true) }
        }
        val rest = config.getSyncedCalendarIdsAsList().filter { it !in ids }
        config.caldavSyncedCalendarIds = rest.joinToString(",")
        if (rest.isEmpty()) config.caldavSync = false
        KalenderAbgleich.entfernen(a)
        return true
    }
}
