package com.sohan.diutransportadmin

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.compose.runtime.MutableState
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * ScheduleManager
 *
 * Schedule upload er sob logic ekhane thakbe:
 *  - File pick hoa uris theke schedule parse kora (CSV / XLSX)
 *  - Duplicate route merge kora
 *  - Firestore-e upload kora
 */
class ScheduleManager(
    private val context: Context,
    private val db: FirebaseFirestore,
    private val adminAuth: AdminAuthManager,
    private val status: MutableState<String>,
    private val isLoading: MutableState<Boolean>,
    private val progressPercent: MutableState<Int>,
    private val progressLabel: MutableState<String>,
    private val lastUploadedFiles: MutableState<List<String>>,
    private val scope: CoroutineScope
) {

    // -------------------------------------------------------------------
    // Public entry point – called from MainActivity when files are picked
    // -------------------------------------------------------------------

    companion object {
        private const val DEFAULT_ADMIN_TITLE = "DIU Transport Schedule"
        private const val DEFAULT_ADMIN_BODY = "Schedule updated from Admin"
        private const val DEFAULT_ADMIN_TARGET = "diu_admin"
    }

    fun onUploadFiles(uris: List<Uri>) {
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

        // Merge duplicate routeNo + routeName rows, union all times
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
            val routeNo    = (item["routeNo"]    as? String).orEmpty().trim()
            val routeName  = (item["routeName"]  as? String).orEmpty().trim()
            val key = "${routeNo}_${routeName}".trim()
            if (key.isBlank()) return@forEach

            val incomingStart   = cleanTimes(asStringList(item["startTimes"]))
            val incomingDep     = cleanTimes(asStringList(item["departureTimes"]))
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
                val prevDep   = cleanTimes(asStringList(prev["departureTimes"]))
                prev["startTimes"]     = cleanTimes(prevStart + incomingStart)
                prev["departureTimes"] = cleanTimes(prevDep   + incomingDep)

                val prevDetails = (prev["routeDetails"] as? String).orEmpty().trim()
                if (prevDetails.isBlank() && incomingDetails.isNotBlank()) {
                    prev["routeDetails"] = incomingDetails
                }
                if (((prev["routeNo"]   as? String).orEmpty().trim()).isBlank() && routeNo.isNotBlank())   prev["routeNo"]   = routeNo
                if (((prev["routeName"] as? String).orEmpty().trim()).isBlank() && routeName.isNotBlank()) prev["routeName"] = routeName

                val prevApplies     = (prev["appliesOn"]  as? String).orEmpty().trim()
                val incomingApplies = (item["appliesOn"]  as? String).orEmpty().trim()
                if (prevApplies.isBlank() && incomingApplies.isNotBlank()) prev["appliesOn"] = incomingApplies
            }
        }

        val safeItems = merged.values.toList().filter { item ->
            val rn = (item["routeNo"] as? String).orEmpty()
            if (!isValidRouteNo(rn)) return@filter false
            val st = asStringList(item["startTimes"]).any { it.trim().isNotBlank() }
            val dp = asStringList(item["departureTimes"]).any { it.trim().isNotBlank() }
            st || dp
        }

        status.value = "Parsed ${safeItems.size} route(s) from ${usedFiles.size} file(s)."
        Toast.makeText(context, "Parsed ${safeItems.size} route(s). Uploading…", Toast.LENGTH_SHORT).show()

        if (safeItems.isEmpty()) {
            isLoading.value  = false
            status.value     = "No valid rows found in selected files. (CSV/XLSX format mismatch?)"
            progressPercent.value = 0
            progressLabel.value   = ""
            return
        }

        scope.launch {
            try {
                progressPercent.value = 80
                progressLabel.value   = "Uploading to Firestore..."
                uploadSchedule(safeItems, usedFiles)
            } catch (e: Exception) {
                isLoading.value       = false
                status.value          = "FAILED ❌ Upload failed: ${e.message}"
                progressPercent.value = 0
                progressLabel.value   = ""
                Toast.makeText(context, "FAILED ❌ ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // -------------------------------------------------------------------
    // Firestore upload
    // -------------------------------------------------------------------

    private fun uploadSchedule(items: List<Map<String, Any>>, fileNames: List<String>) {
        adminAuth.ensureAdminAuth {
            status.value = "Uploading ${items.size} routes..."

            val scheduleRef = db.collection("schedules")
                .document("current")
                .collection("data")
                .document("items")
            val metaRef = db.collection("meta").document("app")

            val batch = db.batch()
            batch.set(
                scheduleRef,
                mapOf(
                    "items"       to items,
                    "sourceFiles" to fileNames,
                    "updatedAt"   to FieldValue.serverTimestamp()
                )
            )
            batch.set(
                metaRef,
                mapOf(
                    "version" to FieldValue.increment(1),
                    "message" to DEFAULT_ADMIN_BODY
                )
            )
            batch.commit().addOnSuccessListener {

                db.collection("admin_messages").document().set(
                    mapOf(
                        "title"       to DEFAULT_ADMIN_TITLE,
                        "body"        to DEFAULT_ADMIN_BODY,
                        "target"      to DEFAULT_ADMIN_TARGET,
                        "type"        to "schedule",
                        "createdAt"   to FieldValue.serverTimestamp(),
                        "updatedAt"   to FieldValue.serverTimestamp(),
                        "source"      to "admin_app",
                        "sourceFiles" to fileNames
                    ),
                    com.google.firebase.firestore.SetOptions.merge()
                )

                isLoading.value       = false
                progressPercent.value = 0
                progressLabel.value   = ""
                lastUploadedFiles.value = fileNames
                status.value = "DONE ✅ Uploaded ${items.size} route(s). Schedule fully replaced."
                Toast.makeText(context, "DONE ✅ Uploaded ${items.size} route(s)", Toast.LENGTH_LONG).show()
            }.addOnFailureListener { e ->
                isLoading.value       = false
                progressPercent.value = 0
                progressLabel.value   = ""
                val msg = FirestoreWriteHints.fromException(e)
                status.value = "FAILED ❌ $msg"
                Toast.makeText(context, "FAILED ❌ $msg", Toast.LENGTH_LONG).show()
            }
        }
    }

    // -------------------------------------------------------------------
    // Parsing helpers
    // -------------------------------------------------------------------

    private fun parseScheduleFile(uri: Uri): List<Map<String, Any>> {
        val name = getDisplayName(uri).lowercase()
        return when {
            name.endsWith(".xlsx") || name.endsWith(".xls") -> parseXlsx(uri)
            else -> parseCsv(uri)
        }
    }

    private fun parseCsv(uri: Uri): List<Map<String, Any>> {
        val input = context.contentResolver.openInputStream(uri) ?: return emptyList()
        val br = BufferedReader(InputStreamReader(input))

        data class Temp(
            val routeNo: String,
            val routeName: String,
            var routeDetails: String,
            val startTimes: MutableList<String> = mutableListOf(),
            val departureTimes: MutableList<String> = mutableListOf()
        )

        val routes = linkedMapOf<String, Temp>()
        var lastRouteNo   = ""
        var lastRouteName = ""

        br.forEachLine { lineRaw ->
            val line = lineRaw.trim()
            if (line.isBlank()) return@forEachLine
            val cols = parseCsvLine(line)
            if (cols.size < 5) return@forEachLine

            var routeNo   = cols[0].trim()
            val start     = normalizeTimeString(cols[1])
            var routeName = cols[2].trim()
            val details   = cols[3].trim()
            val dep       = normalizeTimeString(cols[4])

            if (routeNo.isBlank())   routeNo   = lastRouteNo
            if (routeName.isBlank()) routeName = lastRouteName
            if (routeNo.isNotBlank())   lastRouteNo   = routeNo
            if (routeName.isNotBlank()) lastRouteName = routeName

            if (routeNo.equals("Route No", true)) return@forEachLine
            if (routeNo.contains("Daffodil", true)) return@forEachLine
            if (!isValidRouteNo(routeNo)) return@forEachLine

            val key = "${routeNo}_${routeName}".trim()
            val t = routes.getOrPut(key) { Temp(routeNo, routeName, details) }
            if (t.routeDetails.isBlank() && details.isNotBlank() && details.lowercase() != "nan") t.routeDetails = details
            if (start.isNotBlank() && start.lowercase() != "nan") t.startTimes.add(start)
            if (dep.isNotBlank()   && dep.lowercase()   != "nan") t.departureTimes.add(dep)
        }

        return routes.values.map {
            mapOf(
                "routeNo"        to it.routeNo,
                "routeName"      to it.routeName,
                "routeDetails"   to it.routeDetails,
                "appliesOn"      to (if (it.routeNo.startsWith("F", true)) "FRIDAY" else "DAILY"),
                "startTimes"     to it.startTimes.distinct(),
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
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') { sb.append('"'); i++ }
                    else inQuotes = !inQuotes
                }
                ch == ',' && !inQuotes -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(ch)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    private fun parseXlsx(uri: Uri): List<Map<String, Any>> {
        val input = context.contentResolver.openInputStream(uri) ?: return emptyList()
        val formatter = DataFormatter()

        data class Temp(
            val routeNo: String,
            val routeName: String,
            var routeDetails: String,
            val startTimes: MutableList<String> = mutableListOf(),
            val departureTimes: MutableList<String> = mutableListOf()
        )

        val routes = linkedMapOf<String, Temp>()
        var lastRouteNo   = ""
        var lastRouteName = ""

        return try {
            val wb    = WorkbookFactory.create(input)
            val sheet = wb.getSheetAt(0) ?: return emptyList()

            for (rowIdx in 0..sheet.lastRowNum) {
                val row = sheet.getRow(rowIdx) ?: continue

                fun cellStr(col: Int): String {
                    val cell = row.getCell(col) ?: return ""
                    return when (cell.cellType) {
                        CellType.STRING               -> cell.stringCellValue
                        CellType.NUMERIC,
                        CellType.BOOLEAN,
                        CellType.FORMULA              -> formatter.formatCellValue(cell)
                        else                           -> formatter.formatCellValue(cell)
                    }.trim()
                }

                var routeNo   = cellStr(0)
                val start     = normalizeTimeString(cellStr(1))
                var routeName = cellStr(2)
                val details   = cellStr(3)
                val dep       = normalizeTimeString(cellStr(4))

                if (routeNo.isBlank())   routeNo   = lastRouteNo
                if (routeName.isBlank()) routeName = lastRouteName
                if (routeNo.isNotBlank())   lastRouteNo   = routeNo
                if (routeName.isNotBlank()) lastRouteName = routeName

                if (routeNo.equals("Route No", true) || routeNo.equals("Route", true)) continue
                if (routeNo.isBlank()) continue
                if (routeNo.contains("Daffodil", true)) continue
                if (!isValidRouteNo(routeNo)) continue

                val key = "${routeNo}_${routeName}".trim()
                val t = routes.getOrPut(key) { Temp(routeNo, routeName, details) }
                if (t.routeDetails.isBlank() && details.isNotBlank() && details.lowercase() != "nan") t.routeDetails = details
                if (start.isNotBlank() && start.lowercase() != "nan") t.startTimes.add(start)
                if (dep.isNotBlank()   && dep.lowercase()   != "nan") t.departureTimes.add(dep)
            }
            wb.close()

            routes.values.map {
                mapOf(
                    "routeNo"        to it.routeNo,
                    "routeName"      to it.routeName,
                    "routeDetails"   to it.routeDetails,
                    "startTimes"     to it.startTimes.distinct(),
                    "departureTimes" to it.departureTimes.distinct(),
                    "appliesOn"      to (if (it.routeNo.startsWith("F", true)) "FRIDAY" else "DAILY")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // -------------------------------------------------------------------
    // Utility helpers (shared with MainActivity via package-private use)
    // -------------------------------------------------------------------

    fun getDisplayName(uri: Uri): String {
        var name = ""
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) name = cursor.getString(idx) ?: ""
        }
        return name.ifBlank { uri.lastPathSegment ?: "unknown" }
    }

    fun isValidRouteNo(routeNoRaw: String): Boolean {
        val rn = routeNoRaw.trim()
        if (rn.isBlank()) return false
        if (rn.length > 10) return false
        if (rn.contains("schedule", ignoreCase = true)) return false
        if (rn.contains("route no", ignoreCase = true)) return false
        return rn.matches(Regex("[A-Za-z]\\d{1,3}[A-Za-z]?"))
    }

    private fun normalizeTimeString(raw: String): String {
        val s0 = raw.replace("\r", "").replace("\n", " ").trim()
        if (s0.isBlank()) return ""
        val m = Regex("(\\d{1,2})[\\.:](\\d{2})(?:[\\.:](\\d{2}))?\\s*([AP]M)", RegexOption.IGNORE_CASE)
            .find(s0.replace(Regex("\\s+"), " "))
        if (m != null) {
            val hh   = m.groupValues[1]
            val mm   = m.groupValues[2]
            val ss   = m.groupValues.getOrNull(3).orEmpty()
            val ap   = m.groupValues[4].uppercase(Locale.getDefault())
            val time = if (ss.isNotBlank()) "$hh:$mm:$ss $ap" else "$hh:$mm $ap"
            val note = s0.substring(m.range.last + 1).trim()
            return if (note.isNotBlank()) "$time $note" else time
        }
        val simpleDot = Regex("^(\\d{1,2})\\.(\\d{2})(.*)$").find(s0)
        if (simpleDot != null) {
            return "${simpleDot.groupValues[1]}:${simpleDot.groupValues[2]}${simpleDot.groupValues[3]}".trim()
        }
        return s0
    }
}