package com.khoichon.rush

import android.content.Context
import android.content.SharedPreferences

/** What happens after an entity catches you (opt-in; skipped entirely in test mode). */
enum class DeathAction { NONE, HOME, LOCK, GUIDING_LIGHT }

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

    /** 1-100; how fast the entity crosses the screen. 10 is about 0.9 s, 100 is as fast as it gets. */
    var rushSpeed: Int
        get() = p.getInt("RushSpeed", 10).coerceIn(1, 100)
        set(v) = p.edit().putInt("RushSpeed", v.coerceIn(1, 100)).apply()

    /** 0-100 % of automatic encounters that are fake-outs (the sound starts, then stops). */
    var fakeoutChance: Int
        get() = p.getInt("FakeoutChance", 15).coerceIn(0, 100)
        set(v) = p.edit().putInt("FakeoutChance", v.coerceIn(0, 100)).apply()

    /** Vibration on the warning, while the entity is coming, and on the jumpscare. */
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

    // ---------------------------------------------------------------- entities

    /** Spawn weight 0-100 per entity; 0 = never. Rush considerably more common by default. */
    fun weight(entity: Entity): Int =
        p.getInt("Weight_${entity.key}", entity.defaultWeight).coerceIn(0, 100)

    fun setWeight(entity: Entity, v: Int) =
        p.edit().putInt("Weight_${entity.key}", v.coerceIn(0, 100)).apply()

    /**
     * Pick the next entity by weight. All-zero falls back to Rush so the app never silently
     * stops spawning. Returns null only for a manual call that should not fake-out.
     */
    fun pickEntity(): Entity {
        val weights = Entity.ALL.associateWith { weight(it) }
        val total = weights.values.sum()
        if (total <= 0) return Entity.RUSH
        var r = kotlin.random.Random.nextInt(total)
        for ((e, w) in weights) {
            if (r < w) return e
            r -= w
        }
        return Entity.RUSH
    }

    /**
     * Hide kicks you out of the closet if you hide too long. Only Rush and Ambush encounters
     * have Hide in them (the Backdoor / Archives / Outdoors have no hiding penalty).
     */
    var hideEnabled: Boolean
        get() = p.getBoolean("HideEnabled", true)
        set(v) = p.edit().putBoolean("HideEnabled", v).apply()

    /** How many times you have died to each entity (drives the escalating death messages). */
    fun deaths(entity: Entity): Int = p.getInt("Deaths_${entity.key}", 0)

    fun bumpDeath(entity: Entity) = p.edit().putInt("Deaths_${entity.key}", deaths(entity) + 1).apply()

    /** How many times each entity has shown up (summoned or spawned). */
    fun encounters(entity: Entity): Int = p.getInt("Encounters_${entity.key}", 0)

    fun bumpEncounter(entity: Entity) = p.edit().putInt("Encounters_${entity.key}", encounters(entity) + 1).apply()

    /** Everything back to the defaults: settings, weights, and the encounter and death counters. */
    fun reset() = p.edit().clear().apply()

    /** Min/max in seconds with max never below min. */
    fun spawnRange(): Pair<Int, Int> {
        val lo = minSpawnDelay
        return lo to maxSpawnDelay.coerceAtLeast(lo)
    }
}
