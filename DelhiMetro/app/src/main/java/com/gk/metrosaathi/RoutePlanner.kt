package com.gk.metrosaathi

import java.util.PriorityQueue

sealed class Leg {
    /** Ride on [line] from stations[0] to stations.last(), heading towards [towards]. */
    data class Ride(val line: MetroLine, val stations: List<String>, val towards: String, val km: Double, val minutes: Double) : Leg()

    /** Walk between two differently named stations. */
    data class Walk(val from: String, val to: String, val minutes: Int) : Leg()
}

data class Journey(
    val legs: List<Leg>,
    val minutes: Int,
    val km: Double,
    /** Km travelled on DMRC-fare lines (excludes Airport Express, Aqua, Rapid). */
    val dmrcKm: Double,
    val separateFareLines: List<MetroLine>,
) {
    val rides get() = legs.filterIsInstance<Leg.Ride>()
    val interchanges get() = (legs.size - 1).coerceAtLeast(0)
    val stationCount get() = rides.sumOf { it.stations.size - 1 }
    val from get() = rides.first().stations.first()
    val to get() = rides.last().stations.last()
}

class RoutePlanner(private val data: MetroData) {

    private companion object {
        const val KMH = 33.0             // average running speed incl. acceleration
        const val DWELL_MIN = 0.5        // stop at each station
        const val CHANGE_MIN = 5.0       // walk between platforms + wait
        const val WAIT_START_MIN = 3.0   // average first wait
        val SEPARATE_FARE = setOf("AIRPORT", "AQUA", "RAPID")
    }

    private data class State(val station: String, val line: String)

    private sealed class Edge(val to: State, val cost: Double) {
        class RideEdge(to: State, cost: Double, val km: Double) : Edge(to, cost)
        class ChangeEdge(to: State, cost: Double) : Edge(to, cost)
        class WalkEdge(to: State, cost: Double, val minutes: Int) : Edge(to, cost)
    }

    private val graph: Map<State, List<Edge>> = buildGraph()

    private fun buildGraph(): Map<State, List<Edge>> {
        val g = mutableMapOf<State, MutableList<Edge>>()
        fun add(a: State, e: Edge) = g.getOrPut(a) { mutableListOf() }.add(e)

        for (line in data.operationalLines) {
            for (i in 0 until line.stations.size - 1) {
                val a = State(line.stations[i], line.id)
                val b = State(line.stations[i + 1], line.id)
                val km = (line.km[i + 1] - line.km[i]).coerceAtLeast(0.3)
                val t = km / KMH * 60 + DWELL_MIN
                add(a, Edge.RideEdge(b, t, km))
                add(b, Edge.RideEdge(a, t, km))
            }
        }
        for (c in data.connectors) {
            val t = c.km / KMH * 60 + DWELL_MIN
            add(State(c.a, c.line), Edge.RideEdge(State(c.b, c.line), t, c.km))
            add(State(c.b, c.line), Edge.RideEdge(State(c.a, c.line), t, c.km))
        }
        for (st in data.stations.values) {
            for (l1 in st.lines) for (l2 in st.lines) {
                if (l1 != l2) add(State(st.name, l1), Edge.ChangeEdge(State(st.name, l2), CHANGE_MIN))
            }
        }
        for ((a, list) in data.walks) {
            val sa = data.stations[a] ?: continue
            for ((b, minutes) in list) {
                val sb = data.stations[b] ?: continue
                for (la in sa.lines) for (lb in sb.lines) {
                    add(State(a, la), Edge.WalkEdge(State(b, lb), minutes + CHANGE_MIN, minutes))
                }
            }
        }
        return g
    }

    /**
     * @param changePenalty extra cost per interchange (0 = fastest, large = fewest changes)
     */
    fun plan(from: String, to: String, changePenalty: Double = 0.0): Journey? {
        val start = data.stations[from] ?: return null
        val end = data.stations[to] ?: return null
        if (from == to) return null

        val dist = HashMap<State, Double>()
        val prev = HashMap<State, Pair<State, Edge>>()
        val pq = PriorityQueue<Pair<State, Double>>(compareBy { it.second })
        for (l in start.lines) {
            val s = State(from, l)
            dist[s] = 0.0
            pq += s to 0.0
        }
        var goal: State? = null
        while (pq.isNotEmpty()) {
            val (s, d) = pq.poll()!!
            if (d > (dist[s] ?: Double.MAX_VALUE)) continue
            if (s.station == to) {
                goal = s; break
            }
            for (e in graph[s].orEmpty()) {
                val extra = if (e is Edge.RideEdge) 0.0 else changePenalty
                val nd = d + e.cost + extra
                if (nd < (dist[e.to] ?: Double.MAX_VALUE)) {
                    dist[e.to] = nd
                    prev[e.to] = s to e
                    pq += e.to to nd
                }
            }
        }
        val g = goal ?: return null
        if (end.lines.isEmpty()) return null

        // Reconstruct edge list.
        val path = mutableListOf<Pair<State, Edge>>()
        var cur = g
        while (true) {
            val p = prev[cur] ?: break
            path += p
            cur = p.first
        }
        path.reverse()

        val legs = mutableListOf<Leg>()
        var rideStations = mutableListOf<String>()
        var rideLine: String? = null
        var rideKm = 0.0
        var rideMin = 0.0
        var totalMin = WAIT_START_MIN
        var totalKm = 0.0
        var dmrcKm = 0.0
        val separate = linkedSetOf<String>()

        fun closeRide() {
            val lid = rideLine ?: return
            if (rideStations.size >= 2) {
                val line = data.lineById.getValue(lid)
                val i0 = line.stations.indexOf(rideStations[0])
                val i1 = line.stations.indexOf(rideStations[1])
                val towards = when (i1 - i0) {
                    1 -> line.last
                    -1 -> line.first
                    else -> rideStations.last() // ring connector: name the destination instead
                }
                legs += Leg.Ride(line, rideStations.toList(), towards, rideKm, rideMin)
            }
            rideStations = mutableListOf()
            rideLine = null
            rideKm = 0.0
            rideMin = 0.0
        }

        for ((s, e) in path) {
            when (e) {
                is Edge.RideEdge -> {
                    if (rideLine != s.line) {
                        closeRide()
                        rideLine = s.line
                        rideStations += s.station
                    }
                    rideStations += e.to.station
                    rideKm += e.km
                    rideMin += e.cost
                    totalKm += e.km
                    totalMin += e.cost
                    if (s.line in SEPARATE_FARE) separate += s.line else dmrcKm += e.km
                }
                is Edge.ChangeEdge -> {
                    closeRide()
                    totalMin += e.cost
                }
                is Edge.WalkEdge -> {
                    closeRide()
                    legs += Leg.Walk(s.station, e.to.station, e.minutes)
                    totalMin += e.cost
                }
            }
        }
        closeRide()
        if (legs.none { it is Leg.Ride }) return null
        return Journey(
            legs = legs,
            minutes = Math.round(totalMin).toInt(),
            km = totalKm,
            dmrcKm = dmrcKm,
            separateFareLines = separate.map { data.lineById.getValue(it) },
        )
    }

    /** Fastest route, plus a "fewer changes" alternative when it actually differs. */
    fun options(from: String, to: String): List<Journey> {
        val fast = plan(from, to) ?: return emptyList()
        val fewer = plan(from, to, changePenalty = 20.0)
        return if (fewer != null && fewer.interchanges < fast.interchanges) listOf(fast, fewer) else listOf(fast)
    }
}

/** DMRC fare slabs (revised 25 Aug 2025). Approximate: real fares use DMRC's own distance table. */
object Fare {
    private val limits = doubleArrayOf(2.0, 5.0, 12.0, 21.0, 32.0)
    private val normal = intArrayOf(11, 21, 32, 43, 54, 64)
    private val sunday = intArrayOf(11, 11, 21, 32, 43, 54)

    private fun slab(km: Double): Int = limits.indexOfFirst { km <= it }.let { if (it < 0) limits.size else it }

    fun token(km: Double) = normal[slab(km)]
    fun sundayHoliday(km: Double) = sunday[slab(km)]
    fun smartCard(km: Double) = Math.round(token(km) * 0.9).toInt()

    val chart: List<Triple<String, Int, Int>> = listOf(
        Triple("0 – 2 km", normal[0], sunday[0]),
        Triple("2 – 5 km", normal[1], sunday[1]),
        Triple("5 – 12 km", normal[2], sunday[2]),
        Triple("12 – 21 km", normal[3], sunday[3]),
        Triple("21 – 32 km", normal[4], sunday[4]),
        Triple("32+ km", normal[5], sunday[5]),
    )
}
