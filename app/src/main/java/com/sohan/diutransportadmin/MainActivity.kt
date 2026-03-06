package com.sohan.diutransportadmin

import android.location.Geocoder
import android.os.Build
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private suspend fun geocodeStop(stopNameRaw: String): Pair<Double, Double>? {
        val stopName = stopNameRaw.trim()
        if (stopName.isBlank()) return null

        val geocoder = Geocoder(this@MainActivity, Locale.getDefault())
        val queries = listOf(
            "$stopName, Dhaka, Bangladesh",
            "$stopName, Savar, Dhaka, Bangladesh",
            "$stopName, Ashulia, Dhaka, Bangladesh",
            "$stopName, Uttara, Dhaka, Bangladesh",
            "$stopName, Mirpur, Dhaka, Bangladesh",
            "$stopName, Bangladesh",
            stopName
        )

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
                        latch.await()
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

    private suspend fun enrichRoutesWithMapData(items: List<Map<String, Any>>): List<Map<String, Any>> {
        val cache = linkedMapOf<String, Pair<Double, Double>?>()

        suspend fun cachedGeocode(stop: String): Pair<Double, Double>? {
            return if (cache.containsKey(stop)) {
                cache[stop]
            } else {
                val value = geocodeStop(stop)
                cache[stop] = value
                value
            }
        }

        return items.map { item ->
            val routeDetails = (item["routeDetails"] as? String).orEmpty().trim()
            if (routeDetails.isBlank()) return@map item

            val stopNames = splitRouteDetails(routeDetails)
            val routeStops = mutableListOf<Map<String, Any>>()
            val routePolyline = mutableListOf<Map<String, Any>>()

            stopNames.forEachIndexed { index, stopName ->
                val latLng = cachedGeocode(stopName)
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
                    routePolyline.add(
                        mapOf(
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
                put("routePolylineCount", routePolyline.size)
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

    private val pickFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val names = uris.map { getDisplayName(it) }
            selectedFileName.value = names.joinToString(", ")
            onUploadFiles(uris)
        }
    }


    private val status = mutableStateOf("Ready")
    private val isLoading = mutableStateOf(false)
    private val selectedFileName = mutableStateOf("No file selected")
    private val lastUploadedFiles = mutableStateOf<List<String>>(emptyList())

    private fun getDisplayName(uri: Uri): String {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) (c.getString(idx) ?: (uri.lastPathSegment ?: "selected")) else (uri.lastPathSegment ?: "selected")
            } ?: (uri.lastPathSegment ?: "selected")
        } catch (_: Exception) {
            uri.lastPathSegment ?: "selected"
        }
    }

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
                    onPublishNotice = { publishNoticeToUsers() },
                    onCleanupNotices = { cleanupOldNotices() },
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

    private fun onUploadFiles(uris: List<Uri>) {
        isLoading.value = true
        status.value = "Reading ${uris.size} file(s)..."

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
            return
        }

        lifecycleScope.launch {
            try {
                status.value = "Generating routeStops + routePolyline from routeDetails…"
                val enrichedItems = enrichRoutesWithMapData(safeItems)
                val totalPolylinePoints = enrichedItems.sumOf {
                    (it["routePolylineCount"] as? Int) ?: 0
                }
                status.value = "Uploading ${enrichedItems.size} routes with map data… ($totalPolylinePoints mapped points)"
                uploadSchedule(enrichedItems, usedFiles)
            } catch (e: Exception) {
                isLoading.value = false
                status.value = "FAILED ❌ Map data generation failed: ${e.message}"
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

        // This document is overwritten each time, so previous schedule is effectively removed.
        val scheduleRef = db.collection("schedules")
            .document("current")
            .collection("data")
            .document("items")

        val metaRef = db.collection("meta").document("app")

        db.runBatch { batch ->
            batch.set(scheduleRef, mapOf(
                "items" to items,
                "sourceFiles" to fileNames,
                "updatedAt" to FieldValue.serverTimestamp()
            ))

            batch.set(metaRef, mapOf(
                "version" to FieldValue.increment(1),
                "message" to "Schedule updated from Admin",
                "lastUploadedFiles" to fileNames,
                "updatedAt" to FieldValue.serverTimestamp()
            ), SetOptions.merge())

            // Also publish an admin message so users get a push (via Cloud Function)
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
            status.value = "DONE ✅ Uploaded ${items.size} route(s). Replaced old schedule."

            Toast.makeText(
                this@MainActivity,
                "DONE ✅ Uploaded ${items.size} route(s)\nOld schedule replaced.",
                Toast.LENGTH_LONG
            ).show()
        }.addOnFailureListener { e ->
            isLoading.value = false
            status.value = "FAILED ❌ ${e.message}"
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdminScreen(
    selectedFileName: String,
    lastUploadedFiles: List<String>,
    status: String,
    isLoading: Boolean,
    onPickFiles: () -> Unit,
    onSendAdminPush: (title: String, body: String, target: String, incrementVersion: Boolean) -> Unit,
    onPublishNotice: () -> Unit,
    onCleanupNotices: () -> Unit,
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
        Column(
            modifier = Modifier
                .padding(pad)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            ElevatedCard(colors = profileLikeCardColors) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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

                    val lastUp = if (lastUploadedFiles.isEmpty()) "(none yet)" else lastUploadedFiles.joinToString(", ")
                    Text(
                        "Last uploaded: $lastUp",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            ElevatedCard(colors = profileLikeCardColors) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Publish Notice (User App)", style = MaterialTheme.typography.titleMedium)

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
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Notice text (Bangla)") },
                        placeholder = { Text("যেমন: আজ বাস ১৫ মিনিট লেট…") },
                        minLines = 4
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
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Message") },
                        placeholder = { Text("Write message for users…") },
                        minLines = 3
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
    }
}