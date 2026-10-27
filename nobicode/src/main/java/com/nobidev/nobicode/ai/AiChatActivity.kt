package com.nobidev.nobicode.ai

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nobidev.nobicode.R
import kotlinx.coroutines.launch

/**
 * AI assistant chat panel. The first message is the file context passed by
 * the editor (file name + selected text, capped). If no provider is
 * configured, the screen shows setup fields instead of faking an answer.
 */
class AiChatActivity : AppCompatActivity() {

    private lateinit var list: RecyclerView
    private lateinit var input: EditText
    private lateinit var sendBtn: Button
    private lateinit var setupBox: View
    private lateinit var endpointEt: EditText
    private lateinit var modelEt: EditText
    private lateinit var keyEt: EditText
    private lateinit var saveBtn: Button
    private lateinit var statusText: TextView

    private val messages = mutableListOf<AiProvider.ChatMessage>()
    private lateinit var adapter: ChatAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_chat)
        title = "AI Assistant"
        list = findViewById(R.id.aiList)
        input = findViewById(R.id.aiInput)
        sendBtn = findViewById(R.id.aiSendBtn)
        setupBox = findViewById(R.id.aiSetupBox)
        endpointEt = findViewById(R.id.aiEndpoint)
        modelEt = findViewById(R.id.aiModel)
        keyEt = findViewById(R.id.aiKey)
        saveBtn = findViewById(R.id.aiSaveBtn)
        statusText = findViewById(R.id.aiStatus)

        list.layoutManager = LinearLayoutManager(this)
        adapter = ChatAdapter(messages)
        list.adapter = adapter

        val fileName = intent.getStringExtra("ai_file_name") ?: ""
        val contextText = intent.getStringExtra("ai_context") ?: ""
        if (fileName.isNotBlank() || contextText.isNotBlank()) {
            val ctx = buildString {
                if (fileName.isNotBlank()) append("File: ").append(fileName).append("\n")
                if (contextText.isNotBlank()) append(contextText.take(4000))
            }
            messages.add(AiProvider.ChatMessage("user", ctx))
            adapter.notifyDataSetChanged()
        }

        refreshSetup()
        saveBtn.setOnClickListener {
            AiSettings.save(
                this,
                endpointEt.text.toString().trim(),
                modelEt.text.toString().trim(),
                keyEt.text.toString()
            )
            Toast.makeText(this, "AI provider saved", Toast.LENGTH_SHORT).show()
            refreshSetup()
        }
        sendBtn.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            val provider = AiSettings.provider(this)
            if (provider == null) {
                Toast.makeText(this, "Configure an AI provider first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            messages.add(AiProvider.ChatMessage("user", text))
            adapter.notifyDataSetChanged()
            input.setText("")
            sendBtn.isEnabled = false
            lifecycleScope.launch {
                val history = messages.toList()
                provider.chat(history).onSuccess { reply ->
                    messages.add(AiProvider.ChatMessage("assistant", reply))
                    adapter.notifyDataSetChanged()
                    list.scrollToPosition(messages.size - 1)
                }.onFailure { e ->
                    messages.add(AiProvider.ChatMessage("assistant", "Error: " + e.message))
                    adapter.notifyDataSetChanged()
                }
                sendBtn.isEnabled = true
            }
        }
    }

    private fun refreshSetup() {
        val configured = AiSettings.isConfigured(this)
        setupBox.visibility = if (configured) View.GONE else View.VISIBLE
        statusText.text = if (configured) {
            "Provider: " + AiSettings.endpoint(this) + " · " + AiSettings.model(this)
        } else {
            "No AI provider configured. Enter an OpenAI-compatible endpoint, " +
                "model and API key to chat. Nothing is faked: without a " +
                "provider this screen cannot answer."
        }
        endpointEt.setText(AiSettings.endpoint(this))
        modelEt.setText(AiSettings.model(this))
        keyEt.setText(AiSettings.apiKey(this))
    }

    private inner class ChatAdapter(
        private val items: List<AiProvider.ChatMessage>
    ) : RecyclerView.Adapter<ChatAdapter.VH>() {

        override fun onCreateViewHolder(p: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(p.context).inflate(R.layout.item_ai_message, p, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, pos: Int) = h.bind(items[pos])
        override fun getItemCount() = items.size

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val role: TextView = v.findViewById(R.id.aiMsgRole)
            private val body: TextView = v.findViewById(R.id.aiMsgBody)
            fun bind(m: AiProvider.ChatMessage) {
                role.text = if (m.role == "assistant") "AI" else "You"
                body.text = m.content
            }
        }
    }
}
