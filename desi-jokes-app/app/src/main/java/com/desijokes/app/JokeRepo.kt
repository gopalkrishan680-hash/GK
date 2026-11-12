package com.desijokes.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

data class Joke(val id: String, val text: String, val categoryId: String)
data class Category(val id: String, val name: String, val emoji: String, val adult: Boolean)

/** Loads the bundled jokes plus the jokes the user wrote themselves. */
object JokeRepo {
    const val PREFS = "desi_jokes"
    const val MINE = "__mine"
    val mineCategory = Category(MINE, "Mere Jokes", "✍️", false)

    class Data(val categories: List<Category>, val jokes: List<Joke>)

    @Volatile private var cache: Data? = null

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context): Data {
        cache?.let { return it }
        val json = ctx.assets.open("jokes.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val cats = JSONObject(json).getJSONArray("categories")
        val categories = mutableListOf<Category>()
        val jokes = mutableListOf<Joke>()
        for (i in 0 until cats.length()) {
            val c = cats.getJSONObject(i)
            val cat = Category(c.getString("id"), c.getString("name"), c.getString("emoji"), c.optBoolean("adult"))
            categories.add(cat)
            val arr = c.getJSONArray("jokes")
            // ids are "<category>_<position>"; new jokes are appended so saved favourites stay valid
            for (j in 0 until arr.length()) jokes.add(Joke("${cat.id}_$j", arr.getString(j), cat.id))
        }
        return Data(categories, jokes).also { cache = it }
    }

    /** Same safe (non-18+) joke for everyone on a given day. */
    fun jokeOfDay(ctx: Context): Joke {
        val d = load(ctx)
        val adult = d.categories.filter { it.adult }.map { it.id }.toSet()
        val pool = d.jokes.filter { it.categoryId !in adult }
        val day = LocalDate.now().toEpochDay()
        val idx = Math.floorMod(day * 2654435761L, pool.size.toLong()).toInt()
        return pool[idx]
    }

    // ----- user's own jokes -----

    fun userJokes(ctx: Context): List<Joke> {
        val arr = JSONArray(prefs(ctx).getString("mine", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Joke(o.getString("id"), o.getString("text"), MINE)
        }
    }

    fun addUserJoke(ctx: Context, text: String) {
        val arr = JSONArray(prefs(ctx).getString("mine", "[]"))
        arr.put(JSONObject().put("id", "mine_${System.currentTimeMillis()}").put("text", text))
        prefs(ctx).edit().putString("mine", arr.toString()).apply()
    }

    fun deleteUserJoke(ctx: Context, id: String) {
        val old = JSONArray(prefs(ctx).getString("mine", "[]"))
        val arr = JSONArray()
        for (i in 0 until old.length()) if (old.getJSONObject(i).getString("id") != id) arr.put(old.getJSONObject(i))
        prefs(ctx).edit().putString("mine", arr.toString()).apply()
    }
}
