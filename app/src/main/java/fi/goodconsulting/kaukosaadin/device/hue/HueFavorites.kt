package fi.goodconsulting.kaukosaadin.device.hue

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The lights and rooms one bridge's operator kept, by resource id, in that bridge's own [HueStorage]
 * beside its address, app key and pin. Storing the bridge's ids rather than its names means a light
 * or room renamed on the bridge stays favorited. The whole file is one bridge's, so
 * [HueCredentials.forget] takes the favorites with the rest of it.
 *
 * A kept id the bridge no longer reports is harmless: nothing here resolves ids to resources, and
 * the screen only ever lists what the bridge returned.
 */
internal class HueFavorites(
    private val storage: HueStorage,
) {
    /** The favorited light ids; an absent or unreadable entry reads as none kept. */
    val lightIds: Set<String> get() = read().lights

    /** The favorited room ids; an absent or unreadable entry reads as none kept. */
    val roomIds: Set<String> get() = read().rooms

    /** Keeps [id] among the favorites, or drops it when it already is; false when the write did not stick. */
    fun toggleLight(id: String): Boolean {
        val kept = read()
        return write(kept.copy(lights = kept.lights.toggled(id)))
    }

    /** Keeps room [id] among the favorites, or drops it when it already is; false when the write did not stick. */
    fun toggleRoom(id: String): Boolean {
        val kept = read()
        return write(kept.copy(rooms = kept.rooms.toggled(id)))
    }

    private fun read(): Kept = decode(storage.get(FAVORITES))

    private fun write(kept: Kept): Boolean = storage.put(FAVORITES, encode(kept))

    /** The stored favorites: the two id sets, as one value. */
    private data class Kept(
        val lights: Set<String> = emptySet(),
        val rooms: Set<String> = emptySet(),
    )

    private fun encode(kept: Kept): String =
        JSONObject()
            .put(LIGHTS, JSONArray(kept.lights.toList()))
            .put(ROOMS, JSONArray(kept.rooms.toList()))
            .toString()

    // Unreadable storage reads as nothing kept rather than crashing the lighting screen.
    private fun decode(value: String?): Kept {
        if (value == null) return Kept()
        return try {
            val json = JSONObject(value)
            Kept(lights = ids(json.optJSONArray(LIGHTS)), rooms = ids(json.optJSONArray(ROOMS)))
        } catch (_: JSONException) {
            Kept()
        }
    }

    /** The ids an array holds; a blank id is junk and is dropped. */
    private fun ids(array: JSONArray?): Set<String> {
        if (array == null) return emptySet()
        return (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }.toSet()
    }

    private companion object {
        /** The one entry in the bridge's file; the favorites sharing it is what makes forget clear them. */
        const val FAVORITES = "favorites"
        const val LIGHTS = "lights"
        const val ROOMS = "rooms"
    }
}

/** [id] added when absent, dropped when present. */
private fun Set<String>.toggled(id: String): Set<String> = if (id in this) this - id else this + id
