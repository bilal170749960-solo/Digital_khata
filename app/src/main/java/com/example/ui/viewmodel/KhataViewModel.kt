package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.model.*
import com.example.data.repository.KhataRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class KhataViewModel(private val repository: KhataRepository) : ViewModel() {

    val currentUser: StateFlow<UserProfile?> = repository.currentUser
    val isAuthChecking: StateFlow<Boolean> = repository.isAuthChecking
    val themeMode: StateFlow<ThemeMode> = repository.themeMode

    // Shopkeeper state
    val customers: StateFlow<List<Customer>> = repository.customers

    // Customer state: list of connected shops/khatas
    val connectedKhatas: StateFlow<List<ConnectedKhata>> = repository.connectedKhatas

    // Active connected khata currently opened in customer detail view
    private val _activeConnectedKhata = MutableStateFlow<ConnectedKhata?>(null)
    val activeConnectedKhata: StateFlow<ConnectedKhata?> = _activeConnectedKhata.asStateFlow()

    val items: StateFlow<List<KhataItem>> = repository.items
    val monthlyRecords: StateFlow<List<MonthlyKhataRecord>> = repository.monthlyRecords
    val paymentRequests: StateFlow<List<PaymentRequest>> = repository.paymentRequests
    val notifications: StateFlow<List<KhataNotification>> = repository.notifications

    private val _appMode = MutableStateFlow(AppMode.SHOPKEEPER)
    val appMode: StateFlow<AppMode> = _appMode.asStateFlow()

    private val _selectedCustomerId = MutableStateFlow("")
    val selectedCustomerId: StateFlow<String> = _selectedCustomerId.asStateFlow()

    private val _shopkeeperTab = MutableStateFlow(ShopkeeperTab.HOME)
    val shopkeeperTab: StateFlow<ShopkeeperTab> = _shopkeeperTab.asStateFlow()

    private val _customerTab = MutableStateFlow(CustomerTab.HOME)
    val customerTab: StateFlow<CustomerTab> = _customerTab.asStateFlow()

    // Currently focused customer for Shopkeeper detail view
    private val _shopkeeperActiveKhataCustomerId = MutableStateFlow<String?>(null)
    val shopkeeperActiveKhataCustomerId: StateFlow<String?> = _shopkeeperActiveKhataCustomerId.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    init {
        // Automatically sync appMode with currentUser from Firebase
        viewModelScope.launch {
            currentUser.collect { user ->
                if (user != null) {
                    if (user.role == UserRole.CUSTOMER) {
                        _appMode.value = AppMode.CUSTOMER
                    } else {
                        _appMode.value = AppMode.SHOPKEEPER
                    }
                }
            }
        }
    }

    fun clearAuthError() {
        _authError.value = null
    }

    fun setSelectedCustomerId(id: String) {
        _selectedCustomerId.value = id
    }

    fun setShopkeeperTab(tab: ShopkeeperTab) {
        _shopkeeperTab.value = tab
    }

    fun setCustomerTab(tab: CustomerTab) {
        _customerTab.value = tab
    }

    fun openShopkeeperCustomerKhata(customerId: String) {
        _shopkeeperActiveKhataCustomerId.value = customerId
        _shopkeeperTab.value = ShopkeeperTab.CUSTOMERS
    }

    fun closeShopkeeperCustomerKhata() {
        _shopkeeperActiveKhataCustomerId.value = null
    }

    fun openCustomerKhataView(connection: ConnectedKhata) {
        _activeConnectedKhata.value = connection
        _customerTab.value = CustomerTab.KHATA
    }

    fun closeCustomerKhataView() {
        _activeConnectedKhata.value = null
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setThemeMode(mode: ThemeMode) {
        repository.setThemeMode(mode)
    }

    fun login(email: String, pass: String, onSuccess: () -> Unit = {}) {
        _isLoading.value = true
        _authError.value = null
        viewModelScope.launch {
            val result = repository.login(email, pass)
            _isLoading.value = false
            result.onSuccess { user ->
                if (user.role == UserRole.CUSTOMER) {
                    _appMode.value = AppMode.CUSTOMER
                    _customerTab.value = CustomerTab.HOME
                } else {
                    _appMode.value = AppMode.SHOPKEEPER
                    _shopkeeperTab.value = ShopkeeperTab.HOME
                }
                onSuccess()
            }.onFailure { err ->
                _authError.value = err.message ?: "Login failed"
            }
        }
    }

    fun registerShopkeeper(
        name: String,
        email: String,
        pass: String,
        shopName: String,
        phone: String,
        onSuccess: () -> Unit = {}
    ) {
        _isLoading.value = true
        _authError.value = null
        viewModelScope.launch {
            val result = repository.registerShopkeeper(name, email, pass, shopName, phone)
            _isLoading.value = false
            result.onSuccess {
                _appMode.value = AppMode.SHOPKEEPER
                _shopkeeperTab.value = ShopkeeperTab.HOME
                onSuccess()
            }.onFailure { err ->
                _authError.value = err.message ?: "Registration failed"
            }
        }
    }

    fun registerCustomer(
        name: String,
        email: String,
        pass: String,
        phone: String,
        onSuccess: () -> Unit = {}
    ) {
        _isLoading.value = true
        _authError.value = null
        viewModelScope.launch {
            val result = repository.registerCustomer(name, email, pass, phone)
            _isLoading.value = false
            result.onSuccess {
                _appMode.value = AppMode.CUSTOMER
                _customerTab.value = CustomerTab.HOME
                onSuccess()
            }.onFailure { err ->
                _authError.value = err.message ?: "Registration failed"
            }
        }
    }

    fun sendPasswordReset(email: String, onComplete: (Result<Unit>) -> Unit) {
        _isLoading.value = true
        viewModelScope.launch {
            val res = repository.sendPasswordReset(email)
            _isLoading.value = false
            onComplete(res)
        }
    }

    fun updateShopName(newShopName: String, onComplete: (Result<Unit>) -> Unit) {
        _isLoading.value = true
        viewModelScope.launch {
            val res = repository.updateShopName(newShopName)
            _isLoading.value = false
            onComplete(res)
        }
    }

    fun deleteAccount(passwordForReauth: String? = null, onComplete: (Result<Unit>) -> Unit) {
        _isLoading.value = true
        viewModelScope.launch {
            val res = repository.deleteAccount(passwordForReauth)
            _isLoading.value = false
            if (res.isSuccess) {
                _shopkeeperActiveKhataCustomerId.value = null
                _activeConnectedKhata.value = null
                _shopkeeperTab.value = ShopkeeperTab.HOME
                _customerTab.value = CustomerTab.HOME
            }
            onComplete(res)
        }
    }

    fun logout() {
        repository.logout()
        _shopkeeperActiveKhataCustomerId.value = null
        _activeConnectedKhata.value = null
        _shopkeeperTab.value = ShopkeeperTab.HOME
        _customerTab.value = CustomerTab.HOME
    }

    fun getCustomer(id: String): Customer? {
        return customers.value.find { it.id == id || it.khataNumber == id }
    }

    fun getCustomerBalanceSummary(customerId: String, shopId: String? = null): CustomerBalanceSummary {
        return repository.calculateBalanceSummary(customerId, shopId)
    }

    fun calculateTotalPayableAcrossAllKhatas(): Double {
        return repository.calculateTotalPayableAcrossAllKhatas()
    }

    fun addCustomer(
        name: String,
        khataNumber: String,
        phone: String = "",
        address: String = ""
    ): Customer {
        val cust = repository.addCustomer(name, khataNumber, phone, address)
        if (_selectedCustomerId.value.isEmpty()) {
            _selectedCustomerId.value = cust.id
        }
        return cust
    }

    fun addItem(
        customerId: String,
        name: String,
        price: Double,
        dateStr: String
    ): KhataItem {
        return repository.addItem(customerId, name, price, dateStr)
    }

    fun generateQrLinkingSession(
        customerId: String,
        onComplete: (Result<QrLinkingSession>) -> Unit
    ) {
        _isLoading.value = true
        viewModelScope.launch {
            val res = repository.generateQrLinkingSession(customerId)
            _isLoading.value = false
            onComplete(res)
        }
    }

    fun verifyQrLinkingToken(
        rawCode: String,
        onComplete: (Result<QrLinkVerificationResult>) -> Unit
    ) {
        _isLoading.value = true
        viewModelScope.launch {
            val res = repository.verifyQrLinkingToken(rawCode)
            _isLoading.value = false
            onComplete(res)
        }
    }

    fun connectKhataFromVerifiedQr(
        verifiedResult: QrLinkVerificationResult,
        onComplete: (Result<ConnectedKhata>) -> Unit
    ) {
        _isLoading.value = true
        viewModelScope.launch {
            val res = repository.connectKhataFromVerifiedQr(verifiedResult)
            _isLoading.value = false
            onComplete(res)
        }
    }

    fun removeKhataConnection(
        connectionId: String,
        onComplete: (Result<Unit>) -> Unit
    ) {
        _isLoading.value = true
        viewModelScope.launch {
            val res = repository.removeKhataConnection(connectionId)
            _isLoading.value = false
            if (res.isSuccess && _activeConnectedKhata.value?.connectionId == connectionId) {
                _activeConnectedKhata.value = null
                _customerTab.value = CustomerTab.HOME
            }
            onComplete(res)
        }
    }

    fun createPaymentRequest(
        shopId: String,
        customerId: String,
        amount: Double,
        dateStr: String = "Today"
    ): PaymentRequest {
        return repository.createPaymentRequest(shopId, customerId, amount, dateStr)
    }

    fun acceptPaymentRequest(requestId: String, decisionDate: String = "Today") {
        repository.acceptPaymentRequest(requestId, decisionDate)
    }

    fun rejectPaymentRequest(requestId: String, decisionDate: String = "Today") {
        repository.rejectPaymentRequest(requestId, decisionDate)
    }

    fun markNotificationRead(notifId: String) {
        repository.markNotificationRead(notifId)
    }

    fun markAllNotificationsRead(targetApp: UserRole = UserRole.SHOPKEEPER, customerId: String? = null) {
        repository.markAllNotificationsRead(targetApp, customerId)
    }

    class Factory(private val repository: KhataRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(KhataViewModel::class.java)) {
                return KhataViewModel(repository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
