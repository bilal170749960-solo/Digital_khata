package com.example.ui.qr

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONObject

/**
 * Parsed linking payload extracted from a shopkeeper-generated QR code.
 * Contains only non-sensitive identifiers and the secure verification token.
 */
data class ParsedQrPayload(
    val shopId: String,
    val customerId: String,
    val token: String
)

object QRService {

    /**
     * Builds the standard URI payload for Khata linking.
     * Financial information, passwords, and private data are strictly excluded.
     */
    fun createPayload(shopId: String, customerId: String, token: String): String {
        return "digitalkhata://link?shopId=${Uri.encode(shopId)}&customerId=${Uri.encode(customerId)}&token=${Uri.encode(token)}"
    }

    /**
     * Parses and validates raw QR string content into a structured ParsedQrPayload.
     * Supports both URI format and JSON format for maximum compatibility.
     */
    fun parsePayload(raw: String): ParsedQrPayload? {
        val trimmed = raw.trim()
        try {
            if (trimmed.startsWith("digitalkhata://link")) {
                val uri = Uri.parse(trimmed)
                val shopId = uri.getQueryParameter("shopId")
                val customerId = uri.getQueryParameter("customerId")
                val token = uri.getQueryParameter("token")
                if (!shopId.isNullOrBlank() && !customerId.isNullOrBlank() && !token.isNullOrBlank()) {
                    return ParsedQrPayload(shopId = shopId, customerId = customerId, token = token)
                }
            } else if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                val json = JSONObject(trimmed)
                val shopId = json.optString("shopId", "")
                val customerId = json.optString("customerId", "")
                val token = json.optString("token", "")
                if (shopId.isNotBlank() && customerId.isNotBlank() && token.isNotBlank()) {
                    return ParsedQrPayload(shopId = shopId, customerId = customerId, token = token)
                }
            }
        } catch (_: Exception) {
            return null
        }
        return null
    }

    /**
     * Generates a high-contrast black-and-white Bitmap from a payload string using ZXing.
     * Uses HIGH error correction so QR codes remain scan-friendly even under poor lighting.
     */
    fun generateQRCodeBitmap(content: String, sizePx: Int = 512): Bitmap {
        val hints = hashMapOf<EncodeHintType, Any>(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
            EncodeHintType.MARGIN to 1
        )
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }
}
