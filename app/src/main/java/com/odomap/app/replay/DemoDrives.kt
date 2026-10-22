package com.odomap.app.replay

import com.odomap.app.model.DriveStatus
import com.odomap.app.model.Fix
import com.odomap.app.model.Gear
import com.odomap.app.model.SourceEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.Reader

/** One bundled demo drive (assets/drives/index.json entry). */
data class DemoDrive(val id: String, val title: String, val start: String, val distanceM: Double, val minutes: Double)

/** A timed replay event: t = original ms epoch. */
data class TimedEvent(val t: Long, val event: SourceEvent)

object DemoDriveFormat {
    const val ASSET_DIR = "drives"
    const val SOURCE = "replay"

    fun parseIndex(text: String): List<DemoDrive> {
        val a = JSONArray(text)
        return List(a.length()) {
            val o = a.getJSONObject(it)
            DemoDrive(o.getString("id"), o.optString("title", o.getString("id")), o.optString("start"), o.optDouble("distance_m"), o.optDouble("minutes"))
        }
    }

    /**
     * jsonl → time-ordered events: {"k":"meta",…} (→ DemoBegin), {"k":"gear","t","g":"D"|"P"} (→ State), {"k":"loc","t","lat","lng","acc","spd","brg"} (→ Loc).
     * A drive that does not end in P gets one after its last fix.
     */
    fun parseDrive(reader: Reader, fallbackId: String): List<TimedEvent> {
        val out = ArrayList<TimedEvent>()
        var lastT = 0L
        var gear = Gear.UNKNOWN
        reader.buffered().useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val o = JSONObject(line)
                when (o.optString("k")) {
                    "meta" -> out.add(TimedEvent(Long.MIN_VALUE, SourceEvent.DemoBegin(o.optString("id", fallbackId), o.optString("title", fallbackId))))
                    "gear" -> {
                        val t = o.getLong("t")
                        gear = gearOf(o.optString("g"))
                        out.add(TimedEvent(t, SourceEvent.State(DriveStatus(t, gear, gear != Gear.PARK && gear != Gear.UNKNOWN, SOURCE))))
                        lastT = maxOf(lastT, t)
                    }
                    "loc" -> {
                        val t = o.getLong("t")
                        val fix =
                            Fix(
                                t = t,
                                lat = o.getDouble("lat"),
                                lng = o.getDouble("lng"),
                                acc = o.optDouble("acc").takeIf { it.isFinite() }?.toFloat(),
                                speed = o.optDouble("spd").takeIf { it.isFinite() }?.toFloat(),
                                bearing = o.optDouble("brg").takeIf { it.isFinite() }?.toFloat(),
                            )
                        out.add(TimedEvent(t, SourceEvent.Loc(fix)))
                        lastT = maxOf(lastT, t)
                    }
                }
            }
        }
        if (out.none { it.event is SourceEvent.DemoBegin }) out.add(0, TimedEvent(Long.MIN_VALUE, SourceEvent.DemoBegin(fallbackId, fallbackId)))
        if (gear != Gear.PARK) out.add(TimedEvent(lastT + 1000, SourceEvent.State(DriveStatus(lastT + 1000, Gear.PARK, false, SOURCE))))
        return out
    }

    fun gearOf(g: String): Gear =
        when (g.uppercase()) {
            "P" -> Gear.PARK
            "D" -> Gear.DRIVE
            "R" -> Gear.REVERSE
            "N" -> Gear.NEUTRAL
            else -> Gear.UNKNOWN
        }
}
