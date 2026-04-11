package com.sohan.diutransportadmin

import androidx.compose.runtime.MutableState
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.ktx.Firebase
import com.google.firebase.auth.ktx.auth

/**
 * AdminAuthManager
 *
 * Firebase anonymous auth er logic ekhane thakbe.
 * Sob manager (Schedule, Notice, Map, Notification) ei class use korbe
 * Firestore-e write korar age auth check korte.
 */
class AdminAuthManager(
    private val status: MutableState<String>,
    private val adminAuthReady: MutableState<Boolean>
) {
    private val auth: FirebaseAuth = Firebase.auth

    /**
     * Checks if the user is currently authenticated with Firebase.
     * If they are, it immediately invokes [onReady].
     * If not, it marks the session as invalid.
     */
    fun ensureAdminAuth(onReady: (() -> Unit)? = null) {
        val current = auth.currentUser
        if (current != null) {
            adminAuthReady.value = true
            onReady?.invoke()
            return
        }

        adminAuthReady.value = false
        status.value = "Admin login required. Please sign in."
    }

    /**
     * Attempts to log in using an email and password.
     */
    fun loginWithEmail(
        email: String,
        pass: String,
        onSuccess: () -> Unit,
        onFailure: () -> Unit
    ) {
        auth.signInWithEmailAndPassword(email, pass)
            .addOnSuccessListener { result ->
                val uid = result.user?.uid.orEmpty()
                adminAuthReady.value = true
                status.value =
                    "Admin connected ✅ — if Firestore writes fail, add document admins/$uid (see Logcat ADMIN_AUTH)"
                android.util.Log.d("ADMIN_AUTH", "Logged in UID=$uid — add Firestore admins/$uid if writes denied")
                onSuccess()
            }
            .addOnFailureListener { e ->
                adminAuthReady.value = false
                status.value = "FAILED ❌ Login failed: ${e.message}"
                android.util.Log.e("ADMIN_AUTH", "Login failed", e)
                onFailure()
            }
    }

    fun logout() {
        auth.signOut()
        adminAuthReady.value = false
        status.value = "Logged out successfully"
    }

    /** Currently signed-in UID, or empty string if not authenticated. */
    fun currentUid(): String = auth.currentUser?.uid ?: ""

    /** True if already authenticated. */
    fun isAuthenticated(): Boolean = auth.currentUser != null
}