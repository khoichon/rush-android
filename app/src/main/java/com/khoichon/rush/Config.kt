package com.khoichon.rush

import android.content.Context
import android.content.SharedPreferences

/** What happens after Rush catches you (opt-in; skipped entirely in test mode). */
enum class DeathAction { NONE, HOME, LOCK }

/** Settings. Values are clamped on read, so a bad number can never reach the game. */
class Config(context: Context) {
    private val p: SharedPreferences =
        context.applicationContext.getSharedPreferences("rushconfig", Context.MODE_PRIVATE)

    var spawnAutomatically: Boolean
        get() = p.getBoolean("SpawnAutomatically", true)
        set(v) = p.edit().putBoolean("SpawnAutomatically", v).apply()

    var minSpawnDelay: Int
        get() = p.getInt("MinSpawnDelay", 60).coerceIn(0, 86400)
        set(v) = p.edit().putInt("MinSpawnDelay", v.coerceIn(0, 86400)).apply()

    var maxSpawnDelay: Int
        get() = p.getInt("MaxSpawnDelay", 900).coerceIn(0, 86400)
        set(v) = p.edit().putInt("MaxSpawnDelay", v.coerceIn(0, 86400)).apply()

    /** 1-100; how fast Rush crosses the screen. 10 is about 0.9 s, 100 is as fast as it gets. */
    var rushSpeed: Int
        get() = p.getInt("RushSpeed", 10).coerceIn(1, 100)
        set(v) = p.edit().putInt("RushSpeed", v.coerceIn(1, 100)).apply()

    /** 0-100 % of automatic encounters that are fake-outs (the sound starts, then stops). */
    var fakeoutChance: Int
        get() = p.getInt("FakeoutChance", 15).coerceIn(0, 100)
        set(v) = p.edit().putInt("FakeoutChance", v.coerceIn(0, 100)).apply()

    /** Vibration on the warning, while Rush is coming, and on the jumpscare. */
    var haptics: Boolean
        get() = p.getBoolean("Haptics", true)
        set(v) = p.edit().putBoolean("Haptics", v).apply()

    var deathAction: DeathAction
        get() = runCatching { DeathAction.valueOf(p.getString("DeathAction", "NONE") ?: "NONE") }
            .getOrDefault(DeathAction.NONE)
        set(v) = p.edit().putString("DeathAction", v.name).apply()

    /** Skips the death consequences. */
    var testMode: Boolean
        get() = p.getBoolean("TestMode", false)
        set(v) = p.edit().putBoolean("TestMode", v).apply()

    var startOnBoot: Boolean
        get() = p.getBoolean("StartOnBoot", false)
        set(v) = p.edit().putBoolean("StartOnBoot", v).apply()

    /** Min/max in seconds with max never below min. */
    fun spawnRange(): Pair<Int, Int> {
        val lo = minSpawnDelay
        return lo to maxSpawnDelay.coerceAtLeast(lo)
    }
}
