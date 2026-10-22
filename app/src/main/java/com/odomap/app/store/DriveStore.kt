package com.odomap.app.store

import com.odomap.app.model.Bounds
import com.odomap.app.model.DriveRecord
import com.odomap.app.model.Fix
import com.odomap.core.explore.EdgePair
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File

/**
 * Drives on disk, one folder each: `drives/<id>/summary.json`, `fixes.jsonl` (raw fixes, appended while driving — enough to recompute the
 * drive later with a new rule set), `edges.json` (edges walked / created, for the detail map).
 * On-device only. TODO(privacy): encrypt at rest (see GraphStore) before release.
 */
class DriveStore(root: File) {
    private val dir = File(root, "drives")

    fun list(): List<DriveRecord> {
        val folders = dir.listFiles { f -> f.isDirectory } ?: return emptyList()
        return folders.mapNotNull { f -> runCatching { recordOf(JSONObject(File(f, "summary.json").readText())) }.getOrNull() }
            .sortedByDescending { it.startedAt }
    }

    /** Opens the raw-fix log of a drive in progress. */
    fun openFixLog(id: String): FixLog = FixLog(File(File(dir, id).apply { mkdirs() }, "fixes.jsonl"))

    fun save(record: DriveRecord, walkEdges: List<EdgePair>, newEdges: List<EdgePair>) {
        val folder = File(dir, record.id).apply { mkdirs() }
        writeAtomic(File(folder, "edges.json"), JSONObject().put("walk", pairs(walkEdges)).put("new", pairs(newEdges)).toString())
        writeAtomic(File(folder, "summary.json"), jsonOf(record).toString())
    }

    fun edges(id: String): Pair<List<EdgePair>, List<EdgePair>>? {
        val file = File(File(dir, id), "edges.json")
        if (!file.exists()) return null
        val o = JSONObject(file.readText())
        return unpairs(o.getJSONArray("walk")) to unpairs(o.getJSONArray("new"))
    }

    /** Removes a folder left by a drive that never finished (no summary). */
    fun discard(id: String) {
        val folder = File(dir, id)
        if (!File(folder, "summary.json").exists()) folder.deleteRecursively()
    }

    fun clear() {
        dir.deleteRecursively()
    }

    class FixLog(file: File) {
        private val out: BufferedWriter = file.bufferedWriter()

        fun append(fix: Fix) {
            out.write(
                buildString {
                    append("{\"t\":").append(fix.t).append(",\"lat\":").append(fix.lat).append(",\"lng\":").append(fix.lng)
                    fix.acc?.let { append(",\"acc\":").append(it) }
                    fix.speed?.let { append(",\"spd\":").append(it) }
                    fix.bearing?.let { append(",\"brg\":").append(it) }
                    append("}\n")
                },
            )
        }

        fun close() {
            runCatching { out.close() }
        }
    }

    companion object {
        private fun pairs(list: List<EdgePair>): JSONArray {
            val a = JSONArray()
            for (p in list) a.put(p.a).put(p.b)
            return a
        }

        private fun unpairs(a: JSONArray): List<EdgePair> = List(a.length() / 2) { EdgePair(a.getInt(it * 2), a.getInt(it * 2 + 1)) }

        fun jsonOf(r: DriveRecord): JSONObject =
            JSONObject()
                .put("id", r.id)
                .put("title", r.title ?: JSONObject.NULL)
                .put("startedAt", r.startedAt)
                .put("endedAt", r.endedAt)
                .put("distanceM", r.distanceM)
                .put("pioneeredM", r.pioneeredM)
                .put("edgeCount", r.edgeCount)
                .put("newEdgeCount", r.newEdgeCount)
                .put("repassEdges", r.repassEdges)
                .put("maxPasses", r.maxPasses)
                .put("fixCount", r.fixCount)
                .put("source", r.source)
                .put("bounds", r.bounds?.let { JSONArray().put(it.minLat).put(it.minLng).put(it.maxLat).put(it.maxLng) } ?: JSONObject.NULL)

        fun recordOf(o: JSONObject): DriveRecord =
            DriveRecord(
                id = o.getString("id"),
                title = if (o.isNull("title")) null else o.getString("title"),
                startedAt = o.getLong("startedAt"),
                endedAt = o.getLong("endedAt"),
                distanceM = o.getDouble("distanceM"),
                pioneeredM = o.getDouble("pioneeredM"),
                edgeCount = o.optInt("edgeCount"),
                newEdgeCount = o.optInt("newEdgeCount"),
                repassEdges = o.optInt("repassEdges"),
                maxPasses = o.optInt("maxPasses"),
                fixCount = o.optInt("fixCount"),
                source = o.optString("source"),
                bounds = o.optJSONArray("bounds")?.let { Bounds(it.getDouble(0), it.getDouble(1), it.getDouble(2), it.getDouble(3)) },
            )
    }
}
