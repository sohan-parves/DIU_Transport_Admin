package com.sohan.diutransportadmin

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.MutableState
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * MapManager
 *
 * Route map er sob logic ekhane thakbe:
 *  - Stop / Road point parse kora
 *  - Separated stop + road map Firestore-e save kora
 *  - Route map version bump kora
 *  - Auto road polyline generate kora (snap-to-road via OSRM)
 */
class MapManager(
    private val context: Context,
    private val db: FirebaseFirestore,
    private val adminAuth: AdminAuthManager,
    private val status: MutableState<String>,
    private val isLoading: MutableState<Boolean>,
    private val progressPercent: MutableState<Int>,
    private val progressLabel: MutableState<String>,
    private val isGeneratingRoadPolyline: MutableState<Boolean>,
    private val scope: CoroutineScope
) {

    // -------------------------------------------------------------------
    // Route map document reference helper
    // -------------------------------------------------------------------

    private fun routeMapDoc(routeNoRaw: String) = db.collection("route_maps")
        .document("current")
        .collection("routes")
        .document(normalizedRouteKey(routeNoRaw))

    private fun normalizedRouteKey(routeNoRaw: String): String =
        routeNoRaw.trim().uppercase(java.util.Locale.getDefault())

    // -------------------------------------------------------------------
    // Parse helpers (used by AdminScreen callbacks)
    // -------------------------------------------------------------------

    fun parseStopsOnly(raw: String): MutableList<Pair<String, GeoPoint>> =
        raw.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split("|").map { it.trim() }
                if (parts.size < 2) return@mapNotNull null
                val name   = parts[0]
                val latLng = parts[1].split(",").map { it.trim() }
                if (latLng.size < 2) return@mapNotNull null
                val lat = latLng[0].toDoubleOrNull() ?: return@mapNotNull null
                val lng = latLng[1].toDoubleOrNull() ?: return@mapNotNull null
                if (name.isBlank()) return@mapNotNull null
                name to GeoPoint(lat, lng)
            }.toMutableList()

    fun parseRoadOnly(raw: String): MutableList<GeoPoint> =
        raw.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split(",").map { it.trim() }
                if (parts.size < 2) return@mapNotNull null
                val lat = parts[0].toDoubleOrNull() ?: return@mapNotNull null
                val lng = parts[1].toDoubleOrNull() ?: return@mapNotNull null
                GeoPoint(lat, lng)
            }.toMutableList()

    fun parseManualStopsOnly(raw: String): List<Map<String, Any>> =
        raw.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapIndexedNotNull { index, line ->
                val parts  = line.split("|").map { it.trim() }
                if (parts.size < 2) return@mapIndexedNotNull null
                val name   = parts[0]
                val latLng = parts[1].split(",").map { it.trim() }
                if (latLng.size < 2) return@mapIndexedNotNull null
                val lat = latLng[0].toDoubleOrNull() ?: return@mapIndexedNotNull null
                val lng = latLng[1].toDoubleOrNull() ?: return@mapIndexedNotNull null
                if (name.isBlank()) return@mapIndexedNotNull null
                mapOf("index" to index, "name" to name, "lat" to lat, "lng" to lng)
            }

    fun parseManualRoadOnly(raw: String): List<GeoPoint> =
        raw.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split(",").map { it.trim() }
                if (parts.size < 2) return@mapNotNull null
                val lat = parts[0].toDoubleOrNull() ?: return@mapNotNull null
                val lng = parts[1].toDoubleOrNull() ?: return@mapNotNull null
                GeoPoint(lat, lng)
            }

    // -------------------------------------------------------------------
    // Save separated stop + road map to Firestore
    // -------------------------------------------------------------------

    fun saveSeparatedRouteMapData(
        routeNoRaw: String,
        stopsRaw: String,
        roadRaw: String
    ) {
        val routeNo     = normalizedRouteKey(routeNoRaw)
        val parsedStops = parseManualStopsOnly(stopsRaw)
        val roadPoints  = parseManualRoadOnly(roadRaw)

        if (routeNo.isBlank()) {
            status.value = "Route No blank – save cancelled"
            return
        }

        isLoading.value       = true
        progressPercent.value = 10
        progressLabel.value   = "Preparing separated route map data..."
        status.value          = "Preparing raw separated route map data for $routeNo..."

        val rawRoadPolyline = roadPoints.map { p ->
            mapOf("lat" to p.latitude, "lng" to p.longitude)
        }
        val stopNames      = parsedStops.map { (it["name"] as? String).orEmpty() }
        val stopNamesCount = stopNames.size

        progressLabel.value = "Uploading raw stop map + road map..."

        val mapDoc  = routeMapDoc(routeNo)
        val payload = mapOf(
            "routeNo"                  to routeNo,
            "routeStops"               to parsedStops,
            "routeStopsCount"          to parsedStops.size,
            "routeStopNames"           to stopNames,
            "routeStopNamesCount"      to stopNamesCount,
            "routeRoadPolyline"        to rawRoadPolyline,
            "routeRoadPolylineCount"   to rawRoadPolyline.size,
            "mapDataSource"            to "raw_admin_editor",
            "mapDataFormat"            to "separated_stop_and_raw_polyline",
            "mapDataGenerator"         to "admin_auto_snapped_polyline",
            "mapDataNote"              to "Stop markers, stop names, stop-name count, and raw road polyline were saved directly from admin editor.",
            "updatedAt"                to FieldValue.serverTimestamp()
        )

        mapDoc.set(payload, SetOptions.merge())
            .addOnSuccessListener {
                bumpRouteMapVersion(
                    message = "Separated route map updated from Admin",
                    extra   = mapOf("routeNo" to routeNo)
                ) {
                    isLoading.value       = false
                    progressPercent.value = 0
                    progressLabel.value   = ""
                    status.value = "DONE ✅ Saved raw separated stop map + road map for $routeNo " +
                            "(${parsedStops.size} stops, $stopNamesCount stop names, ${rawRoadPolyline.size} road points)"
                    Toast.makeText(context, "Saved separated stop map + road map for $routeNo", Toast.LENGTH_LONG).show()
                }
            }
            .addOnFailureListener { e ->
                isLoading.value       = false
                progressPercent.value = 0
                progressLabel.value   = ""
                val msg = FirestoreWriteHints.fromException(e)
                status.value = "FAILED ❌ $msg"
                Toast.makeText(context, "FAILED ❌ $msg", Toast.LENGTH_LONG).show()
            }
    }

    // -------------------------------------------------------------------
    // Auto generate road polyline (OSRM snap-to-road)
    // -------------------------------------------------------------------

    fun autoGenerateRoadPolyline(
        routeNoRaw: String,
        stopsRaw: String,
        roadRaw: String,
        onResult: (snappedRoadText: String) -> Unit
    ) {
        val routeNo    = normalizedRouteKey(routeNoRaw)
        val roadPoints = parseManualRoadOnly(roadRaw)
        val stopPoints = parseManualStopsOnly(stopsRaw)
            .mapNotNull {
                val lat = it["lat"] as? Double ?: return@mapNotNull null
                val lng = it["lng"] as? Double ?: return@mapNotNull null
                GeoPoint(lat, lng)
            }

        val anchorPoints = roadPoints.ifEmpty { stopPoints }
        if (anchorPoints.size < 2) {
            status.value = "At least 2 anchor points needed to generate road polyline"
            return
        }

        isGeneratingRoadPolyline.value = true
        status.value                   = "Generating snapped road polyline for $routeNo..."

        scope.launch {
            try {
                val snapped = snapPolylineToRoad(anchorPoints)
                val resultText = snapped.joinToString("\n") { "${it.latitude},${it.longitude}" }
                isGeneratingRoadPolyline.value = false
                status.value = "Road polyline generated: ${snapped.size} points"
                onResult(resultText)
            } catch (e: Exception) {
                isGeneratingRoadPolyline.value = false
                status.value = "Road polyline generation failed: ${e.message}"
                Toast.makeText(context, "Road polyline failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // -------------------------------------------------------------------
    // Route map version bump
    // -------------------------------------------------------------------

    fun bumpRouteMapVersion(
        message: String = "Route map updated from Admin",
        extra: Map<String, Any> = emptyMap(),
        onDone: (() -> Unit)? = null
    ) {
        adminAuth.ensureAdminAuth {
            val routeMapMetaRef = db.collection("meta").document("route_maps")
            val payload = hashMapOf<String, Any>(
                "version"   to FieldValue.increment(1),
                "message"   to message,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            payload.putAll(extra)
            routeMapMetaRef
                .set(payload, SetOptions.merge())
                .addOnSuccessListener { onDone?.invoke() }
                .addOnFailureListener { onDone?.invoke() }
        }
    }

    // -------------------------------------------------------------------
    // OSRM snap-to-road (runs on IO dispatcher via scope.launch)
    // -------------------------------------------------------------------

    private suspend fun snapPolylineToRoad(points: List<GeoPoint>): List<GeoPoint> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            fun openJson(url: String): JSONObject? = try {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 8_000
                conn.readTimeout    = 15_000
                conn.setRequestProperty("User-Agent", "DIUTransportAdmin/1.0")
                if (conn.responseCode == 200)
                    JSONObject(conn.inputStream.bufferedReader().readText())
                else null
            } catch (_: Exception) { null }

            fun decodeCoords(coords: org.json.JSONArray): List<GeoPoint> {
                val out = mutableListOf<GeoPoint>()
                for (i in 0 until coords.length()) {
                    val pt = coords.getJSONArray(i)
                    out.add(GeoPoint(pt.getDouble(1), pt.getDouble(0)))
                }
                return out
            }

            fun routeSegment(a: GeoPoint, b: GeoPoint): List<GeoPoint> {
                val coords = "${a.longitude},${a.latitude};${b.longitude},${b.latitude}"
                val url    = "https://router.project-osrm.org/route/v1/driving/$coords?overview=full&geometries=geojson"
                val json   = openJson(url) ?: return listOf(a, b)
                return try {
                    val route  = json.getJSONArray("routes").getJSONObject(0)
                    val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
                    decodeCoords(coords).ifEmpty { listOf(a, b) }
                } catch (_: Exception) { listOf(a, b) }
            }

            val result = mutableListOf<GeoPoint>()
            for (i in 0 until points.size - 1) {
                val seg = routeSegment(points[i], points[i + 1])
                if (result.isEmpty()) result.addAll(seg)
                else result.addAll(seg.drop(1))
            }
            result
        }
}