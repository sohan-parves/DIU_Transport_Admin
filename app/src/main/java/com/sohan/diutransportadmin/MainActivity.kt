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
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import com.google.firebase.messaging.FirebaseMessaging
import com.sohan.diutransportadmin.ui.AdminScreen
import com.sohan.diutransportadmin.ui.LoginScreen
// (keep only one of each)
// (imports cleaned up below)
// Remove duplicate imports

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
    private val auth by lazy { Firebase.auth }
    private val adminAuthReady = mutableStateOf(false)

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
                bumpRouteMapVersion(
                    message = "Separated route map updated from Admin",
                    extra = mapOf("routeNo" to routeNo)
                )
            }
            .addOnFailureListener { e ->
                isLoading.value = false
                progressPercent.value = 0
                progressLabel.value = ""
                status.value = "FAILED ❌ ${FirestoreWriteHints.fromException(e)}"
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


    private val adminAuthManager by lazy {
        AdminAuthManager(
            status = status,
            adminAuthReady = adminAuthReady
        )
    }

    private val noticeManager by lazy {
        NoticeManager(
            context = this,
            db = db,
            status = status,
            isLoading = isLoading,
            progressPercent = progressPercent,
            progressLabel = progressLabel,
            adminAuth = adminAuthManager,
            notificationManager = adminNotificationManager
        )
    }

    private val adminNotificationManager by lazy {
        AdminNotificationManager(
            context = this,
            db = db,
            scope = lifecycleScope,
            status = status,
            isLoading = isLoading,
            progressPercent = progressPercent,
            progressLabel = progressLabel
        )
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure this admin device receives admin pushes (topic-based).
        FirebaseMessaging.getInstance().subscribeToTopic("diu_admin")
        FirebaseMessaging.getInstance().subscribeToTopic("diu_transport")


        // ==============================
        // ✅ Connect Admin app to Firestore Emulator (DEV only)
        // ==============================
        if (USE_EMULATOR) {
            FirebaseFirestore.getInstance().useEmulator(EMULATOR_HOST, EMULATOR_FIRESTORE_PORT)
        }
        adminAuthManager.ensureAdminAuth()

        // Last uploaded file names live on schedule doc (meta/app = version + message only)
        db.collection("schedules").document("current")
            .collection("data").document("items")
            .addSnapshotListener { snap, _ ->
                val arr = snap?.get("sourceFiles")
                val list = when (arr) {
                    is List<*> -> arr.filterIsInstance<String>()
                    else -> emptyList()
                }
                lastUploadedFiles.value = list
            }

        setContent {
            MaterialTheme(colorScheme = AdminDarkColorScheme) {
                if (!adminAuthReady.value) {
                    LoginScreen(
                        isLoading = isLoading.value,
                        statusMessage = status.value,
                        onLoginSubmit = { email, password ->
                            isLoading.value = true
                            status.value = "Authenticating..."
                            adminAuthManager.loginWithEmail(
                                email = email,
                                pass = password,
                                onSuccess = {
                                    isLoading.value = false
                                },
                                onFailure = {
                                    isLoading.value = false
                                }
                            )
                        }
                    )
                } else {
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
                        onSendAdminPush = { title, body, target, incrementVersion, onDone ->
                            sendAdminPush(title, body, target, incrementVersion, onDone)
                        },
                        onPublishNotice = { onDone ->
                            noticeManager.publishNoticeToUsers(
                                title = noticeTitleState.value,
                                body = noticeBodyState.value,
                                releaseDateRaw = noticeDateState.value,
                                onSuccess = onDone,
                                onFailure = { }
                            )
                        },
                    onLogout = { adminAuthManager.logout() },
                    onCleanupNotices = { noticeManager.cleanupOldNotices() },
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
        adminAuthManager.ensureAdminAuth {
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

            val batch = db.batch()
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
                    "message" to "Schedule updated from Admin"
                )
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
            batch.commit().addOnSuccessListener {
                isLoading.value = false
                status.value = "DONE ✅ Uploaded ${items.size} route(s). Schedule fully replaced. Map version unchanged."
                progressPercent.value = 0
                progressLabel.value = ""
                Toast.makeText(
                    this@MainActivity,
                    "DONE ✅ Uploaded ${items.size} route(s)\nSchedule fully replaced. Map version unchanged.",
                    Toast.LENGTH_LONG
                ).show()
            }.addOnFailureListener { e ->
                isLoading.value = false
                val msg = FirestoreWriteHints.fromException(e)
                status.value = "FAILED ❌ $msg"
                progressPercent.value = 0
                progressLabel.value = ""
                Toast.makeText(
                    this@MainActivity,
                    "FAILED ❌ $msg",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    private fun bumpRouteMapVersion(
        message: String = "Route map updated from Admin",
        extra: Map<String, Any> = emptyMap(),
        onDone: (() -> Unit)? = null
    ) {
        adminAuthManager.ensureAdminAuth {
            val routeMapMetaRef = db.collection("meta").document("route_maps")
            val payload = hashMapOf<String, Any>(
                "version" to FieldValue.increment(1),
                "message" to message,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            payload.putAll(extra)

            routeMapMetaRef
                .set(payload, SetOptions.merge())
                .addOnSuccessListener { onDone?.invoke() }
                .addOnFailureListener { onDone?.invoke() }
        }
    }

    private fun sendAdminPush(
        title: String,
        body: String,
        target: String,
        incrementVersion: Boolean,
        onSuccess: (() -> Unit)? = null
    ) {
        val cleanTitle = title.trim().ifBlank { "DIU Transport Schedule" }
        val cleanBody = body.trim()
        val cleanTarget = target.trim().ifBlank { "diu_admin" }

        if (cleanBody.isBlank()) {
            status.value = "Message is empty"
            return
        }

        adminAuthManager.ensureAdminAuth {
            adminNotificationManager.sendAdminPush(
                title = cleanTitle,
                body = cleanBody,
                target = cleanTarget,
                incrementVersion = incrementVersion,
                onDone = onSuccess
            )
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
