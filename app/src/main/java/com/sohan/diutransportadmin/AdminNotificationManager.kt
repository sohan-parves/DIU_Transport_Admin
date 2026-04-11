package com.sohan.diutransportadmin

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.MutableState
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import com.google.firebase.firestore.SetOptions

/**
 * AdminNotificationManager
 *
 * FCM push er sob logic ekhane thakbe:
 *  - Admin message push pathano (sendAdminPush)
 *  - Notice push pathano (sendNoticePush)
 *  - FCM HTTP v1 API request (JWT mint + OkHttp call)
 */
class AdminNotificationManager(
    private val context: Context,
    private val db: FirebaseFirestore,
    private val status: MutableState<String>,
    private val isLoading: MutableState<Boolean>,
    private val progressPercent: MutableState<Int>,
    private val progressLabel: MutableState<String>,
    private val scope: CoroutineScope
) {
    companion object {
        private const val DEFAULT_TOPIC = "diu_admin"
        private const val NOTICE_TOPIC  = "diu_transport"
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }
    private val gson       by lazy { Gson() }

    // -------------------------------------------------------------------
    // Admin push (Send Notification card)
    // -------------------------------------------------------------------

    fun saveAdminMessageAndSendPush(
        title: String,
        body: String,
        target: String,
        incrementVersion: Boolean,
        onDone: (() -> Unit)? = null
    ) {
        val cleanTitle  = title.trim().ifBlank { "DIU Transport Schedule" }
        val cleanBody   = body.trim()
        val cleanTarget = target.trim().removePrefix("/topics/").ifBlank { DEFAULT_TOPIC }

        if (cleanBody.isBlank()) {
            status.value = "Message body required"
            return
        }

        isLoading.value       = true
        progressPercent.value = 20
        progressLabel.value   = if (incrementVersion) {
            "Saving admin message (FCM will include refresh hint)..."
        } else {
            "Saving admin message..."
        }
        status.value = progressLabel.value

        val data = linkedMapOf<String, String>(
            "type"        to "admin_message",
            "category"    to "admin_message",
            "messageType" to "admin_message",
            "title"       to cleanTitle,
            "body"        to cleanBody,
            "message"     to cleanBody,
            "target"      to cleanTarget,
            "createdAtMs" to System.currentTimeMillis().toString()
        )

        if (incrementVersion) {
            data["incrementVersion"] = "true"
            data["versionBumpSource"] = "admin_push"
        }

        val msgRef = db.collection("admin_messages").document()

        // meta/app = schedule version + message only; admin content lives in admin_messages + FCM.
        val batch = db.batch()
        batch.set(
            msgRef,
            mapOf(
                "id"               to msgRef.id,
                "title"            to cleanTitle,
                "body"             to cleanBody,
                "target"           to cleanTarget,
                "incrementVersion" to incrementVersion,
                "data"             to data,
                "status"           to "saved",
                "createdAt"        to FieldValue.serverTimestamp(),
                "updatedAt"        to FieldValue.serverTimestamp(),
                "senderUid"        to (FirebaseAuth.getInstance().currentUser?.uid ?: "")
            ),
            SetOptions.merge()
        )
        batch.commit().addOnSuccessListener {
            progressPercent.value = 65
            progressLabel.value = "Admin message saved. Sending push..."
            status.value = progressLabel.value

            sendDataMessageToTopic(
                topic = cleanTarget,
                data = data,
                notificationTitle = cleanTitle,
                notificationBody = cleanBody,
                onSuccess = {
                    msgRef.update(
                        mapOf(
                            "status" to "sent",
                            "sentAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                    isLoading.value = false
                    progressPercent.value = 0
                    progressLabel.value = ""
                    status.value = "DONE ✅ Admin message saved + push sent"
                    Toast.makeText(
                        context,
                        "Admin message saved + push sent",
                        Toast.LENGTH_LONG
                    ).show()
                    onDone?.invoke()
                },
                onFailure = { err ->
                    msgRef.update(
                        mapOf(
                            "status" to "failed",
                            "error" to err,
                            "failedAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                    isLoading.value = false
                    progressPercent.value = 0
                    progressLabel.value = ""
                    status.value =
                        "FAILED ❌ Admin message saved, but push failed: ${err.ifBlank { "Unknown error" }}"
                }
            )
        }.addOnFailureListener { e ->
            isLoading.value = false
            progressPercent.value = 0
            progressLabel.value = ""
            status.value = "FAILED ❌ ${FirestoreWriteHints.fromException(e)}"
        }
    }

    fun sendAdminPush(
        title: String,
        body: String,
        target: String,
        incrementVersion: Boolean,
        onDone: (() -> Unit)? = null
    ) {
        saveAdminMessageAndSendPush(
            title = title,
            body = body,
            target = target,
            incrementVersion = incrementVersion,
            onDone = onDone
        )
    }

    // -------------------------------------------------------------------
    // Notice push (called from NoticeManager after Firestore save)
    // -------------------------------------------------------------------

    fun sendNoticePush(
        noticeId: String,
        title: String,
        body: String,
        releaseAtMs: Long,
        createdAtMs: Long,
        metaNoticeVersion: Long,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        val expiresAtMs = releaseAtMs + java.util.concurrent.TimeUnit.DAYS.toMillis(120)

        val data = linkedMapOf<String, String>(
            "type"              to "notice",
            "category"          to "notice",
            "messageType"       to "notice",
            "screen"            to "notice",
            "target"            to NOTICE_TOPIC,
            "open_notice"       to "true",
            "id"                to noticeId,
            "noticeId"          to noticeId,
            "title"             to title,
            "noticeTitle"       to title,
            "body"              to body,
            "noticeBody"        to body,
            "releaseDateMs"     to releaseAtMs.toString(),
            "releaseAtMs"       to releaseAtMs.toString(),
            "createdAtMs"       to createdAtMs.toString(),
            "expiresAt"         to expiresAtMs.toString(),
            "noticeVersion"     to metaNoticeVersion.toString(),
            "metaNoticeVersion" to metaNoticeVersion.toString()
        )

        sendDataMessageToTopic(
            topic             = NOTICE_TOPIC,
            data              = data,
            notificationTitle = title,
            notificationBody  = body,
            onSuccess         = onSuccess,
            onFailure         = onFailure
        )
    }

    // -------------------------------------------------------------------
    // Core: send data message to an FCM topic
    // -------------------------------------------------------------------

    private fun sendDataMessageToTopic(
        topic: String,
        data: Map<String, String>,
        notificationTitle: String? = null,
        notificationBody: String? = null,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        val cleanTopic = topic.trim().removePrefix("/topics/").ifBlank { DEFAULT_TOPIC }

        progressPercent.value = 55
        progressLabel.value   = "Connecting to FCM..."
        status.value          = "Sending push to topic: $cleanTopic"

        val nTitle = notificationTitle?.trim().orEmpty()
        val nBody  = notificationBody?.trim().orEmpty()
        val jsonBody = buildFcmV1MessageJson(
            topic = cleanTopic,
            data = data,
            notificationTitle = nTitle.takeIf { it.isNotBlank() },
            notificationBody = nBody.takeIf { it.isNotBlank() }
        )

        sendFcmHttpV1Request(
            jsonBody  = jsonBody,
            onSuccess = {
                progressPercent.value = 100
                progressLabel.value   = "Push delivered to FCM"
                onSuccess()
            },
            onFailure = onFailure
        )
    }

    // -------------------------------------------------------------------
    // FCM HTTP v1 – JWT mint + OkHttp request
    // -------------------------------------------------------------------

    private fun resolvedFcmProjectId(): String {
        val fromBuild = com.sohan.diutransportadmin.BuildConfig.FCM_PROJECT_ID.trim()
        if (fromBuild.isNotBlank()) return fromBuild
        return FirebaseApp.getInstance().options.projectId.orEmpty().trim()
    }

    /**
     * FCM HTTP v1 JSON body (JSONObject avoids Gson map nesting quirks).
     */
    private fun buildFcmV1MessageJson(
        topic: String,
        data: Map<String, String>,
        notificationTitle: String?,
        notificationBody: String?
    ): String {
        val message = JSONObject()
        message.put("topic", topic)
        val dataObj = JSONObject()
        for ((k, v) in data) dataObj.put(k, v)
        message.put("data", dataObj)
        val android = JSONObject()
        android.put("priority", "HIGH")
        message.put("android", android)
        if (!notificationTitle.isNullOrBlank() || !notificationBody.isNullOrBlank()) {
            val n = JSONObject()
            if (!notificationTitle.isNullOrBlank()) n.put("title", notificationTitle)
            if (!notificationBody.isNullOrBlank()) n.put("body", notificationBody)
            message.put("notification", n)
        }
        return JSONObject().put("message", message).toString()
    }

    private fun sendFcmHttpV1Request(
        jsonBody: String,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        val projectId = resolvedFcmProjectId()
        if (projectId.isBlank()) {
            onFailure("Missing FCM project id: set fcm.project.id in local.properties or google-services.json + FirebaseApp")
            return
        }

        val saBase64 = com.sohan.diutransportadmin.BuildConfig.FCM_SERVICE_ACCOUNT_JSON_BASE64.trim()
        if (saBase64.isBlank()) {
            onFailure(
                "Missing FCM service account: add to project root local.properties:\n" +
                    "fcm.service.account.json.base64=<entire service account JSON file as one line Base64>\n" +
                    "(Optional) fcm.project.id=$projectId — Enable Cloud Messaging API in Google Cloud for this project."
            )
            return
        }

        scope.launch {
            try {
                val accessToken = withContext(Dispatchers.IO) {
                    mintAccessToken(saBase64)
                }

                val url     = "https://fcm.googleapis.com/v1/projects/$projectId/messages:send"
                val reqBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())

                val request = Request.Builder()
                    .url(url)
                    .post(reqBody)
                    .addHeader("Authorization", "Bearer $accessToken")
                    .build()

                val (ok, errText) = withContext(Dispatchers.IO) {
                    httpClient.newCall(request).execute().use { resp ->
                        val text = resp.body?.string().orEmpty()
                        if (resp.isSuccessful) true to ""
                        else false to "FCM HTTP ${resp.code}: ${text.ifBlank { "Empty error body" }}"
                    }
                }

                if (ok) onSuccess() else onFailure(errText)

            } catch (t: Throwable) {
                onFailure(t.message ?: t.toString())
            }
        }
    }

    // -------------------------------------------------------------------
    // JWT helpers
    // -------------------------------------------------------------------

    private data class ServiceAccountJson(
        val client_email: String? = null,
        val private_key: String? = null,
        val token_uri: String? = null
    )

    private fun mintAccessToken(saBase64: String): String {
        val jsonBytes = Base64.getDecoder().decode(saBase64)
        val sa        = gson.fromJson(String(jsonBytes, StandardCharsets.UTF_8), ServiceAccountJson::class.java)

        val email      = sa.client_email?.trim().orEmpty()
        val privateKey = sa.private_key?.trim().orEmpty()
        val tokenUri   = sa.token_uri?.trim().orEmpty().ifBlank { "https://oauth2.googleapis.com/token" }

        if (email.isBlank())      throw IllegalArgumentException("service account missing client_email")
        if (privateKey.isBlank()) throw IllegalArgumentException("service account missing private_key")

        val iat = (System.currentTimeMillis() / 1000L)
        val exp = iat + 3600L

        val header = gson.toJson(mapOf("alg" to "RS256", "typ" to "JWT"))
        val claims = gson.toJson(mapOf(
            "iss"   to email,
            "scope" to "https://www.googleapis.com/auth/firebase.messaging",
            "aud"   to tokenUri,
            "iat"   to iat,
            "exp"   to exp
        ))

        val encodedHeader = b64Url(header.toByteArray(StandardCharsets.UTF_8))
        val encodedClaims = b64Url(claims.toByteArray(StandardCharsets.UTF_8))
        val signingInput  = "$encodedHeader.$encodedClaims"
        val sig           = b64Url(signRs256(privateKey, signingInput))
        val assertion     = "$signingInput.$sig"

        val form = "grant_type=${URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", "UTF-8")}" +
                "&assertion=${URLEncoder.encode(assertion, "UTF-8")}"

        val req = Request.Builder()
            .url(tokenUri)
            .post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("TOKEN HTTP ${resp.code}: $text")
            return JSONObject(text).optString("access_token").ifBlank {
                throw IllegalStateException("Token response missing access_token")
            }
        }
    }

    private fun b64Url(input: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(input)

    private fun signRs256(privateKeyPem: String, signingInput: String): ByteArray {
        val cleaned  = privateKeyPem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        val keyBytes = Base64.getDecoder().decode(cleaned)
        val kf       = KeyFactory.getInstance("RSA")
        val privKey  = kf.generatePrivate(PKCS8EncodedKeySpec(keyBytes))
        val sig      = Signature.getInstance("SHA256withRSA")
        sig.initSign(privKey)
        sig.update(signingInput.toByteArray(StandardCharsets.UTF_8))
        return sig.sign()
    }
}