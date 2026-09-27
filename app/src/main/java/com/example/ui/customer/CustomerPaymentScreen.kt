package com.example.ui.customer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ConnectedKhata
import com.example.data.model.PaymentRequest
import com.example.data.model.PaymentStatus
import com.example.ui.theme.KhataAmber
import com.example.ui.theme.KhataGreenPrimary
import com.example.ui.theme.KhataRed
import com.example.ui.viewmodel.KhataViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerPaymentScreen(
    viewModel: KhataViewModel,
    modifier: Modifier = Modifier
) {
    val connectedKhatas by viewModel.connectedKhatas.collectAsState()
    val activeConnectedKhata by viewModel.activeConnectedKhata.collectAsState()
    val requests by viewModel.paymentRequests.collectAsState()

    var selectedConnectionId by remember(activeConnectedKhata, connectedKhatas) {
        mutableStateOf(
            activeConnectedKhata?.connectionId ?: connectedKhatas.firstOrNull()?.connectionId ?: ""
        )
    }

    val currentConn = remember(selectedConnectionId, connectedKhatas) {
        connectedKhatas.find { it.connectionId == selectedConnectionId } ?: connectedKhatas.firstOrNull()
    }

    var isCustomMode by remember { mutableStateOf(false) }
    var customAmountStr by remember { mutableStateOf("") }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            snackbarMessage = null
        }
    }

    if (connectedKhatas.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Payment,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "No Connected Shops",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "You don't have any shop khatas connected yet. Go to Home and tap '+ Add Khata' to link your khata.",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
        return
    }

    if (currentConn == null) return

    val summary = viewModel.getCustomerBalanceSummary(currentConn.customerId, currentConn.shopId)
    val shopRequests = requests.filter {
        it.shopkeeperUid == currentConn.shopId && it.customerId == currentConn.customerId
    }
    val pendingRequest = shopRequests.find { it.status == PaymentStatus.PENDING }

    Scaffold(
        modifier = modifier.testTag("customer_payment_screen"),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Pay Previous Balance",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${currentConn.shopName} • Khata #${currentConn.khataNumber}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Select Shop Chips (if multiple shops connected)
            if (connectedKhatas.size > 1) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "SELECT SHOP TO PAY",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            letterSpacing = 0.5.sp
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(connectedKhatas, key = { it.connectionId }) { conn ->
                                val isSelected = conn.connectionId == currentConn.connectionId
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        selectedConnectionId = conn.connectionId
                                        errorMessage = null
                                    },
                                    label = {
                                        Text(
                                            text = "${conn.shopName} (#${conn.khataNumber})",
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    leadingIcon = {
                                        if (isSelected) {
                                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Outstanding Previous Balance Card
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (summary.previousBalance > 0.0)
                            KhataAmber.copy(alpha = 0.15f)
                        else
                            MaterialTheme.colorScheme.primaryContainer
                    ),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "PREVIOUS OUTSTANDING",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (summary.previousBalance > 0.0) KhataAmber else MaterialTheme.colorScheme.onPrimaryContainer,
                                letterSpacing = 0.5.sp
                            )
                            Surface(
                                color = if (summary.previousBalance > 0.0) KhataAmber else MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = summary.previousMonthLabel ?: "September 2026",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (summary.previousBalance > 0.0) Color.Black else MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "Rs ${summary.previousBalance.toLong()}",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (summary.previousBalance > 0.0) KhataAmber else MaterialTheme.colorScheme.primary
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = if (summary.previousBalance > 0.0)
                                "Original Bill: Rs ${summary.previousOriginalBill.toLong()} • Paid so far: Rs ${summary.previousPaidAmount.toLong()}"
                            else
                                "All previous dues for ${currentConn.shopName} have been cleared!",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Notice if Pending Request already exists
            if (pendingRequest != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.HourglassEmpty,
                                contentDescription = null,
                                tint = KhataAmber,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Request Pending Shopkeeper Approval",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "You submitted a payment of Rs ${pendingRequest.requestAmount.toLong()} on ${pendingRequest.requestDate}. Please wait for ${currentConn.shopName} to accept or decline.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Payment Form (Enabled when balance > 0 and no pending request)
            if (summary.previousBalance > 0.0 && pendingRequest == null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text(
                                text = "Select Payment Amount",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            // Option 1: Full Amount
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (!isCustomMode) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                    )
                                    .clickable {
                                        isCustomMode = false
                                        errorMessage = null
                                    }
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = !isCustomMode,
                                    onClick = {
                                        isCustomMode = false
                                        errorMessage = null
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "Pay Full Balance",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = "Rs ${summary.previousBalance.toLong()}",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            // Option 2: Custom Amount
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (isCustomMode) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                    )
                                    .clickable {
                                        isCustomMode = true
                                        errorMessage = null
                                    }
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isCustomMode,
                                    onClick = {
                                        isCustomMode = true
                                        errorMessage = null
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Pay Custom / Partial Amount",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }

                            if (isCustomMode) {
                                OutlinedTextField(
                                    value = customAmountStr,
                                    onValueChange = {
                                        customAmountStr = it.filter { char -> char.isDigit() || char == '.' }
                                        errorMessage = null
                                    },
                                    label = { Text("Enter Amount (Rs)") },
                                    placeholder = { Text("e.g. 2000") },
                                    leadingIcon = {
                                        Text(
                                            "Rs ",
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(start = 12.dp)
                                        )
                                    },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("custom_payment_amount_input"),
                                    shape = RoundedCornerShape(12.dp)
                                )
                            }

                            errorMessage?.let {
                                Text(
                                    text = it,
                                    color = MaterialTheme.colorScheme.error,
                                    fontSize = 12.sp
                                )
                            }

                            Button(
                                onClick = {
                                    val amountToPay = if (!isCustomMode) {
                                        summary.previousBalance
                                    } else {
                                        customAmountStr.toDoubleOrNull() ?: 0.0
                                    }

                                    if (amountToPay <= 0) {
                                        errorMessage = "Please enter a valid amount greater than Rs 0."
                                    } else if (amountToPay > summary.previousBalance) {
                                        errorMessage = "Amount cannot exceed outstanding balance of Rs ${summary.previousBalance.toLong()}."
                                    } else {
                                        errorMessage = null
                                        viewModel.createPaymentRequest(
                                            shopId = currentConn.shopId,
                                            customerId = currentConn.customerId,
                                            amount = amountToPay,
                                            dateStr = "Today"
                                        )
                                        snackbarMessage = "Payment request of Rs ${amountToPay.toLong()} sent to ${currentConn.shopName}."
                                        customAmountStr = ""
                                        isCustomMode = false
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .testTag("submit_payment_request_button"),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Send Payment Request", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Payment Requests History for this shop
            if (shopRequests.isNotEmpty()) {
                item {
                    Text(
                        text = "PAYMENT REQUEST HISTORY (${currentConn.shopName})",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 0.5.sp
                    )
                }

                items(shopRequests, key = { it.id }) { req ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(14.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Rs ${req.requestAmount.toLong()}",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${req.targetMonthLabel} • ${req.requestDate}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            val (statusColor, statusBg) = when (req.status) {
                                PaymentStatus.ACCEPTED -> Color(0xFF2E7D32) to Color(0xFFE8F5E9)
                                PaymentStatus.REJECTED -> KhataRed to KhataRed.copy(alpha = 0.15f)
                                PaymentStatus.PENDING -> Color(0xFFE65100) to KhataAmber.copy(alpha = 0.2f)
                            }

                            Surface(
                                color = statusBg,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = req.status.name,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = statusColor,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
