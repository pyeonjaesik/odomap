package com.odomap.app.store

import com.odomap.core.car.CarProfile
import com.odomap.core.explore.EDGE_KEY_BASE
import com.odomap.core.explore.ExploreGraph
import com.odomap.core.explore.ExploreTileData
import com.odomap.core.explore.TileEdge
import com.odomap.core.explore.TileNode
import com.odomap.core.explore.exploreTileOf
import com.odomap.core.explore.exploreTilesOfGraph
import com.odomap.core.explore.loadExploreTile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The cumulative road net on disk: one JSON file per z14 tile (ExploreTile split), `graph/tiles/<x>_<y>.json`:
 * {"n":[[id,latE6,lngE6,visits,day],…],"e":[[a,b,visits,day,aLatE6,aLngE6,bLatE6,bLngE6],…]}.
 * Only tiles touched by a drive are rewritten.
 * TODO(privacy): encrypt at rest (Jetpack Security / Tink AEAD keyed from the Android Keystore) before release.
 */
class GraphStore(root: File) {
    private val dir = File(root, "graph/tiles")

    /** All tiles into a fresh graph (phase 1: the whole net is small enough to keep in memory). */
    fun load(): ExploreGraph {
        val graph = CarProfile.newGraph()
        val files = dir.listFiles { f -> f.name.endsWith(".json") } ?: return graph
        for (file in files.sortedBy { it.name }) {
            runCatching { loadExploreTile(graph, parseTile(file.readText())) }
        }
        graph.clearDirty()
        return graph
    }

    /** Writes the tiles holding changed nodes/edges, then clears the dirty marks. Returns the tile keys written. */
    fun saveDirty(graph: ExploreGraph): Set<String> {
        val keys = HashSet<String>()
        for (id in graph.dirtyNodes) graph.node(id)?.let { keys.add(exploreTileOf(it.lat, it.lng).key) }
        for (key in graph.dirtyEdges) {
            val a = (key / EDGE_KEY_BASE).toInt()
            val b = (key % EDGE_KEY_BASE).toInt()
            graph.edge(a, b)?.let { keys.add(exploreTileOf((it.aLat + it.bLat) / 2, (it.aLng + it.bLng) / 2).key) }
        }
        if (keys.isEmpty()) return keys
        dir.mkdirs()
        for ((key, data) in exploreTilesOfGraph(graph, keys)) {
            writeAtomic(File(dir, key.replace('/', '_') + ".json"), tileJson(data))
        }
        graph.clearDirty()
        return keys
    }

    fun tileCount(): Int = dir.listFiles()?.size ?: 0

    fun clear() {
        dir.deleteRecursively()
    }

    companion object {
        fun tileJson(data: ExploreTileData): String {
            val o = JSONObject()
            o.put("n", JSONArray().apply { for (n in data.n) put(JSONArray().put(n.id).put(n.latE6).put(n.lngE6).put(n.visits).put(n.day)) })
            o.put(
                "e",
                JSONArray().apply {
                    for (e in data.e) put(JSONArray().put(e.a).put(e.b).put(e.visits).put(e.day).put(e.aLatE6).put(e.aLngE6).put(e.bLatE6).put(e.bLngE6))
                },
            )
            return o.toString()
        }

        fun parseTile(text: String): ExploreTileData {
            val o = JSONObject(text)
            val data = ExploreTileData()
            val n = o.optJSONArray("n") ?: JSONArray()
            for (i in 0 until n.length()) {
                val r = n.getJSONArray(i)
                data.n.add(TileNode(r.getInt(0), r.getLong(1), r.getLong(2), r.getInt(3), r.getInt(4)))
            }
            val e = o.optJSONArray("e") ?: JSONArray()
            for (i in 0 until e.length()) {
                val r = e.getJSONArray(i)
                data.e.add(TileEdge(r.getInt(0), r.getInt(1), r.getInt(2), r.getInt(3), r.getLong(4), r.getLong(5), r.getLong(6), r.getLong(7)))
            }
            return data
        }
    }
}

internal fun writeAtomic(file: File, text: String) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(file)) {
        file.delete()
        tmp.renameTo(file)
    }
}
