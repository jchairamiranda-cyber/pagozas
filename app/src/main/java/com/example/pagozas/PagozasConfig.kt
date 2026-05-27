package com.example.pagozas

import android.content.Context
import android.content.SharedPreferences

object PagozasConfig {

    private const val PREFS = "pagozas_config"

    const val DEFAULT_BACKEND_URL = "https://eveta-core.vercel.app/api/v1"
    const val DEFAULT_WORKER_ID   = "pagozas-phone-1"
    const val DEFAULT_PROVIDER    = "zas"
    const val DEFAULT_PIN         = "6880"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun backendUrl(ctx: Context): String = prefs(ctx).getString("backend_url", DEFAULT_BACKEND_URL) ?: DEFAULT_BACKEND_URL
    fun token(ctx: Context): String      = prefs(ctx).getString("token", "") ?: ""
    fun workerId(ctx: Context): String   = prefs(ctx).getString("worker_id", DEFAULT_WORKER_ID) ?: DEFAULT_WORKER_ID
    fun provider(ctx: Context): String   = prefs(ctx).getString("provider", DEFAULT_PROVIDER) ?: DEFAULT_PROVIDER
    fun pin(ctx: Context): String        = prefs(ctx).getString("pin", DEFAULT_PIN) ?: DEFAULT_PIN

    fun save(
        ctx: Context,
        backendUrl: String,
        token: String,
        workerId: String,
        provider: String,
        pin: String
    ) {
        prefs(ctx).edit()
            .putString("backend_url", backendUrl.ifBlank { DEFAULT_BACKEND_URL })
            .putString("token",       token.trim())
            .putString("worker_id",   workerId.ifBlank { DEFAULT_WORKER_ID })
            .putString("provider",    provider.ifBlank { DEFAULT_PROVIDER })
            .putString("pin",         pin.ifBlank { DEFAULT_PIN })
            .apply()
    }
}
