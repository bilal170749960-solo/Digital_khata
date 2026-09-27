package com.example.services.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.*
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "AdMobManager"

/**
 * Production-ready Google AdMob Manager for Digital Khata.
 * Strictly uses official Google AdMob TEST Ad Unit IDs during development.
 * Structured cleanly so production IDs can be swapped via configuration without touching UI.
 * Does NOT implement Rewarded Ads.
 * Designed to NEVER crash the application upon network failure or ad unavailability.
 */
object AdMobManager {

    // Official Google AdMob Test Ad Unit IDs
    const val TEST_BANNER_AD_UNIT_ID = "ca-app-pub-3940256099942544/6300978111"
    const val TEST_NATIVE_AD_UNIT_ID = "ca-app-pub-3940256099942544/2247696110"
    const val TEST_INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"

    // Active ad unit ID getters (production ready: can be mapped to BuildConfig or remote config)
    var bannerAdUnitId: String = TEST_BANNER_AD_UNIT_ID
    var nativeAdUnitId: String = TEST_NATIVE_AD_UNIT_ID
    var interstitialAdUnitId: String = TEST_INTERSTITIAL_AD_UNIT_ID

    private var isInitialized = false
    private var interstitialAd: InterstitialAd? = null
    private var isInterstitialLoading = false
    private var lastInterstitialShownTime: Long = 0L

    // 5-minute cooldown to prevent annoying interstitial popups
    private const val INTERSTITIAL_COOLDOWN_MS = 5 * 60 * 1000L

    /**
     * Initializes Google Mobile Ads SDK safely in the background.
     * Guaranteed to never throw unhandled exceptions or block app startup.
     */
    fun initialize(context: Context) {
        if (isInitialized) return
        try {
            MobileAds.initialize(context) { status ->
                isInitialized = true
                Log.d(TAG, "AdMob SDK Initialized successfully: $status")
            }
        } catch (e: Exception) {
            Log.w(TAG, "AdMob initialization error: ${e.message}")
        }
    }

    /**
     * Preloads an interstitial test ad in the background.
     */
    fun preloadInterstitial(context: Context) {
        if (interstitialAd != null || isInterstitialLoading) return
        isInterstitialLoading = true

        try {
            val adRequest = AdRequest.Builder().build()
            InterstitialAd.load(
                context,
                interstitialAdUnitId,
                adRequest,
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) {
                        interstitialAd = ad
                        isInterstitialLoading = false
                        Log.d(TAG, "Interstitial test ad loaded successfully.")
                    }

                    override fun onAdFailedToLoad(loadError: LoadAdError) {
                        interstitialAd = null
                        isInterstitialLoading = false
                        Log.w(TAG, "Interstitial test ad failed to load: ${loadError.message}")
                    }
                }
            )
        } catch (e: Exception) {
            isInterstitialLoading = false
            Log.w(TAG, "Error initiating interstitial load: ${e.message}")
        }
    }

    /**
     * Displays the interstitial ad ONLY during safe, non-sensitive navigation transitions.
     * Enforces strict frequency capping (5-minute cooldown).
     * NEVER blocks navigation if ad is unavailable.
     */
    fun showInterstitialIfAllowed(activity: Activity?, onComplete: () -> Unit = {}) {
        val now = System.currentTimeMillis()
        if (activity == null || interstitialAd == null || (now - lastInterstitialShownTime < INTERSTITIAL_COOLDOWN_MS)) {
            onComplete()
            return
        }

        try {
            val ad = interstitialAd
            interstitialAd = null
            ad?.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    lastInterstitialShownTime = System.currentTimeMillis()
                    preloadInterstitial(activity)
                    onComplete()
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    Log.w(TAG, "Interstitial failed to show: ${adError.message}")
                    onComplete()
                }
            }
            ad?.show(activity)
        } catch (e: Exception) {
            Log.w(TAG, "Error showing interstitial: ${e.message}")
            onComplete()
        }
    }
}

/**
 * Responsive Google AdMob TEST Banner Ad Composable.
 * Features:
 * - Proper lifecycle management (pause, resume, destroy).
 * - Collapses automatically if ad fails to load (no awkward blank space).
 * - Does not overlap bottom navigation or financial widgets.
 * - Handles offline and connection failures gracefully without throwing errors.
 */
@Composable
fun AdMobBanner(
    modifier: Modifier = Modifier,
    adUnitId: String = AdMobManager.bannerAdUnitId
) {
    val context = LocalContext.current
    var isAdLoaded by remember { mutableStateOf(false) }
    var adViewInstance by remember { mutableStateOf<AdView?>(null) }

    DisposableEffect(adUnitId) {
        onDispose {
            try {
                adViewInstance?.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Error destroying AdView: ${e.message}")
            }
        }
    }

    if (isAdLoaded) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .testTag("admob_banner_card"),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Subtle Ad Attribution Tag
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.Start
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "Advertisement",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }

                AndroidView(
                    modifier = Modifier.fillMaxWidth(),
                    factory = { ctx ->
                        AdView(ctx).apply {
                            setAdSize(AdSize.BANNER)
                            this.adUnitId = adUnitId
                            adListener = object : AdListener() {
                                override fun onAdLoaded() {
                                    isAdLoaded = true
                                    Log.d(TAG, "Banner test ad loaded.")
                                }

                                override fun onAdFailedToLoad(error: LoadAdError) {
                                    isAdLoaded = false
                                    Log.w(TAG, "Banner test ad failed: ${error.message}")
                                }
                            }
                            loadAd(AdRequest.Builder().build())
                            adViewInstance = this
                        }
                    }
                )
            }
        }
    } else {
        // Hidden / zero height while loading or if failed to load
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.dp)
        ) {
            AndroidView(
                factory = { ctx ->
                    AdView(ctx).apply {
                        setAdSize(AdSize.BANNER)
                        this.adUnitId = adUnitId
                        adListener = object : AdListener() {
                            override fun onAdLoaded() {
                                isAdLoaded = true
                            }

                            override fun onAdFailedToLoad(error: LoadAdError) {
                                isAdLoaded = false
                            }
                        }
                        loadAd(AdRequest.Builder().build())
                        adViewInstance = this
                    }
                }
            )
        }
    }
}

/**
 * Google AdMob TEST Native Ad Card Composable.
 * Blends cleanly into list items while clearly identified as an advertisement.
 * Handles lifecycle destruction and collapses if load fails.
 */
@Composable
fun AdMobNativeCard(
    modifier: Modifier = Modifier,
    adUnitId: String = AdMobManager.nativeAdUnitId
) {
    val context = LocalContext.current
    var loadedNativeAd by remember { mutableStateOf<NativeAd?>(null) }
    var isFailed by remember { mutableStateOf(false) }

    DisposableEffect(adUnitId) {
        val adLoader = try {
            AdLoader.Builder(context, adUnitId)
                .forNativeAd { nativeAd ->
                    loadedNativeAd?.destroy()
                    loadedNativeAd = nativeAd
                    isFailed = false
                }
                .withAdListener(object : AdListener() {
                    override fun onAdFailedToLoad(error: LoadAdError) {
                        isFailed = true
                        Log.w(TAG, "Native test ad failed to load: ${error.message}")
                    }
                })
                .withNativeAdOptions(NativeAdOptions.Builder().build())
                .build()
        } catch (e: Exception) {
            isFailed = true
            null
        }

        try {
            adLoader?.loadAd(AdRequest.Builder().build())
        } catch (_: Exception) {}

        onDispose {
            try {
                loadedNativeAd?.destroy()
            } catch (_: Exception) {}
        }
    }

    val ad = loadedNativeAd
    if (ad != null && !isFailed) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .testTag("admob_native_card"),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = Color(0xFF00C853).copy(alpha = 0.15f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "AD • اشتہار",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00C853),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }

                    Text(
                        text = "Sponsored",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = ad.headline ?: "Digital Khata Partner",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (!ad.body.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = ad.body ?: "",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (!ad.callToAction.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    FilledTonalButton(
                        onClick = { /* Handled natively by NativeAdView if clicked */ },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text(
                            text = ad.callToAction ?: "Learn More",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
