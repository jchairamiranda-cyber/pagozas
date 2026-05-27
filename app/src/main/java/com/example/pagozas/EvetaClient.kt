package com.example.pagozas

import android.content.Context
import android.util.Log
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object EvetaClient {
    private const val TAG = "EvetaClient"

    fun sendPayment(ctx: Context, codigo: String, monto: String, fecha: String): Boolean {
        return try {
            val backendUrl = PagozasConfig.backendUrl(ctx)
            val token      = PagozasConfig.token(ctx)
            val workerId   = PagozasConfig.workerId(ctx)
            val provider   = PagozasConfig.provider(ctx)

            if (token.isBlank()) {
                Log.w(TAG, "Token no configurado — omitiendo $codigo")
                return false
            }

            val detectedAt = isoBolivia()
            val amountNorm = normalizeAmount(monto)
            val rawText    = "$codigo Bs${amountNorm} $fecha"

            val body = buildString {
                append("{")
                append("\"worker_id\":\"$workerId\",")
                append("\"provider\":\"$provider\",")
                append("\"raw_text\":\"${rawText.replace("\"", "'")}\",")
                append("\"detected_amount\":\"$amountNorm\",")
                append("\"detected_reference\":\"$codigo\",")
                append("\"detected_at\":\"$detectedAt\",")
                append("\"notification_package\":\"bec.vdb.direct\",")
                append("\"notification_title\":\"PagoZas\"")
                append("}")
            }

            val url  = URL("$backendUrl/bank-notifications/tasker")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout    = 10_000

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }

            val code = conn.responseCode
            Log.i(TAG, "POST $codigo → HTTP $code")

            when (code) {
                200, 201 -> true
                401, 403 -> { Log.e(TAG, "Token inválido (HTTP $code)"); false }
                else     -> { Log.w(TAG, "HTTP $code para $codigo"); false }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enviando $codigo: ${e.message}")
            false
        }
    }

    // "Bs3,50" → "3.50"   |   "Bs1.000,50" → "1000.50"
    private fun normalizeAmount(monto: String): String {
        val raw = monto.removePrefix("Bs").trim()
        return if (raw.contains(",")) raw.replace(".", "").replace(",", ".") else raw
    }

    private fun isoBolivia(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault())
        sdf.timeZone = TimeZone.getTimeZone("America/La_Paz")
        return sdf.format(Date())
    }
}
