package org.fossify.calendar.helpers

import android.app.Activity
import android.content.Context
import android.provider.CalendarContract
import android.util.Log
import androidx.work.WorkerParameters
import ch.rosenweg4303.cloud.Konto
import ch.rosenweg4303.cloud.KontoSpeicher
import ch.rosenweg4303.cloud.dav.DavArbeit
import ch.rosenweg4303.cloud.dav.DavPlaner
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
 * dem eigenen Handy nach dem Anmeldeschirm (Rosenweg-Karte oder Rosenweg-Konto) ueber die Anlage.
 * Der Abgleich (commons: KalenderAbgleich) legt jeden Nextcloud-Kalender als Systemkalender an
 * (eigene, freigegebene, Nur-Lesen, Abos - jeder mit seiner Farbe aus der Nextcloud); diese App
 * zeigt und bearbeitet sie wie jeden "CalDAV"-Kalender.
 *
 * Wann: im Hintergrund alle 15 Minuten, kurz nach jeder Aenderung in der App (WorkManager,
 * [KalenderArbeit]) und beim Oeffnen (hoechstens alle 2 Minuten).
 *
 * Nur Nextcloud-Kalender (Stefan 27.11.2026: "Die App braucht keine lokalen Kalender"): sobald
 * Nextcloud-Kalender da sind, blendet die App ihre lokalen Kalender aus (Auswahl, Filter,
 * Verwaltung; ihre Termine werden nicht mehr angezeigt, bleiben aber gespeichert), und neue Termine
 * gehen in den Standardkalender (Einstellungen, Vorgabe "Persönlich"). Ein- und Ausblenden der
 * Nextcloud-Kalender ueber den Filter der App (pro Geraet gespeichert).
 */
@Suppress("TooManyFunctions")
object RosenwegNextcloud {
    private const val TAG = "RosenwegNextcloud"
    private const val ABSTAND_MS = 2 * 60 * 1000L
    const val APP = "Rosenweg Kalender (Android)"
    const val BESCHREIBUNG = "Deine Kalender aus der Rosenweg-Nextcloud erscheinen hier – alle, auch " +
        "freigegebene. Was du hier einträgst oder änderst, geht sofort zurück in die Nextcloud; " +
        "Einladungen verschickt die Nextcloud."
    private const val PREFS = "rosenweg_nextcloud_kalender"
    private const val IDS = "ids"

    val PLAN = DavPlaner.Plan("kalender", KalenderArbeit::class.java, listOf(CalendarContract.CONTENT_URI))

    @Volatile private var zuletzt = 0L

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Systemkalender-Ids (caldavCalendarId) der Nextcloud-Kalender, wie zuletzt eingebunden. */
    fun ids(c: Context): Set<Int> = prefs(c).getStringSet(IDS, emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()

    /** Gibt es Nextcloud-Kalender? Dann zeigt die App keine lokalen Kalender. */
    fun nurNextcloud(c: Context) = ids(c).isNotEmpty()

    private fun berechtigt(c: Context) =
        c.hasPermission(PERMISSION_WRITE_CALENDAR) && c.hasPermission(PERMISSION_READ_CALENDAR)

    /** Beim Oeffnen: Hintergrund planen und (hoechstens alle 2 Minuten) gleich abgleichen; [fertig] im UI-Thread. */
    fun abgleichen(a: Activity, sofort: Boolean = false, fertig: (Boolean) -> Unit = {}) {
        if (!berechtigt(a)) return
        DavPlaner.einrichten(a, PLAN)
        if (!sofort && System.currentTimeMillis() - zuletzt < ABSTAND_MS) return
        zuletzt = System.currentTimeMillis()
        val app = a.applicationContext
        ensureBackgroundThread {
            var geaendert = false
            try {
                geaendert = DavArbeit.exklusiv(PLAN.name) { abgleichenJetzt(app, KontoSpeicher(app).laden()) }
            } catch (e: Exception) {
                Log.w(TAG, "Abgleich: ${e.javaClass.simpleName} ${e.message}")
            }
            a.runOnUiThread { fertig(geaendert) }
        }
    }

    /** Anmeldung am geteilten Telefon u.ae.: im Hintergrund gleich abgleichen. */
    fun anstossen(c: Context) {
        if (!berechtigt(c)) return
        DavPlaner.einrichten(c, PLAN)
        DavPlaner.sofort(c, PLAN)
    }

    /** Blockierend (Hintergrund). true = etwas geaendert. */
    fun abgleichenJetzt(c: Context, konto: Konto?): Boolean {
        if (konto == null) return entfernen(c)
        val e = KalenderAbgleich(c, konto, APP).abgleichen()
        einbinden(c)
        return e.hoch + e.herunter + e.geloescht > 0
    }

    /**
     * Nextcloud-Kalender in die App uebernehmen: neue aufnehmen, auf dem Server geloeschte entfernen,
     * lokale ausblenden, Standardkalender setzen, Termine einlesen (Fossify haelt eine eigene Kopie).
     */
    fun einbinden(c: Context) {
        val ids = KalenderAbgleich.kalenderIds(c).map { it.toInt() }
        val alt = ids(c)
        val config = c.config
        val eh = c.eventsHelper
        // auf dem Server verschwundene Kalender
        (alt - ids.toSet()).forEach { id ->
            c.calDAVHelper.deleteCalDAVCalendarEvents(id.toLong())
            eh.getCalendarWithCalDAVCalendarId(id)?.let { eh.deleteCalendars(arrayListOf(it), true) }
        }
        prefs(c).edit().putStringSet(IDS, ids.map { it.toString() }.toSet()).apply()
        val synced = (config.getSyncedCalendarIdsAsList().filter { it !in alt || it in ids } + ids).distinct()
        config.caldavSyncedCalendarIds = synced.joinToString(",")
        if (ids.isEmpty()) return
        config.caldavSync = true
        val bekannt = eh.getCalendarsSync().map { it.caldavCalendarId }.toSet()
        c.calDAVHelper.getCalDAVCalendars(ids.joinToString(","), false).filter { it.id !in bekannt }.forEach {
            // neue Kalender werden dabei auch eingeblendet (Fossify: addDisplayCalendar)
            eh.insertOrUpdateCalendarSync(
                CalendarEntity(null, it.displayName, it.color, it.id, it.displayName, it.accountName)
            )
        }
        lokaleAusblenden(c)
        standardSetzen(c)
        c.calDAVHelper.refreshCalendars(showToasts = false, scheduleNextSync = true) {}
    }

    /** Lokale Kalender der App: nicht anzeigen (ihre Termine bleiben gespeichert). */
    private fun lokaleAusblenden(c: Context) {
        val config = c.config
        val lokal = lokaleIds(c)
        if (config.displayCalendars.any { it in lokal }) config.displayCalendars = config.displayCalendars - lokal
        if (config.quickFilterCalendars.any { it in lokal }) {
            config.quickFilterCalendars = config.quickFilterCalendars - lokal
        }
        // Aufgaben gibt es in der App nur in lokalen Kalendern (kein VTODO-Abgleich): aus
        if (config.allowCreatingTasks && !prefs(c).getBoolean("aufgaben_aus", false)) {
            config.allowCreatingTasks = false
            prefs(c).edit().putBoolean("aufgaben_aus", true).apply()
        }
    }

    /** Standardkalender fuer neue Termine: gewaehlter, sonst "Persönlich", sonst der erste beschreibbare. */
    private fun standardSetzen(c: Context) {
        val config = c.config
        val gewaehlt = c.eventsHelper.getCalendarsSync().firstOrNull { it.id == config.defaultCalendarId }
        val ok = gewaehlt != null && gewaehlt.caldavCalendarId in ids(c) && beschreibbar(c, gewaehlt.caldavCalendarId)
        if (ok) return
        vorgabe(c)?.let { config.defaultCalendarId = it.id!! }
    }

    private fun beschreibbar(c: Context, caldavId: Int) =
        KalenderAbgleich.kalender(c).any { it.id.toInt() == caldavId && !it.nurLesen }

    private fun lokaleIds(c: Context) =
        c.eventsHelper.getCalendarsSync().filter { it.caldavCalendarId == 0 }.map { it.id.toString() }.toSet()

    /** "Persönlich" (Nextcloud-Standard) oder der erste beschreibbare Nextcloud-Kalender. */
    private fun vorgabe(c: Context): CalendarEntity? {
        val nc = KalenderAbgleich.kalender(c).filter { !it.nurLesen }
        val wahl = nc.firstOrNull { it.url.trimEnd('/').endsWith("/personal") } ?: nc.firstOrNull {
            it.name.equals("Persönlich", true) || it.name.equals("Personal", true)
        } ?: nc.firstOrNull() ?: return null
        return c.eventsHelper.getCalendarsSync().firstOrNull { it.caldavCalendarId == wahl.id.toInt() }
    }

    /**
     * Fuer neue Termine (EventActivity): ist [id] ein lokaler (oder nur lesbarer) Kalender, der
     * Nextcloud-Standardkalender statt dessen. null = so lassen.
     */
    fun standardStatt(c: Context, kalender: List<CalendarEntity>, id: Long): Long? {
        if (!nurNextcloud(c)) return null
        val k = kalender.firstOrNull { it.id == id }
        if (k != null && k.caldavCalendarId in ids(c) && beschreibbar(c, k.caldavCalendarId)) return null
        val std = kalender.firstOrNull { it.id == c.config.defaultCalendarId && it.caldavCalendarId in ids(c) }
        return std?.id ?: vorgabe(c)?.id
    }

    /** Fuer Auswahllisten: ohne die lokalen Kalender, wenn es Nextcloud-Kalender gibt. */
    fun filtern(c: Context, liste: ArrayList<CalendarEntity>): ArrayList<CalendarEntity> {
        if (!nurNextcloud(c)) return liste
        return ArrayList(liste.filter { it.caldavCalendarId != 0 })
    }

    /** Abgemeldet: Nextcloud-Kalender samt Terminen weg (die Daten bleiben auf dem Server). */
    fun entfernen(c: Context): Boolean {
        val ids = (KalenderAbgleich.kalenderIds(c).map { it.toInt() } + ids(c)).distinct()
        prefs(c).edit().remove(IDS).apply()
        if (ids.isEmpty()) return false
        val config = c.config
        ids.forEach { id ->
            c.calDAVHelper.deleteCalDAVCalendarEvents(id.toLong())
            val k = c.eventsHelper.getCalendarWithCalDAVCalendarId(id) ?: return@forEach
            c.eventsHelper.deleteCalendars(arrayListOf(k), true)
        }
        val rest = config.getSyncedCalendarIdsAsList().filter { it !in ids }
        config.caldavSyncedCalendarIds = rest.joinToString(",")
        if (rest.isEmpty()) config.caldavSync = false
        // lokale Kalender wieder zeigen
        config.displayCalendars = config.displayCalendars + lokaleIds(c)
        KalenderAbgleich.entfernen(c)
        DavPlaner.abschalten(c, PLAN)
        return true
    }
}

/** Hintergrund-Abgleich (WorkManager, siehe commons DavArbeit). */
class KalenderArbeit(c: Context, p: WorkerParameters) : DavArbeit(c, p) {
    override val plan get() = RosenwegNextcloud.PLAN
    override fun berechtigt() = applicationContext.hasPermission(PERMISSION_WRITE_CALENDAR) &&
        applicationContext.hasPermission(PERMISSION_READ_CALENDAR)
    override fun lokalGeaendert() = KalenderAbgleich.lokalGeaendert(applicationContext)
    override fun abgleichen(konto: Konto) = RosenwegNextcloud.abgleichenJetzt(applicationContext, konto)
    // Im Hintergrund nie lokal loeschen: "kein Konto" kann auch heissen, dass Rosenweg Geraet gerade
    // nicht antwortet. Aufgeraeumt wird beim naechsten Oeffnen der App.
    override fun abgemeldet() { DavPlaner.abschalten(applicationContext, plan) }
}
