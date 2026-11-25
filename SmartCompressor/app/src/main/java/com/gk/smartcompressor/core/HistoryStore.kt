package com.gk.smartcompressor.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

class HistoryStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("history", Context.MODE_PRIVATE)
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<HistoryItem>> = _items

    private fun load(): List<HistoryItem> = runCatching {
        val arr = JSONArray(prefs.getString("items", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            HistoryItem(
                name = o.getString("name"),
                kind = FileKind.valueOf(o.getString("kind")),
                origSize = o.getLong("orig"),
                newSize = o.getLong("new"),
                outUri = o.getString("uri"),
                mime = o.getString("mime"),
                time = o.getLong("time"),
            )
        }
    }.getOrDefault(emptyList())

    private fun persist(list: List<HistoryItem>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("name", it.name); put("kind", it.kind.name); put("orig", it.origSize)
                put("new", it.newSize); put("uri", it.outUri); put("mime", it.mime); put("time", it.time)
            })
        }
        prefs.edit().putString("items", arr.toString()).apply()
        _items.value = list
    }

    fun add(item: HistoryItem) = persist((listOf(item) + _items.value).take(500))
    fun remove(item: HistoryItem) = persist(_items.value - item)
    fun clear() = persist(emptyList())
}
