package com.nobidev.nobicode.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * AI assistant provider interface. Implementations talk to a real model
 * backend; there is deliberately NO bundled key, NO default vendor, and
 * NO faked response: without a configured provider the chat UI says so
 * and offers setup instead of inventing an answer.
 *
 * A provider can come from AI settings (endpoint + model + key) or from
 * an enabled ai-provider plugin manifest (endpoint + model; the key is
 * still entered in AI settings).
 */
interface AiProvider {
    val displayName: String
    suspend fun chat(messages: List<ChatMessage>): Result<String>

    data class ChatMessage(val role: String, val content: String)
}

/** OpenAI-compatible chat-completions provider (any compatible endpoint). */
class OpenAiCompatibleProvider(
    override val displayName: String,
    private val endpoint: String,
    private val apiKey: String,
    private val model: String
) : AiProvider {

    override suspend fun chat(messages: List<AiProvider.ChatMessage>): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = URL(endpoint.trimEnd('/') + "/chat/completions")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                if (apiKey.isNotBlank()) {
                    conn.setRequestProperty("Authorization", "Bearer $apiKey")
                }
                conn.connectTimeout = 30000
                conn.readTimeout = 90000
                conn.doOutput = true
                val body = JSONObject()
                body.put("model", model)
                val arr = JSONArray()
                for (m in messages) {
                    val o = JSONObject()
                    o.put("role", m.role)
                    o.put("content", m.content)
                    arr.put(o)
                }
                body.put("messages", arr)
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.readText() ?: ""
                if (code !in 200..299) throw Exception("HTTP $code: " + text.take(300))
                val json = JSONObject(text)
                val choices = json.optJSONArray("choices")
                    ?: throw Exception("No choices in response")
                if (choices.length() == 0) throw Exception("Empty response")
                val msg = choices.getJSONObject(0).optJSONObject("message")
                    ?: throw Exception("No message in response")
                msg.optString("content", "")
            }
        }
}

/** Settings for the AI assistant (app-private prefs; key stored in plain
 * app-private storage — the user opts in by entering it; nothing is
 * uploaded anywhere except to the endpoint the user configured). */
object AiSettings {

    private const val PREFS = "nobi_ai"

    fun endpoint(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pluginEndpoint = com.nobidev.nobicode.plugins.PluginManager
            .aiProviderManifest(context)?.optString("endpoint", "") ?: ""
        return prefs.getString("endpoint", "")?.ifBlank { pluginEndpoint } ?: pluginEndpoint
    }

    fun model(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pluginModel = com.nobidev.nobicode.plugins.PluginManager
            .aiProviderManifest(context)?.optString("model", "") ?: ""
        return prefs.getString("model", "")?.ifBlank { pluginModel } ?: pluginModel
    }

    fun apiKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString("api_key", "") ?: ""
    }

    fun save(context: Context, endpoint: String, model: String, apiKey: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("endpoint", endpoint)
            .putString("model", model)
            .putString("api_key", apiKey)
            .apply()
    }

    fun isConfigured(context: Context): Boolean =
        endpoint(context).isNotBlank() && model(context).isNotBlank()

    fun provider(context: Context): AiProvider? {
        if (!isConfigured(context)) return null
        return OpenAiCompatibleProvider(
            displayName = "Custom provider",
            endpoint = endpoint(context),
            apiKey = apiKey(context),
            model = model(context)
        )
    }
}
