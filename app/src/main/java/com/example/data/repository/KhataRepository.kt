package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.data.model.*
import com.example.ui.qr.ParsedQrPayload
import com.example.ui.qr.QRService
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private const val TAG = "KhataRepository"

/**
 * Production Repository for Digital Khata.
 * Supports:
 * - One Customer Account with multiple connected Shops/Khatas.
 * - Single Shopkeeper accounts isolated strictly to their own shop.
 * - Secure QR Linking between Shopkeeper customer records and Customer accounts.
 * - Persistent Firebase Authentication without spurious logouts.
 * - Separate monthly billing and financial isolation per shop.
 */
class KhataRepository(private val context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("digital_khata_storage_v3", Context.MODE_PRIVATE)

    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val currentMonthKey = "2026-10"
    val currentMonthLabel = "October 2026"
    val previousMonthKey = "2026-09"
    val previousMonthLabel = "September 2026"

    // Safe Firebase references
    private val firebaseAuth: FirebaseAuth? by lazy {
        try {
            if (FirebaseApp.getApps(context).isNotEmpty()) {
                FirebaseAuth.getInstance()
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Firebase Auth not available: ${e.message}")
            null
        }
    }

    private val firestore: FirebaseFirestore? by lazy {
        try {
            if (FirebaseApp.getApps(context).isNotEmpty()) {
                FirebaseFirestore.getInstance()
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Firestore not available: ${e.message}")
            null
        }
    }

    // Active Firestore snapshot listener registrations to clean up when logging out
    private val activeListeners = mutableListOf<ListenerRegistration>()

    private val _currentUser = MutableStateFlow<UserProfile?>(null)
    val currentUser: StateFlow<UserProfile?> = _currentUser.asStateFlow()

    // Shopkeeper: Customers belonging to this shopkeeper
    private val _customers = MutableStateFlow<List<Customer>>(emptyList())
    val customers: StateFlow<List<Customer>> = _customers.asStateFlow()

    // Customer: List of connected shops/khatas for this customer
    private val _connectedKhatas = MutableStateFlow<List<ConnectedKhata>>(emptyList())
    val connectedKhatas: StateFlow<List<ConnectedKhata>> = _connectedKhatas.asStateFlow()

    // Items recorded in ledger (for shopkeeper: all items; for customer: loaded for connected shops)
    private val _items = MutableStateFlow<List<KhataItem>>(emptyList())
    val items: StateFlow<List<KhataItem>> = _items.asStateFlow()

    // Monthly billing records
    private val _monthlyRecords = MutableStateFlow<List<MonthlyKhataRecord>>(emptyList())
    val monthlyRecords: StateFlow<List<MonthlyKhataRecord>> = _monthlyRecords.asStateFlow()

    // Payment Requests
    private val _paymentRequests = MutableStateFlow<List<PaymentRequest>>(emptyList())
    val paymentRequests: StateFlow<List<PaymentRequest>> = _paymentRequests.asStateFlow()

    // Notifications
    private val _notifications = MutableStateFlow<List<KhataNotification>>(emptyList())
    val notifications: StateFlow<List<KhataNotification>> = _notifications.asStateFlow()

    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _isAuthChecking = MutableStateFlow(true)
    val isAuthChecking: StateFlow<Boolean> = _isAuthChecking.asStateFlow()

    private var authStateListener: FirebaseAuth.AuthStateListener? = null

    init {
        loadSavedTheme()
        setupAuthListener()
    }

    /**
     * Handles Firebase authentication state changes.
     * Keeps the user logged in across app restarts and routes the user to the correct role-based application.
     */
    private fun setupAuthListener() {
        val auth = firebaseAuth
        if (auth != null) {
            authStateListener = FirebaseAuth.AuthStateListener { fbAuth ->
                val fbUser = fbAuth.currentUser
                if (fbUser != null) {
                    val current = _currentUser.value
                    if (current != null && current.uid == fbUser.uid) {
                        _isAuthChecking.value = false
                        return@AuthStateListener
                    }

                    // Restore cached session first for instant responsiveness
                    restoreCachedSession()

                    // Asynchronously fetch/verify with Firestore
                    repositoryScope.launch {
                        try {
                            val doc = firestore?.collection("users")?.document(fbUser.uid)?.get()?.await()
                            if (doc != null && doc.exists()) {
                                val roleStr = doc.getString("role") ?: "customer"
                                val role = if (roleStr.equals("shopkeeper", ignoreCase = true)) UserRole.SHOPKEEPER else UserRole.CUSTOMER
                                val user = UserProfile(
                                    uid = fbUser.uid,
                                    role = role,
                                    name = doc.getString("name") ?: (fbUser.displayName ?: "User"),
                                    email = doc.getString("email") ?: (fbUser.email ?: ""),
                                    shopName = doc.getString("shopName"),
                                    khataNumber = doc.getString("khataNumber"),
                                    phone = doc.getString("phone") ?: "",
                                    createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()
                                )
                                saveUserSession(user)
                                loadUserData(user.uid, user.role)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to load user profile on auth change: ${e.message}")
                        } finally {
                            _isAuthChecking.value = false
                        }
                    }
                } else {
                    restoreCachedSession()
                    _isAuthChecking.value = false
                }
            }
            auth.addAuthStateListener(authStateListener!!)
        } else {
            restoreCachedSession()
            _isAuthChecking.value = false
        }
    }

    private fun loadSavedTheme() {
        val savedTheme = prefs.getString("theme_mode", "SYSTEM") ?: "SYSTEM"
        _themeMode.value = try {
            ThemeMode.valueOf(savedTheme)
        } catch (_: Exception) {
            ThemeMode.SYSTEM
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString("theme_mode", mode.name).apply()
    }

    private fun restoreCachedSession() {
        val userJson = prefs.getString("current_user_json", null)
        if (!userJson.isNullOrBlank()) {
            try {
                val obj = JSONObject(userJson)
                val user = UserProfile(
                    uid = obj.getString("uid"),
                    role = UserRole.valueOf(obj.getString("role")),
                    name = obj.getString("name"),
                    email = obj.getString("email"),
                    shopName = if (obj.has("shopName")) obj.getString("shopName") else null,
                    khataNumber = if (obj.has("khataNumber")) obj.getString("khataNumber") else null,
                    phone = obj.optString("phone", ""),
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                )
                _currentUser.value = user
                loadUserData(user.uid, user.role)
            } catch (e: Exception) {
                Log.e(TAG, "Error restoring session: ${e.message}")
            }
        }
    }

    suspend fun registerShopkeeper(
        name: String,
        email: String,
        password: String,
        shopName: String,
        phone: String
    ): Result<UserProfile> = withContext(Dispatchers.IO) {
        try {
            val cleanEmail = email.trim().lowercase()
            val cleanName = name.trim()
            val cleanShopName = shopName.trim()
            val cleanPhone = phone.trim()

            var uid = "shop_${UUID.randomUUID().toString().take(8)}"

            val auth = firebaseAuth
            if (auth != null) {
                try {
                    val authResult = auth.createUserWithEmailAndPassword(cleanEmail, password).await()
                    uid = authResult.user?.uid ?: uid
                } catch (e: Exception) {
                    Log.w(TAG, "Firebase Auth failed: ${e.message}")
                    if (e.message?.contains("email address is already in use") == true) {
                        return@withContext Result.failure(Exception("This email is already registered. Please log in instead."))
                    }
                }
            }

            val user = UserProfile(
                uid = uid,
                role = UserRole.SHOPKEEPER,
                name = cleanName,
                email = cleanEmail,
                shopName = cleanShopName,
                phone = cleanPhone,
                createdAt = System.currentTimeMillis()
            )

            firestore?.let { db ->
                try {
                    val userMap = hashMapOf(
                        "uid" to user.uid,
                        "role" to "shopkeeper",
                        "name" to user.name,
                        "email" to user.email,
                        "shopName" to (user.shopName ?: ""),
                        "phone" to user.phone,
                        "createdAt" to user.createdAt
                    )
                    db.collection("users").document(user.uid).set(userMap).await()

                    val shopMap = hashMapOf(
                        "shopkeeperUid" to user.uid,
                        "shopName" to cleanShopName,
                        "ownerName" to cleanName,
                        "email" to cleanEmail,
                        "phone" to cleanPhone,
                        "createdAt" to user.createdAt
                    )
                    db.collection("shops").document(user.uid).set(shopMap).await()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to write user to Firestore: ${e.message}")
                }
            }

            saveRegisteredUser(user, password)
            saveUserSession(user)
            loadUserData(user.uid, user.role)

            Result.success(user)
        } catch (e: Exception) {
            Log.e(TAG, "Registration error: ${e.message}")
            Result.failure(Exception(e.message ?: "Failed to register. Please check your network and details."))
        }
    }

    suspend fun registerCustomer(
        name: String,
        email: String,
        password: String,
        phone: String
    ): Result<UserProfile> = withContext(Dispatchers.IO) {
        try {
            val cleanEmail = email.trim().lowercase()
            val cleanName = name.trim()
            val cleanPhone = phone.trim()

            var uid = "cust_${UUID.randomUUID().toString().take(8)}"

            val auth = firebaseAuth
            if (auth != null) {
                try {
                    val authResult = auth.createUserWithEmailAndPassword(cleanEmail, password).await()
                    uid = authResult.user?.uid ?: uid
                } catch (e: Exception) {
                    Log.w(TAG, "Firebase Auth failed: ${e.message}")
                    if (e.message?.contains("email address is already in use") == true) {
                        return@withContext Result.failure(Exception("This email is already registered. Please log in instead."))
                    }
                }
            }

            val user = UserProfile(
                uid = uid,
                role = UserRole.CUSTOMER,
                name = cleanName,
                email = cleanEmail,
                phone = cleanPhone,
                createdAt = System.currentTimeMillis()
            )

            firestore?.let { db ->
                try {
                    val userMap = hashMapOf(
                        "uid" to user.uid,
                        "role" to "customer",
                        "name" to user.name,
                        "email" to user.email,
                        "phone" to user.phone,
                        "createdAt" to user.createdAt
                    )
                    db.collection("users").document(user.uid).set(userMap).await()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to write customer to Firestore: ${e.message}")
                }
            }

            saveRegisteredUser(user, password)
            saveUserSession(user)
            loadUserData(user.uid, user.role)

            Result.success(user)
        } catch (e: Exception) {
            Log.e(TAG, "Customer registration error: ${e.message}")
            Result.failure(Exception(e.message ?: "Failed to register. Please check your credentials."))
        }
    }

    suspend fun login(email: String, password: String): Result<UserProfile> = withContext(Dispatchers.IO) {
        try {
            val cleanEmail = email.trim().lowercase()
            val auth = firebaseAuth

            var user: UserProfile? = null

            if (auth != null) {
                try {
                    val authResult = auth.signInWithEmailAndPassword(cleanEmail, password).await()
                    val uid = authResult.user?.uid
                    if (uid != null) {
                        val doc = firestore?.collection("users")?.document(uid)?.get()?.await()
                        if (doc != null && doc.exists()) {
                            val roleStr = doc.getString("role") ?: "customer"
                            user = UserProfile(
                                uid = uid,
                                role = if (roleStr.equals("shopkeeper", ignoreCase = true)) UserRole.SHOPKEEPER else UserRole.CUSTOMER,
                                name = doc.getString("name") ?: "User",
                                email = doc.getString("email") ?: cleanEmail,
                                shopName = doc.getString("shopName"),
                                khataNumber = doc.getString("khataNumber"),
                                phone = doc.getString("phone") ?: "",
                                createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Firebase login attempt failed: ${e.message}")
                }
            }

            if (user == null) {
                user = findRegisteredUser(cleanEmail, password)
            }

            if (user != null) {
                saveUserSession(user)
                loadUserData(user.uid, user.role)
                Result.success(user)
            } else {
                Result.failure(Exception("Incorrect email or password. Please verify and try again."))
            }
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: "Login failed. Please check your credentials."))
        }
    }

    fun logout() {
        try {
            firebaseAuth?.signOut()
        } catch (e: Exception) {
            Log.w(TAG, "Error signing out: ${e.message}")
        }

        activeListeners.forEach { it.remove() }
        activeListeners.clear()

        _currentUser.value = null
        _customers.value = emptyList()
        _connectedKhatas.value = emptyList()
        _items.value = emptyList()
        _monthlyRecords.value = emptyList()
        _paymentRequests.value = emptyList()
        _notifications.value = emptyList()

        prefs.edit().remove("current_user_json").apply()
    }

    suspend fun updateShopName(newShopName: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val cleanName = newShopName.trim()
            if (cleanName.isBlank()) return@withContext Result.failure(Exception("Shop name cannot be blank."))
            val user = _currentUser.value ?: return@withContext Result.failure(Exception("Not authenticated."))
            if (user.role != UserRole.SHOPKEEPER) {
                return@withContext Result.failure(Exception("Only shopkeepers can edit shop name."))
            }

            val updatedUser = user.copy(shopName = cleanName)
            saveUserSession(updatedUser)

            firestore?.let { db ->
                try {
                    db.collection("users").document(user.uid).update("shopName", cleanName).await()
                    db.collection("shops").document(user.uid).update("shopName", cleanName).await()
                } catch (e: Exception) {
                    Log.w(TAG, "Firestore shopName update failed: ${e.message}")
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteAccount(passwordForReauth: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val user = _currentUser.value ?: return@withContext Result.failure(Exception("No active session to delete."))
            val auth = firebaseAuth
            val fbUser = auth?.currentUser

            if (fbUser != null && !passwordForReauth.isNullOrBlank()) {
                try {
                    val credential = EmailAuthProvider.getCredential(user.email, passwordForReauth)
                    fbUser.reauthenticate(credential).await()
                } catch (e: Exception) {
                    return@withContext Result.failure(Exception("Re-authentication failed: ${e.message}"))
                }
            }

            // Deleting a customer does not delete shopkeeper's original records
            firestore?.let { db ->
                try {
                    db.collection("users").document(user.uid).delete().await()
                    if (user.role == UserRole.SHOPKEEPER) {
                        db.collection("shops").document(user.uid).delete().await()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error removing Firestore user docs: ${e.message}")
                }
            }

            if (fbUser != null) {
                try {
                    fbUser.delete().await()
                } catch (e: FirebaseAuthRecentLoginRequiredException) {
                    return@withContext Result.failure(e)
                } catch (e: Exception) {
                    if (e.message?.contains("recent", ignoreCase = true) == true) {
                        return@withContext Result.failure(e)
                    }
                }
            }

            logout()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendPasswordReset(email: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val cleanEmail = email.trim().lowercase()
            if (cleanEmail.isBlank()) return@withContext Result.failure(Exception("Please enter your email address."))
            val auth = firebaseAuth
            if (auth != null) {
                auth.sendPasswordResetEmail(cleanEmail).await()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: "Failed to send reset link. Please check your email address."))
        }
    }

    private fun saveUserSession(user: UserProfile) {
        _currentUser.value = user
        val obj = JSONObject()
        obj.put("uid", user.uid)
        obj.put("role", user.role.name)
        obj.put("name", user.name)
        obj.put("email", user.email)
        user.shopName?.let { obj.put("shopName", it) }
        user.khataNumber?.let { obj.put("khataNumber", it) }
        obj.put("phone", user.phone)
        obj.put("createdAt", user.createdAt)
        prefs.edit().putString("current_user_json", obj.toString()).apply()
    }

    private fun saveRegisteredUser(user: UserProfile, passwordHash: String) {
        val allUsersJson = prefs.getString("all_registered_users", "[]") ?: "[]"
        val arr = JSONArray(allUsersJson)
        val obj = JSONObject()
        obj.put("uid", user.uid)
        obj.put("role", user.role.name)
        obj.put("name", user.name)
        obj.put("email", user.email)
        user.shopName?.let { obj.put("shopName", it) }
        user.khataNumber?.let { obj.put("khataNumber", it) }
        obj.put("phone", user.phone)
        obj.put("password", passwordHash)
        obj.put("createdAt", user.createdAt)
        arr.put(obj)
        prefs.edit().putString("all_registered_users", arr.toString()).apply()
    }

    private fun findRegisteredUser(email: String, password: String): UserProfile? {
        val allUsersJson = prefs.getString("all_registered_users", "[]") ?: "[]"
        val arr = JSONArray(allUsersJson)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.getString("email").equals(email, ignoreCase = true) && obj.getString("password") == password) {
                return UserProfile(
                    uid = obj.getString("uid"),
                    role = UserRole.valueOf(obj.getString("role")),
                    name = obj.getString("name"),
                    email = obj.getString("email"),
                    shopName = if (obj.has("shopName")) obj.getString("shopName") else null,
                    khataNumber = if (obj.has("khataNumber")) obj.getString("khataNumber") else null,
                    phone = obj.optString("phone", ""),
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                )
            }
        }
        return null
    }

    private fun loadUserData(uid: String, role: UserRole) {
        activeListeners.forEach { it.remove() }
        activeListeners.clear()

        deserializeLocalData(uid)

        val db = firestore ?: return

        if (role == UserRole.SHOPKEEPER) {
            // Listen to customers of this shop: shops/{shopkeeperUid}/customers
            val custReg = db.collection("shops").document(uid).collection("customers")
                .addSnapshotListener { snapshot, e ->
                    if (e != null) {
                        Log.w(TAG, "Listen customers failed: ${e.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot != null) {
                        val list = snapshot.documents.mapNotNull { doc ->
                            Customer(
                                id = doc.id,
                                shopkeeperUid = uid,
                                khataNumber = doc.getString("khataNumber") ?: "",
                                name = doc.getString("name") ?: "",
                                phone = doc.getString("phone") ?: "",
                                address = doc.getString("address") ?: "",
                                linkedCustomerUid = doc.getString("linkedCustomerUid"),
                                createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()
                            )
                        }
                        _customers.value = list
                        saveLocalData(uid)
                    }
                }
            activeListeners.add(custReg)

            // Listen to payment requests for this shop: shops/{shopkeeperUid}/paymentRequests
            val reqReg = db.collection("shops").document(uid).collection("paymentRequests")
                .addSnapshotListener { snapshot, e ->
                    if (e != null) return@addSnapshotListener
                    if (snapshot != null) {
                        val list = snapshot.documents.mapNotNull { doc ->
                            try {
                                PaymentRequest(
                                    id = doc.id,
                                    shopkeeperUid = uid,
                                    shopName = doc.getString("shopName") ?: (_currentUser.value?.shopName ?: "Shop"),
                                    customerUid = doc.getString("customerUid") ?: "",
                                    customerId = doc.getString("customerId") ?: "",
                                    customerName = doc.getString("customerName") ?: "",
                                    khataNumber = doc.getString("khataNumber") ?: "",
                                    targetMonthLabel = doc.getString("targetMonthLabel") ?: previousMonthLabel,
                                    originalBill = doc.getDouble("originalBill") ?: 0.0,
                                    requestAmount = doc.getDouble("requestAmount") ?: 0.0,
                                    status = PaymentStatus.valueOf(doc.getString("status") ?: "PENDING"),
                                    requestDate = doc.getString("requestDate") ?: "",
                                    decisionDate = doc.getString("decisionDate"),
                                    createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()
                                )
                            } catch (_: Exception) {
                                null
                            }
                        }
                        _paymentRequests.value = list
                        saveLocalData(uid)
                    }
                }
            activeListeners.add(reqReg)

            // Listen to notifications: users/{uid}/notifications
            val notifReg = db.collection("users").document(uid).collection("notifications")
                .addSnapshotListener { snapshot, e ->
                    if (e != null) return@addSnapshotListener
                    if (snapshot != null) {
                        val list = snapshot.documents.mapNotNull { doc ->
                            try {
                                KhataNotification(
                                    id = doc.id,
                                    recipientUid = uid,
                                    shopId = doc.getString("shopId"),
                                    shopName = doc.getString("shopName"),
                                    customerId = doc.getString("customerId"),
                                    targetRole = UserRole.SHOPKEEPER,
                                    title = doc.getString("title") ?: "",
                                    message = doc.getString("message") ?: "",
                                    date = doc.getString("date") ?: "",
                                    isRead = doc.getBoolean("isRead") ?: false,
                                    type = doc.getString("type") ?: "GENERAL",
                                    createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()
                                )
                            } catch (_: Exception) {
                                null
                            }
                        }
                        _notifications.value = list
                        saveLocalData(uid)
                    }
                }
            activeListeners.add(notifReg)
        } else {
            // Customer Mode:
            // 1. Listen to customer's connected khatas: users/{uid}/connectedKhatas
            val connectedReg = db.collection("users").document(uid).collection("connectedKhatas")
                .addSnapshotListener { snapshot, e ->
                    if (e != null) {
                        Log.w(TAG, "Listen connectedKhatas error: ${e.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot != null) {
                        val list = snapshot.documents.mapNotNull { doc ->
                            try {
                                ConnectedKhata(
                                    connectionId = doc.id,
                                    customerUid = uid,
                                    shopId = doc.getString("shopId") ?: "",
                                    shopName = doc.getString("shopName") ?: "Shop",
                                    shopOwnerName = doc.getString("shopOwnerName") ?: "",
                                    customerId = doc.getString("customerId") ?: "",
                                    khataNumber = doc.getString("khataNumber") ?: "",
                                    customerName = doc.getString("customerName") ?: "",
                                    connectedAt = doc.getLong("connectedAt") ?: System.currentTimeMillis(),
                                    status = doc.getString("status") ?: "ACTIVE"
                                )
                            } catch (_: Exception) {
                                null
                            }
                        }
                        _connectedKhatas.value = list
                        saveLocalData(uid)

                        // Attach listeners for each connected shop's entries
                        attachConnectedShopListeners(list)
                    }
                }
            activeListeners.add(connectedReg)

            // 2. Listen to notifications: users/{uid}/notifications
            val notifReg = db.collection("users").document(uid).collection("notifications")
                .addSnapshotListener { snapshot, e ->
                    if (e != null) return@addSnapshotListener
                    if (snapshot != null) {
                        val list = snapshot.documents.mapNotNull { doc ->
                            try {
                                KhataNotification(
                                    id = doc.id,
                                    recipientUid = uid,
                                    shopId = doc.getString("shopId"),
                                    shopName = doc.getString("shopName"),
                                    customerId = doc.getString("customerId"),
                                    targetRole = UserRole.CUSTOMER,
                                    title = doc.getString("title") ?: "",
                                    message = doc.getString("message") ?: "",
                                    date = doc.getString("date") ?: "",
                                    isRead = doc.getBoolean("isRead") ?: false,
                                    type = doc.getString("type") ?: "GENERAL",
                                    createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()
                                )
                            } catch (_: Exception) {
                                null
                            }
                        }
                        _notifications.value = list
                        saveLocalData(uid)
                    }
                }
            activeListeners.add(notifReg)
        }
    }

    private fun attachConnectedShopListeners(connections: List<ConnectedKhata>) {
        val db = firestore ?: return
        connections.forEach { conn ->
            if (conn.shopId.isNotBlank() && conn.customerId.isNotBlank()) {
                // Listen to items for this connected customer
                val itemReg = db.collection("shops").document(conn.shopId)
                    .collection("customers").document(conn.customerId)
                    .collection("khataEntries")
                    .addSnapshotListener { snapshot, _ ->
                        if (snapshot != null) {
                            val remoteItems = snapshot.documents.mapNotNull { doc ->
                                try {
                                    KhataItem(
                                        id = doc.id,
                                        customerId = conn.customerId,
                                        shopkeeperUid = conn.shopId,
                                        monthKey = doc.getString("monthKey") ?: currentMonthKey,
                                        date = doc.getString("date") ?: "",
                                        name = doc.getString("name") ?: "",
                                        price = doc.getDouble("price") ?: 0.0,
                                        createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()
                                    )
                                } catch (_: Exception) {
                                    null
                                }
                            }
                            // Merge with existing items
                            val otherItems = _items.value.filterNot { it.customerId == conn.customerId && it.shopkeeperUid == conn.shopId }
                            _items.value = otherItems + remoteItems
                            _currentUser.value?.let { saveLocalData(it.uid) }
                        }
                    }
                activeListeners.add(itemReg)

                // Listen to monthly bills
                val billReg = db.collection("shops").document(conn.shopId)
                    .collection("customers").document(conn.customerId)
                    .collection("monthlyBills")
                    .addSnapshotListener { snapshot, _ ->
                        if (snapshot != null) {
                            val remoteRecords = snapshot.documents.mapNotNull { doc ->
                                try {
                                    MonthlyKhataRecord(
                                        id = doc.id,
                                        customerId = conn.customerId,
                                        shopkeeperUid = conn.shopId,
                                        monthKey = doc.getString("monthKey") ?: previousMonthKey,
                                        monthLabel = doc.getString("monthLabel") ?: previousMonthLabel,
                                        totalPurchases = doc.getDouble("totalPurchases") ?: 0.0,
                                        paidAmount = doc.getDouble("paidAmount") ?: 0.0,
                                        isCurrentActiveMonth = doc.getBoolean("isCurrentActiveMonth") ?: false,
                                        createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()
                                    )
                                } catch (_: Exception) {
                                    null
                                }
                            }
                            val otherRecords = _monthlyRecords.value.filterNot { it.customerId == conn.customerId && it.shopkeeperUid == conn.shopId }
                            _monthlyRecords.value = otherRecords + remoteRecords
                            _currentUser.value?.let { saveLocalData(it.uid) }
                        }
                    }
                activeListeners.add(billReg)
            }
        }
    }

    private fun saveLocalData(uid: String) {
        val editor = prefs.edit()

        // Customers
        val custArr = JSONArray()
        _customers.value.forEach { c ->
            val obj = JSONObject()
            obj.put("id", c.id)
            obj.put("shopkeeperUid", c.shopkeeperUid)
            obj.put("khataNumber", c.khataNumber)
            obj.put("name", c.name)
            obj.put("phone", c.phone)
            obj.put("address", c.address)
            c.linkedCustomerUid?.let { obj.put("linkedCustomerUid", it) }
            obj.put("createdAt", c.createdAt)
            custArr.put(obj)
        }
        editor.putString("customers_$uid", custArr.toString())

        // Connected Khatas
        val connArr = JSONArray()
        _connectedKhatas.value.forEach { k ->
            val obj = JSONObject()
            obj.put("connectionId", k.connectionId)
            obj.put("customerUid", k.customerUid)
            obj.put("shopId", k.shopId)
            obj.put("shopName", k.shopName)
            obj.put("shopOwnerName", k.shopOwnerName)
            obj.put("customerId", k.customerId)
            obj.put("khataNumber", k.khataNumber)
            obj.put("customerName", k.customerName)
            obj.put("connectedAt", k.connectedAt)
            obj.put("status", k.status)
            connArr.put(obj)
        }
        editor.putString("connected_khatas_$uid", connArr.toString())

        // Items
        val itemsArr = JSONArray()
        _items.value.forEach { item ->
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("customerId", item.customerId)
            obj.put("shopkeeperUid", item.shopkeeperUid)
            obj.put("monthKey", item.monthKey)
            obj.put("date", item.date)
            obj.put("name", item.name)
            obj.put("price", item.price)
            obj.put("createdAt", item.createdAt)
            itemsArr.put(obj)
        }
        editor.putString("items_$uid", itemsArr.toString())

        // Monthly Records
        val recArr = JSONArray()
        _monthlyRecords.value.forEach { r ->
            val obj = JSONObject()
            obj.put("id", r.id)
            obj.put("customerId", r.customerId)
            obj.put("shopkeeperUid", r.shopkeeperUid)
            obj.put("monthKey", r.monthKey)
            obj.put("monthLabel", r.monthLabel)
            obj.put("totalPurchases", r.totalPurchases)
            obj.put("paidAmount", r.paidAmount)
            obj.put("isCurrentActiveMonth", r.isCurrentActiveMonth)
            obj.put("createdAt", r.createdAt)
            recArr.put(obj)
        }
        editor.putString("records_$uid", recArr.toString())

        // Requests
        val reqArr = JSONArray()
        _paymentRequests.value.forEach { req ->
            val obj = JSONObject()
            obj.put("id", req.id)
            obj.put("shopkeeperUid", req.shopkeeperUid)
            obj.put("shopName", req.shopName)
            obj.put("customerUid", req.customerUid)
            obj.put("customerId", req.customerId)
            obj.put("customerName", req.customerName)
            obj.put("khataNumber", req.khataNumber)
            obj.put("targetMonthLabel", req.targetMonthLabel)
            obj.put("originalBill", req.originalBill)
            obj.put("requestAmount", req.requestAmount)
            obj.put("status", req.status.name)
            obj.put("requestDate", req.requestDate)
            req.decisionDate?.let { obj.put("decisionDate", it) }
            obj.put("createdAt", req.createdAt)
            reqArr.put(obj)
        }
        editor.putString("requests_$uid", reqArr.toString())

        // Notifications
        val notifArr = JSONArray()
        _notifications.value.forEach { notif ->
            val obj = JSONObject()
            obj.put("id", notif.id)
            obj.put("recipientUid", notif.recipientUid)
            notif.shopId?.let { obj.put("shopId", it) }
            notif.shopName?.let { obj.put("shopName", it) }
            notif.customerId?.let { obj.put("customerId", it) }
            obj.put("targetRole", notif.targetRole.name)
            obj.put("title", notif.title)
            obj.put("message", notif.message)
            obj.put("date", notif.date)
            obj.put("isRead", notif.isRead)
            obj.put("type", notif.type)
            obj.put("createdAt", notif.createdAt)
            notifArr.put(obj)
        }
        editor.putString("notifs_$uid", notifArr.toString())

        editor.apply()
    }

    private fun deserializeLocalData(uid: String) {
        val custStr = prefs.getString("customers_$uid", "[]") ?: "[]"
        val custArr = JSONArray(custStr)
        val custList = mutableListOf<Customer>()
        for (i in 0 until custArr.length()) {
            val o = custArr.getJSONObject(i)
            custList.add(
                Customer(
                    id = o.getString("id"),
                    shopkeeperUid = o.optString("shopkeeperUid", uid),
                    khataNumber = o.getString("khataNumber"),
                    name = o.getString("name"),
                    phone = o.optString("phone", ""),
                    address = o.optString("address", ""),
                    linkedCustomerUid = if (o.has("linkedCustomerUid")) o.getString("linkedCustomerUid") else null,
                    createdAt = o.optLong("createdAt", System.currentTimeMillis())
                )
            )
        }

        val connStr = prefs.getString("connected_khatas_$uid", "[]") ?: "[]"
        val connArr = JSONArray(connStr)
        val connList = mutableListOf<ConnectedKhata>()
        for (i in 0 until connArr.length()) {
            val o = connArr.getJSONObject(i)
            connList.add(
                ConnectedKhata(
                    connectionId = o.getString("connectionId"),
                    customerUid = o.getString("customerUid"),
                    shopId = o.getString("shopId"),
                    shopName = o.getString("shopName"),
                    shopOwnerName = o.optString("shopOwnerName", ""),
                    customerId = o.getString("customerId"),
                    khataNumber = o.getString("khataNumber"),
                    customerName = o.optString("customerName", ""),
                    connectedAt = o.optLong("connectedAt", System.currentTimeMillis()),
                    status = o.optString("status", "ACTIVE")
                )
            )
        }

        val itemStr = prefs.getString("items_$uid", "[]") ?: "[]"
        val itemArr = JSONArray(itemStr)
        val itemList = mutableListOf<KhataItem>()
        for (i in 0 until itemArr.length()) {
            val o = itemArr.getJSONObject(i)
            itemList.add(
                KhataItem(
                    id = o.getString("id"),
                    customerId = o.getString("customerId"),
                    shopkeeperUid = o.optString("shopkeeperUid", uid),
                    monthKey = o.getString("monthKey"),
                    date = o.getString("date"),
                    name = o.getString("name"),
                    price = o.getDouble("price"),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis())
                )
            )
        }

        val recStr = prefs.getString("records_$uid", "[]") ?: "[]"
        val recArr = JSONArray(recStr)
        val recList = mutableListOf<MonthlyKhataRecord>()
        for (i in 0 until recArr.length()) {
            val o = recArr.getJSONObject(i)
            recList.add(
                MonthlyKhataRecord(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    customerId = o.getString("customerId"),
                    shopkeeperUid = o.optString("shopkeeperUid", uid),
                    monthKey = o.getString("monthKey"),
                    monthLabel = o.getString("monthLabel"),
                    totalPurchases = o.getDouble("totalPurchases"),
                    paidAmount = o.getDouble("paidAmount"),
                    isCurrentActiveMonth = o.optBoolean("isCurrentActiveMonth", false),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis())
                )
            )
        }

        val reqStr = prefs.getString("requests_$uid", "[]") ?: "[]"
        val reqArr = JSONArray(reqStr)
        val reqList = mutableListOf<PaymentRequest>()
        for (i in 0 until reqArr.length()) {
            val o = reqArr.getJSONObject(i)
            reqList.add(
                PaymentRequest(
                    id = o.getString("id"),
                    shopkeeperUid = o.optString("shopkeeperUid", uid),
                    shopName = o.optString("shopName", "Shop"),
                    customerUid = o.optString("customerUid", ""),
                    customerId = o.getString("customerId"),
                    customerName = o.getString("customerName"),
                    khataNumber = o.getString("khataNumber"),
                    targetMonthLabel = o.getString("targetMonthLabel"),
                    originalBill = o.getDouble("originalBill"),
                    requestAmount = o.getDouble("requestAmount"),
                    status = PaymentStatus.valueOf(o.getString("status")),
                    requestDate = o.getString("requestDate"),
                    decisionDate = if (o.has("decisionDate")) o.getString("decisionDate") else null,
                    createdAt = o.optLong("createdAt", System.currentTimeMillis())
                )
            )
        }

        val notifStr = prefs.getString("notifs_$uid", "[]") ?: "[]"
        val notifArr = JSONArray(notifStr)
        val notifList = mutableListOf<KhataNotification>()
        for (i in 0 until notifArr.length()) {
            val o = notifArr.getJSONObject(i)
            notifList.add(
                KhataNotification(
                    id = o.getString("id"),
                    recipientUid = o.optString("recipientUid", uid),
                    shopId = if (o.has("shopId")) o.getString("shopId") else null,
                    shopName = if (o.has("shopName")) o.getString("shopName") else null,
                    customerId = if (o.has("customerId")) o.getString("customerId") else null,
                    targetRole = UserRole.valueOf(o.getString("targetRole")),
                    title = o.getString("title"),
                    message = o.getString("message"),
                    date = o.getString("date"),
                    isRead = o.optBoolean("isRead", false),
                    type = o.optString("type", "GENERAL"),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis())
                )
            )
        }

        _customers.value = custList
        _connectedKhatas.value = connList
        _items.value = itemList
        _monthlyRecords.value = recList
        _paymentRequests.value = reqList
        _notifications.value = notifList
    }

    /**
     * Calculates balance for a customer. When in customer multi-shop mode, shopId can be supplied
     * to isolate calculations strictly to that shop.
     */
    fun calculateBalanceSummary(customerId: String, shopId: String? = null): CustomerBalanceSummary {
        val currentItems = _items.value.filter {
            it.customerId == customerId &&
            it.monthKey == currentMonthKey &&
            (shopId == null || it.shopkeeperUid == shopId)
        }
        val currentMonthTotal = currentItems.sumOf { it.price }

        val prevRecords = _monthlyRecords.value.filter {
            it.customerId == customerId &&
            it.monthKey != currentMonthKey &&
            (shopId == null || it.shopkeeperUid == shopId)
        }
        val previousBalance = prevRecords.sumOf { it.remainingBalance }

        val latestPrevRecord = prevRecords.maxByOrNull { it.monthKey }
        val prevOriginalBill = latestPrevRecord?.totalPurchases ?: 0.0
        val prevPaidAmount = latestPrevRecord?.paidAmount ?: 0.0

        val totalPayable = currentMonthTotal + previousBalance

        return CustomerBalanceSummary(
            currentMonthTotal = currentMonthTotal,
            previousBalance = previousBalance,
            totalPayable = totalPayable,
            currentMonthLabel = currentMonthLabel,
            previousMonthLabel = latestPrevRecord?.monthLabel ?: previousMonthLabel,
            previousOriginalBill = prevOriginalBill,
            previousPaidAmount = prevPaidAmount
        )
    }

    /**
     * Calculates the aggregate payable amount across all connected shops for Customer Home summary.
     */
    fun calculateTotalPayableAcrossAllKhatas(): Double {
        return _connectedKhatas.value.sumOf { conn ->
            val summary = calculateBalanceSummary(conn.customerId, conn.shopId)
            summary.totalPayable
        }
    }

    /**
     * Creates a temporary QR linking token.
     * Financial information is intentionally NOT stored inside the QR code for security reasons.
     * The token is associated with the shopkeeper's customer record and expires in 15 minutes.
     */
    suspend fun generateQrLinkingSession(customerId: String): Result<QrLinkingSession> = withContext(Dispatchers.IO) {
        try {
            val user = _currentUser.value ?: return@withContext Result.failure(Exception("Not authenticated."))
            if (user.role != UserRole.SHOPKEEPER) {
                return@withContext Result.failure(Exception("Only shopkeepers can generate QR linking codes."))
            }

            val cust = _customers.value.find { it.id == customerId }
                ?: return@withContext Result.failure(Exception("Customer record not found."))

            val token = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val expiresAt = now + (15 * 60 * 1000L) // 15 minutes

            val session = QrLinkingSession(
                token = token,
                shopId = user.uid,
                shopName = user.shopName ?: "My Shop",
                shopOwnerName = user.name,
                customerId = cust.id,
                customerName = cust.name,
                khataNumber = cust.khataNumber,
                createdAt = now,
                expiresAt = expiresAt,
                isUsed = false
            )

            firestore?.let { db ->
                try {
                    val map = hashMapOf(
                        "token" to session.token,
                        "shopId" to session.shopId,
                        "shopName" to session.shopName,
                        "shopOwnerName" to session.shopOwnerName,
                        "customerId" to session.customerId,
                        "customerName" to session.customerName,
                        "khataNumber" to session.khataNumber,
                        "createdAt" to session.createdAt,
                        "expiresAt" to session.expiresAt,
                        "isUsed" to false
                    )
                    db.collection("shops").document(user.uid)
                        .collection("qrLinkingSessions").document(token).set(map).await()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to save QR session to Firestore: ${e.message}")
                }
            }

            Result.success(session)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Verifies the scanned QR token against Firebase without modifying state.
     * Checks token authenticity, expiration, and ensures single-use security.
     */
    suspend fun verifyQrLinkingToken(rawCode: String): Result<QrLinkVerificationResult> = withContext(Dispatchers.IO) {
        try {
            val parsed = QRService.parsePayload(rawCode)
                ?: return@withContext Result.failure(Exception("Invalid Digital Khata QR code format."))

            // Check if customer already has this khata connected
            val alreadyConnected = _connectedKhatas.value.any {
                it.shopId == parsed.shopId && it.customerId == parsed.customerId
            }
            if (alreadyConnected) {
                return@withContext Result.failure(Exception("This Khata is already connected to your account."))
            }

            var verifiedResult: QrLinkVerificationResult? = null

            // Verify with Firestore
            val db = firestore
            if (db != null) {
                try {
                    val doc = db.collection("shops").document(parsed.shopId)
                        .collection("qrLinkingSessions").document(parsed.token).get().await()

                    if (!doc.exists()) {
                        return@withContext Result.failure(Exception("QR code not found or expired."))
                    }

                    val isUsed = doc.getBoolean("isUsed") ?: false
                    if (isUsed) {
                        return@withContext Result.failure(Exception("This QR code has already been used."))
                    }

                    val expiresAt = doc.getLong("expiresAt") ?: 0L
                    if (System.currentTimeMillis() > expiresAt) {
                        return@withContext Result.failure(Exception("This QR code has expired. Please ask the shopkeeper to generate a new QR."))
                    }

                    val customerId = doc.getString("customerId") ?: parsed.customerId
                    val customerName = doc.getString("customerName") ?: "Customer"
                    val khataNumber = doc.getString("khataNumber") ?: "---"
                    val shopName = doc.getString("shopName") ?: "Shop"
                    val shopOwnerName = doc.getString("shopOwnerName") ?: ""

                    verifiedResult = QrLinkVerificationResult(
                        token = parsed.token,
                        shopId = parsed.shopId,
                        shopName = shopName,
                        shopOwnerName = shopOwnerName,
                        customerId = customerId,
                        customerName = customerName,
                        khataNumber = khataNumber
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Firestore QR verification error: ${e.message}")
                }
            }

            if (verifiedResult != null) {
                Result.success(verifiedResult)
            } else {
                Result.failure(Exception("Unable to verify QR code. Please check your network and try again."))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Connects this customer account to an existing shopkeeper khata without creating duplicate khata data.
     * Safely marks the QR linking session as used to prevent replay attacks.
     */
    suspend fun connectKhataFromVerifiedQr(result: QrLinkVerificationResult): Result<ConnectedKhata> = withContext(Dispatchers.IO) {
        try {
            val user = _currentUser.value ?: return@withContext Result.failure(Exception("Please log in as Customer first."))
            if (user.role != UserRole.CUSTOMER) {
                return@withContext Result.failure(Exception("Only Customer accounts can connect to shop Khatas."))
            }

            val connectionId = "${result.shopId}_${result.customerId}"
            val connection = ConnectedKhata(
                connectionId = connectionId,
                customerUid = user.uid,
                shopId = result.shopId,
                shopName = result.shopName,
                shopOwnerName = result.shopOwnerName,
                customerId = result.customerId,
                khataNumber = result.khataNumber,
                customerName = result.customerName,
                connectedAt = System.currentTimeMillis(),
                status = "ACTIVE"
            )

            // Save connection in customer's profile
            firestore?.let { db ->
                try {
                    val connMap = hashMapOf(
                        "connectionId" to connection.connectionId,
                        "customerUid" to connection.customerUid,
                        "shopId" to connection.shopId,
                        "shopName" to connection.shopName,
                        "shopOwnerName" to connection.shopOwnerName,
                        "customerId" to connection.customerId,
                        "khataNumber" to connection.khataNumber,
                        "customerName" to connection.customerName,
                        "connectedAt" to connection.connectedAt,
                        "status" to connection.status
                    )
                    db.collection("users").document(user.uid)
                        .collection("connectedKhatas").document(connectionId).set(connMap).await()

                    // Link customer UID in shopkeeper's customer document
                    db.collection("shops").document(result.shopId)
                        .collection("customers").document(result.customerId)
                        .update("linkedCustomerUid", user.uid).await()

                    // Mark QR linking session as used
                    db.collection("shops").document(result.shopId)
                        .collection("qrLinkingSessions").document(result.token)
                        .update("isUsed", true, "usedByUid", user.uid, "usedAt", System.currentTimeMillis()).await()

                    // Notify shopkeeper
                    val notifMap = hashMapOf(
                        "recipientUid" to result.shopId,
                        "shopId" to result.shopId,
                        "shopName" to result.shopName,
                        "customerId" to result.customerId,
                        "targetRole" to "SHOPKEEPER",
                        "title" to "Khata Connected",
                        "message" to "${user.name} linked Khata #${result.khataNumber}.",
                        "date" to "Today",
                        "isRead" to false,
                        "type" to "KHATA_CONNECTED",
                        "createdAt" to System.currentTimeMillis()
                    )
                    db.collection("users").document(result.shopId)
                        .collection("notifications").add(notifMap).await()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed updating Firestore on connect: ${e.message}")
                }
            }

            _connectedKhatas.value = _connectedKhatas.value.filterNot { it.connectionId == connectionId } + connection
            saveLocalData(user.uid)
            attachConnectedShopListeners(listOf(connection))

            Result.success(connection)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Removes the customer's connection to that shop without deleting the shopkeeper's original records.
     */
    suspend fun removeKhataConnection(connectionId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val user = _currentUser.value ?: return@withContext Result.failure(Exception("Not authenticated."))
            val conn = _connectedKhatas.value.find { it.connectionId == connectionId }
                ?: return@withContext Result.failure(Exception("Connection not found."))

            firestore?.let { db ->
                try {
                    db.collection("users").document(user.uid)
                        .collection("connectedKhatas").document(connectionId).delete().await()

                    // Unlink in shopkeeper's record if currently linked to this customer
                    db.collection("shops").document(conn.shopId)
                        .collection("customers").document(conn.customerId)
                        .update("linkedCustomerUid", null).await()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to delete connection in Firestore: ${e.message}")
                }
            }

            _connectedKhatas.value = _connectedKhatas.value.filterNot { it.connectionId == connectionId }
            saveLocalData(user.uid)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun addCustomer(
        name: String,
        khataNumber: String,
        phone: String = "",
        address: String = ""
    ): Customer {
        val user = _currentUser.value
        val shopkeeperUid = user?.uid ?: "shop_local"
        val cleanName = name.trim().ifEmpty { "Customer" }
        val cleanKhata = khataNumber.trim().ifEmpty { (100 + _customers.value.size).toString() }
        val customerId = "cust_${cleanKhata}_${System.currentTimeMillis()}"

        val newCustomer = Customer(
            id = customerId,
            shopkeeperUid = shopkeeperUid,
            khataNumber = cleanKhata,
            name = cleanName,
            phone = phone.trim(),
            address = address.trim(),
            createdAt = System.currentTimeMillis()
        )
        _customers.value = _customers.value + newCustomer

        val initialRec = MonthlyKhataRecord(
            id = UUID.randomUUID().toString(),
            customerId = newCustomer.id,
            shopkeeperUid = shopkeeperUid,
            monthKey = currentMonthKey,
            monthLabel = currentMonthLabel,
            totalPurchases = 0.0,
            paidAmount = 0.0,
            isCurrentActiveMonth = true
        )
        _monthlyRecords.value = _monthlyRecords.value + initialRec

        if (user != null) {
            saveLocalData(user.uid)
            firestore?.let { db ->
                try {
                    val map = hashMapOf(
                        "customerId" to newCustomer.id,
                        "shopkeeperUid" to user.uid,
                        "khataNumber" to newCustomer.khataNumber,
                        "name" to newCustomer.name,
                        "phone" to newCustomer.phone,
                        "address" to newCustomer.address,
                        "createdAt" to newCustomer.createdAt
                    )
                    db.collection("shops").document(user.uid)
                        .collection("customers").document(newCustomer.id).set(map)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to write customer to Firestore: ${e.message}")
                }
            }
        }
        return newCustomer
    }

    fun addItem(
        customerId: String,
        name: String,
        price: Double,
        dateStr: String
    ): KhataItem {
        val user = _currentUser.value
        val shopkeeperUid = user?.uid ?: "shop_local"
        val shopName = user?.shopName ?: "Shop"

        val newItem = KhataItem(
            id = "item_${UUID.randomUUID().toString().take(8)}",
            customerId = customerId,
            shopkeeperUid = shopkeeperUid,
            monthKey = currentMonthKey,
            date = dateStr.trim(),
            name = name.trim(),
            price = price,
            createdAt = System.currentTimeMillis()
        )
        _items.value = _items.value + newItem

        val existingRec = _monthlyRecords.value.find { it.customerId == customerId && it.monthKey == currentMonthKey && it.shopkeeperUid == shopkeeperUid }
        if (existingRec != null) {
            val updated = existingRec.copy(totalPurchases = existingRec.totalPurchases + price)
            _monthlyRecords.value = _monthlyRecords.value.map {
                if (it.customerId == customerId && it.monthKey == currentMonthKey && it.shopkeeperUid == shopkeeperUid) updated else it
            }
        } else {
            val newRec = MonthlyKhataRecord(
                id = UUID.randomUUID().toString(),
                customerId = customerId,
                shopkeeperUid = shopkeeperUid,
                monthKey = currentMonthKey,
                monthLabel = currentMonthLabel,
                totalPurchases = price,
                paidAmount = 0.0,
                isCurrentActiveMonth = true
            )
            _monthlyRecords.value = _monthlyRecords.value + newRec
        }

        val formattedPrice = if (price % 1.0 == 0.0) price.toLong().toString() else price.toString()
        val notif = KhataNotification(
            id = "notif_${UUID.randomUUID().toString().take(8)}",
            recipientUid = customerId,
            shopId = shopkeeperUid,
            shopName = shopName,
            customerId = customerId,
            targetRole = UserRole.CUSTOMER,
            title = "New Khata Entry",
            message = "$shopName added ${name.trim()} for Rs $formattedPrice.",
            date = dateStr.trim(),
            isRead = false,
            type = "ITEM_ADDED"
        )
        _notifications.value = listOf(notif) + _notifications.value

        if (user != null) {
            saveLocalData(user.uid)
            firestore?.let { db ->
                try {
                    val map = hashMapOf(
                        "id" to newItem.id,
                        "customerId" to customerId,
                        "shopkeeperUid" to user.uid,
                        "monthKey" to newItem.monthKey,
                        "date" to newItem.date,
                        "name" to newItem.name,
                        "price" to newItem.price,
                        "createdAt" to newItem.createdAt
                    )
                    db.collection("shops").document(user.uid)
                        .collection("customers").document(customerId)
                        .collection("khataEntries").document(newItem.id).set(map)

                    // Find linked customer UID if linked, and send notification
                    val custDoc = _customers.value.find { it.id == customerId }
                    custDoc?.linkedCustomerUid?.let { linkedUid ->
                        val notifMap = hashMapOf(
                            "recipientUid" to linkedUid,
                            "shopId" to user.uid,
                            "shopName" to shopName,
                            "customerId" to customerId,
                            "targetRole" to "CUSTOMER",
                            "title" to "New Khata Entry",
                            "message" to "$shopName added ${name.trim()} for Rs $formattedPrice.",
                            "date" to dateStr.trim(),
                            "isRead" to false,
                            "type" to "ITEM_ADDED",
                            "createdAt" to System.currentTimeMillis()
                        )
                        db.collection("users").document(linkedUid).collection("notifications").add(notifMap)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to write item to Firestore: ${e.message}")
                }
            }
        }
        return newItem
    }

    /**
     * Sends a shop-specific payment request from a customer.
     */
    fun createPaymentRequest(
        shopId: String,
        customerId: String,
        amount: Double,
        dateStr: String = "Today"
    ): PaymentRequest {
        val user = _currentUser.value
        val conn = _connectedKhatas.value.find { it.shopId == shopId && it.customerId == customerId }
        val shopName = conn?.shopName ?: "Shop"
        val customerName = conn?.customerName ?: (user?.name ?: "Customer")
        val khataNumber = conn?.khataNumber ?: "---"

        val summary = calculateBalanceSummary(customerId, shopId)
        val originalBill = if (summary.previousBalance > 0) summary.previousBalance else amount

        val req = PaymentRequest(
            id = "req_${UUID.randomUUID().toString().take(8)}",
            shopkeeperUid = shopId,
            shopName = shopName,
            customerUid = user?.uid ?: "",
            customerId = customerId,
            customerName = customerName,
            khataNumber = khataNumber,
            targetMonthLabel = previousMonthLabel,
            originalBill = originalBill,
            requestAmount = amount,
            status = PaymentStatus.PENDING,
            requestDate = dateStr,
            createdAt = System.currentTimeMillis()
        )
        _paymentRequests.value = listOf(req) + _paymentRequests.value

        val formattedAmount = if (amount % 1.0 == 0.0) amount.toLong().toString() else amount.toString()
        val notif = KhataNotification(
            id = "notif_${UUID.randomUUID().toString().take(8)}",
            recipientUid = shopId,
            shopId = shopId,
            shopName = shopName,
            customerId = customerId,
            targetRole = UserRole.SHOPKEEPER,
            title = "New Payment Request",
            message = "$customerName (Khata #$khataNumber) requested Rs $formattedAmount for $shopName.",
            date = dateStr,
            isRead = false,
            type = "PAYMENT_REQUEST"
        )
        _notifications.value = listOf(notif) + _notifications.value

        if (user != null) {
            saveLocalData(user.uid)
            firestore?.let { db ->
                try {
                    val map = hashMapOf(
                        "id" to req.id,
                        "shopkeeperUid" to req.shopkeeperUid,
                        "shopName" to req.shopName,
                        "customerUid" to req.customerUid,
                        "customerId" to req.customerId,
                        "customerName" to req.customerName,
                        "khataNumber" to req.khataNumber,
                        "targetMonthLabel" to req.targetMonthLabel,
                        "originalBill" to req.originalBill,
                        "requestAmount" to req.requestAmount,
                        "status" to req.status.name,
                        "requestDate" to req.requestDate,
                        "createdAt" to req.createdAt
                    )
                    db.collection("shops").document(req.shopkeeperUid)
                        .collection("paymentRequests").document(req.id).set(map)

                    // Also add notification in shopkeeper's notifications
                    val notifMap = hashMapOf(
                        "recipientUid" to req.shopkeeperUid,
                        "shopId" to req.shopkeeperUid,
                        "shopName" to req.shopName,
                        "customerId" to req.customerId,
                        "targetRole" to "SHOPKEEPER",
                        "title" to "New Payment Request",
                        "message" to "$customerName (Khata #$khataNumber) submitted Rs $formattedAmount payment.",
                        "date" to dateStr,
                        "isRead" to false,
                        "type" to "PAYMENT_REQUEST",
                        "createdAt" to System.currentTimeMillis()
                    )
                    db.collection("users").document(req.shopkeeperUid).collection("notifications").add(notifMap)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to write request to Firestore: ${e.message}")
                }
            }
        }
        return req
    }

    fun acceptPaymentRequest(
        requestId: String,
        decisionDate: String = "Today"
    ) {
        val req = _paymentRequests.value.find { it.id == requestId } ?: return
        if (req.status != PaymentStatus.PENDING) {
            // Already processed; prevent duplicate balance change or double acceptance
            return
        }
        _paymentRequests.value = _paymentRequests.value.map {
            if (it.id == requestId) it.copy(status = PaymentStatus.ACCEPTED, decisionDate = decisionDate) else it
        }

        val prevRecord = _monthlyRecords.value.find {
            it.customerId == req.customerId &&
            it.monthKey == previousMonthKey &&
            it.shopkeeperUid == req.shopkeeperUid
        }
        if (prevRecord != null) {
            val newPaid = prevRecord.paidAmount + req.requestAmount
            val updatedRecord = prevRecord.copy(paidAmount = newPaid)
            _monthlyRecords.value = _monthlyRecords.value.map {
                if (it.customerId == req.customerId && it.monthKey == previousMonthKey && it.shopkeeperUid == req.shopkeeperUid) updatedRecord else it
            }
        } else {
            val newRec = MonthlyKhataRecord(
                id = UUID.randomUUID().toString(),
                customerId = req.customerId,
                shopkeeperUid = req.shopkeeperUid,
                monthKey = previousMonthKey,
                monthLabel = previousMonthLabel,
                totalPurchases = req.originalBill,
                paidAmount = req.requestAmount,
                isCurrentActiveMonth = false
            )
            _monthlyRecords.value = _monthlyRecords.value + newRec
        }

        val formattedAmount = if (req.requestAmount % 1.0 == 0.0) req.requestAmount.toLong().toString() else req.requestAmount.toString()
        val notif = KhataNotification(
            id = "notif_${UUID.randomUUID().toString().take(8)}",
            recipientUid = req.customerUid.ifEmpty { req.customerId },
            shopId = req.shopkeeperUid,
            shopName = req.shopName,
            customerId = req.customerId,
            targetRole = UserRole.CUSTOMER,
            title = "Payment Accepted",
            message = "Your payment of Rs $formattedAmount for ${req.shopName} has been accepted.",
            date = decisionDate,
            isRead = false,
            type = "PAYMENT_DECISION"
        )
        _notifications.value = listOf(notif) + _notifications.value

        _currentUser.value?.let { user ->
            saveLocalData(user.uid)
            firestore?.let { db ->
                try {
                    db.collection("shops").document(req.shopkeeperUid)
                        .collection("paymentRequests").document(req.id)
                        .update("status", "ACCEPTED", "decisionDate", decisionDate)

                    if (req.customerUid.isNotBlank()) {
                        val notifMap = hashMapOf(
                            "recipientUid" to req.customerUid,
                            "shopId" to req.shopkeeperUid,
                            "shopName" to req.shopName,
                            "customerId" to req.customerId,
                            "targetRole" to "CUSTOMER",
                            "title" to "Payment Accepted",
                            "message" to "Payment of Rs $formattedAmount for ${req.shopName} has been accepted.",
                            "date" to decisionDate,
                            "isRead" to false,
                            "type" to "PAYMENT_DECISION",
                            "createdAt" to System.currentTimeMillis()
                        )
                        db.collection("users").document(req.customerUid).collection("notifications").add(notifMap)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to update Firestore request: ${e.message}")
                }
            }
        }
    }

    fun rejectPaymentRequest(
        requestId: String,
        decisionDate: String = "Today"
    ) {
        val req = _paymentRequests.value.find { it.id == requestId } ?: return
        if (req.status != PaymentStatus.PENDING) {
            // Already processed; prevent duplicate decision
            return
        }
        _paymentRequests.value = _paymentRequests.value.map {
            if (it.id == requestId) it.copy(status = PaymentStatus.REJECTED, decisionDate = decisionDate) else it
        }

        val formattedAmount = if (req.requestAmount % 1.0 == 0.0) req.requestAmount.toLong().toString() else req.requestAmount.toString()
        val notif = KhataNotification(
            id = "notif_${UUID.randomUUID().toString().take(8)}",
            recipientUid = req.customerUid.ifEmpty { req.customerId },
            shopId = req.shopkeeperUid,
            shopName = req.shopName,
            customerId = req.customerId,
            targetRole = UserRole.CUSTOMER,
            title = "Payment Request Declined",
            message = "Payment request of Rs $formattedAmount was declined by ${req.shopName}.",
            date = decisionDate,
            isRead = false,
            type = "PAYMENT_DECISION"
        )
        _notifications.value = listOf(notif) + _notifications.value

        _currentUser.value?.let { user ->
            saveLocalData(user.uid)
            firestore?.let { db ->
                try {
                    db.collection("shops").document(req.shopkeeperUid)
                        .collection("paymentRequests").document(req.id)
                        .update("status", "REJECTED", "decisionDate", decisionDate)

                    if (req.customerUid.isNotBlank()) {
                        val notifMap = hashMapOf(
                            "recipientUid" to req.customerUid,
                            "shopId" to req.shopkeeperUid,
                            "shopName" to req.shopName,
                            "customerId" to req.customerId,
                            "targetRole" to "CUSTOMER",
                            "title" to "Payment Request Declined",
                            "message" to "Payment request of Rs $formattedAmount was declined by ${req.shopName}.",
                            "date" to decisionDate,
                            "isRead" to false,
                            "type" to "PAYMENT_DECISION",
                            "createdAt" to System.currentTimeMillis()
                        )
                        db.collection("users").document(req.customerUid).collection("notifications").add(notifMap)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to update Firestore request: ${e.message}")
                }
            }
        }
    }

    fun markNotificationRead(notifId: String) {
        _currentUser.value?.let { user ->
            _notifications.value = _notifications.value.map {
                if (it.id == notifId) it.copy(isRead = true) else it
            }
            saveLocalData(user.uid)
        }
    }

    fun markAllNotificationsRead(targetRole: UserRole = UserRole.SHOPKEEPER, customerId: String? = null) {
        _currentUser.value?.let { user ->
            _notifications.value = _notifications.value.map { n ->
                val matches = if (targetRole == UserRole.SHOPKEEPER) {
                    n.targetRole == UserRole.SHOPKEEPER
                } else {
                    n.targetRole == UserRole.CUSTOMER && (customerId == null || n.customerId == null || n.customerId == customerId)
                }
                if (matches) n.copy(isRead = true) else n
            }
            saveLocalData(user.uid)
        }
    }
}
