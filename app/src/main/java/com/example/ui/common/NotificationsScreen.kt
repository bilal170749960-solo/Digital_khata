package com.example.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AppMode
import com.example.data.model.CustomerTab
import com.example.data.model.KhataNotification
import com.example.data.model.ShopkeeperTab
import com.example.ui.theme.KhataAmber
import com.example.ui.theme.KhataGreenPrimary
import com.example.ui.viewmodel.KhataViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    viewModel: KhataViewModel,
    modifier: Modifier = Modifier
) {
    val currentAppMode by viewModel.appMode.collectAsState()
    val selectedCustomerId by viewModel.selectedCustomerId.collectAsState()
    val allNotifications by viewModel.notifications.collectAsState()

    // Filter relevant notifications
    val notifications = remember(allNotifications, currentAppMode, selectedCustomerId) {
        if (currentAppMode == AppMode.SHOPKEEPER) {
            allNotifications.filter { it.targetApp == AppMode.SHOPKEEPER }
        } else {
            allNotifications.filter {
                it.targetApp == AppMode.CUSTOMER && (it.customerId == null || it.customerId == selectedCustomerId)
            }
        }
    }

    val unreadCount = notifications.count { !it.isRead }

    Scaffold(
        modifier = modifier.testTag("notifications_screen"),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Notifications",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (unreadCount > 0) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.primary,
                                shape = CircleShape
                            ) {
                                Text(
                                    text = "$unreadCount new",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                },
                actions = {
                    if (unreadCount > 0) {
                        TextButton(
                            onClick = {
                                viewModel.markAllNotificationsRead(
                                    targetApp = currentAppMode,
                                    customerId = if (currentAppMode == AppMode.CUSTOMER) selectedCustomerId else null
                                )
                            }
                        ) {
                            Text("Mark all read", fontSize = 12.sp)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        if (notifications.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Outlined.NotificationsNone,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(54.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No notifications yet",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "New khata entries and payment alerts will appear here.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(notifications) { notif ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (!notif.isRead)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            else MaterialTheme.colorScheme.surface
                        ),
                        shape = RoundedCornerShape(14.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = if (!notif.isRead) 2.dp else 1.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.markNotificationRead(notif.id)
                                // Navigate based on notification type and mode
                                if (currentAppMode == AppMode.CUSTOMER) {
                                    if (notif.type == "ITEM_ADDED") {
                                        viewModel.setCustomerTab(CustomerTab.KHATA)
                                    } else if (notif.type == "MONTH_REMINDER") {
                                        viewModel.setCustomerTab(CustomerTab.PAYMENTS)
                                    }
                                } else {
                                    if (notif.type == "PAYMENT_REQUEST") {
                                        viewModel.setShopkeeperTab(ShopkeeperTab.REQUESTS)
                                    }
                                }
                            }
                            .testTag("notif_item_${notif.id}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when (notif.type) {
                                            "ITEM_ADDED" -> MaterialTheme.colorScheme.primaryContainer
                                            "MONTH_REMINDER" -> KhataAmber.copy(alpha = 0.2f)
                                            "PAYMENT_REQUEST" -> MaterialTheme.colorScheme.secondaryContainer
                                            else -> MaterialTheme.colorScheme.surfaceVariant
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = when (notif.type) {
                                        "ITEM_ADDED" -> Icons.Default.AddShoppingCart
                                        "MONTH_REMINDER" -> Icons.Default.CalendarMonth
                                        "PAYMENT_REQUEST" -> Icons.Default.Payment
                                        else -> Icons.Default.Notifications
                                    },
                                    contentDescription = null,
                                    tint = when (notif.type) {
                                        "ITEM_ADDED" -> MaterialTheme.colorScheme.primary
                                        "MONTH_REMINDER" -> KhataAmber
                                        "PAYMENT_REQUEST" -> MaterialTheme.colorScheme.secondary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = notif.title,
                                        fontSize = 15.sp,
                                        fontWeight = if (!notif.isRead) FontWeight.Bold else FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = notif.date,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                Text(
                                    text = notif.message,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 18.sp
                                )

                                if (currentAppMode == AppMode.CUSTOMER && notif.type == "ITEM_ADDED") {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Tap to view khata statement →",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
