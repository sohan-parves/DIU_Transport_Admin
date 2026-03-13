package com.sohan.diutransportadmin
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalView


import android.location.Geocoder
import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.shape.RoundedCornerShape
import android.os.Build
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import android.net.Uri as AndroidUri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

import android.os.Bundle
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.font.FontWeight
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.Timestamp
import java.util.concurrent.TimeUnit
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import android.app.DatePickerDialog
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import android.graphics.Color as AndroidColor
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import androidx.compose.ui.window.DialogProperties
import org.json.JSONObject
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.runtime.mutableIntStateOf
// (keep only one of each)
// (imports cleaned up below)
// Remove duplicate imports

// Premium dark theme for the whole Admin app (forced)
private val AdminDarkColorScheme = darkColorScheme(
    primary = Color(0xFF00C853),        // premium green
    onPrimary = Color.White,
    secondary = Color(0xFF00BFA5),
    onSecondary = Color.White,
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF0F1623),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF162033),
    onSurfaceVariant = Color(0xFFB8C4D0),
    outline = Color(0xFF2A3A55)
)

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private fun normalizedRouteKey(routeNoRaw: String): String {
        return routeNoRaw.trim().uppercase(Locale.getDefault())
    }

    private fun routeMapDoc(routeNoRaw: String) = db.collection("route_maps")
        .document("current")
        .collection("routes")
        .document(normalizedRouteKey(routeNoRaw))

    private fun getDisplayName(uri: Uri): String {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) {
                    c.getString(idx) ?: (uri.lastPathSegment ?: "selected")
                } else {
                    uri.lastPathSegment ?: "selected"
                }
            } ?: (uri.lastPathSegment ?: "selected")
        } catch (_: Exception) {
            uri.lastPathSegment ?: "selected"
        }
    }
    // Accept route numbers like R, F, R5, F1, etc. Reject headers/junk.
    private fun isValidRouteNo(routeNoRaw: String): Boolean {
        val rn = routeNoRaw.trim()
        if (rn.isBlank()) return false

        // Reject section headers / junk
        if (rn.contains("schedule", ignoreCase = true)) return false
        if (rn.contains("@")) return false

        // Accept: R or F alone, or R/F followed by digits (R5, R15, F1, ...)
        return Regex("^[RF](\\d+)?$", RegexOption.IGNORE_CASE).matches(rn)
    }


    private fun splitRouteDetails(detailsRaw: String): List<String> {
        val normalized = detailsRaw
            .replace("\n", " ")
            .replace("->", "<>")
            .replace("=>", "<>")
            .replace("＞", ">")
            .replace("–", "-")

        return normalized
            .split(Regex("\\s*<>\\s*|\\s*>\\s*"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun normalizePlaceToken(raw: String): String {
        return raw
            .trim()
            .lowercase(Locale.getDefault())
            .replace("dsc", "daffodil smart city")
            .replace("diu", "daffodil international university")
            .replace(Regex("[^a-z0-9\u0980-\u09ff ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun stopAliasQueries(stopNameRaw: String): List<String> {
        val key = normalizePlaceToken(stopNameRaw)
        return when (key) {
            "gudaraghat" -> listOf(
                "Gudaraghat, Beribadh, Dhaka, Bangladesh",
                "Gudaraghat Road, Beribadh, Dhaka, Bangladesh",
                "Gudaraghat, Mohammadpur, Dhaka, Bangladesh"
            )
            "beribadh" -> listOf(
                "Beribadh, Mohammadpur, Dhaka, Bangladesh",
                "Beribadh, Dhaka, Bangladesh"
            )
            "eastern housing" -> listOf(
                "Eastern Housing, Mirpur, Dhaka, Bangladesh",
                "Eastern Housing, Beribadh, Dhaka, Bangladesh"
            )
            "birulia" -> listOf(
                "Birulia, Savar, Dhaka, Bangladesh",
                "Birulia Bus Stand, Savar, Dhaka, Bangladesh"
            )
            "akran" -> listOf(
                "Akran, Birulia, Savar, Dhaka, Bangladesh",
                "Akran Bazar, Savar, Dhaka, Bangladesh"
            )
            else -> emptyList()
        }
    }

    private fun exactStopCoordinateOverride(stopNameRaw: String): Pair<Double, Double>? {
        return when (normalizePlaceToken(stopNameRaw)) {
            // Mirpur / Beribadh side Gudaraghat
            "gudaraghat" -> 23.8248157 to 90.3437695

            // Beribadh side reference point used for this route chain
            "beribadh" -> 23.8314338 to 90.4148441

            else -> null
        }
    }

    private fun filterLikelyRouteStops(routeNameRaw: String, stopNames: List<String>): List<String> {
        if (stopNames.isEmpty()) return emptyList()

        val cleaned = stopNames
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot {
                val n = normalizePlaceToken(it)
                n.length < 3 ||
                    n == "only" ||
                    n == "students" ||
                    n == "bus" ||
                    n == "stop" ||
                    n == "road"
            }
            .distinct()
            .toMutableList()

        if (cleaned.isEmpty()) return emptyList()

        val routeNameParts = splitRouteDetails(routeNameRaw)
            .map { normalizePlaceToken(it) }
            .filter { it.isNotBlank() }

        if (routeNameParts.isEmpty()) return cleaned

        fun indexOfAnchor(anchor: String): Int {
            return cleaned.indexOfFirst { stop ->
                val n = normalizePlaceToken(stop)
                n == anchor || n.contains(anchor) || anchor.contains(n)
            }
        }

        val anchorIndexes = routeNameParts.mapNotNull { anchor ->
            indexOfAnchor(anchor).takeIf { it >= 0 }
        }.sorted()

        if (anchorIndexes.size >= 2) {
            val start = anchorIndexes.first()
            val end = anchorIndexes.last()
            return cleaned.subList(start, end + 1).distinct()
        }

        return cleaned
    }

    private suspend fun geocodeStop(stopNameRaw: String): Pair<Double, Double>? {
        val stopName = stopNameRaw.trim()
        if (stopName.isBlank()) return null
        exactStopCoordinateOverride(stopName)?.let { return it }

        val geocoder = Geocoder(this@MainActivity, Locale.getDefault())
        val queries = (
            stopAliasQueries(stopName) + listOf(
                "$stopName, Dhaka, Bangladesh",
                "$stopName, Mirpur, Dhaka, Bangladesh",
                "$stopName, Savar, Dhaka, Bangladesh",
                "$stopName, Ashulia, Dhaka, Bangladesh",
                "$stopName, Uttara, Dhaka, Bangladesh",
                "$stopName, Mohammadpur, Dhaka, Bangladesh",
                "$stopName, Beribadh, Dhaka, Bangladesh",
                "$stopName, Bangladesh",
                stopName
            )
        ).distinct()

        for (query in queries) {
            val result = try {
                withContext(Dispatchers.IO) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        var latLng: Pair<Double, Double>? = null
                        val latch = java.util.concurrent.CountDownLatch(1)
                        geocoder.getFromLocationName(query, 1) { addresses ->
                            val a = addresses.firstOrNull()
                            if (a != null) {
                                latLng = a.latitude to a.longitude
                            }
                            latch.countDown()
                        }
                        latch.await(2500, java.util.concurrent.TimeUnit.MILLISECONDS)
                        latLng
                    } else {
                        @Suppress("DEPRECATION")
                        geocoder.getFromLocationName(query, 1)
                            ?.firstOrNull()
                            ?.let { it.latitude to it.longitude }
                    }
                }
            } catch (_: Exception) {
                null
            }

            if (result != null) return result
        }

        return null
    }

    private suspend fun geocodeStopNear(
        stopNameRaw: String,
        nearLat: Double,
        nearLng: Double,
        radiusDeg: Double = 0.28
    ): Pair<Double, Double>? {
        val stopName = stopNameRaw.trim()
        if (stopName.isBlank()) return null
        exactStopCoordinateOverride(stopName)?.let { return it }

        val geocoder = Geocoder(this@MainActivity, Locale.getDefault())
        val lowerLeftLat = nearLat - radiusDeg
        val lowerLeftLng = nearLng - radiusDeg
        val upperRightLat = nearLat + radiusDeg
        val upperRightLng = nearLng + radiusDeg

        val queries = (
            stopAliasQueries(stopName) + listOf(
                "$stopName, Dhaka, Bangladesh",
                "$stopName, Savar, Dhaka, Bangladesh",
                "$stopName, Ashulia, Dhaka, Bangladesh",
                "$stopName, Uttara, Dhaka, Bangladesh",
                "$stopName, Mirpur, Dhaka, Bangladesh",
                "$stopName, Bangladesh",
                stopName
            )
        ).distinct()

        for (query in queries) {
            val result = try {
                withContext(Dispatchers.IO) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        var latLng: Pair<Double, Double>? = null
                        val latch = java.util.concurrent.CountDownLatch(1)
                        geocoder.getFromLocationName(
                            query,
                            1,
                            lowerLeftLat,
                            lowerLeftLng,
                            upperRightLat,
                            upperRightLng
                        ) { addresses ->
                            val a = addresses.firstOrNull()
                            if (a != null) {
                                latLng = a.latitude to a.longitude
                            }
                            latch.countDown()
                        }
                        latch.await(1800, java.util.concurrent.TimeUnit.MILLISECONDS)
                        latLng
                    } else {
                        @Suppress("DEPRECATION")
                        geocoder.getFromLocationName(
                            query,
                            1,
                            lowerLeftLat,
                            lowerLeftLng,
                            upperRightLat,
                            upperRightLng
                        )
                            ?.firstOrNull()
                            ?.let { it.latitude to it.longitude }
                    }
                }
            } catch (_: Exception) {
                null
            }

            if (result != null) return result
        }

        return null
    }

    private suspend fun geocodeStopNearWithContext(
        stopNameRaw: String,
        nearLat: Double,
        nearLng: Double,
        previousStopNameRaw: String?,
        nextStopNameRaw: String?,
        routeNameRaw: String,
        radiusDeg: Double = 0.32
    ): Pair<Double, Double>? {
        val stopName = stopNameRaw.trim()
        if (stopName.isBlank()) return null
        exactStopCoordinateOverride(stopName)?.let { return it }

        val previousStop = previousStopNameRaw.orEmpty().trim()
        val nextStop = nextStopNameRaw.orEmpty().trim()
        val routeName = routeNameRaw.trim()

        val geocoder = Geocoder(this@MainActivity, Locale.getDefault())
        val lowerLeftLat = nearLat - radiusDeg
        val lowerLeftLng = nearLng - radiusDeg
        val upperRightLat = nearLat + radiusDeg
        val upperRightLng = nearLng + radiusDeg

        val contextualQueries = (
            stopAliasQueries(stopName) + buildList {
                if (previousStop.isNotBlank()) add("$stopName near $previousStop, Dhaka, Bangladesh")
                if (nextStop.isNotBlank()) add("$stopName near $nextStop, Dhaka, Bangladesh")
                if (previousStop.isNotBlank() && nextStop.isNotBlank()) {
                    add("$stopName between $previousStop and $nextStop, Dhaka, Bangladesh")
                    add("$stopName $previousStop $nextStop Dhaka Bangladesh")
                }
                if (routeName.isNotBlank()) add("$stopName on $routeName, Dhaka, Bangladesh")
                add("$stopName, Dhaka, Bangladesh")
                add("$stopName, Savar, Dhaka, Bangladesh")
                add("$stopName, Ashulia, Dhaka, Bangladesh")
                add("$stopName, Mirpur, Dhaka, Bangladesh")
                add("$stopName, Bangladesh")
                add(stopName)
            }
        ).distinct()

        for (query in contextualQueries) {
            val result = try {
                withContext(Dispatchers.IO) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        var latLng: Pair<Double, Double>? = null
                        val latch = java.util.concurrent.CountDownLatch(1)
                        geocoder.getFromLocationName(
                            query,
                            3,
                            lowerLeftLat,
                            lowerLeftLng,
                            upperRightLat,
                            upperRightLng
                        ) { addresses ->
                            val a = addresses.firstOrNull()
                            if (a != null) {
                                latLng = a.latitude to a.longitude
                            }
                            latch.countDown()
                        }
                        latch.await(1800, java.util.concurrent.TimeUnit.MILLISECONDS)
                        latLng
                    } else {
                        @Suppress("DEPRECATION")
                        geocoder.getFromLocationName(
                            query,
                            3,
                            lowerLeftLat,
                            lowerLeftLng,
                            upperRightLat,
                            upperRightLng
                        )
                            ?.firstOrNull()
                            ?.let { it.latitude to it.longitude }
                    }
                }
            } catch (_: Exception) {
                null
            }

            if (result != null) return result
        }

        return null
    }

    private suspend fun enrichRoutesWithMapData(
        items: List<Map<String, Any>>,
        onProgress: (done: Int, total: Int, label: String) -> Unit
    ): List<Map<String, Any>> {
        val cache = linkedMapOf<String, Pair<Double, Double>?>()
        val allStops = items.flatMap { item ->
            splitRouteDetails((item["routeDetails"] as? String).orEmpty().trim())
        }
        val totalStops = allStops.size.coerceAtLeast(1)
        var processedStops = 0

        suspend fun cachedGeocode(stop: String): Pair<Double, Double>? {
            val value = if (cache.containsKey(stop)) {
                cache[stop]
            } else {
                val fresh = geocodeStop(stop)
                cache[stop] = fresh
                fresh
            }
            processedStops += 1
            onProgress(processedStops, totalStops, stop)
            return value
        }

        return items.map { item ->
            val routeDetails = (item["routeDetails"] as? String).orEmpty().trim()
            if (routeDetails.isBlank()) return@map item

            val routeName = (item["routeName"] as? String).orEmpty().trim()
            val stopNames = filterLikelyRouteStops(routeName, splitRouteDetails(routeDetails))
            val routeStops = mutableListOf<Map<String, Any>>()
            // IMPORTANT:
            // Do NOT auto-build routePolyline from stop geocoding.
            // That creates misleading straight/cut lines and is not 100% accurate.
            // Exact road highlighting must come from a verified road-geometry source.
            val routePolyline = mutableListOf<Map<String, Any>>()

            stopNames.forEachIndexed { index, stopName ->
                val previous = routeStops.lastOrNull()
                val prevLat = previous?.get("lat") as? Double
                val prevLng = previous?.get("lng") as? Double
                val previousStopName = previous?.get("name") as? String
                val nextStopName = stopNames.getOrNull(index + 1)

                val latLng = if (prevLat != null && prevLng != null) {
                    // Prefer a same-name match that stays close to the already matched route chain
                    // and also uses previous/next stop context for ambiguous names.
                    geocodeStopNearWithContext(
                        stopNameRaw = stopName,
                        nearLat = prevLat,
                        nearLng = prevLng,
                        previousStopNameRaw = previousStopName,
                        nextStopNameRaw = nextStopName,
                        routeNameRaw = routeName
                    ) ?: geocodeStopNear(stopName, prevLat, prevLng) ?: cachedGeocode(stopName)
                } else {
                    cachedGeocode(stopName)
                }

                if (latLng != null && prevLat != null && prevLng != null) {
                    val results = FloatArray(1)
                    android.location.Location.distanceBetween(
                        prevLat,
                        prevLng,
                        latLng.first,
                        latLng.second,
                        results
                    )
                    val km = results.firstOrNull()?.div(1000f) ?: 0f
                    if (km > 26f) {
                        // Likely outside/geocoder mismatch for this route chain; skip it.
                        return@forEachIndexed
                    }
                }

                if (latLng != null) {
                    val (lat, lng) = latLng
                    routeStops.add(
                        mapOf(
                            "name" to stopName,
                            "seq" to index,
                            "lat" to lat,
                            "lng" to lng
                        )
                    )
                }
            }

            item.toMutableMap().apply {
                put("routeStops", routeStops)
                put("routePolyline", routePolyline)
                put("routeStopNames", stopNames)
                put("routeStopsCount", routeStops.size)
                put("routePolylineCount", 0)
                put(
                    "mapDataNote",
                    "routeStops are approximate. routePolyline is intentionally left empty until exact verified road geometry is provided."
                )
            }
        }
    }



    // ==============================
// 🔧 FIRESTORE TARGET (DEV vs PROD)
// ==============================
// ✅ Emulator test করতে চাইলে: true রাখো
// 🚀 Real publish (Production) করতে চাইলে: false করে দাও
    private val USE_EMULATOR = false  // DEV: set false before real production publish

    // Real phone test করলে (same Wi-Fi) তোমার Mac/PC IP
    private val EMULATOR_HOST = "192.168.0.105"

    private val EMULATOR_FIRESTORE_PORT = 8080



    private val db by lazy { FirebaseFirestore.getInstance() }

    // Notice publish UI state
    private val noticeTitleState = mutableStateOf("")
    private val noticeBodyState = mutableStateOf("")
    // Optional release date (yyyy-MM-dd). If blank, defaults to today.
    private val noticeDateState = mutableStateOf("")

    // Manual route road polyline admin UI state

    private val manualRouteNoState = mutableStateOf("")
    private val stopEditorVisibleState = mutableStateOf(false)
    private val roadEditorVisibleState = mutableStateOf(false)
    private val manualRouteStopsOnlyState = mutableStateOf("")
    private val manualRouteRoadOnlyState = mutableStateOf("")
    private val selectedRoadPointIndexState = mutableIntStateOf(-1)
    private val selectedRoadInsertAfterIndexState = mutableIntStateOf(-1)
    private val isGeneratingRoadPolylineState = mutableStateOf(false)


    private fun parseManualStopsOnly(raw: String): List<Map<String, Any>> {
        return raw
            .replace("\r", "\n")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapIndexedNotNull { index, line ->
                val parts = line.split("|").map { it.trim() }
                if (parts.size < 2) return@mapIndexedNotNull null

                val name = parts[0]
                val latLng = parts[1].split(",").map { it.trim() }
                val lat = latLng.getOrNull(0)?.toDoubleOrNull()
                val lng = latLng.getOrNull(1)?.toDoubleOrNull()

                if (name.isBlank() || lat == null || lng == null) return@mapIndexedNotNull null

                mapOf(
                    "name" to name,
                    "seq" to index,
                    "lat" to lat,
                    "lng" to lng
                )
            }
            .toList()
    }

    private fun parseManualRoadOnly(raw: String): List<GeoPoint> {
        return raw
            .replace("\r", "\n")
            .replace(";", "\n")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split(",").map { it.trim() }
                val lat = parts.getOrNull(0)?.toDoubleOrNull()
                val lng = parts.getOrNull(1)?.toDoubleOrNull()
                if (lat != null && lng != null) GeoPoint(lat, lng) else null
            }
            .toList()
    }
    private fun parseStopsOnlyEditorInput(raw: String): MutableList<Pair<String, GeoPoint>> {
        return raw
            .replace("\r", "\n")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split("|").map { it.trim() }
                if (parts.size < 2) return@mapNotNull null
                val name = parts[0]
                val latLng = parts[1].split(",").map { it.trim() }
                val lat = latLng.getOrNull(0)?.toDoubleOrNull()
                val lng = latLng.getOrNull(1)?.toDoubleOrNull()
                if (name.isBlank() || lat == null || lng == null) return@mapNotNull null
                name to GeoPoint(lat, lng)
            }
            .toMutableList()
    }

    private fun parseRoadOnlyEditorInput(raw: String): MutableList<GeoPoint> {
        return raw
            .replace("\r", "\n")
            .replace(";", "\n")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split(",").map { it.trim() }
                val lat = parts.getOrNull(0)?.toDoubleOrNull()
                val lng = parts.getOrNull(1)?.toDoubleOrNull()
                if (lat != null && lng != null) GeoPoint(lat, lng) else null
            }
            .toMutableList()
    }

    private fun saveSeparatedRouteMapData(
        routeNoRaw: String,
        stopsRaw: String,
        roadRaw: String
    ) {
        val routeNo = routeNoRaw.trim()
        val parsedStops = parseManualStopsOnly(stopsRaw)
        val roadPoints = parseManualRoadOnly(roadRaw)

        if (!isValidRouteNo(routeNo)) {
            status.value = "Invalid route number"
            return
        }

        if (parsedStops.isEmpty()) {
            status.value = "Need at least 1 stop marker"
            return
        }

        if (roadPoints.size < 2) {
            status.value = "Need at least 2 road points"
            return
        }

        isLoading.value = true
        progressPercent.value = 20
        progressLabel.value = "Preparing raw stop markers and road polyline..."
        status.value = "Preparing raw separated route map data for $routeNo..."

        val rawRoadPolyline = roadPoints.map { p ->
            mapOf("lat" to p.latitude, "lng" to p.longitude)
        }

        val stopNames = parsedStops.map { (it["name"] as? String).orEmpty() }
        val stopNamesCount = stopNames.count { it.isNotBlank() }

        progressPercent.value = 80
        progressLabel.value = "Uploading raw stop map + road map..."

        val mapDoc = routeMapDoc(routeNo)
        val payload = linkedMapOf<String, Any>(
            "routeNo" to routeNo,
            "routeStops" to parsedStops,
            "routeStopsCount" to parsedStops.size,
            "routeStopNames" to stopNames,
            "routeStopNamesCount" to stopNamesCount,
            "routeRoadPolylineAnchors" to rawRoadPolyline,
            "routeRoadPolylineAnchorCount" to rawRoadPolyline.size,
            "routeRoadPolyline" to rawRoadPolyline,
            "routeRoadPolylineCount" to rawRoadPolyline.size,
            "mapDataSource" to "raw_admin_editor",
            "mapDataFormat" to "separated_stop_and_raw_polyline",
            "mapDataGenerator" to "admin_auto_snapped_polyline",
            "mapDataNote" to "Stop markers, stop names, stop-name count, and raw road polyline were saved directly from admin editor without snapping or changing the road polyline.",
            "updatedAt" to FieldValue.serverTimestamp()
        )
        mapDoc.set(payload, SetOptions.merge())
            .addOnSuccessListener {
                isLoading.value = false
                progressPercent.value = 0
                progressLabel.value = ""
                status.value =
                    "DONE ✅ Saved raw separated stop map + road map for $routeNo (${parsedStops.size} stops, ${stopNamesCount} stop names, ${rawRoadPolyline.size} road points)"

                manualRouteNoState.value = ""
                manualRouteStopsOnlyState.value = ""
                manualRouteRoadOnlyState.value = ""
                stopEditorVisibleState.value = false
                roadEditorVisibleState.value = false

                Toast.makeText(
                    this@MainActivity,
                    "Saved separated stop map + road map for $routeNo",
                    Toast.LENGTH_LONG
                ).show()
            }
            .addOnFailureListener { e ->
                isLoading.value = false
                progressPercent.value = 0
                progressLabel.value = ""
                status.value = "FAILED ❌ ${e.message}"
            }
    }


    private val pickFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val names = uris.map { uri: Uri -> getDisplayName(uri) }
            selectedFileName.value = names.joinToString(", ")
            onUploadFiles(uris)
        }
    }


    private val status = mutableStateOf("Ready")
    private val isLoading = mutableStateOf(false)
    private val selectedFileName = mutableStateOf("No file selected")
    private val lastUploadedFiles = mutableStateOf<List<String>>(emptyList())
    private val progressPercent = mutableStateOf(0)
    private val progressLabel = mutableStateOf("")
    private val processingPreviewTitle = mutableStateOf("")
    private val processingPreviewBody = mutableStateOf("")


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        // ==============================
        // ✅ Connect Admin app to Firestore Emulator (DEV only)
        // ==============================
        if (USE_EMULATOR) {
            FirebaseFirestore.getInstance().useEmulator(EMULATOR_HOST, EMULATOR_FIRESTORE_PORT)
        }

        // Keep a live view of the last uploaded files (shown in UI)
        db.collection("meta").document("app")
            .addSnapshotListener { snap, _ ->
                val arr = snap?.get("lastUploadedFiles")
                val list = when (arr) {
                    is List<*> -> arr.filterIsInstance<String>()
                    else -> emptyList()
                }
                lastUploadedFiles.value = list
            }

        setContent {
            MaterialTheme(colorScheme = AdminDarkColorScheme) {
                AdminScreen(
                    selectedFileName = selectedFileName.value,
                    lastUploadedFiles = lastUploadedFiles.value,
                    status = status.value,
                    isLoading = isLoading.value,
                    progressPercent = progressPercent.value,
                    progressLabel = progressLabel.value,
                    processingPreviewTitle = processingPreviewTitle.value,
                    processingPreviewBody = processingPreviewBody.value,
                    onPickFiles = {
                        pickFiles.launch(
                            arrayOf(
                                "text/csv",
                                "text/*",
                                "application/vnd.ms-excel",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                            )
                        )
                    },
                    onSendAdminPush = { title, body, target, incrementVersion ->
                        sendAdminPush(title = title, body = body, target = target, incrementVersion = incrementVersion)
                    },
                    onPublishNotice = { publishNoticeToUsers()
                        isLoading.value = true
                        status.value = "Publishing notice…"
                                      },
                    onCleanupNotices = { cleanupOldNotices() },
                    manualRouteNo = manualRouteNoState.value,
                    onManualRouteNoChange = { manualRouteNoState.value = it },
                    manualRouteStopsOnly = manualRouteStopsOnlyState.value,
                    onManualRouteStopsOnlyChange = { manualRouteStopsOnlyState.value = it },
                    manualRouteRoadOnly = manualRouteRoadOnlyState.value,
                    onManualRouteRoadOnlyChange = { manualRouteRoadOnlyState.value = it },
                    onOpenStopMapEditor = { stopEditorVisibleState.value = true },
                    onOpenRoadMapEditor = { roadEditorVisibleState.value = true },
                    showStopMapEditor = stopEditorVisibleState.value,
                    showRoadMapEditor = roadEditorVisibleState.value,
                    onDismissStopMapEditor = { stopEditorVisibleState.value = false },
                    onDismissRoadMapEditor = { roadEditorVisibleState.value = false },
                    onSaveSeparatedRouteMapData = {
                        saveSeparatedRouteMapData(
                            routeNoRaw = manualRouteNoState.value,
                            stopsRaw = manualRouteStopsOnlyState.value,
                            roadRaw = manualRouteRoadOnlyState.value
                        )
                    },
                    isGeneratingRoadPolyline = isGeneratingRoadPolylineState.value,
                    onAutoGenerateRoadPolyline = {
                        val routeNo = manualRouteNoState.value.trim()
                        val rawRoad = manualRouteRoadOnlyState.value
                        val rawStops = manualRouteStopsOnlyState.value


                        val anchorPoints = parseRoadOnlyEditorInput(rawRoad)
                        val stopPoints = parseStopsOnlyEditorInput(rawStops).map { it.second }
                        val sourcePoints = if (anchorPoints.size >= 2) anchorPoints else stopPoints

                        if (routeNo.isBlank()) {
                            status.value = "Route No required before generating road polyline"
                            return@AdminScreen
                        }

                        if (sourcePoints.size < 2) {
                            status.value = "Need at least 2 anchor points or 2 stop points to generate road polyline"
                            return@AdminScreen
                        }

                        isGeneratingRoadPolylineState.value = true
                        isLoading.value = true
                        progressPercent.value = 5
                        progressLabel.value = "Preparing anchor points..."
                        status.value = "Generating Google Maps style snapped road polyline for $routeNo..."

                        lifecycleScope.launch {
                            try {
                                progressPercent.value = 25
                                progressLabel.value = "Snapping to nearest road..."

                                val snapped = snapPolylineToRoadForAdmin(sourcePoints)

                                if (snapped.size < 2) {
                                    isGeneratingRoadPolylineState.value = false
                                    isLoading.value = false
                                    progressPercent.value = 0
                                    progressLabel.value = ""
                                    status.value = "FAILED ❌ Could not generate road-following polyline"
                                    return@launch
                                }

                                progressPercent.value = 80
                                progressLabel.value = "Auto filling road points..."

                                manualRouteRoadOnlyState.value = snapped.joinToString("\n") {
                                    "${it.latitude},${it.longitude}"
                                }

                                selectedRoadPointIndexState.intValue = -1
                                selectedRoadInsertAfterIndexState.intValue = -1

                                isGeneratingRoadPolylineState.value = false
                                isLoading.value = false
                                progressPercent.value = 0
                                progressLabel.value = ""
                                status.value = "DONE ✅ Generated snapped road polyline for $routeNo (${snapped.size} points)"
                            } catch (e: Exception) {
                                isGeneratingRoadPolylineState.value = false
                                isLoading.value = false
                                progressPercent.value = 0
                                progressLabel.value = ""
                                status.value = "FAILED ❌ ${e.message}"
                            }
                        }
                    },
                    noticeTitle = noticeTitleState.value,
                    onNoticeTitleChange = { noticeTitleState.value = it },
                    noticeDate = noticeDateState.value,
                    onNoticeDateChange = { noticeDateState.value = it },
                    onPickNoticeDate = {
                        val cal = Calendar.getInstance()
                        DatePickerDialog(
                            this,
                            { _, y, m, d ->
                                val mm = (m + 1).toString().padStart(2, '0')
                                val dd = d.toString().padStart(2, '0')
                                noticeDateState.value = "$y-$mm-$dd"
                            },
                            cal.get(Calendar.YEAR),
                            cal.get(Calendar.MONTH),
                            cal.get(Calendar.DAY_OF_MONTH)
                        ).show()
                    },
                    noticeBody = noticeBodyState.value,
                    onNoticeBodyChange = { noticeBodyState.value = it }
                )
            }
        }
    }


    // ---------------- UI Actions ----------------

    private fun publishNoticeToUsers() {
        val title = noticeTitleState.value.trim().ifBlank { "Transport Notice" }
        val body = noticeBodyState.value.trim()

        // Release date: optional yyyy-MM-dd from UI; if blank/invalid, use today
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val releaseDateStr = noticeDateState.value.trim()
        val releaseDateMs = try {
            if (releaseDateStr.isBlank()) {
                System.currentTimeMillis()
            } else {
                (dateFmt.parse(releaseDateStr)?.time ?: System.currentTimeMillis())
            }
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
        val releaseDateFinalStr = dateFmt.format(releaseDateMs)

        if (body.isBlank()) {
            status.value = "Notice text is empty (please type Bangla notice text)"
            return
        }

        isLoading.value = true
        status.value = "Publishing notice…"

        // Keep notices for 14 days, then allow cleanup
        val nowMs = System.currentTimeMillis()
        val expiresMs = nowMs + TimeUnit.DAYS.toMillis(14)

        val doc = hashMapOf<String, Any>(
            "title" to title,
            "body" to body,
            "releaseDate" to releaseDateFinalStr,          // yyyy-MM-dd
            "releaseDateMs" to releaseDateMs,              // epoch ms
            "createdAt" to FieldValue.serverTimestamp(),
            "createdAtMs" to nowMs,
            "expiresAt" to Timestamp(expiresMs / 1000, ((expiresMs % 1000) * 1_000_000).toInt())
        )

        db.collection("notices").document()
            .set(doc)
            .addOnSuccessListener {
                noticeTitleState.value = ""
                noticeBodyState.value = ""
                noticeDateState.value = ""

                isLoading.value = false
                status.value = "DONE ✅ Notice published"
            }
            .addOnFailureListener { e ->
                isLoading.value = false
                status.value = "FAILED ❌ ${e.message}"
            }
    }

    // Manual cleanup: delete notices that expired (older than 14 days)
    // NOTE: For automatic cleanup every ~4 months, use a Cloud Scheduler + Cloud Function.
    private fun cleanupOldNotices() {
        isLoading.value = true
        status.value = "Cleaning old notices…"

        val cutoffMs = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(14)

        db.collection("notices")
            .whereLessThan("createdAtMs", cutoffMs)
            .get()
            .addOnSuccessListener { snap ->
                if (snap.isEmpty) {
                    isLoading.value = false
                    status.value = "DONE ✅ No old notices to delete"
                    return@addOnSuccessListener
                }

                db.runBatch { batch ->
                    for (d in snap.documents) {
                        batch.delete(d.reference)
                    }
                }.addOnSuccessListener {
                    isLoading.value = false
                    status.value = "DONE ✅ Deleted ${snap.size()} old notice(s)"
                }.addOnFailureListener { e ->
                    isLoading.value = false
                    status.value = "FAILED ❌ ${e.message}"
                }
            }
            .addOnFailureListener { e ->
                isLoading.value = false
                status.value = "FAILED ❌ ${e.message}"
            }
    }
    private suspend fun snapPolylineToRoadForAdmin(points: List<GeoPoint>): List<GeoPoint> =
        withContext(Dispatchers.IO) {
            if (points.size < 2) return@withContext emptyList()

            fun openJson(url: String): JSONObject? {
                var conn: java.net.HttpURLConnection? = null
                return try {
                    conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                        connectTimeout = 5000
                        readTimeout = 5000
                        requestMethod = "GET"
                        setRequestProperty("Accept", "application/json")
                    }
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    JSONObject(text)
                } catch (_: Exception) {
                    null
                } finally {
                    conn?.disconnect()
                }
            }

            fun decodeCoords(coords: org.json.JSONArray): List<GeoPoint> {
                val out = mutableListOf<GeoPoint>()
                for (i in 0 until coords.length()) {
                    val pair = coords.getJSONArray(i)
                    val lng = pair.getDouble(0)
                    val lat = pair.getDouble(1)
                    out.add(GeoPoint(lat, lng))
                }
                return out
            }

            fun distanceMeters(a: GeoPoint, b: GeoPoint): Float {
                val results = FloatArray(1)
                android.location.Location.distanceBetween(
                    a.latitude, a.longitude,
                    b.latitude, b.longitude,
                    results
                )
                return results[0]
            }

            fun simplifyInput(input: List<GeoPoint>): List<GeoPoint> {
                if (input.size <= 2) return input
                val simplified = mutableListOf<GeoPoint>()
                simplified.add(input.first())
                var lastKept = input.first()

                for (i in 1 until input.lastIndex) {
                    val current = input[i]
                    if (distanceMeters(lastKept, current) >= 8f) {
                        simplified.add(current)
                        lastKept = current
                    }
                }

                if (simplified.last() != input.last()) {
                    simplified.add(input.last())
                }
                return simplified
            }

            fun snapPointToNearestRoad(point: GeoPoint): GeoPoint {
                return try {
                    val url = "https://router.project-osrm.org/nearest/v1/driving/${point.longitude},${point.latitude}?number=1"
                    val json = openJson(url) ?: return point
                    val waypoints = json.optJSONArray("waypoints") ?: return point
                    if (waypoints.length() == 0) return point
                    val location = waypoints.getJSONObject(0).optJSONArray("location") ?: return point
                    if (location.length() < 2) return point
                    val lng = location.getDouble(0)
                    val lat = location.getDouble(1)
                    GeoPoint(lat, lng)
                } catch (_: Exception) {
                    point
                }
            }

            fun matchWholeTrace(input: List<GeoPoint>): List<GeoPoint> {
                return try {
                    val simplified = simplifyInput(input)
                    if (simplified.size < 2 || simplified.size > 24) return emptyList()
                    val snappedInput = simplified.map { snapPointToNearestRoad(it) }
                    val coords = snappedInput.joinToString(";") { "${it.longitude},${it.latitude}" }
                    val url = "https://router.project-osrm.org/match/v1/driving/$coords?overview=full&geometries=geojson&steps=false&gaps=ignore&tidy=true"
                    val json = openJson(url) ?: return emptyList()
                    val matchings = json.optJSONArray("matchings") ?: return emptyList()
                    if (matchings.length() == 0) return emptyList()

                    val merged = mutableListOf<GeoPoint>()
                    for (i in 0 until matchings.length()) {
                        val geometry = matchings.getJSONObject(i)
                            .optJSONObject("geometry")
                            ?.optJSONArray("coordinates") ?: continue
                        val pts = decodeCoords(geometry)
                        if (pts.isEmpty()) continue
                        if (merged.isEmpty()) merged.addAll(pts) else merged.addAll(pts.drop(1))
                    }
                    if (merged.size >= 2) merged else emptyList()
                } catch (_: Exception) {
                    emptyList()
                }
            }

            fun routeSegment(a: GeoPoint, b: GeoPoint): List<GeoPoint> {
                return try {
                    val snappedA = snapPointToNearestRoad(a)
                    val snappedB = snapPointToNearestRoad(b)
                    val coords = "${snappedA.longitude},${snappedA.latitude};${snappedB.longitude},${snappedB.latitude}"
                    val url = "https://router.project-osrm.org/route/v1/driving/$coords?overview=full&geometries=geojson&steps=false"

                    val json = openJson(url) ?: return emptyList()
                    val routes = json.optJSONArray("routes") ?: return emptyList()
                    if (routes.length() == 0) return emptyList()

                    val geometry = routes.getJSONObject(0)
                        .getJSONObject("geometry")
                        .getJSONArray("coordinates")

                    val snapped = decodeCoords(geometry)
                    if (snapped.size >= 2) snapped else emptyList()
                } catch (_: Exception) {
                    emptyList()
                }
            }

            val matchedTrace = matchWholeTrace(points)
            if (matchedTrace.size >= 2) return@withContext matchedTrace

            val simplifiedPoints = simplifyInput(points)
            if (simplifiedPoints.size < 2) return@withContext emptyList()

            val merged = mutableListOf<GeoPoint>()
            for (i in 0 until simplifiedPoints.lastIndex) {
                val seg = routeSegment(simplifiedPoints[i], simplifiedPoints[i + 1])
                if (seg.size < 2) continue
                if (merged.isEmpty()) {
                    merged.addAll(seg)
                } else {
                    merged.addAll(seg.drop(1))
                }
            }

            return@withContext if (merged.size >= 2) merged else emptyList()
        }

    private fun onUploadFiles(uris: List<Uri>) {
        isLoading.value = true
        status.value = "Reading ${uris.size} file(s)..."
        progressPercent.value = 0
        progressLabel.value = "Reading files..."

        val allItems = mutableListOf<Map<String, Any>>()
        val usedFiles = mutableListOf<String>()

        uris.forEach { uri ->
            val name = getDisplayName(uri)
            val items = parseScheduleFile(uri)
            if (items.isNotEmpty()) {
                usedFiles.add(name)
                allItems.addAll(items)
            }
        }

        // Merge by routeNo + routeName and UNION times so repeated rows/files keep ALL times
        val merged = linkedMapOf<String, MutableMap<String, Any>>()

        fun asStringList(v: Any?): List<String> = when (v) {
            is List<*> -> v.filterIsInstance<String>()
            is Array<*> -> v.filterIsInstance<String>()
            else -> emptyList()
        }

        fun cleanTimes(list: List<String>): List<String> = list
            .map { normalizeTimeString(it) }
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.equals("nan", true) }
            .distinct()

        allItems.forEach { item ->
            val routeNo = (item["routeNo"] as? String).orEmpty().trim()
            val routeName = (item["routeName"] as? String).orEmpty().trim()
            val key = "${routeNo}_${routeName}".trim()
            if (key.isBlank()) return@forEach

            val incomingStart = cleanTimes(asStringList(item["startTimes"]))
            val incomingDep = cleanTimes(asStringList(item["departureTimes"]))
            val incomingDetails = (item["routeDetails"] as? String).orEmpty().trim()

            val prev = merged[key]
            if (prev == null) {
                merged[key] = item.toMutableMap().apply {
                    put("startTimes", incomingStart)
                    put("departureTimes", incomingDep)
                    if (incomingDetails.isNotBlank()) put("routeDetails", incomingDetails)
                    val appliesOn = (item["appliesOn"] as? String).orEmpty().trim()
                    if (appliesOn.isNotBlank()) put("appliesOn", appliesOn)
                }
            } else {
                val prevStart = cleanTimes(asStringList(prev["startTimes"]))
                val prevDep = cleanTimes(asStringList(prev["departureTimes"]))

                prev["startTimes"] = cleanTimes(prevStart + incomingStart)
                prev["departureTimes"] = cleanTimes(prevDep + incomingDep)

                // Prefer non-empty routeDetails if current one is blank
                val prevDetails = (prev["routeDetails"] as? String).orEmpty().trim()
                if (prevDetails.isBlank() && incomingDetails.isNotBlank()) {
                    prev["routeDetails"] = incomingDetails
                }

                // Route name/no are already in key; still keep fields non-empty
                if (((prev["routeNo"] as? String).orEmpty().trim()).isBlank() && routeNo.isNotBlank()) {
                    prev["routeNo"] = routeNo
                }
                if (((prev["routeName"] as? String).orEmpty().trim()).isBlank() && routeName.isNotBlank()) {
                    prev["routeName"] = routeName
                }
                // Prefer non-empty appliesOn if current one is blank
                val prevApplies = (prev["appliesOn"] as? String).orEmpty().trim()
                val incomingApplies = (item["appliesOn"] as? String).orEmpty().trim()
                if (prevApplies.isBlank() && incomingApplies.isNotBlank()) {
                    prev["appliesOn"] = incomingApplies
                }
            }
        }

        val finalItems = merged.values.toList()

        // Final safety filter: only keep items with valid routeNo and at least one time
        val safeItems = finalItems.filter { item ->
            val rn = (item["routeNo"] as? String).orEmpty()
            if (!isValidRouteNo(rn)) return@filter false

            val st = asStringList(item["startTimes"]).map { it.trim() }.any { it.isNotBlank() }
            val dp = asStringList(item["departureTimes"]).map { it.trim() }.any { it.isNotBlank() }
            st || dp
        }

        status.value = "Parsed ${safeItems.size} route(s) from ${usedFiles.size} file(s). Preparing map data…"
        Toast.makeText(
            this@MainActivity,
            "Parsed ${safeItems.size} route(s). Preparing map data…",
            Toast.LENGTH_SHORT
        ).show()

        if (safeItems.isEmpty()) {
            isLoading.value = false
            status.value = "No valid rows found in selected files. (CSV/XLSX format mismatch?)"
            progressPercent.value = 0
            progressLabel.value = ""
            return
        }

        lifecycleScope.launch {
            try {
                status.value = "Generating approximate routeStops from routeDetails… (exact routePolyline disabled)"
                progressPercent.value = 0
                progressLabel.value = "Preparing stop locations..."
                val enrichedItems = enrichRoutesWithMapData(safeItems) { done, total, label ->
                    val percent = ((done * 100f) / total.toFloat()).toInt().coerceIn(0, 100)
                    progressPercent.value = percent
                    progressLabel.value = "Processing stop $done/$total: $label"
                    status.value = "Preparing map data... $percent%"
                }
                val totalStops = enrichedItems.sumOf {
                    (it["routeStopsCount"] as? Int) ?: 0
                }
                val scheduleOnlyItems = enrichedItems.map { item -> item.toMutableMap().apply { remove("routeStops"); remove("routePolyline"); remove("routeStopNames"); remove("routeStopsCount"); remove("routePolylineCount"); remove("routeRoadPolyline"); remove("routeRoadPolylineCount"); remove("routeRoadPolylineAnchors"); remove("routeRoadPolylineAnchorCount"); remove("mapDataNote") }.toMap() }
                status.value = "Uploading ${scheduleOnlyItems.size} schedule routes… ($totalStops mapped stops prepared separately, exact polyline disabled)"
                progressPercent.value = 100
                progressLabel.value = "Uploading to Firestore..."
                progressPercent.value = 100
                progressLabel.value = "Final upload in progress..."
                uploadSchedule(scheduleOnlyItems, usedFiles)
            } catch (e: Exception) {
                isLoading.value = false
                status.value = "FAILED ❌ Map data generation failed: ${e.message}"
                progressPercent.value = 0
                progressLabel.value = ""
                Toast.makeText(
                    this@MainActivity,
                    "FAILED ❌ Map data generation failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun uploadSchedule(items: List<Map<String, Any>>, fileNames: List<String>) {
        status.value = "Uploading ${items.size} routes..."
        Toast.makeText(
            this@MainActivity,
            "Uploading ${items.size} routes…",
            Toast.LENGTH_SHORT
        ).show()

        val scheduleRef = db.collection("schedules")
            .document("current")
            .collection("data")
            .document("items")

        val metaRef = db.collection("meta").document("app")

        db.runBatch { batch ->
            batch.set(
                scheduleRef,
                mapOf(
                    "items" to items,
                    "sourceFiles" to fileNames,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )

            batch.set(
                metaRef,
                mapOf(
                    "version" to FieldValue.increment(1),
                    "message" to "Schedule updated from Admin",
                    "lastUploadedFiles" to fileNames,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )

            val msgRef = db.collection("admin_messages").document()
            batch.set(
                msgRef,
                mapOf(
                    "title" to "DIU Transport Schedule",
                    "body" to "Schedule updated from Admin",
                    "target" to "diu_admin",
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )
        }.addOnSuccessListener {
            isLoading.value = false
            status.value = "DONE ✅ Uploaded ${items.size} route(s). Schedule fully replaced. Separate map collection was not touched."
            progressPercent.value = 0
            progressLabel.value = ""
            Toast.makeText(
                this@MainActivity,
                "DONE ✅ Uploaded ${items.size} route(s)\nSchedule fully replaced. Separate map collection unchanged.",
                Toast.LENGTH_LONG
            ).show()
        }.addOnFailureListener { e ->
            isLoading.value = false
            status.value = "FAILED ❌ ${e.message}"
            progressPercent.value = 0
            progressLabel.value = ""
            Toast.makeText(
                this@MainActivity,
                "FAILED ❌ Upload failed: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun sendAdminPush(
        title: String,
        body: String,
        target: String,
        incrementVersion: Boolean
    ) {
        val cleanTitle = title.trim().ifBlank { "DIU Transport Schedule" }
        val cleanBody = body.trim()
        val cleanTarget = target.trim().ifBlank { "diu_admin" }

        if (cleanBody.isBlank()) {
            status.value = "Message is empty"
            return
        }

        isLoading.value = true
        status.value = "Sending notification…"

        val msgRef = db.collection("admin_messages").document()
        val metaRef = db.collection("meta").document("app")

        val msgDoc = hashMapOf<String, Any>(
            "title" to cleanTitle,
            "body" to cleanBody,
            "target" to cleanTarget,
            "createdAt" to FieldValue.serverTimestamp()
        )

        db.runBatch { batch ->
            // Create a new admin message document (Cloud Function can trigger on this)
            batch.set(msgRef, msgDoc)

            // Keep existing meta/app behavior (used by the user app to refresh)
            val metaUpdates = hashMapOf<String, Any>(
                "message" to cleanBody,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (incrementVersion) {
                metaUpdates["version"] = FieldValue.increment(1)
            }
            batch.set(metaRef, metaUpdates, SetOptions.merge())
        }.addOnSuccessListener {
            isLoading.value = false
            status.value = if (incrementVersion)
                "DONE ✅ Notification queued + version incremented"
            else
                "DONE ✅ Notification queued"
        }.addOnFailureListener { e ->
            isLoading.value = false
            status.value = "FAILED ❌ ${e.message}"
        }
    }

    // ---------------- CSV/XLSX Parser ----------------
    // Normalize time strings so variants like `9.40 AM` or `3.20.00 PM` become `9:40 AM` / `3:20:00 PM`.
    // Also trims and removes newlines.
    private fun normalizeTimeString(raw: String): String {
        val s0 = raw.replace("\r", "").replace("\n", " ").trim()
        if (s0.isBlank()) return ""

        // Extract the first time token and keep any trailing note.
        val m = Regex("(\\d{1,2})[\\.:](\\d{2})(?:[\\.:](\\d{2}))?\\s*([AP]M)", RegexOption.IGNORE_CASE)
            .find(s0.replace(Regex("\\s+"), " "))
        if (m != null) {
            val hh = m.groupValues[1]
            val mm = m.groupValues[2]
            val ss = m.groupValues.getOrNull(3).orEmpty()
            val ap = m.groupValues[4].uppercase()
            val time = if (ss.isNotBlank()) "$hh:$mm:$ss $ap" else "$hh:$mm $ap"
            val note = s0.substring(m.range.last + 1).trim()
            return if (note.isNotBlank()) "$time $note" else time
        }

        // Fallback: if it contains a dot between hour and minute but no AM/PM, convert the first dot to ':'
        val simpleDot = Regex("^(\\d{1,2})\\.(\\d{2})(.*)$").find(s0)
        if (simpleDot != null) {
            val hh = simpleDot.groupValues[1]
            val mm = simpleDot.groupValues[2]
            val rest = simpleDot.groupValues[3]
            return "$hh:$mm$rest".trim()
        }

        return s0
    }
    private fun parseScheduleFile(uri: Uri): List<Map<String, Any>> {
        val name = getDisplayName(uri).lowercase()
        return when {
            name.endsWith(".xlsx") || name.endsWith(".xls") -> parseXlsx(uri)
            else -> parseCsv(uri)
        }
    }

    // CSV (robust: supports quoted commas)
    private fun parseCsv(uri: Uri): List<Map<String, Any>> {
        val input = contentResolver.openInputStream(uri) ?: return emptyList()
        val br = BufferedReader(InputStreamReader(input))

        data class Temp(
            val routeNo: String,
            val routeName: String,
            var routeDetails: String,
            val startTimes: MutableList<String> = mutableListOf(),
            val departureTimes: MutableList<String> = mutableListOf()
        )

        val routes = linkedMapOf<String, Temp>()
        var lastRouteNo = ""
        var lastRouteName = ""

        br.forEachLine { lineRaw ->
            val line = lineRaw.trim()
            if (line.isBlank()) return@forEachLine

            val cols = parseCsvLine(line)
            if (cols.size < 5) return@forEachLine

            var routeNo = cols[0].trim()
            val start = normalizeTimeString(cols[1])
            var routeName = cols[2].trim()
            val details = cols[3].trim()
            val dep = normalizeTimeString(cols[4])

            // If CSV came from merged cells, routeNo/routeName may be blank in subsequent rows
            if (routeNo.isBlank()) routeNo = lastRouteNo
            if (routeName.isBlank()) routeName = lastRouteName

            if (routeNo.isNotBlank()) lastRouteNo = routeNo
            if (routeName.isNotBlank()) lastRouteName = routeName

            if (routeNo.equals("Route No", true)) return@forEachLine
            if (routeNo.contains("Daffodil", true)) return@forEachLine
            if (!isValidRouteNo(routeNo)) return@forEachLine

            val key = "${routeNo}_${routeName}".trim()
            val t = routes.getOrPut(key) { Temp(routeNo, routeName, details) }

            if (t.routeDetails.isBlank() && details.isNotBlank() && details.lowercase() != "nan") {
                t.routeDetails = details
            }
            if (start.isNotBlank() && start.lowercase() != "nan") t.startTimes.add(start)
            if (dep.isNotBlank() && dep.lowercase() != "nan") t.departureTimes.add(dep)
        }

        return routes.values.map {
            mapOf(
                "routeNo" to it.routeNo,
                "routeName" to it.routeName,
                "routeDetails" to it.routeDetails,
                "appliesOn" to (if (it.routeNo.startsWith("F", true)) "FRIDAY" else "DAILY"),
                "startTimes" to it.startTimes.distinct(),
                "departureTimes" to it.departureTimes.distinct()
            )
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' -> {
                    // Handle escaped quotes ""
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                ch == ',' && !inQuotes -> {
                    out.add(sb.toString())
                    sb.setLength(0)
                }
                else -> sb.append(ch)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    // XLSX parser (expects same 5 columns order: Route No, Start Time, Route Name, Route Details, Departure Time)
    private fun parseXlsx(uri: Uri): List<Map<String, Any>> {
        val input = contentResolver.openInputStream(uri) ?: return emptyList()
        val formatter = DataFormatter()

        data class Temp(
            val routeNo: String,
            val routeName: String,
            var routeDetails: String,
            val startTimes: MutableList<String> = mutableListOf(),
            val departureTimes: MutableList<String> = mutableListOf()
        )

        val routes = linkedMapOf<String, Temp>()
        var lastRouteNo = ""
        var lastRouteName = ""

        return try {
            val wb = WorkbookFactory.create(input)
            val sheet = wb.getSheetAt(0) ?: return emptyList()

            for (rowIdx in 0..sheet.lastRowNum) {
                val row = sheet.getRow(rowIdx) ?: continue

                fun cellStr(col: Int): String {
                    val cell = row.getCell(col) ?: return ""
                    return when (cell.cellType) {
                        CellType.STRING -> cell.stringCellValue
                        CellType.NUMERIC, CellType.BOOLEAN, CellType.FORMULA -> formatter.formatCellValue(cell)
                        else -> formatter.formatCellValue(cell)
                    }.trim()
                }

                var routeNo = cellStr(0)
                val start = normalizeTimeString(cellStr(1))
                var routeName = cellStr(2)
                val details = cellStr(3)
                val dep = normalizeTimeString(cellStr(4))

                // If Excel has merged cells, routeNo/routeName may be blank in subsequent rows
                if (routeNo.isBlank()) routeNo = lastRouteNo
                if (routeName.isBlank()) routeName = lastRouteName

                if (routeNo.isNotBlank()) lastRouteNo = routeNo
                if (routeName.isNotBlank()) lastRouteName = routeName

                if (routeNo.equals("Route No", true) || routeNo.equals("Route", true)) continue
                if (routeNo.isBlank()) continue
                if (routeNo.contains("Daffodil", true)) continue
                if (!isValidRouteNo(routeNo)) continue

                val key = "${routeNo}_${routeName}".trim()
                val t = routes.getOrPut(key) { Temp(routeNo, routeName, details) }

                if (t.routeDetails.isBlank() && details.isNotBlank() && details.lowercase() != "nan") {
                    t.routeDetails = details
                }
                if (start.isNotBlank() && start.lowercase() != "nan") t.startTimes.add(start)
                if (dep.isNotBlank() && dep.lowercase() != "nan") t.departureTimes.add(dep)
            }

            wb.close()

            routes.values.map {
                mapOf(
                    "routeNo" to it.routeNo,
                    "routeName" to it.routeName,
                    "routeDetails" to it.routeDetails,
                    "startTimes" to it.startTimes.distinct(),
                    "departureTimes" to it.departureTimes.distinct(),
                    "appliesOn" to (if (it.routeNo.startsWith("F", true)) "FRIDAY" else "DAILY")
                )
            }
        } catch (e: Exception) {
            // If XLSX parsing fails (missing dependency, etc.) return empty so UI shows a clear status.
            emptyList()
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun AdminScreen(
    selectedFileName: String,
    lastUploadedFiles: List<String>,
    status: String,
    isLoading: Boolean,
    progressPercent: Int,
    progressLabel: String,
    processingPreviewTitle: String,
    processingPreviewBody: String,
    onPickFiles: () -> Unit,
    onSendAdminPush: (title: String, body: String, target: String, incrementVersion: Boolean) -> Unit,
    onPublishNotice: () -> Unit,
    onCleanupNotices: () -> Unit,
    manualRouteNo: String,
    onManualRouteNoChange: (String) -> Unit,
    manualRouteStopsOnly: String,
    onManualRouteStopsOnlyChange: (String) -> Unit,
    manualRouteRoadOnly: String,
    onManualRouteRoadOnlyChange: (String) -> Unit,
    onOpenStopMapEditor: () -> Unit,
    onOpenRoadMapEditor: () -> Unit,
    showStopMapEditor: Boolean,
    showRoadMapEditor: Boolean,
    onDismissStopMapEditor: () -> Unit,
    onDismissRoadMapEditor: () -> Unit,
    onSaveSeparatedRouteMapData: () -> Unit,
    isGeneratingRoadPolyline: Boolean,
    onAutoGenerateRoadPolyline: () -> Unit,
    noticeTitle: String,
    onNoticeTitleChange: (String) -> Unit,
    noticeDate: String,
    onNoticeDateChange: (String) -> Unit,
    onPickNoticeDate: () -> Unit,
    noticeBody: String,
    onNoticeBodyChange: (String) -> Unit
) {
    var title by remember { mutableStateOf("DIU Transport Schedule") }
    var body by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("diu_admin") }
    var showAdvanced by remember { mutableStateOf(false) }
    var bumpVersion by remember { mutableStateOf(true) }
    var autoPolylineGenerated by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

    // Match user app Profile card look
    val profileLikeCardColors = CardDefaults.elevatedCardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DIU Admin Panel") }
            )
        }
    ) { pad ->
        Box(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxSize()
                    .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                ElevatedCard(colors = profileLikeCardColors) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Upload Schedule", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Selected: $selectedFileName",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Button(
                            onClick = onPickFiles,
                            enabled = !isLoading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Pick File(s) & Upload (Replace)")
                        }

                        Text(
                            "Supports CSV / XLSX. Upload replaces the current schedule.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        val lastUp =
                            if (lastUploadedFiles.isEmpty()) "(none yet)" else lastUploadedFiles.joinToString(
                                ", "
                            )
                        Text(
                            "Last uploaded: $lastUp",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                ElevatedCard(colors = profileLikeCardColors) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Separated Route Map Editor", style = MaterialTheme.typography.titleMedium)

                        OutlinedTextField(
                            value = manualRouteNo,
                            onValueChange = {
                                onManualRouteNoChange(it)
                                autoPolylineGenerated = false
                            },
                            label = { Text("Route No (e.g. R11)") }
                        )

                        OutlinedTextField(
                            value = manualRouteStopsOnly,
                            onValueChange = onManualRouteStopsOnlyChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp),
                            label = { Text("Stops only (Name|lat,lng)") },
                            placeholder = {
                                Text(
                                    "Daffodil Smart City|23.8749776,90.3228843\n" +
                                        "Kumkumari|23.88240205790201,90.3100423395869"
                                )
                            },
                            maxLines = 100
                        )

                        OutlinedTextField(
                            value = manualRouteRoadOnly,
                            onValueChange = onManualRouteRoadOnlyChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp),
                            label = { Text("Road points only (lat,lng)") },
                            placeholder = {
                                Text(
                                    "23.8749776,90.3228843\n" +
                                        "23.88240205790201,90.3100423395869"
                                )
                            },
                            maxLines = 100
                        )

                        Text(
                            "Stop marker ar road polyline ekhon fully separate. Open Stop Map diye stop add/edit korben, Open Road Map diye road point add/edit korben.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        HorizontalDivider()

                        Text(
                            "Separated Editors",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onOpenStopMapEditor,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Open Stop Map")
                            }

                            OutlinedButton(
                                onClick = onOpenRoadMapEditor,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Open Road Map")
                            }
                        }
                        Button(
                            onClick = onAutoGenerateRoadPolyline,
                            enabled = !isLoading && !isGeneratingRoadPolyline && !autoPolylineGenerated,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (isGeneratingRoadPolyline)
                                    "Generating Road Polyline..."
                                else
                                    "Auto Generate Road Polyline"
                            )
                        }

                        Text(
                            "Road points only box a jodi 2+ anchor point thake tahole oi gula use kore snapped road polyline generate hobe. Nahole stop point gula theke auto road polyline generate hobe.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Button(
                            onClick = onSaveSeparatedRouteMapData,
                            enabled = manualRouteNo.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Save Separated Stop + Road Map")
                        }
                        if (manualRouteNo.isBlank()) {
                            Text(
                                text = "Route No select / enter korle save enable hobe",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp, start = 4.dp)
                            )
                        }
                    }
                }

                ElevatedCard(colors = profileLikeCardColors) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "Publish Notice (User App)",
                            style = MaterialTheme.typography.titleMedium
                        )

                        OutlinedTextField(
                            value = noticeTitle,
                            onValueChange = onNoticeTitleChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Notice title") },
                            singleLine = true
                        )

                        // Release date (optional). If empty, today's date will be used.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedTextField(
                                value = noticeDate,
                                onValueChange = onNoticeDateChange,
                                modifier = Modifier.weight(1f),
                                label = { Text("Release date (optional)") },
                                placeholder = { Text("yyyy-MM-dd") },
                                singleLine = true
                            )
                            OutlinedButton(
                                onClick = onPickNoticeDate,
                                enabled = !isLoading
                            ) {
                                Text("Pick")
                            }
                        }

                        OutlinedTextField(
                            value = noticeBody,
                            onValueChange = onNoticeBodyChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                            label = { Text("Notice text (Bangla)") },
                            placeholder = { Text("যেমন: আজ বাস ১৫ মিনিট লেট…") },
                            maxLines = 100
                        )

                        Button(
                            onClick = { onPublishNotice() },
                            enabled = !isLoading && noticeBody.trim().isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Publish Notice")
                        }

                        OutlinedButton(
                            onClick = { onCleanupNotices() },
                            enabled = !isLoading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Delete old notices (14 days)")
                        }
                    }
                }

                ElevatedCard(colors = profileLikeCardColors) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Send Notification", style = MaterialTheme.typography.titleMedium)

                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Title") },
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = body,
                            onValueChange = { body = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp),
                            label = { Text("Message") },
                            placeholder = { Text("Write message for users…") },
                            maxLines = 100
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TextButton(onClick = { showAdvanced = !showAdvanced }) {
                                Text(if (showAdvanced) "Hide advanced" else "Show advanced")
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Increment version",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(10.dp))
                                Switch(
                                    checked = bumpVersion,
                                    onCheckedChange = { bumpVersion = it }
                                )
                            }
                        }

                        if (showAdvanced) {
                            OutlinedTextField(
                                value = target,
                                onValueChange = { target = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Target topic") },
                                placeholder = { Text("diu_admin") },
                                singleLine = true
                            )

                            Text(
                                "Tip: default target is diu_admin. Version bump helps user app auto-refresh.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Button(
                            onClick = {
                                onSendAdminPush(
                                    title,
                                    body,
                                    target,
                                    bumpVersion
                                )
                            },
                            enabled = !isLoading && body.trim().isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (bumpVersion) "Send Notification + Refresh" else "Send Notification")
                        }
                    }
                }
            }

            if (isLoading) {
                Dialog(onDismissRequest = { }) {
                    ElevatedCard(
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 20.dp, vertical = 18.dp)
                                .widthIn(min = 320.dp, max = 720.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                val progressValue = (progressPercent.coerceIn(0,100) / 100f)
                                CircularProgressIndicator(progress = { progressValue })
                                Text(
                                    text = if (progressPercent in 1..100) "Processing... $progressPercent%" else "Processing...",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (progressLabel.isNotBlank()) {
                                    Text(
                                        text = progressLabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = status.ifBlank { "Please wait" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (processingPreviewTitle.isNotBlank() || processingPreviewBody.isNotBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    tonalElevation = 2.dp,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = processingPreviewTitle.ifBlank { "Live preview" },
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = processingPreviewBody.ifBlank { "Preparing preview..." },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (showStopMapEditor) {
                SeparateStopMapEditorDialog(
                    initialStopsRaw = manualRouteStopsOnly,
                    onDismiss = onDismissStopMapEditor,
                    onSave = { updatedStopsRaw: String ->
                        onManualRouteStopsOnlyChange(updatedStopsRaw)
                        autoPolylineGenerated = false
                        onDismissStopMapEditor()
                    }
                )
            }

            if (showRoadMapEditor) {
                SeparateRoadMapEditorDialog(
                    initialRoadRaw = manualRouteRoadOnly,
                    onDismiss = onDismissRoadMapEditor,onSave = { updatedRoadRaw: String ->
                        onManualRouteRoadOnlyChange(updatedRoadRaw)
                        autoPolylineGenerated = false
                        onDismissRoadMapEditor()
                    }
                )
            }
        }
    }
}
private fun parseStopsOnlyEditorInputForDialog(raw: String): MutableList<Pair<String, GeoPoint>> {
    return raw
        .replace("\r", "\n")
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val parts = line.split("|").map { it.trim() }
            if (parts.size < 2) return@mapNotNull null
            val name = parts[0]
            val latLng = parts[1].split(",").map { it.trim() }
            val lat = latLng.getOrNull(0)?.toDoubleOrNull()
            val lng = latLng.getOrNull(1)?.toDoubleOrNull()
            if (name.isBlank() || lat == null || lng == null) return@mapNotNull null
            name to GeoPoint(lat, lng)
        }
        .toMutableList()
}

private fun parseRoadOnlyEditorInputForDialog(raw: String): MutableList<GeoPoint> {
    return raw
        .replace("\r", "\n")
        .replace(";", "\n")
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val parts = line.split(",").map { it.trim() }
            val lat = parts.getOrNull(0)?.toDoubleOrNull()
            val lng = parts.getOrNull(1)?.toDoubleOrNull()
            if (lat != null && lng != null) GeoPoint(lat, lng) else null
        }
        .toMutableList()
}

@Composable
private fun SeparateStopMapEditorDialog(
    initialStopsRaw: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    val context = LocalContext.current
    val stops: MutableList<Pair<String, GeoPoint>> = remember(initialStopsRaw) { parseStopsOnlyEditorInputForDialog(initialStopsRaw) }
    var selectedIndex by remember { mutableStateOf(-1) }
    var pendingName by remember { mutableStateOf("") }
    var stopNamesBulkText by remember {
        mutableStateOf(
            stops.joinToString("\n") { it.first }.ifBlank { "" }
        )
    }
    var selectedNameIndex by remember { mutableStateOf(0) }
    var manualLatLngText by remember { mutableStateOf("") }
    var bulkLatLngText by remember {
        mutableStateOf(
            stops.joinToString("\n") { "${it.second.latitude},${it.second.longitude}" }
        )
    }
    var refreshTick by remember { mutableStateOf(0) }
    var hasAutoFittedMap by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Stop Map Editor",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = onDismiss) { Text("Close") }
                    Button(
                        onClick = {
                            val out = stops.joinToString("\n") { (name, point) ->
                                "$name|${point.latitude},${point.longitude}"
                            }
                            onSave(out)
                        }
                    ) { Text("Save") }
                }

                Text(
                    text = "Stop names list",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )

                val bulkNames = remember(stopNamesBulkText) {
                    stopNamesBulkText
                        .replace("\r", "\n")
                        .lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .toList()
                }
                val compactEditorMode = bulkNames.isNotEmpty()
                val topEditorFieldHeight = if (compactEditorMode) 112.dp else 160.dp
                val mapMinHeight = if (compactEditorMode) 250.dp else 180.dp

                fun parseManualGeoPoint(raw: String): GeoPoint? {
                    val cleaned = raw.trim().removePrefix("(").removeSuffix(")")
                    val parts = cleaned.split(",").map { it.trim() }
                    if (parts.size < 2) return null
                    val lat = parts[0].toDoubleOrNull() ?: return null
                    val lng = parts[1].toDoubleOrNull() ?: return null
                    if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
                    return GeoPoint(lat, lng)
                }

                fun parseBulkGeoPoints(raw: String): List<GeoPoint> {
                    return raw
                        .replace("\r", "\n")
                        .lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .mapNotNull { parseManualGeoPoint(it) }
                        .toList()
                }

                OutlinedTextField(
                    value = stopNamesBulkText,
                    onValueChange = {
                        stopNamesBulkText = it
                        val maxIndex = (
                            it.replace("\r", "\n")
                                .lineSequence()
                                .map { line -> line.trim() }
                                .filter { line -> line.isNotBlank() }
                                .count() - 1
                            ).coerceAtLeast(0)
                        if (selectedNameIndex > maxIndex) selectedNameIndex = maxIndex
                        if (bulkNames.isNotEmpty()) {
                            pendingName = bulkNames.getOrElse(selectedNameIndex) { bulkNames.last() }
                        } else {
                            pendingName = ""
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(topEditorFieldHeight)
                        .padding(horizontal = 12.dp),
                    label = { Text("Stop names list (one per line)") },
                    placeholder = {
                        Text(
                            "Daffodil Smart City\nKumkumari\nCharabag\nKolma"
                        )
                    },
                    maxLines = 100
                )
                OutlinedTextField(
                    value = bulkLatLngText,
                    onValueChange = { bulkLatLngText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(topEditorFieldHeight)
                        .padding(horizontal = 12.dp),
                    label = { Text("Stop lat,lng list (serial, one per line)") },
                    placeholder = {
                        Text(
                            "23.8749776,90.3228843\n23.88240205790201,90.3100423395869\n23.885947119640388,90.3108975886436"
                        )
                    },
                    maxLines = 100
                )

                if (bulkNames.isNotEmpty()) {
                    val selectedName = bulkNames.getOrElse(selectedNameIndex) { bulkNames.last() }
                    pendingName = selectedName
                    val existingSelectedStopIndex = stops.indexOfFirst { it.first.equals(selectedName, ignoreCase = true) }
                    if (existingSelectedStopIndex >= 0) {
                        selectedIndex = existingSelectedStopIndex
                        val existingPoint = stops[existingSelectedStopIndex].second
                        manualLatLngText = "${existingPoint.latitude},${existingPoint.longitude}"
                    } else if (selectedIndex < 0) {
                        manualLatLngText = ""
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (selectedNameIndex > 0) {
                                    selectedNameIndex -= 1
                                    pendingName = bulkNames.getOrElse(selectedNameIndex) { "" }
                                }
                            }
                        ) {
                            Text("Prev")
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            tonalElevation = 1.dp,
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = if (compactEditorMode) 8.dp else 10.dp)) {
                                Text(
                                    text = "Selected stop name",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "${selectedNameIndex + 1}. $selectedName",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                if (selectedNameIndex < bulkNames.lastIndex) {
                                    selectedNameIndex += 1
                                    pendingName = bulkNames.getOrElse(selectedNameIndex) { "" }
                                }
                            }
                        ) {
                            Text("Next")
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val parsedPoints = parseBulkGeoPoints(bulkLatLngText)
                                if (parsedPoints.isEmpty()) {
                                    Toast.makeText(context, "Lat,lng list empty or invalid", Toast.LENGTH_SHORT).show()
                                } else {
                                    val limit = minOf(bulkNames.size, parsedPoints.size)
                                    repeat(limit) { index ->
                                        val name = bulkNames[index]
                                        val point = parsedPoints[index]
                                        val existingIndex = stops.indexOfFirst { it.first.equals(name, ignoreCase = true) }
                                        if (existingIndex >= 0) {
                                            stops[existingIndex] = name to point
                                        } else {
                                            stops.add(name to point)
                                        }
                                    }
                                    if (limit < bulkNames.size) {
                                        Toast.makeText(context, "Sob stop-er lat,lng den nai. $limit ta apply hoyeche", Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(context, "Serially sob stop set hoyeche", Toast.LENGTH_SHORT).show()
                                    }
                                    selectedNameIndex = 0
                                    pendingName = bulkNames.firstOrNull().orEmpty()
                                    selectedIndex = stops.indexOfFirst { it.first.equals(pendingName, ignoreCase = true) }
                                    manualLatLngText = stops.getOrNull(selectedIndex)?.second?.let { "${it.latitude},${it.longitude}" }.orEmpty()
                                    refreshTick++
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Apply All Serial Lat,Lng")
                        }
                    }

                    OutlinedTextField(
                        value = manualLatLngText,
                        onValueChange = { manualLatLngText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        label = { Text("Selected stop lat,lng") },
                        placeholder = { Text("23.8749776,90.3228843") },
                        singleLine = true
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val point = parseManualGeoPoint(manualLatLngText)
                                if (point == null) {
                                    Toast.makeText(context, "Invalid lat,lng", Toast.LENGTH_SHORT).show()
                                } else {
                                    val name = pendingName.trim()
                                    if (name.isBlank()) {
                                        Toast.makeText(context, "Stop name din", Toast.LENGTH_SHORT).show()
                                    } else {
                                        val existingIndex = stops.indexOfFirst { it.first.equals(name, ignoreCase = true) }
                                        if (existingIndex >= 0) {
                                            stops[existingIndex] = name to point
                                            selectedIndex = existingIndex
                                        } else {
                                            stops.add(name to point)
                                            selectedIndex = stops.lastIndex
                                        }

                                        val currentBulkNames = stopNamesBulkText
                                            .replace("\r", "\n")
                                            .lineSequence()
                                            .map { it.trim() }
                                            .filter { it.isNotBlank() }
                                            .toList()

                                        val orderedPoints = currentBulkNames.mapNotNull { stopName ->
                                            stops.firstOrNull { it.first.equals(stopName, ignoreCase = true) }?.second
                                        }
                                        bulkLatLngText = orderedPoints.joinToString("\n") { "${it.latitude},${it.longitude}" }
                                        refreshTick++
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Set by Lat,Lng")
                        }

                        OutlinedButton(
                            onClick = {
                                val selectedPoint = stops.getOrNull(selectedIndex)?.second
                                manualLatLngText = selectedPoint?.let { "${it.latitude},${it.longitude}" }.orEmpty()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Use Selected")
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = pendingName,
                        onValueChange = { pendingName = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        label = { Text("Single stop name") },
                        placeholder = { Text("Daffodil Smart City") },
                        singleLine = true
                    )
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = true)
                        .heightIn(min = mapMinHeight)
                        .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                    shape = RoundedCornerShape(16.dp),
                    tonalElevation = 2.dp,
                    shadowElevation = 2.dp
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            Configuration.getInstance().load(ctx, ctx.getSharedPreferences("osmdroid", android.content.Context.MODE_PRIVATE))
                            MapView(ctx).apply {
                                setTileSource(TileSourceFactory.MAPNIK)
                                setMultiTouchControls(true)
                                val initialPoint = stops.firstOrNull()?.second ?: GeoPoint(23.8103, 90.4125)
                                if (stops.size == 1) {
                                    controller.setZoom(15.0)
                                    controller.setCenter(initialPoint)
                                } else if (stops.size > 1) {
                                    val pts = stops.map { it.second }
                                    val minLat = pts.minOf { it.latitude }
                                    val maxLat = pts.maxOf { it.latitude }
                                    val minLng = pts.minOf { it.longitude }
                                    val maxLng = pts.maxOf { it.longitude }
                                    zoomToBoundingBox(
                                        BoundingBox(maxLat, maxLng, minLat, minLng),
                                        true,
                                        120
                                    )
                                } else {
                                    controller.setZoom(11.5)
                                    controller.setCenter(initialPoint)
                                }
                            }
                        },
                        update = { mapView ->
                            refreshTick
                            mapView.overlays.clear()

                            val mapEvents = MapEventsOverlay(object : MapEventsReceiver {
                                override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                                    val point = p ?: return true
                                    if (pendingName.isBlank() && selectedIndex < 0) {
                                        Toast.makeText(context, "Stop name din", Toast.LENGTH_SHORT).show()
                                        return true
                                    }

                                    if (selectedIndex >= 0 && selectedIndex < stops.size && stops[selectedIndex].first.equals(pendingName.trim(), ignoreCase = true)) {
                                        val name = pendingName.trim().ifBlank { stops[selectedIndex].first }
                                        stops[selectedIndex] = name to point
                                        pendingName = name
                                        manualLatLngText = "${point.latitude},${point.longitude}"

                                        val currentBulkNames = stopNamesBulkText
                                            .replace("\r", "\n")
                                            .lineSequence()
                                            .map { it.trim() }
                                            .filter { it.isNotBlank() }
                                            .toList()
                                        val orderedPoints = currentBulkNames.mapNotNull { stopName ->
                                            stops.firstOrNull { it.first.equals(stopName, ignoreCase = true) }?.second
                                        }
                                        bulkLatLngText = orderedPoints.joinToString("\n") { "${it.latitude},${it.longitude}" }
                                    } else {
                                        val name = pendingName.trim()
                                        if (name.isBlank()) {
                                            Toast.makeText(context, "Stop name din", Toast.LENGTH_SHORT).show()
                                            return true
                                        }
                                        val existingIndex = stops.indexOfFirst { it.first.equals(name, ignoreCase = true) }
                                        if (existingIndex >= 0) {
                                            stops[existingIndex] = name to point
                                            selectedIndex = existingIndex
                                        } else {
                                            stops.add(name to point)
                                            selectedIndex = stops.lastIndex
                                        }
                                        manualLatLngText = "${point.latitude},${point.longitude}"

                                        val currentBulkNames = stopNamesBulkText
                                            .replace("\r", "\n")
                                            .lineSequence()
                                            .map { it.trim() }
                                            .filter { it.isNotBlank() }
                                            .toList()
                                        val orderedPoints = currentBulkNames.mapNotNull { stopName ->
                                            stops.firstOrNull { it.first.equals(stopName, ignoreCase = true) }?.second
                                        }
                                        bulkLatLngText = orderedPoints.joinToString("\n") { "${it.latitude},${it.longitude}" }
                                        val bulkNamesNow = stopNamesBulkText
                                            .replace("\r", "\n")
                                            .lineSequence()
                                            .map { it.trim() }
                                            .filter { it.isNotBlank() }
                                            .toList()
                                        if (bulkNamesNow.isNotEmpty() && selectedNameIndex < bulkNamesNow.lastIndex) {
                                            selectedNameIndex += 1
                                            pendingName = bulkNamesNow.getOrElse(selectedNameIndex) { pendingName }
                                        }
                                    }
                                    refreshTick++
                                    return true
                                }

                                override fun longPressHelper(p: GeoPoint?): Boolean = false
                            })
                            mapView.overlays.add(mapEvents)


                            stops.forEachIndexed { index, pair ->
                                val isCurrentSelectedName = pair.first.equals(pendingName.trim(), ignoreCase = true)
                                val markerBitmap = android.graphics.Bitmap.createBitmap(
                                    52,
                                    52,
                                    android.graphics.Bitmap.Config.ARGB_8888
                                )
                                val markerCanvas = android.graphics.Canvas(markerBitmap)
                                val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                    color = if (isCurrentSelectedName) AndroidColor.parseColor("#F59E0B") else AndroidColor.parseColor("#16A34A")
                                    style = android.graphics.Paint.Style.FILL
                                }
                                val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                    color = AndroidColor.WHITE
                                    textAlign = android.graphics.Paint.Align.CENTER
                                    textSize = 24f
                                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                                }
                                markerCanvas.drawCircle(26f, 26f, 22f, fillPaint)
                                val textY = 26f - ((textPaint.descent() + textPaint.ascent()) / 2f)
                                markerCanvas.drawText((index + 1).toString(), 26f, textY, textPaint)
                                val numberedIcon = android.graphics.drawable.BitmapDrawable(mapView.context.resources, markerBitmap)

                                val marker = Marker(mapView).apply {
                                    position = pair.second
                                    title = pair.first
                                    subDescription = if (isCurrentSelectedName) "Currently selected stop" else "Stop ${index + 1}"
                                    icon = numberedIcon
                                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                    setOnMarkerClickListener { mk, _ ->
                                        selectedIndex = index
                                        pendingName = pair.first
                                        selectedNameIndex = bulkNames.indexOfFirst { it.equals(pair.first, ignoreCase = true) }.takeIf { it >= 0 } ?: selectedNameIndex
                                        manualLatLngText = "${pair.second.latitude},${pair.second.longitude}"
                                        val currentBulkNames = stopNamesBulkText
                                            .replace("\r", "\n")
                                            .lineSequence()
                                            .map { it.trim() }
                                            .filter { it.isNotBlank() }
                                            .toList()
                                        val orderedPoints = currentBulkNames.mapNotNull { stopName ->
                                            stops.firstOrNull { it.first.equals(stopName, ignoreCase = true) }?.second
                                        }
                                        bulkLatLngText = orderedPoints.joinToString("\n") { "${it.latitude},${it.longitude}" }
                                        mk.showInfoWindow()
                                        true
                                    }
                                }
                                mapView.overlays.add(marker)
                            }

                            if (!hasAutoFittedMap) {
                                if (stops.isNotEmpty()) {
                                    val pts = stops.map { it.second }
                                    val minLat = pts.minOf { it.latitude }
                                    val maxLat = pts.maxOf { it.latitude }
                                    val minLng = pts.minOf { it.longitude }
                                    val maxLng = pts.maxOf { it.longitude }
                                    if (pts.size == 1) {
                                        mapView.controller.setCenter(pts.first())
                                        mapView.controller.setZoom(15.0)
                                    } else {
                                        mapView.zoomToBoundingBox(
                                            BoundingBox(maxLat, maxLng, minLat, minLng),
                                            true,
                                            120
                                        )
                                    }
                                }
                                hasAutoFittedMap = true
                            }
                            mapView.invalidate()
                        }
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            if (selectedIndex >= 0 && selectedIndex < stops.size) {
                                stops.removeAt(selectedIndex)
                                selectedIndex = -1
                                val bulkNamesNow = stopNamesBulkText
                                    .replace("\r", "\n")
                                    .lineSequence()
                                    .map { it.trim() }
                                    .filter { it.isNotBlank() }
                                    .toList()
                                pendingName = bulkNamesNow.getOrElse(selectedNameIndex) { "" }
                                manualLatLngText = ""
                                val orderedPoints = bulkNamesNow.mapNotNull { stopName ->
                                    stops.firstOrNull { it.first.equals(stopName, ignoreCase = true) }?.second
                                }
                                bulkLatLngText = orderedPoints.joinToString("\n") { "${it.latitude},${it.longitude}" }
                                refreshTick++
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Delete Selected")
                    }
                    OutlinedButton(
                        onClick = {
                            stops.clear()
                            selectedIndex = -1
                            val bulkNamesNow = stopNamesBulkText
                                .replace("\r", "\n")
                                .lineSequence()
                                .map { it.trim() }
                                .filter { it.isNotBlank() }
                                .toList()
                            selectedNameIndex = 0
                            pendingName = bulkNamesNow.firstOrNull().orEmpty()
                            manualLatLngText = ""
                            bulkLatLngText = ""
                            refreshTick++
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Clear All")
                    }
                }
            }
        }
    }
}

@Composable
private fun SeparateRoadMapEditorDialog(
    initialRoadRaw: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    val context = LocalContext.current
    val points: MutableList<GeoPoint> = remember(initialRoadRaw) { parseRoadOnlyEditorInputForDialog(initialRoadRaw) }
    var roadBulkText by remember {
        mutableStateOf(
            points.joinToString("\n") { "${it.latitude},${it.longitude}" }
        )
    }
    var selectedIndex by remember { mutableStateOf(-1) }
    var selectedInsertAfterIndex by remember { mutableIntStateOf(-1) }
    var roadInsertMode by remember { mutableStateOf(false) }
    var manualLatLngText by remember { mutableStateOf("") }
    var refreshTick by remember { mutableStateOf(0) }
    var hasAutoFittedMap by remember { mutableStateOf(false) }

    fun parseManualGeoPoint(raw: String): GeoPoint? {
        val cleaned = raw.trim().removePrefix("(").removeSuffix(")")
        val parts = cleaned.split(",").map { it.trim() }
        if (parts.size < 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lng = parts[1].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return GeoPoint(lat, lng)
    }

    fun parseBulkRoadPoints(raw: String): List<GeoPoint> {
        return raw
            .replace("\r", "\n")
            .replace(";", "\n")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { parseManualGeoPoint(it) }
            .toList()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Road Map Editor",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = onDismiss) { Text("Close") }
                    Button(
                        onClick = {
                            val out = points.joinToString("\n") { "${it.latitude},${it.longitude}" }
                            onSave(out)
                        }
                    ) { Text("Save") }
                }

                Text(
                    text = "Full road-er lat,lng list dile niche Apply All dile oi pura list map e polyline hisebe show hobe. Chaile map e tap kore individual point o add/edit korte parben.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )

                OutlinedTextField(
                    value = roadBulkText,
                    onValueChange = { roadBulkText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .padding(horizontal = 12.dp),
                    label = { Text("Full road lat,lng list") },
                    placeholder = {
                        Text(
                            "23.8749776,90.3228843\n23.8751200,90.3221100\n23.8754305,90.2874124"
                        )
                    },
                    maxLines = 100
                )

                OutlinedTextField(
                    value = manualLatLngText,
                    onValueChange = { manualLatLngText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    label = { Text("Selected road point lat,lng") },
                    placeholder = { Text("23.8749776,90.3228843") },
                    singleLine = true
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val parsed = parseBulkRoadPoints(roadBulkText)
                            if (parsed.isEmpty()) {
                                Toast.makeText(context, "Road lat,lng list empty or invalid", Toast.LENGTH_SHORT).show()
                            } else {
                                points.clear()
                                points.addAll(parsed)
                                selectedIndex = if (points.isNotEmpty()) 0 else -1
                                selectedInsertAfterIndex = selectedIndex
                                roadInsertMode = false
                                manualLatLngText = points.firstOrNull()?.let { "${it.latitude},${it.longitude}" }.orEmpty()
                                refreshTick++
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Apply All Road Points")
                    }

                    OutlinedButton(
                        onClick = {
                            val point = parseManualGeoPoint(manualLatLngText)
                            if (point == null) {
                                Toast.makeText(context, "Invalid lat,lng", Toast.LENGTH_SHORT).show()
                            } else {
                                val appliedIndex = if (roadInsertMode) {
                                    val insertIndex = if (selectedInsertAfterIndex in points.indices) {
                                        selectedInsertAfterIndex + 1
                                    } else {
                                        points.size
                                    }
                                    points.add(insertIndex, point)
                                    insertIndex
                                } else if (selectedIndex >= 0 && selectedIndex < points.size) {
                                    points[selectedIndex] = point
                                    selectedIndex
                                } else {
                                    points.add(point)
                                    points.lastIndex
                                }

                                selectedIndex = appliedIndex
                                selectedInsertAfterIndex = appliedIndex
                                roadInsertMode = false
                                manualLatLngText = "${point.latitude},${point.longitude}"
                                roadBulkText = points.joinToString("\n") { "${it.latitude},${it.longitude}" }
                                refreshTick++
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Set Selected")
                    }
                }

                OutlinedButton(
                    onClick = {
                        roadInsertMode = true
                        if (selectedIndex in points.indices) {
                            selectedInsertAfterIndex = selectedIndex
                        }
                    },
                    enabled = selectedIndex in points.indices,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                ) {
                    Text(
                        if (roadInsertMode)
                            "Insert Mode ON: Map tap korlei add hobe"
                        else
                            "Enable Insert After Selected Point"
                    )
                }

                Text(
                    text = if (roadInsertMode)
                        "Insert mode on thakle map a tap korlei selected point er pore notun point add hobe. Baki serial automatically shift hobe."
                    else
                        "Prothome kono existing point select koro, tarpor insert mode on kore map a tap kore new point add koro. Chaile manual lat,lng diye Set Selected o korte parba.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = true)
                        .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                    shape = RoundedCornerShape(16.dp),
                    tonalElevation = 2.dp,
                    shadowElevation = 2.dp
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            Configuration.getInstance().load(ctx, ctx.getSharedPreferences("osmdroid", android.content.Context.MODE_PRIVATE))
                            MapView(ctx).apply {
                                setTileSource(TileSourceFactory.MAPNIK)
                                setMultiTouchControls(true)
                                val initialPoint = points.firstOrNull() ?: GeoPoint(23.8103, 90.4125)
                                if (points.size == 1) {
                                    controller.setZoom(15.0)
                                    controller.setCenter(initialPoint)
                                } else if (points.size > 1) {
                                    val minLat = points.minOf { it.latitude }
                                    val maxLat = points.maxOf { it.latitude }
                                    val minLng = points.minOf { it.longitude }
                                    val maxLng = points.maxOf { it.longitude }
                                    zoomToBoundingBox(
                                        BoundingBox(maxLat, maxLng, minLat, minLng),
                                        true,
                                        120
                                    )
                                } else {
                                    controller.setZoom(12.5)
                                    controller.setCenter(initialPoint)
                                }
                            }
                        },
                        update = { mapView ->
                            refreshTick
                            mapView.overlays.clear()

                            val mapEvents = MapEventsOverlay(object : MapEventsReceiver {
                                override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                                    val point = p ?: return true

                                    if (roadInsertMode) {
                                        val insertIndex = if (selectedInsertAfterIndex in points.indices) {
                                            selectedInsertAfterIndex + 1
                                        } else {
                                            points.size
                                        }
                                        points.add(insertIndex, point)
                                        selectedIndex = insertIndex
                                        selectedInsertAfterIndex = insertIndex
                                        roadInsertMode = false
                                        manualLatLngText = "${point.latitude},${point.longitude}"
                                        roadBulkText = points.joinToString("\n") { "${it.latitude},${it.longitude}" }
                                        refreshTick++
                                        return true
                                    }

                                    manualLatLngText = "${point.latitude},${point.longitude}"
                                    if (points.isEmpty()) {
                                        selectedIndex = -1
                                        selectedInsertAfterIndex = -1
                                    }
                                    refreshTick++
                                    return true
                                }

                                override fun longPressHelper(p: GeoPoint?): Boolean = false
                            })
                            mapView.overlays.add(mapEvents)

                            val polyline = Polyline().apply {
                                setPoints(points)
                                outlinePaint.color = AndroidColor.parseColor("#00BFA5")
                                outlinePaint.strokeWidth = 5f
                            }
                            if (points.size >= 2) mapView.overlays.add(polyline)

                            points.forEachIndexed { index, point ->
                                val markerBitmap = android.graphics.Bitmap.createBitmap(
                                    52,
                                    52,
                                    android.graphics.Bitmap.Config.ARGB_8888
                                )
                                val markerCanvas = android.graphics.Canvas(markerBitmap)
                                val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                    color = if (index == selectedIndex) AndroidColor.parseColor("#F59E0B") else AndroidColor.parseColor("#0EA5E9")
                                    style = android.graphics.Paint.Style.FILL
                                }
                                val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                    color = AndroidColor.WHITE
                                    textAlign = android.graphics.Paint.Align.CENTER
                                    textSize = 24f
                                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                                }
                                markerCanvas.drawCircle(26f, 26f, 22f, fillPaint)
                                val textY = 26f - ((textPaint.descent() + textPaint.ascent()) / 2f)
                                markerCanvas.drawText((index + 1).toString(), 26f, textY, textPaint)
                                val numberedIcon = android.graphics.drawable.BitmapDrawable(mapView.context.resources, markerBitmap)

                                val marker = Marker(mapView).apply {
                                    position = point
                                    title = "Road Point ${index + 1}"
                                    icon = numberedIcon
                                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                    setOnMarkerClickListener { mk, _ ->
                                        selectedIndex = index
                                        selectedInsertAfterIndex = index
                                        manualLatLngText = "${point.latitude},${point.longitude}"
                                        mk.showInfoWindow()
                                        true
                                    }
                                }
                                mapView.overlays.add(marker)
                            }

                            if (!hasAutoFittedMap) {
                                if (points.isNotEmpty()) {
                                    val minLat = points.minOf { it.latitude }
                                    val maxLat = points.maxOf { it.latitude }
                                    val minLng = points.minOf { it.longitude }
                                    val maxLng = points.maxOf { it.longitude }
                                    if (points.size == 1) {
                                        mapView.controller.setCenter(points.first())
                                        mapView.controller.setZoom(15.0)
                                    } else {
                                        mapView.zoomToBoundingBox(
                                            BoundingBox(maxLat, maxLng, minLat, minLng),
                                            true,
                                            120
                                        )
                                    }
                                }
                                hasAutoFittedMap = true
                            }
                            mapView.invalidate()
                        }
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            if (selectedIndex >= 0 && selectedIndex < points.size) {
                                points.removeAt(selectedIndex)
                                if (points.isEmpty()) {
                                    selectedIndex = -1
                                    selectedInsertAfterIndex = -1
                                    manualLatLngText = ""
                                    roadInsertMode = false
                                } else {
                                    selectedIndex = selectedIndex.coerceAtMost(points.lastIndex)
                                    selectedInsertAfterIndex = selectedIndex.coerceAtLeast(0)
                                    manualLatLngText = points.getOrNull(selectedIndex)?.let { "${it.latitude},${it.longitude}" }.orEmpty()
                                }
                                roadBulkText = points.joinToString("\n") { "${it.latitude},${it.longitude}" }
                                refreshTick++
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Delete Selected")
                    }
                    OutlinedButton(
                        onClick = {
                            if (points.isNotEmpty()) {
                                points.removeAt(points.lastIndex)
                                if (points.isEmpty()) {
                                    selectedIndex = -1
                                    selectedInsertAfterIndex = -1
                                    manualLatLngText = ""
                                    roadInsertMode = false
                                } else {
                                    if (selectedIndex !in points.indices) {
                                        selectedIndex = points.lastIndex
                                    }
                                    if (selectedInsertAfterIndex !in points.indices) {
                                        selectedInsertAfterIndex = points.lastIndex
                                    }
                                    manualLatLngText = points.getOrNull(selectedIndex)?.let { "${it.latitude},${it.longitude}" }.orEmpty()
                                }
                                roadBulkText = points.joinToString("\n") { "${it.latitude},${it.longitude}" }
                                refreshTick++
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Undo Last")
                    }
                    OutlinedButton(
                        onClick = {
                            points.clear()
                            selectedIndex = -1
                            selectedInsertAfterIndex = -1
                            manualLatLngText = ""
                            roadBulkText = ""
                            roadInsertMode = false
                            refreshTick++
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Clear All")
                    }
                }
            }
        }
    }
}

fun stopNamesToText(names: List<String>): String {
    return names.joinToString("\n")
}

fun syncStopNamesWithPointCount(currentText: String, count: Int): String {
    val existing = currentText
        .replace("\r", "\n")
        .replace("<>", "\n")
        .replace(";", "\n")
        .lineSequence()
        .map { it.trim() }
        .toMutableList()

    while (existing.size < count) {
        existing.add("")
    }
    while (existing.size > count) {
        existing.removeAt(existing.lastIndex)
    }
    return stopNamesToText(existing)
}

private suspend fun findFailedRoadPreviewSegments(points: List<GeoPoint>): List<Int> =
    withContext(Dispatchers.IO) {
        if (points.size < 2) return@withContext emptyList()

        fun openJson(url: String): JSONObject? {
            var conn: java.net.HttpURLConnection? = null
            return try {
                conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 3500
                    readTimeout = 3500
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                }
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                JSONObject(text)
            } catch (_: Exception) {
                null
            } finally {
                conn?.disconnect()
            }
        }

        fun snapPointToNearestRoad(point: GeoPoint): GeoPoint {
            return try {
                val url = "https://router.project-osrm.org/nearest/v1/driving/${point.longitude},${point.latitude}?number=1"
                val json = openJson(url) ?: return point
                val waypoints = json.optJSONArray("waypoints") ?: return point
                if (waypoints.length() == 0) return point
                val location = waypoints.getJSONObject(0).optJSONArray("location") ?: return point
                if (location.length() < 2) return point
                val lng = location.getDouble(0)
                val lat = location.getDouble(1)
                GeoPoint(lat, lng)
            } catch (_: Exception) {
                point
            }
        }

        fun hasRoadRoute(a: GeoPoint, b: GeoPoint): Boolean {
            return try {
                val snappedA = snapPointToNearestRoad(a)
                val snappedB = snapPointToNearestRoad(b)
                val coords = "${snappedA.longitude},${snappedA.latitude};${snappedB.longitude},${snappedB.latitude}"
                val url = "https://router.project-osrm.org/route/v1/driving/$coords?overview=false&steps=false"
                val json = openJson(url) ?: return false
                val routes = json.optJSONArray("routes") ?: return false
                routes.length() > 0
            } catch (_: Exception) {
                false
            }
        }

        val failed = mutableListOf<Int>()
        for (i in 0 until points.lastIndex) {
            if (!hasRoadRoute(points[i], points[i + 1])) {
                failed.add(i)
            }
        }
        return@withContext failed
    }

// Inserted helper for midpoint retry on failed segments
private fun insertMidpointsForFailedSegments(
    points: List<GeoPoint>,
    failedIndexes: List<Int>
): List<GeoPoint> {
    if (points.size < 2 || failedIndexes.isEmpty()) return points

    val failedSet = failedIndexes.toSet()
    val refined = mutableListOf<GeoPoint>()

    for (i in 0 until points.lastIndex) {
        val start = points[i]
        val end = points[i + 1]
        refined.add(start)

        if (failedSet.contains(i)) {
            val mid1 = GeoPoint(
                start.latitude + (end.latitude - start.latitude) * 0.33,
                start.longitude + (end.longitude - start.longitude) * 0.33
            )
            val mid2 = GeoPoint(
                start.latitude + (end.latitude - start.latitude) * 0.66,
                start.longitude + (end.longitude - start.longitude) * 0.66
            )
            refined.add(mid1)
            refined.add(mid2)
        }
    }

    refined.add(points.last())
    return refined
}
private suspend fun buildBestRoadPolylineFromAnchors(points: List<GeoPoint>): List<GeoPoint> {
    if (points.size < 2) return points

    val firstTry = snapPolylineToRoadForAdmin(points)
    val firstFailed = findFailedRoadPreviewSegments(points)
    if (firstTry.size >= 2 && firstFailed.isEmpty()) return firstTry

    val refined33 = insertMidpointsForFailedSegments(points, firstFailed)
    val secondTry = snapPolylineToRoadForAdmin(refined33)
    val secondFailed = findFailedRoadPreviewSegments(refined33)

    val refinedDense = buildList {
        add(points.first())
        for (i in 0 until points.lastIndex) {
            val start = points[i]
            val end = points[i + 1]
            add(
                GeoPoint(
                    start.latitude + (end.latitude - start.latitude) * 0.20,
                    start.longitude + (end.longitude - start.longitude) * 0.20
                )
            )
            add(
                GeoPoint(
                    start.latitude + (end.latitude - start.latitude) * 0.40,
                    start.longitude + (end.longitude - start.longitude) * 0.40
                )
            )
            add(
                GeoPoint(
                    start.latitude + (end.latitude - start.latitude) * 0.60,
                    start.longitude + (end.longitude - start.longitude) * 0.60
                )
            )
            add(
                GeoPoint(
                    start.latitude + (end.latitude - start.latitude) * 0.80,
                    start.longitude + (end.longitude - start.longitude) * 0.80
                )
            )
            add(end)
        }
    }

    val thirdTry = snapPolylineToRoadForAdmin(refinedDense)
    val thirdFailed = findFailedRoadPreviewSegments(refinedDense)

    return when {
        thirdTry.size >= 2 && thirdFailed.isEmpty() -> thirdTry
        secondTry.size >= 2 && secondFailed.isEmpty() -> secondTry
        thirdTry.size >= 2 && thirdFailed.size < secondFailed.size -> thirdTry
        secondTry.size >= 2 && secondFailed.size < firstFailed.size -> secondTry
        firstTry.size >= 2 -> firstTry
        else -> points
    }
}
private suspend fun snapPolylineToRoadForAdmin(points: List<GeoPoint>): List<GeoPoint> =
    withContext(Dispatchers.IO) {
        if (points.size < 2) return@withContext emptyList()

        fun openJson(url: String): JSONObject? {
            var conn: java.net.HttpURLConnection? = null
            return try {
                conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                }
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                JSONObject(text)
            } catch (_: Exception) {
                null
            } finally {
                conn?.disconnect()
            }
        }

        fun decodeCoords(coords: org.json.JSONArray): List<GeoPoint> {
            val out = mutableListOf<GeoPoint>()
            for (i in 0 until coords.length()) {
                val pair = coords.getJSONArray(i)
                val lng = pair.getDouble(0)
                val lat = pair.getDouble(1)
                out.add(GeoPoint(lat, lng))
            }
            return out
        }

        fun distanceMeters(a: GeoPoint, b: GeoPoint): Float {
            val results = FloatArray(1)
            android.location.Location.distanceBetween(
                a.latitude, a.longitude,
                b.latitude, b.longitude,
                results
            )
            return results[0]
        }
        fun midpoint(a: GeoPoint, b: GeoPoint): GeoPoint {
            return GeoPoint(
                (a.latitude + b.latitude) / 2.0,
                (a.longitude + b.longitude) / 2.0
            )
        }

        fun dedupeNearby(pointsIn: List<GeoPoint>, minMeters: Float = 3f): List<GeoPoint> {
            if (pointsIn.isEmpty()) return emptyList()
            val out = mutableListOf<GeoPoint>()
            var last = pointsIn.first()
            out.add(last)
            for (i in 1 until pointsIn.size) {
                val p = pointsIn[i]
                if (distanceMeters(last, p) >= minMeters) {
                    out.add(p)
                    last = p
                }
            }
            if (out.last() != pointsIn.last()) {
                out.add(pointsIn.last())
            }
            return out
        }

        fun simplifyInput(input: List<GeoPoint>): List<GeoPoint> {
            if (input.size <= 2) return input
            val simplified = mutableListOf<GeoPoint>()
            simplified.add(input.first())
            var lastKept = input.first()

            for (i in 1 until input.lastIndex) {
                val current = input[i]
                if (distanceMeters(lastKept, current) >= 25f) {
                    simplified.add(current)
                    lastKept = current
                }
            }

            if (simplified.last() != input.last()) {
                simplified.add(input.last())
            }
            return simplified
        }

        fun snapPointToNearestRoad(point: GeoPoint): GeoPoint {
            return try {
                val url = "https://router.project-osrm.org/nearest/v1/driving/${point.longitude},${point.latitude}?number=1"
                val json = openJson(url) ?: return point
                val waypoints = json.optJSONArray("waypoints") ?: return point
                if (waypoints.length() == 0) return point
                val location = waypoints.getJSONObject(0).optJSONArray("location") ?: return point
                if (location.length() < 2) return point
                val lng = location.getDouble(0)
                val lat = location.getDouble(1)
                GeoPoint(lat, lng)
            } catch (_: Exception) {
                point
            }
        }

        fun matchWholeTrace(input: List<GeoPoint>): List<GeoPoint> {
            return try {
                val simplified = simplifyInput(input)
                if (simplified.size < 2 || simplified.size > 24) return emptyList()
                val snappedInput = simplified.map { snapPointToNearestRoad(it) }
                val coords = snappedInput.joinToString(";") { "${it.longitude},${it.latitude}" }
                val url = "https://router.project-osrm.org/match/v1/driving/$coords?overview=full&geometries=geojson&steps=false&gaps=ignore&tidy=true"
                val json = openJson(url) ?: return emptyList()
                val matchings = json.optJSONArray("matchings") ?: return emptyList()
                if (matchings.length() == 0) return emptyList()

                val merged = mutableListOf<GeoPoint>()
                for (i in 0 until matchings.length()) {
                    val geometry = matchings.getJSONObject(i)
                        .optJSONObject("geometry")
                        ?.optJSONArray("coordinates") ?: continue
                    val pts = decodeCoords(geometry)
                    if (pts.isEmpty()) continue
                    if (merged.isEmpty()) merged.addAll(pts) else merged.addAll(pts.drop(1))
                }
                if (merged.size >= 2) merged else emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun routeSegment(a: GeoPoint, b: GeoPoint): List<GeoPoint> {
            fun directRoute(x: GeoPoint, y: GeoPoint): List<GeoPoint> {
                return try {
                    val snappedA = snapPointToNearestRoad(x)
                    val snappedB = snapPointToNearestRoad(y)
                    val coords = "${snappedA.longitude},${snappedA.latitude};${snappedB.longitude},${snappedB.latitude}"
                    val url = "https://router.project-osrm.org/route/v1/driving/$coords?overview=full&geometries=geojson&steps=false"

                    val json = openJson(url) ?: return emptyList()
                    val routes = json.optJSONArray("routes") ?: return emptyList()
                    if (routes.length() == 0) return emptyList()

                    val geometry = routes.getJSONObject(0)
                        .getJSONObject("geometry")
                        .getJSONArray("coordinates")

                    val snapped = decodeCoords(geometry)
                    if (snapped.size >= 2) snapped else emptyList()
                } catch (_: Exception) {
                    emptyList()
                }
            }

            val direct = directRoute(a, b)
            if (direct.size >= 2) return direct

            val mid = midpoint(a, b)
            val first = directRoute(a, mid)
            val second = directRoute(mid, b)

            return when {
                first.size >= 2 && second.size >= 2 -> first + second.drop(1)
                first.size >= 2 -> first
                second.size >= 2 -> second
                else -> emptyList()
            }
        }

        val matchedTrace = dedupeNearby(matchWholeTrace(points))
        if (matchedTrace.size >= 2) return@withContext matchedTrace

        val simplifiedPoints = simplifyInput(points)
        if (simplifiedPoints.size < 2) return@withContext emptyList()

        val merged = mutableListOf<GeoPoint>()
        for (i in 0 until simplifiedPoints.lastIndex) {
            val start = simplifiedPoints[i]
            val end = simplifiedPoints[i + 1]
            val seg = routeSegment(start, end)
            if (seg.size >= 2) {
                if (merged.isEmpty()) {
                    merged.addAll(seg)
                } else {
                    merged.addAll(seg.drop(1))
                }
            } else {
                val snappedStart = snapPointToNearestRoad(start)
                val snappedEnd = snapPointToNearestRoad(end)
                if (merged.isEmpty()) {
                    merged.add(snappedStart)
                    merged.add(snappedEnd)
                } else {
                    if (distanceMeters(merged.last(), snappedStart) >= 3f) {
                        merged.add(snappedStart)
                    }
                    if (distanceMeters(merged.last(), snappedEnd) >= 3f) {
                        merged.add(snappedEnd)
                    }
                }
            }
        }

        return@withContext dedupeNearby(merged)
    }

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ManualRouteLeafletMap(
    polylineText: String,
    useExactDrawnRoad: Boolean,
    stopNames: List<String>,
    mapJumpTarget: GeoPoint?,
    searchedMarkerPoint: GeoPoint?,
    onJumpHandled: () -> Unit,
    onPointAdded: (Double, Double) -> Unit,
    onReplaceText: (String) -> Unit,
    onMarkerTap: (Int) -> Unit
) {
    val dhakaCenter = remember { GeoPoint(23.8103, 90.4125) }

    val anchorPoints = remember(polylineText) {
        polylineText
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split(",").map { it.trim() }
                val lat = parts.getOrNull(0)?.toDoubleOrNull()
                val lng = parts.getOrNull(1)?.toDoubleOrNull()
                if (lat != null && lng != null) GeoPoint(lat, lng) else null
            }
            .toList()
    }

    var displayPoints by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }
    var failedSegmentIndexes by remember { mutableStateOf<List<Int>>(emptyList()) }
    var hasAutoFitted by remember { mutableStateOf(false) }
    var lastAutoFitKey by remember { mutableStateOf("") }

    fun pointsToText(list: List<GeoPoint>): String {
        return list.joinToString("\n") { p ->
            String.format(Locale.US, "%.7f,%.7f", p.latitude, p.longitude)
        }
    }

    LaunchedEffect(anchorPoints, useExactDrawnRoad) {
        if (anchorPoints.size >= 2) {
            if (useExactDrawnRoad) {
                displayPoints = anchorPoints
                failedSegmentIndexes = emptyList()
            } else {
                val snapped = buildBestRoadPolylineFromAnchors(anchorPoints)
                val failed = findFailedRoadPreviewSegments(anchorPoints)

                if (snapped.size >= 2 && failed.isEmpty()) {
                    failedSegmentIndexes = emptyList()
                    displayPoints = snapped
                } else {
                    val densePoints = buildList {
                        add(anchorPoints.first())
                        for (i in 0 until anchorPoints.lastIndex) {
                            val start = anchorPoints[i]
                            val end = anchorPoints[i + 1]
                            add(
                                GeoPoint(
                                    start.latitude + (end.latitude - start.latitude) * 0.25,
                                    start.longitude + (end.longitude - start.longitude) * 0.25
                                )
                            )
                            add(
                                GeoPoint(
                                    start.latitude + (end.latitude - start.latitude) * 0.50,
                                    start.longitude + (end.longitude - start.longitude) * 0.50
                                )
                            )
                            add(
                                GeoPoint(
                                    start.latitude + (end.latitude - start.latitude) * 0.75,
                                    start.longitude + (end.longitude - start.longitude) * 0.75
                                )
                            )
                            add(end)
                        }
                    }

                    val refinedSnapped = snapPolylineToRoadForAdmin(densePoints)
                    val refinedFailed = findFailedRoadPreviewSegments(anchorPoints)

                    if (refinedSnapped.size >= 2) {
                        failedSegmentIndexes = if (refinedFailed.size < failed.size) refinedFailed else failed
                        displayPoints = refinedSnapped
                    } else {
                        failedSegmentIndexes = failed
                        displayPoints = anchorPoints
                    }
                }
            }
        } else {
            displayPoints = emptyList()
            failedSegmentIndexes = emptyList()
        }
    }
    LaunchedEffect(anchorPoints, displayPoints, mapJumpTarget, searchedMarkerPoint) {
        val fitKey = buildString {
            append(anchorPoints.size)
            append("|")
            append(displayPoints.size)
            append("|")
            append(mapJumpTarget?.latitude ?: 0.0)
            append(",")
            append(mapJumpTarget?.longitude ?: 0.0)
            append("|")
            append(searchedMarkerPoint?.latitude ?: 0.0)
            append(",")
            append(searchedMarkerPoint?.longitude ?: 0.0)
        }
        if (fitKey != lastAutoFitKey) {
            lastAutoFitKey = fitKey
            hasAutoFitted = false
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            Configuration.getInstance().load(
                ctx,
                ctx.getSharedPreferences("osmdroid_admin_map", 0)
            )
            Configuration.getInstance().userAgentValue = ctx.packageName

            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)

                // enable pinch zoom
                setMultiTouchControls(true)

                // show zoom in / zoom out buttons on map
                setBuiltInZoomControls(true)
                zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.ALWAYS)

                // initial camera
                controller.setZoom(11.0)
                controller.setCenter(dhakaCenter)
            }
        },
        update = { mapView ->
            mapJumpTarget?.let { target ->
                mapView.controller.animateTo(target)
                if (mapView.zoomLevelDouble < 17.0) {
                    mapView.controller.setZoom(17.0)
                }
                hasAutoFitted = true
                onJumpHandled()
            }
            mapView.overlays.clear()

            searchedMarkerPoint?.let { point ->
                val searchMarker = Marker(mapView).apply {
                    position = point
                    title = "Searched point"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    icon = android.graphics.drawable.ShapeDrawable(
                        android.graphics.drawable.shapes.OvalShape()
                    ).apply {
                        intrinsicWidth = 28
                        intrinsicHeight = 28
                        paint.color = AndroidColor.parseColor("#DC2626")
                    }
                }
                mapView.overlays.add(searchMarker)
            }

            if (displayPoints.size >= 2) {
                val routePolyline = Polyline().apply {
                    setPoints(displayPoints)
                    outlinePaint.color = AndroidColor.parseColor("#2563EB")
                    outlinePaint.strokeWidth = 8f
                }
                mapView.overlays.add(routePolyline)
            }

            if (anchorPoints.isNotEmpty()) {
                anchorPoints.forEachIndexed { index, point ->
                    val stopName = stopNames.getOrNull(index)?.trim().orEmpty()
                    val hasFailedPrev = failedSegmentIndexes.contains(index - 1)
                    val hasFailedNext = failedSegmentIndexes.contains(index)
                    val failureSuffix = when {
                        hasFailedPrev && hasFailedNext -> " • road preview failed on both sides"
                        hasFailedPrev -> " • road preview failed from previous point"
                        hasFailedNext -> " • road preview failed to next point"
                        else -> ""
                    }
                    val markerTitle = if (stopName.isBlank()) {
                        "Point ${index + 1}$failureSuffix"
                    } else {
                        "Point ${index + 1} • $stopName • visible on user map$failureSuffix"
                    }

                    val markerBitmap = android.graphics.Bitmap.createBitmap(
                        52,
                        52,
                        android.graphics.Bitmap.Config.ARGB_8888
                    )
                    val markerCanvas = android.graphics.Canvas(markerBitmap)
                    val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = when {
                            hasFailedPrev || hasFailedNext -> AndroidColor.parseColor("#DC2626")
                            stopName.isNotBlank() -> AndroidColor.parseColor("#16A34A")
                            else -> AndroidColor.parseColor("#2563EB")
                        }
                        style = android.graphics.Paint.Style.FILL
                    }
                    val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = AndroidColor.WHITE
                        textAlign = android.graphics.Paint.Align.CENTER
                        textSize = 24f
                        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                    }
                    markerCanvas.drawCircle(26f, 26f, 22f, fillPaint)
                    val textY = 26f - ((textPaint.descent() + textPaint.ascent()) / 2f)
                    markerCanvas.drawText((index + 1).toString(), 26f, textY, textPaint)
                    val numberedIcon = android.graphics.drawable.BitmapDrawable(mapView.context.resources, markerBitmap)

                    val marker = Marker(mapView).apply {
                        position = point
                        title = markerTitle
                        snippet = if (stopName.isBlank()) {
                            "Unnamed point. Tap to add a visible stop name for Point ${index + 1}"
                        } else {
                            "Named point. This point will be visible on user map as $stopName"
                        }
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        icon = numberedIcon
                        setOnMarkerClickListener { clickedMarker, _ ->
                            clickedMarker.showInfoWindow()
                            onMarkerTap(index)
                            true
                        }
                    }
                    mapView.overlays.add(marker)
                }

                failedSegmentIndexes.forEach { failedIndex ->
                    val start = anchorPoints.getOrNull(failedIndex) ?: return@forEach
                    val end = anchorPoints.getOrNull(failedIndex + 1) ?: return@forEach
                    val mid = GeoPoint(
                        (start.latitude + end.latitude) / 2.0,
                        (start.longitude + end.longitude) / 2.0
                    )
                    val warningMarker = Marker(mapView).apply {
                        position = mid
                        title = "Road preview failed: Point ${failedIndex + 1} → Point ${failedIndex + 2}"
                        snippet = "Add one or more road-side points between these two points."
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        icon = android.graphics.drawable.ShapeDrawable(
                            android.graphics.drawable.shapes.OvalShape()
                        ).apply {
                            intrinsicWidth = 26
                            intrinsicHeight = 26
                            paint.color = AndroidColor.parseColor("#F59E0B")
                        }
                    }
                    mapView.overlays.add(warningMarker)
                }

                if (!hasAutoFitted) {
                    val previewBoundsPoints = if (displayPoints.size >= 2) displayPoints else anchorPoints
                    if (previewBoundsPoints.size == 1) {
                        mapView.controller.setZoom(15.0)
                        mapView.controller.setCenter(previewBoundsPoints.first())
                        hasAutoFitted = true
                    } else if (previewBoundsPoints.size > 1) {
                        val minLat = previewBoundsPoints.minOf { it.latitude }
                        val maxLat = previewBoundsPoints.maxOf { it.latitude }
                        val minLng = previewBoundsPoints.minOf { it.longitude }
                        val maxLng = previewBoundsPoints.maxOf { it.longitude }
                        val box = BoundingBox(maxLat, maxLng, minLat, minLng)
                        mapView.zoomToBoundingBox(box, true, 120)
                        hasAutoFitted = true
                    }
                }
            } else {
                mapView.controller.setZoom(11.0)
                mapView.controller.setCenter(dhakaCenter)
            }

            val tapOverlay = MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                    if (p != null) {
                        onPointAdded(p.latitude, p.longitude)
                    }
                    return true
                }

                override fun longPressHelper(p: GeoPoint?): Boolean {
                    val pressed = p ?: return false
                    if (anchorPoints.isEmpty()) return false

                    val nearestIndex = anchorPoints.indices.minByOrNull { idx ->
                        val point = anchorPoints[idx]
                        val result = FloatArray(1)
                        android.location.Location.distanceBetween(
                            pressed.latitude,
                            pressed.longitude,
                            point.latitude,
                            point.longitude,
                            result
                        )
                        result[0]
                    } ?: return false

                    val nearestPoint = anchorPoints[nearestIndex]
                    val distanceResult = FloatArray(1)
                    android.location.Location.distanceBetween(
                        pressed.latitude,
                        pressed.longitude,
                        nearestPoint.latitude,
                        nearestPoint.longitude,
                        distanceResult
                    )
                    val meters = distanceResult[0]

                    if (meters <= 120f) {
                        val updated = anchorPoints.toMutableList().apply { removeAt(nearestIndex) }
                        onReplaceText(pointsToText(updated))
                        return true
                    }
                    return false
                }
            })
            mapView.overlays.add(tapOverlay)

            mapView.invalidate()
        }
    )
}
