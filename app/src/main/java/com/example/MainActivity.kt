package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.example.data.model.*
import com.example.data.repository.KhataRepository
import com.example.ui.auth.AuthScreen
import com.example.ui.common.NotificationsScreen
import com.example.ui.common.SettingsScreen
import com.example.ui.customer.CustomerHomeScreen
import com.example.ui.customer.CustomerKhataScreen
import com.example.ui.customer.CustomerPaymentScreen
import com.example.ui.shopkeeper.ShopkeeperCustomersScreen
import com.example.ui.shopkeeper.ShopkeeperDashboardScreen
import com.example.ui.shopkeeper.ShopkeeperKhataScreen
import com.example.ui.shopkeeper.ShopkeeperRequestsScreen
import com.example.ui.theme.DigitalKhataTheme
import com.example.services.ads.AdMobManager
import com.example.ui.viewmodel.KhataViewModel

class MainActivity : ComponentActivity() {

    private val repository by lazy { KhataRepository(applicationContext) }
    private val viewModel: KhataViewModel by viewModels { KhataViewModel.Factory(repository) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Safely initialize AdMob in background
        AdMobManager.initialize(applicationContext)
        AdMobManager.preloadInterstitial(applicationContext)

        setContent {
            val isAuthChecking by viewModel.isAuthChecking.collectAsState()
            val currentUser by viewModel.currentUser.collectAsState()
            val themeMode by viewModel.themeMode.collectAsState()

            var isMinSplashDurationPassed by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                // Ensure splash remains visible smoothly for intro fade-in and scale animation (~750ms)
                delay(750)
                isMinSplashDurationPassed = true
            }

            val showSplash = isAuthChecking || !isMinSplashDurationPassed

            DigitalKhataTheme(themeMode = themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Crossfade(
                        targetState = showSplash,
                        animationSpec = tween(durationMillis = 350),
                        label = "splash_transition"
                    ) { displayingSplash ->
                        if (displayingSplash) {
                            DigitalKhataSplashScreen()
                        } else if (currentUser == null) {
                            AuthScreen(viewModel = viewModel)
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .windowInsetsPadding(WindowInsets.statusBars)
                            ) {
                                when (currentUser?.role) {
                                    UserRole.SHOPKEEPER -> {
                                        ShopkeeperAppContainer(viewModel = viewModel)
                                    }
                                    UserRole.CUSTOMER -> {
                                        CustomerAppContainer(
                                            viewModel = viewModel
                                        )
                                    }
                                    null -> {
                                        AuthScreen(viewModel = viewModel)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DigitalKhataSplashScreen() {
    val alphaAnim = remember { Animatable(0f) }
    val scaleAnim = remember { Animatable(0.95f) }

    LaunchedEffect(Unit) {
        launch {
            alphaAnim.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing)
            )
        }
        launch {
            scaleAnim.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing)
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF011A0C))
            .testTag("digital_khata_splash"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.digital_khata_logo),
                contentDescription = "Digital Khata Logo",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .fillMaxWidth(0.68f)
                    .aspectRatio(1f)
                    .scale(scaleAnim.value)
                    .alpha(alphaAnim.value)
                    .testTag("splash_logo")
            )
        }
    }
}

@Composable
fun ShopkeeperAppContainer(viewModel: KhataViewModel) {
    val currentTab by viewModel.shopkeeperTab.collectAsState()
    val activeKhataCustId by viewModel.shopkeeperActiveKhataCustomerId.collectAsState()
    val paymentRequests by viewModel.paymentRequests.collectAsState()
    val notifications by viewModel.notifications.collectAsState()

    val pendingRequestsCount = remember(paymentRequests) {
        paymentRequests.count { it.status == PaymentStatus.PENDING }
    }
    val unreadShopkeeperNotifsCount = remember(notifications) {
        notifications.count { it.targetApp == AppMode.SHOPKEEPER && !it.isRead }
    }

    var showAddCustomerInitially by remember { mutableStateOf(false) }

    // Handle Back Press when viewing a customer khata
    BackHandler(enabled = currentTab == ShopkeeperTab.CUSTOMERS && activeKhataCustId != null) {
        viewModel.closeShopkeeperCustomerKhata()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .testTag("shopkeeper_bottom_nav"),
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                // Home Tab
                NavigationBarItem(
                    selected = currentTab == ShopkeeperTab.HOME,
                    onClick = {
                        viewModel.setShopkeeperTab(ShopkeeperTab.HOME)
                    },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == ShopkeeperTab.HOME)
                                Icons.Default.Home
                            else Icons.Outlined.Home,
                            contentDescription = "Home"
                        )
                    },
                    label = { Text("Home", fontSize = 11.sp, fontWeight = FontWeight.Medium) },
                    modifier = Modifier.testTag("shopkeeper_nav_home")
                )

                // Customers Tab
                NavigationBarItem(
                    selected = currentTab == ShopkeeperTab.CUSTOMERS,
                    onClick = {
                        viewModel.setShopkeeperTab(ShopkeeperTab.CUSTOMERS)
                    },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == ShopkeeperTab.CUSTOMERS)
                                Icons.Default.People
                            else Icons.Outlined.People,
                            contentDescription = "Customers"
                        )
                    },
                    label = { Text("Customers", fontSize = 11.sp, fontWeight = FontWeight.Medium) },
                    modifier = Modifier.testTag("shopkeeper_nav_customers")
                )

                // Requests Tab
                NavigationBarItem(
                    selected = currentTab == ShopkeeperTab.REQUESTS,
                    onClick = {
                        viewModel.setShopkeeperTab(ShopkeeperTab.REQUESTS)
                    },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (pendingRequestsCount > 0) {
                                    Badge { Text("$pendingRequestsCount") }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (currentTab == ShopkeeperTab.REQUESTS)
                                    Icons.Default.Payment
                                else Icons.Outlined.Payment,
                                contentDescription = "Requests"
                            )
                        }
                    },
                    label = { Text("Requests", fontSize = 11.sp, fontWeight = FontWeight.Medium) },
                    modifier = Modifier.testTag("shopkeeper_nav_requests")
                )

                // Notifications Tab
                NavigationBarItem(
                    selected = currentTab == ShopkeeperTab.NOTIFICATIONS,
                    onClick = {
                        viewModel.setShopkeeperTab(ShopkeeperTab.NOTIFICATIONS)
                    },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (unreadShopkeeperNotifsCount > 0) {
                                    Badge { Text("$unreadShopkeeperNotifsCount") }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (currentTab == ShopkeeperTab.NOTIFICATIONS)
                                    Icons.Default.Notifications
                                else Icons.Outlined.Notifications,
                                contentDescription = "Notifications"
                            )
                        }
                    },
                    label = { Text("Alerts", fontSize = 11.sp, fontWeight = FontWeight.Medium) },
                    modifier = Modifier.testTag("shopkeeper_nav_notifications")
                )

                // Settings Tab
                NavigationBarItem(
                    selected = currentTab == ShopkeeperTab.SETTINGS,
                    onClick = {
                        viewModel.setShopkeeperTab(ShopkeeperTab.SETTINGS)
                    },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == ShopkeeperTab.SETTINGS)
                                Icons.Default.Settings
                            else Icons.Outlined.Settings,
                            contentDescription = "Settings"
                        )
                    },
                    label = { Text("Settings", fontSize = 11.sp, fontWeight = FontWeight.Medium) },
                    modifier = Modifier.testTag("shopkeeper_nav_settings")
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentTab) {
                ShopkeeperTab.HOME -> {
                    ShopkeeperDashboardScreen(
                        viewModel = viewModel,
                        onOpenCustomerKhata = { custId ->
                            viewModel.openShopkeeperCustomerKhata(custId)
                        },
                        onOpenAddCustomer = {
                            viewModel.setShopkeeperTab(ShopkeeperTab.CUSTOMERS)
                            viewModel.closeShopkeeperCustomerKhata()
                            showAddCustomerInitially = true
                        }
                    )
                }
                ShopkeeperTab.CUSTOMERS -> {
                    if (activeKhataCustId != null) {
                        ShopkeeperKhataScreen(
                            customerId = activeKhataCustId!!,
                            viewModel = viewModel,
                            onBack = { viewModel.closeShopkeeperCustomerKhata() }
                        )
                    } else {
                        ShopkeeperCustomersScreen(
                            viewModel = viewModel,
                            onOpenCustomerKhata = { custId ->
                                viewModel.openShopkeeperCustomerKhata(custId)
                            },
                            showAddDialogInitially = showAddCustomerInitially,
                            onDismissAddDialog = { showAddCustomerInitially = false }
                        )
                    }
                }
                ShopkeeperTab.REQUESTS -> {
                    ShopkeeperRequestsScreen(viewModel = viewModel)
                }
                ShopkeeperTab.NOTIFICATIONS -> {
                    NotificationsScreen(viewModel = viewModel)
                }
                ShopkeeperTab.SETTINGS -> {
                    SettingsScreen(viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
fun CustomerAppContainer(viewModel: KhataViewModel) {
    val currentTab by viewModel.customerTab.collectAsState()
    val notifications by viewModel.notifications.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()

    val unreadCustomerNotifsCount = remember(notifications, currentUser) {
        notifications.count {
            it.targetApp == AppMode.CUSTOMER &&
            (it.recipientUid == currentUser?.uid || it.recipientUid.isEmpty()) &&
            !it.isRead
        }
    }

    // Handle Back Press to return to Home tab if on a subtab
    BackHandler(enabled = currentTab != CustomerTab.HOME) {
        viewModel.setCustomerTab(CustomerTab.HOME)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .testTag("customer_bottom_nav"),
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                // Home Tab
                NavigationBarItem(
                    selected = currentTab == CustomerTab.HOME,
                    onClick = { viewModel.setCustomerTab(CustomerTab.HOME) },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == CustomerTab.HOME)
                                Icons.Default.Home
                            else Icons.Outlined.Home,
                            contentDescription = "Home"
                        )
                    },
                    label = { Text("Home", fontSize = 11.sp, fontWeight = FontWeight.Medium) },
                    modifier = Modifier.testTag("customer_nav_home")
                )

                // Khata Tab
                NavigationBarItem(
                    selected = currentTab == CustomerTab.KHATA,
                    onClick = { viewModel.setCustomerTab(CustomerTab.KHATA) },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == CustomerTab.KHATA)
                                Icons.Default.ReceiptLong
                            else Icons.Outlined.ReceiptLong,
                            contentDescription = "Khata"
                        )
                    },
                    label = { Text("Khata", fontSize = 11.sp, fontWeight = FontWeight.Medium) },
                    modifier = Modifier.testTag("customer_nav_khata")
                )

                // Payments Tab
                NavigationBarItem(
                    selected = currentTab == CustomerTab.PAYMENTS,
                    onClick = { viewModel.setCustomerTab(CustomerTab.PAYMENTS) },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == CustomerTab.PAYMENTS)
                                Icons.Default.Payment
                            else Icons.Outlined.Payment,
                            contentDescription = "Payments"
                        )
                    },
                    label = { Text("Payments", fontSize = 11.sp, fontWeight = FontWeight.Medium) },
                    modifier = Modifier.testTag("customer_nav_payments")
                )

                // Notifications Tab
                NavigationBarItem(
                    selected = currentTab == CustomerTab.NOTIFICATIONS,
                    onClick = { viewModel.setCustomerTab(CustomerTab.NOTIFICATIONS) },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (unreadCustomerNotifsCount > 0) {
                                     Badge { Text("$unreadCustomerNotifsCount") }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (currentTab == CustomerTab.NOTIFICATIONS)
                                    Icons.Default.Notifications
                                else Icons.Outlined.Notifications,
                                contentDescription = "Notifications"
                            )
                        }
                    },
                    label = { Text("Alerts", fontSize = 11.sp, fontWeight = FontWeight.Medium) },
                    modifier = Modifier.testTag("customer_nav_notifications")
                )

                // Settings Tab
                NavigationBarItem(
                    selected = currentTab == CustomerTab.SETTINGS,
                    onClick = { viewModel.setCustomerTab(CustomerTab.SETTINGS) },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == CustomerTab.SETTINGS)
                                Icons.Default.Settings
                            else Icons.Outlined.Settings,
                            contentDescription = "Settings"
                        )
                    },
                    label = { Text("Settings", fontSize = 11.sp, fontWeight = FontWeight.Medium) },
                    modifier = Modifier.testTag("customer_nav_settings")
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentTab) {
                CustomerTab.HOME -> {
                    CustomerHomeScreen(
                        viewModel = viewModel,
                        onNavigateToTab = { tab -> viewModel.setCustomerTab(tab) }
                    )
                }
                CustomerTab.KHATA -> {
                    CustomerKhataScreen(
                        viewModel = viewModel,
                        onNavigateToPayments = { viewModel.setCustomerTab(CustomerTab.PAYMENTS) },
                        onBack = { viewModel.setCustomerTab(CustomerTab.HOME) }
                    )
                }
                CustomerTab.PAYMENTS -> {
                    CustomerPaymentScreen(
                        viewModel = viewModel
                    )
                }
                CustomerTab.NOTIFICATIONS -> {
                    NotificationsScreen(viewModel = viewModel)
                }
                CustomerTab.SETTINGS -> {
                    SettingsScreen(viewModel = viewModel)
                }
            }
        }
    }
}
