package com.omnichat.ai

import android.content.Context
import java.net.URI

data class CustomApiConfig(
    val baseUrl: String,
    val model: String
)

object CustomApiConfigStore {
    private const val PREFS_NAME = "omnichat_custom_api"
    private const val BASE_URL_KEY = "base_url"
    private const val MODEL_KEY = "model"

    fun save(context: Context, baseUrl: String, model: String): Boolean {
        return try {
            val normalizedUrl = baseUrl.trim().trimEnd('/')
            val parsed = URI(normalizedUrl)

            val validScheme = parsed.scheme.equals("https", true) ||
                parsed.scheme.equals("http", true)

            require(validScheme && !parsed.host.isNullOrBlank())
            require(model.isNotBlank())

            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(BASE_URL_KEY, normalizedUrl)
                .putString(MODEL_KEY, model.trim())
                .commit()
        } catch (_: Exception) {
            false
        }
    }

    fun get(context: Context): CustomApiConfig? {
        val prefs = context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

        val baseUrl = prefs.getString(BASE_URL_KEY, null) ?: return null
        val model = prefs.getString(MODEL_KEY, null) ?: return null

        if (baseUrl.isBlank() || model.isBlank()) return null

        return CustomApiConfig(baseUrl, model)
    }

    fun delete(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(BASE_URL_KEY)
            .remove(MODEL_KEY)
            .commit()
    }
}
