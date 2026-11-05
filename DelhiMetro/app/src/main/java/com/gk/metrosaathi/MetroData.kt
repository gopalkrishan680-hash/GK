package com.gk.metrosaathi

import android.content.Context

class MetroLine(
    val id: String,
    val name: String,
    val color: Long,
    val upcoming: Boolean,
    val note: String,
    val stations: List<String>,
    /** Cumulative km from first station (operational lines only; empty for upcoming). */
    val km: List<Double>,
) {
    val first get() = stations.first()
    val last get() = stations.last()
    val shortName get() = name.substringBefore(" (")
}

class Station(
    val name: String,
    val lat: Double,
    val lon: Double,
    val layout: String,
) {
    /** Operational line ids serving this station. */
    val lines = linkedSetOf<String>()
    /** Upcoming (under construction) line ids. */
    val upcomingLines = linkedSetOf<String>()
    val isInterchange get() = lines.size > 1
}

data class Connector(val line: String, val a: String, val b: String, val km: Double)

class MetroData(
    val lines: List<MetroLine>,
    val stations: Map<String, Station>,
    /** Walking links between differently named stations: a -> (b, minutes). */
    val walks: Map<String, List<Pair<String, Int>>>,
    /** Extra same-line links (e.g. closing the Pink Line ring): lineId, a, b, km. */
    val connectors: List<Connector>,
) {
    val lineById = lines.associateBy { it.id }
    val operationalLines get() = lines.filter { !it.upcoming }
    val upcomingLines get() = lines.filter { it.upcoming }

    /** Stations that can be used for journeys (served by at least one operational line). */
    val operationalStations: List<Station> =
        stations.values.filter { it.lines.isNotEmpty() }.sortedBy { it.name.lowercase() }

    fun search(query: String): List<Station> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return operationalStations
        val compact = q.replace(" ", "")
        return operationalStations
            .filter { s ->
                val n = s.name.lowercase()
                n.contains(q) || n.replace(" ", "").replace("-", "").replace(".", "").contains(compact)
            }
            .sortedBy { if (it.name.lowercase().startsWith(q)) 0 else 1 }
    }

    fun nearest(lat: Double, lon: Double, count: Int = 3): List<Pair<Station, Double>> =
        operationalStations
            .map { it to Geo.distanceKm(lat, lon, it.lat, it.lon) }
            .sortedBy { it.second }
            .take(count)

    companion object {
        @Volatile
        private var cached: MetroData? = null

        fun get(context: Context): MetroData =
            cached ?: synchronized(this) {
                cached ?: load(context).also { cached = it }
            }

        private fun parseColor(hex: String): Long = 0xFF000000L or hex.removePrefix("#").toLong(16)

        private fun load(context: Context): MetroData {
            val lines = mutableListOf<MetroLine>()
            val stations = linkedMapOf<String, Station>()
            val walks = mutableMapOf<String, MutableList<Pair<String, Int>>>()
            val connectors = mutableListOf<Connector>()

            var header: List<String>? = null
            var names = mutableListOf<String>()
            var kms = mutableListOf<Double>()

            fun flush() {
                val h = header ?: return
                val upcoming = h[4] == "up"
                lines += MetroLine(
                    id = h[1], name = h[2], color = parseColor(h[3]), upcoming = upcoming,
                    note = h.getOrElse(5) { "" }, stations = names.toList(),
                    km = if (upcoming) emptyList() else kms.toList(),
                )
                names.forEach { n ->
                    val st = stations.getValue(n)
                    if (upcoming) st.upcomingLines += h[1] else st.lines += h[1]
                }
                names = mutableListOf()
                kms = mutableListOf()
            }

            context.assets.open("metro_data.txt").bufferedReader().useLines { seq ->
                seq.forEach { raw ->
                    if (raw.isBlank() || raw.startsWith("#")) return@forEach
                    val p = raw.split("|")
                    when (p[0]) {
                        "L" -> {
                            flush(); header = p
                        }
                        "S" -> {
                            val name = p[1]
                            if (name !in stations) {
                                stations[name] = Station(name, p[2].toDouble(), p[3].toDouble(), p.getOrElse(5) { "" })
                            }
                            names += name
                            kms += p.getOrElse(4) { "" }.toDoubleOrNull() ?: 0.0
                        }
                        "C" -> connectors += Connector(p[1], p[2], p[3], p[4].toDouble())
                        "W" -> {
                            val m = p[3].toInt()
                            walks.getOrPut(p[1]) { mutableListOf() } += p[2] to m
                            walks.getOrPut(p[2]) { mutableListOf() } += p[1] to m
                        }
                    }
                }
            }
            flush()
            return MetroData(lines, stations, walks, connectors)
        }
    }
}

object Geo {
    fun distanceKm(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val p = Math.PI / 180
        val h = Math.sin((la2 - la1) * p / 2).let { it * it } +
            Math.cos(la1 * p) * Math.cos(la2 * p) * Math.sin((lo2 - lo1) * p / 2).let { it * it }
        return 2 * 6371 * Math.asin(Math.sqrt(h))
    }
}
