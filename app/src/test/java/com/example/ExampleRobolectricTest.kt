package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.MonthlyKhataRecord
import com.example.data.model.PaymentStatus
import com.example.data.model.QrLinkVerificationResult
import com.example.data.model.UserRole
import com.example.data.repository.KhataRepository
import com.example.ui.qr.QRService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    private lateinit var context: Context
    private lateinit var repository: KhataRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("digital_khata_storage_v2", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        repository = KhataRepository(context)
        repository.logout()
    }

    @Test
    fun verifyAppName() {
        val appName = context.getString(R.string.app_name)
        assertEquals("Digital Khata", appName)
    }

    @Test
    fun verifyDatabaseStartsEmptyNoRandomUsers() {
        assertTrue("Customers must start empty", repository.customers.value.isEmpty())
        assertTrue("Items must start empty", repository.items.value.isEmpty())
        assertTrue("Payment requests must start empty", repository.paymentRequests.value.isEmpty())
        assertNull("Ali should not exist automatically", repository.customers.value.find { it.name.contains("Ali", ignoreCase = true) })
        assertNull("Ahmed should not exist automatically", repository.customers.value.find { it.name.contains("Ahmed", ignoreCase = true) })
    }

    @Test
    fun verifyShopkeeperRegistrationAndPersistenceWithoutAutoLogout() = runBlocking {
        val result = repository.registerShopkeeper(
            name = "Muhammad Aslam",
            email = "aslam.shop@example.com",
            password = "password123",
            shopName = "Bwp Chowk Store",
            phone = "03001234567"
        )
        assertTrue(result.isSuccess)
        val user = repository.currentUser.value
        assertNotNull(user)
        assertEquals(UserRole.SHOPKEEPER, user!!.role)
        assertEquals("Bwp Chowk Store", user.shopName)

        // Simulate closing and reopening the app without logging out
        val newRepoInstance = KhataRepository(context)
        val restoredUser = newRepoInstance.currentUser.value
        assertNotNull("User session must survive app restart", restoredUser)
        assertEquals(user.uid, restoredUser!!.uid)
        assertEquals(UserRole.SHOPKEEPER, restoredUser.role)
        assertEquals("Bwp Chowk Store", restoredUser.shopName)
    }

    @Test
    fun verifyCustomerRegistrationAndRoleFromProfile() = runBlocking {
        val result = repository.registerCustomer(
            name = "Zahid Hussain",
            email = "zahid@example.com",
            password = "password123",
            phone = "03121234567"
        )
        assertTrue(result.isSuccess)
        val user = repository.currentUser.value
        assertNotNull(user)
        assertEquals(UserRole.CUSTOMER, user!!.role)
        assertEquals("Zahid Hussain", user.name)
    }

    @Test
    fun verifyEditShopNameUpdatesProfileImmediately() = runBlocking {
        repository.registerShopkeeper(
            name = "Saeed Akhtar",
            email = "saeed@example.com",
            password = "password123",
            shopName = "Old Chowk Shop",
            phone = "03011112222"
        )
        val updateRes = repository.updateShopName("Bwp Chowk")
        assertTrue(updateRes.isSuccess)
        assertEquals("Bwp Chowk", repository.currentUser.value?.shopName)

        // Session persistence has new shop name
        val reloadedRepo = KhataRepository(context)
        assertEquals("Bwp Chowk", reloadedRepo.currentUser.value?.shopName)
    }

    @Test
    fun verifyExplicitLogoutRequiresLogin() = runBlocking {
        repository.registerShopkeeper(
            name = "Kashif",
            email = "kashif@example.com",
            password = "password123",
            shopName = "Kashif Mart",
            phone = ""
        )
        assertNotNull(repository.currentUser.value)

        // Explicit logout
        repository.logout()
        assertNull(repository.currentUser.value)

        // After restart, session is still null
        val reloadedRepo = KhataRepository(context)
        assertNull("After explicit logout, user must not be logged in", reloadedRepo.currentUser.value)
    }

    @Test
    fun verifyAccountDeletionPermanentlyRemovesUser() = runBlocking {
        repository.registerShopkeeper(
            name = "Temp User",
            email = "temp@example.com",
            password = "password123",
            shopName = "Temp Store",
            phone = ""
        )
        assertNotNull(repository.currentUser.value)

        val deleteRes = repository.deleteAccount()
        assertTrue(deleteRes.isSuccess)
        assertNull(repository.currentUser.value)

        val reloadedRepo = KhataRepository(context)
        assertNull(reloadedRepo.currentUser.value)
    }

    @Test
    fun verifyShopkeeperCreatesCustomerAndAddsItems() {
        val customer = repository.addCustomer(
            name = "Tariq Mahmood",
            khataNumber = "201",
            phone = "03001234567",
            address = "Shop #4, Main Market"
        )
        assertNotNull(customer)
        assertEquals("Tariq Mahmood", customer.name)
        assertEquals("201", customer.khataNumber)
        assertEquals(1, repository.customers.value.size)

        repository.addItem(
            customerId = customer.id,
            name = "Rice 5kg",
            price = 2000.0,
            dateStr = "01 Oct 2026"
        )
        repository.addItem(
            customerId = customer.id,
            name = "Sugar 2kg",
            price = 500.0,
            dateStr = "02 Oct 2026"
        )

        val summary = repository.calculateBalanceSummary(customer.id)
        assertEquals(2500.0, summary.currentMonthTotal, 0.01)
        assertEquals(0.0, summary.previousBalance, 0.01)
        assertEquals(2500.0, summary.totalPayable, 0.01)
    }

    @Test
    fun verifyMonthlyKhataFormulaWithPreviousBalanceAndSettlement() {
        val customer = repository.addCustomer(
            name = "Kashif Raza",
            khataNumber = "305",
            phone = "03219876543",
            address = "Lahore"
        )

        repository.addItem(customer.id, "Rice", 2000.0, "01 Oct 2026")
        repository.addItem(customer.id, "Sugar", 500.0, "02 Oct 2026")

        val sepRecord = MonthlyKhataRecord(
            id = "sep_rec",
            customerId = customer.id,
            shopkeeperUid = customer.shopkeeperUid,
            monthKey = "2026-09",
            monthLabel = "September 2026",
            totalPurchases = 10000.0,
            paidAmount = 6000.0,
            isCurrentActiveMonth = false
        )
        val currentRecords = repository.monthlyRecords.value.toMutableList()
        currentRecords.add(sepRecord)

        val summaryBefore = repository.calculateBalanceSummary(customer.id)
        assertEquals(2500.0, summaryBefore.currentMonthTotal, 0.01)

        val req = repository.createPaymentRequest(
            shopId = customer.shopkeeperUid,
            customerId = customer.id,
            amount = 4000.0,
            dateStr = "05 Oct 2026"
        )
        assertEquals(PaymentStatus.PENDING, req.status)
        assertEquals(4000.0, req.requestAmount, 0.01)

        repository.acceptPaymentRequest(req.id, "05 Oct 2026")

        val updatedReq = repository.paymentRequests.value.find { it.id == req.id }!!
        assertEquals(PaymentStatus.ACCEPTED, updatedReq.status)

        val summaryAfter = repository.calculateBalanceSummary(customer.id)
        assertEquals(0.0, summaryAfter.previousBalance, 0.01)
        assertEquals(2500.0, summaryAfter.totalPayable, 0.01)
    }

    @Test
    fun verifyPaymentRejectionMaintainsBalance() {
        val customer = repository.addCustomer(
            name = "Hamza Khan",
            khataNumber = "410",
            phone = "03335551212",
            address = "Karachi"
        )

        repository.addItem(customer.id, "Cooking Oil", 1500.0, "01 Oct 2026")

        val req = repository.createPaymentRequest(
            shopId = customer.shopkeeperUid,
            customerId = customer.id,
            amount = 500.0,
            dateStr = "03 Oct 2026"
        )

        repository.rejectPaymentRequest(req.id, "03 Oct 2026")

        val rejected = repository.paymentRequests.value.find { it.id == req.id }!!
        assertEquals(PaymentStatus.REJECTED, rejected.status)

        val summary = repository.calculateBalanceSummary(customer.id)
        assertEquals(1500.0, summary.currentMonthTotal, 0.01)
    }

    @Test
    fun verifyQrPayloadGenerationAndParsing() {
        val payload = QRService.createPayload(
            shopId = "shop_12345",
            customerId = "cust_102",
            token = "sec_token_98765"
        )
        assertTrue(payload.startsWith("digitalkhata://link"))
        assertFalse(payload.contains("financial"))
        assertFalse(payload.contains("password"))

        val parsed = QRService.parsePayload(payload)
        assertNotNull(parsed)
        assertEquals("shop_12345", parsed!!.shopId)
        assertEquals("cust_102", parsed.customerId)
        assertEquals("sec_token_98765", parsed.token)
    }

    @Test
    fun verifySingleCustomerCanConnectMultipleShops() = runBlocking {
        // Customer registers
        val custReg = repository.registerCustomer(
            name = "Ali",
            email = "ali@example.com",
            password = "password123",
            phone = "03009998877"
        )
        assertTrue(custReg.isSuccess)

        // Connect 3 different shops to the SAME customer account
        val shop1Result = QrLinkVerificationResult(
            token = "tok_1",
            shopId = "shop_ahmed",
            shopName = "Ahmed General Store",
            shopOwnerName = "Ahmed",
            customerId = "c_ahmed_102",
            customerName = "Ali",
            khataNumber = "102"
        )
        val conn1 = repository.connectKhataFromVerifiedQr(shop1Result)
        assertTrue(conn1.isSuccess)

        val shop2Result = QrLinkVerificationResult(
            token = "tok_2",
            shopId = "shop_bilal",
            shopName = "Bilal Chicken Shop",
            shopOwnerName = "Bilal",
            customerId = "c_bilal_45",
            customerName = "Ali",
            khataNumber = "45"
        )
        val conn2 = repository.connectKhataFromVerifiedQr(shop2Result)
        assertTrue(conn2.isSuccess)

        val shop3Result = QrLinkVerificationResult(
            token = "tok_3",
            shopId = "shop_usman",
            shopName = "Usman Sabzi Store",
            shopOwnerName = "Usman",
            customerId = "c_usman_78",
            customerName = "Ali",
            khataNumber = "78"
        )
        val conn3 = repository.connectKhataFromVerifiedQr(shop3Result)
        assertTrue(conn3.isSuccess)

        // All 3 khatas must exist simultaneously inside the same customer account
        assertEquals(3, repository.connectedKhatas.value.size)
        val connectedShops = repository.connectedKhatas.value.map { it.shopName }
        assertTrue(connectedShops.contains("Ahmed General Store"))
        assertTrue(connectedShops.contains("Bilal Chicken Shop"))
        assertTrue(connectedShops.contains("Usman Sabzi Store"))
    }

    @Test
    fun verifyRemovingKhataDoesNotDeleteShopkeeperData() = runBlocking {
        // Setup customer with a connected shop
        repository.registerCustomer(
            name = "Ali",
            email = "ali.test@example.com",
            password = "password123",
            phone = "03001234567"
        )

        val linkResult = QrLinkVerificationResult(
            token = "tok_test",
            shopId = "shop_999",
            shopName = "Test Shop",
            shopOwnerName = "Shopkeeper",
            customerId = "cust_999",
            customerName = "Ali",
            khataNumber = "88"
        )
        val conn = repository.connectKhataFromVerifiedQr(linkResult).getOrThrow()
        assertEquals(1, repository.connectedKhatas.value.size)

        // Remove the connection
        val removeRes = repository.removeKhataConnection(conn.connectionId)
        assertTrue(removeRes.isSuccess)
        assertEquals(0, repository.connectedKhatas.value.size)
    }
}
