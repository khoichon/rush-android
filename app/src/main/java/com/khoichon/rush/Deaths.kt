package com.khoichon.rush

/**
 * Death messages, straight from the Guiding Light / Curious Light lists on the Doors wiki.
 * [GuidingLight] packs the chosen lines into the intent for the helper app (three ';'-separated
 * lines: addressal;advice;closing). The counters in [Config] pick which "Nth death" variant shows.
 */
object Deaths {

    /** (addressal, advice) per death number (1st, 2nd, 3rd...); the last entry repeats forever. */
    private val messages: Map<Entity, List<Pair<String, String>>> = mapOf(
        Entity.RUSH to listOf(
            "You died to who you call Rush..." to
                "Pay attention to any cues that may hint at its attack.",
            "You died to Rush again..." to
                "Pay attention to the lights. They are related to its attack.",
            "It seems you are having trouble with Rush..." to
                "The lights will always flicker when it is near.\nWhenever this happens, find a hiding spot."
        ),
        Entity.AMBUSH to listOf(
            "You died to who you call Ambush..." to
                "Pay attention to any cues that might hint at its arrival.\nIt is a tricky one. Use what you have learned from Rush!",
            "You died to Ambush again..." to
                "It gets you when you least suspect it!\nIt has a unique scream. Once you hear it, be prepared!",
            "It seems you are having trouble with Ambush…" to
                "You might need to hop in and out of your hiding spot a couple of times."
        ),
        Entity.BLITZ to listOf(
            "Oh, one of my favorites." to
                "She said we should call that one Blitz.",
            "Blitz got you again?" to
                "Hmm... it's extremely hostile. It's best not to interact with it.",
            "Wow... Blitz yet again." to
                "...You should really know how to handle this by now.",
            "Wow... Blitz yet again." to
                "Are you even paying attention? Come on, you can do this. Just try harder.",
            "Wow... Blitz yet again." to
                "Blitz is extremely hostile. Just avoid it if you have to."
        ),
        Entity.BASH to listOf(
            "You died to the entity known as Bash." to
                "Bash is extremely fast, and it's speed against the carpet generates flames."
        ),
        Entity.SCRIBBLES to listOf(
            "You died to who I call Scribbles" to
                "Those paper cuts probably don't feel good on your skin.\nI'd try to avoid them if I were you... not that I know what they feel like."
        ),
        Entity.SURGE to listOf(
            "You've died to what I call Surge." to
                "What did it feel like? All the electricity in your body?\n...Probably not great.",
            "Surge electrocuted you to death." to
                "Keep your chin up. It helps to keep a look out.",
            "Surge electrocuted you to death." to
                "Try looking out for Surge's warning. It's pretty obvious, I don't know how you keep missing it.",
            "Surge electrocuted you to death." to
                "Try looking out for Surge's warning. It's pretty obvious, I don't know how you keep missing it."
        )
    )

    /** Rush/Ambush after Hide kicked you out of the closet (the Guiding Light "combo" messages). */
    private val kickedMessages: Map<Entity, List<Pair<String, String>>> = mapOf(
        Entity.RUSH to listOf(
            "Kicked out by Hide" to
                "Remember, minimize how long you hide for!"
        ),
        Entity.AMBUSH to listOf(
            "You died to Ambush because you couldn't hide." to
                "Ambush and Hide prove to be a tricky duo.\nRemember; you don't have to hide when Ambush is away!",
            "You couldn't hide from Ambush again." to
                "I know it may seem difficult, but trust me, you will get the hang of this!\nYou will need to hop in and out of your hiding spot."
        )
    )

    private fun closing(entity: Entity) = when (entity.light) {
        "curious" -> "You'll be back. See you then."
        else -> "You’ve done great! Don’t give up!"
    }

    /** The three message lines for the death screen, escalating with [deathNo] (1-based). */
    fun message(entity: Entity, deathNo: Int, kickedByHide: Boolean): String {
        val kicked = if (kickedByHide) kickedMessages[entity] else null
        val list = kicked ?: messages[entity] ?: messages.getValue(Entity.RUSH)
        val (a, d) = list[(deathNo - 1).coerceIn(0, list.size - 1)]
        return "$a;$d;${closing(entity)}"
    }
}
