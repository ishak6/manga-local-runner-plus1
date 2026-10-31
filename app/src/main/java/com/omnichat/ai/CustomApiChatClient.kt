package com.omnichat.ai

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

object CustomApiChatClient {

    fun sendMessage(
        apiKey: String,
        config: CustomApiConfig,
        messages: List<OpenAiMessage>
    ): String {
        require(apiKey.isNotBlank()) {
            "Custom API key is missing."
        }

        require(config.model.isNotBlank()) {
            "Custom API Model ID is missing."
        }

        val endpoint = buildEndpoint(config.baseUrl)
        val connection = URL(endpoint)
            .openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20000
            connection.readTimeout = 60000
            connection.doOutput = true

            connection.setRequestProperty(
                "Content-Type",
                "application/json"
            )

            connection.setRequestProperty(
                "Accept",
                "application/json"
            )

            connection.setRequestProperty(
                "Authorization",
                "Bearer $apiKey"
            )

            val messageArray = JSONArray()

            messages.forEach { message ->
                val item = JSONObject()

                item.put(
                    "role",
                    if (message.role == "assistant") {
                        "assistant"
                    } else {
                        "user"
                    }
                )

                item.put("content", message.content)
                messageArray.put(item)
            }

            val requestBody = JSONObject().apply {
                put("model", config.model)
                put("messages", messageArray)
                put("max_tokens", 2048)
                put("stream", false)
            }

            connection.outputStream.use { output ->
                output.write(
                    requestBody.toString().toByteArray(Charsets.UTF_8)
                )
            }

            val responseCode = connection.responseCode

            val responseText = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use {
                    it.readText()
                }
            } else {
                val errorText = connection.errorStream
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()

                val errorMessage = try {
                    val error = JSONObject(errorText).opt("error")

                    when (error) {
                        is JSONObject -> error.optString("message")
                        is String -> error
                        else -> null
                    }
                } catch (_: Exception) {
                    null
                }

                throw Exception(
                    errorMessage?.takeIf { it.isNotBlank() }
                        ?: "Custom API request failed. HTTP $responseCode"
                )
            }

            val response = JSONObject(responseText)

            val answer = response
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                .orEmpty()

            if (answer.isBlank()) {
                throw Exception(
                    "Custom API returned an empty response or an unsupported response format."
                )
            }

            return answer

        } finally {
            connection.disconnect()
        }
    }

    private fun buildEndpoint(baseUrl: String): String {
        val cleaned = baseUrl.trim().trimEnd('/')

        require(
            cleaned.startsWith("https://", ignoreCase = true) ||
                cleaned.startsWith("http://", ignoreCase = true)
        ) {
            "API URL must start with http:// or https://."
        }

        return if (
            cleaned.lowercase(Locale.ROOT).endsWith("/chat/completions")
        ) {
            cleaned
        } else {
            "$cleaned/chat/completions"
        }
    }
}
