package com.sohan.diutransportadmin

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.MutableState
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Transaction
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * NoticeManager
 *
 * Notice publish / cleanup er sob logic ekhane thakbe:
 *  - Notice Firestore-e save kora
 *  - meta/notice/version increment kora
 *  - FCM push pathano (NotificationManager er through)
 *  - Old notice cleanup kora (last 30 days rakhbe)
 */
class NoticeManager(
    private val context: Context,
    private val db: FirebaseFirestore,
    private val adminAuth: AdminAuthManager,
    private val notificationManager: AdminNotificationManager,
    private val status: MutableState<String>,
    private val isLoading: MutableState<Boolean>,
    private val progressPercent: MutableState<Int>,
    private val progressLabel: MutableState<String>
) {

    companion object {
        private const val NOTICE_TOPIC = "diu_transport"
    }
    // -------------------------------------------------------------------
    // Publish notice to users
    // -------------------------------------------------------------------

    fun publishNoticeToUsers(
        title: String,
        body: String,
        releaseDateRaw: String,
        onSuccess: () -> Unit = {},
        onFailure: (String) -> Unit = {}
    ) {
        val cleanTitle = title.trim().ifBlank { "DIU Transport Notice" }
        val cleanBody = body.trim()

        if (cleanBody.isBlank()) {
            status.value = "Notice body required"
            onFailure("Notice body required")
            return
        }

        adminAuth.ensureAdminAuth {
            isLoading.value = true
            progressPercent.value = 10
            progressLabel.value = "Preparing notice publish..."
            status.value = "Publishing notice to users..."
            saveNoticeAndBumpVersion(
                title = cleanTitle,
                body = cleanBody,
                releaseDateRaw = releaseDateRaw,
                onSaved = { noticeId, releaseAtMs, createdAtMs, metaNoticeVersion ->
                    progressPercent.value = 75
                    progressLabel.value = "Notice saved. Sending FCM push..."
                    status.value = "Notice saved + meta/notice version $metaNoticeVersion. Sending push..."
                    notificationManager.sendNoticePush(
                        noticeId = noticeId,
                        title = cleanTitle,
                        body = cleanBody,
                        releaseAtMs = releaseAtMs,
                        createdAtMs = createdAtMs,
                        metaNoticeVersion = metaNoticeVersion,
                        onSuccess = {
                            isLoading.value = false
                            progressPercent.value = 0
                            progressLabel.value = ""
                            status.value = "DONE ✅ Notice saved + meta/notice/version increased + push sent"
                            onSuccess()
                        },
                        onFailure = { err ->
                            isLoading.value = false
                            progressPercent.value = 0
                            progressLabel.value = ""
                            val msg = err.ifBlank { "Unknown error" }
                            status.value = "Notice saved + meta/notice/version increased, but FCM failed: $msg"
                            onFailure(msg)
                        }
                    )
                },
                onFailure = { err ->
                    isLoading.value = false
                    progressPercent.value = 0
                    progressLabel.value = ""
                    val msg = err.ifBlank { "Unknown error" }
                    status.value = "FAILED ❌ $msg"
                    onFailure(msg)
                }
            )
        }
    }


    // -------------------------------------------------------------------
    // Cleanup old notices (keep last 30 days)
    // -------------------------------------------------------------------

    fun cleanupOldNotices() {
        adminAuth.ensureAdminAuth {
            isLoading.value       = true
            progressPercent.value = 10
            progressLabel.value   = "Fetching old notices..."
            status.value          = "Fetching notices to clean up..."

            val cutoffMs = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)

            db.collection("notices")
                .whereLessThan("createdAtMs", cutoffMs)
                .get()
                .addOnSuccessListener { snapshot ->
                    if (snapshot.isEmpty) {
                        isLoading.value       = false
                        progressPercent.value = 0
                        progressLabel.value   = ""
                        status.value = "No old notices to delete (all within last 30 days)"
                        return@addOnSuccessListener
                    }

                    progressLabel.value = "Deleting ${snapshot.size()} old notices..."
                    status.value        = "Deleting ${snapshot.size()} old notice(s)..."

                    val batch = db.batch()
                    snapshot.documents.forEach { batch.delete(it.reference) }

                    batch.commit()
                        .addOnSuccessListener {
                            isLoading.value       = false
                            progressPercent.value = 0
                            progressLabel.value   = ""
                            status.value = "DONE ✅ Deleted ${snapshot.size()} old notice(s). Last 30 days kept."
                            Toast.makeText(context, "Deleted ${snapshot.size()} old notice(s)", Toast.LENGTH_LONG).show()
                        }
                        .addOnFailureListener { e ->
                            isLoading.value       = false
                            progressPercent.value = 0
                            progressLabel.value   = ""
                            status.value = "FAILED ❌ Cleanup failed: ${FirestoreWriteHints.fromException(e)}"
                        }
                }
                .addOnFailureListener { e ->
                    isLoading.value       = false
                    progressPercent.value = 0
                    progressLabel.value   = ""
                    status.value = "FAILED ❌ Could not fetch notices: ${FirestoreWriteHints.fromException(e)}"
                }
        }
    }

    // -------------------------------------------------------------------
    // Date helpers
    // -------------------------------------------------------------------

    fun parseReleaseDateMillis(raw: String): Long? {
        val s = raw.trim()
        if (s.isBlank()) return null
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ENGLISH).parse(s)?.time
        } catch (_: Exception) { null }
    }

    private fun startOfTodayMillis(): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Dhaka"))
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun saveNoticeAndBumpVersion(
        title: String,
        body: String,
        releaseDateRaw: String,
        onSaved: (noticeId: String, releaseAtMs: Long, createdAtMs: Long, metaNoticeVersion: Long) -> Unit,
        onFailure: (String) -> Unit
    ) {
        val cleanTitle = title.trim()
        val cleanBody = body.trim()
        val cleanReleaseDateRaw = releaseDateRaw.trim()

        if (cleanTitle.isBlank()) {
            onFailure("Notice title required")
            return
        }
        if (cleanBody.isBlank()) {
            onFailure("Notice body required")
            return
        }

        val releaseAtMs = parseReleaseDateMillis(cleanReleaseDateRaw)
        if (cleanReleaseDateRaw.isNotBlank() && releaseAtMs == null) {
            onFailure("Invalid release date. Use yyyy-MM-dd")
            return
        }

        val safeReleaseAtMs = releaseAtMs ?: startOfTodayMillis()
        val createdAtMs = System.currentTimeMillis()
        progressPercent.value = 25
        progressLabel.value = "Saving notice to Firestore..."
        status.value = "Saving notice + increasing meta/notice/version..."
        val expiresAtMs = safeReleaseAtMs + java.util.concurrent.TimeUnit.DAYS.toMillis(120)
        val releaseDate = if (cleanReleaseDateRaw.isBlank()) {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ENGLISH)
                .format(java.util.Date(safeReleaseAtMs))
        } else {
            cleanReleaseDateRaw
        }

        adminAuth.ensureAdminAuth {
            val noticeRef = db.collection("notices").document()
            val metaRef = db.collection("meta").document("notice")

            val noticeData = mapOf(
                "title" to cleanTitle,
                "body" to cleanBody,
                "releaseDate" to releaseDate,
                "releaseDateMs" to safeReleaseAtMs,
                "createdAt" to FieldValue.serverTimestamp(),
                "createdAtMs" to createdAtMs,
                "expiresAt" to com.google.firebase.Timestamp(
                    expiresAtMs / 1000,
                    ((expiresAtMs % 1000) * 1000000).toInt()
                )
            )

            // Transaction: same atomic unit as batch; reads current meta/notice version then writes both docs.
            db.runTransaction(
                object : Transaction.Function<Long> {
                    override fun apply(transaction: Transaction): Long {
                        val metaSnap = transaction.get(metaRef)
                        val nextVersion = (metaSnap.getLong("version") ?: 0L) + 1L
                        transaction.set(noticeRef, noticeData)
                        transaction.set(metaRef, mapOf("version" to nextVersion))
                        return nextVersion
                    }
                }
            ).addOnSuccessListener { nextVersion ->
                progressPercent.value = 60
                progressLabel.value = "Notice saved. Finalizing publish..."
                status.value = "Notice saved in Firestore. Finalizing publish..."
                onSaved(noticeRef.id, safeReleaseAtMs, createdAtMs, nextVersion)
            }.addOnFailureListener { e ->
                progressPercent.value = 0
                progressLabel.value = ""
                onFailure(FirestoreWriteHints.fromException(e))
            }
        }
    }
}