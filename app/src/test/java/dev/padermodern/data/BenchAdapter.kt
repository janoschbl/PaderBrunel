package dev.padermodern.data

/** The parts of the app the benchmark calls. Older app versions get their own copy of this file. */
object BenchAdapter {
    const val ARRIVAL = true
    fun routes(plan: BPlan, from: String, to: String): List<Route> = BackendApi.toRoutes(plan, null, from, to)
    fun rank(routes: List<Route>, window: SearchWindow, transfer: Int, variety: Boolean): List<Route> =
        TripSearch.rank(routes, window, TripPrefs(minTransferMin = transfer, maxChanges = 2, variety = variety))
}
