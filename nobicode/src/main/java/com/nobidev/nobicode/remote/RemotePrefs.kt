package com.nobidev.nobicode.remote

import android.content.Context
import com.nobidev.nobicode.model.RemoteConnection
import org.json.JSONArray
import org.json.JSONObject

/** Persisted FTP/SFTP connections (SharedPreferences + JSON). */
object RemotePrefs {
    private const val PREF = "remote_conns"
    private const val KEY = "connections"

    fun load(ctx: Context): MutableList<RemoteConnection> {
        val out = mutableListOf<RemoteConnection>()
        try {
            val json = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(KEY, null) ?: return out
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    RemoteConnection(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        protocol = o.optString("protocol", "SFTP"),
                        host = o.optString("host"),
                        port = o.optInt("port", 22),
                        username = o.optString("username"),
                        password = o.optString("password")
                    )
                )
            }
        } catch (e: Exception) { }
        return out
    }

    fun save(ctx: Context, list: List<RemoteConnection>) {
        try {
            val arr = JSONArray()
            for (c in list) {
                arr.put(JSONObject().apply {
                    put("id", c.id)
                    put("name", c.name)
                    put("protocol", c.protocol)
                    put("host", c.host)
                    put("port", c.port)
                    put("username", c.username)
                    put("password", c.password)
                })
            }
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(KEY, arr.toString()).apply()
        } catch (e: Exception) { }
    }
}

