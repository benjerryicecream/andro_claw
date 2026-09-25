package com.androclaw.agent.agent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract

/** How AndroClaw reaches a named person. */
enum class Channel {
    MUSE, SMS
}

/** A person AndroClaw knows how to reach. [hint] pins the destination for the goal. */
data class Identity(val name: String, val channel: Channel, val hint: String)

/** A parsed "text <name> <body>" command. */
data class ParsedTextCommand(val recipient: String, val body: String)

/**
 * The identity map and "text <name>" resolution.
 *
 * Kept deliberately small and conservative: an entry here is authoritative about
 * HOW a person is reached, so AndroClaw never guesses a channel. Known names win
 * over contacts (this is what binds Sebastian to the Muse app, not SMS). Unknown
 * names fall through to the device contacts when READ_CONTACTS is granted; when
 * they resolve nowhere the caller asks which channel instead of guessing.
 */
object IdentityResolver {

    private val MUSE = setOf("sebastian", "seb", "sebastien", "seby")

    /** Parse a command that starts with a messaging verb; null when it is not one. */
    fun parseTextCommand(input: String): ParsedTextCommand? {
        val m = TEXT_COMMAND.matchEntire(input.trim()) ?: return null
        return ParsedTextCommand(m.groupValues[1].trim(), m.groupValues[2].trim())
    }

    /** Identity-map lookup (case- and punctuation-insensitive). */
    fun identityFor(name: String): Identity? {
        val key = normalize(name)
        if (key in MUSE) {
            // Sebastian lives in the Muse app; we never reach him via SMS/contacts.
            return Identity("Sebastian", Channel.MUSE, "the Muse app")
        }
        return null
    }

    /**
     * Device-contacts lookup. Returns null when the permission is missing or the
     * name is not in the address book; matching is case-insensitive, exact first.
     */
    fun contactFor(context: Context, name: String): Identity? {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val displayName = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        val number = ContactsContract.CommonDataKinds.Phone.NUMBER
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(displayName, number),
                "$displayName COLLATE NOCASE = ?",
                arrayOf(name.trim()),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val foundName = cursor.getString(0)?.takeIf { !it.isNullOrBlank() } ?: name.trim()
                    Identity(foundName, Channel.SMS, "SMS to ${cursor.getString(1)}")
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    /**
     * The rewritten goal for a resolved messaging command. Wording is pinned to
     * the exact channel so the agent never substitutes Messages/SMS for Muse (or
     * vice versa) and verifies the sent message on screen.
     */
    fun buildGoal(identity: Identity, body: String): String = when (identity.channel) {
        Channel.MUSE ->
            "Send this message to ${identity.name} inside ${identity.hint} (never SMS): " +
                "\"$body\". ${MUSE_LAYOUT_GUIDANCE} " +
                "Open the Muse app, type the complete message in the main chat input, " +
                "press send, and confirm the sent message appears on screen."
        Channel.SMS ->
            "Send this message to ${identity.name} via SMS to ${identity.hint}: " +
                "\"$body\". Open the Messages app, compose a new text to ${identity.name}, " +
                "type the complete message, press send, and confirm the sent message appears on screen."
    }

    /**
     * Muse layout facts, shared by every Muse-path prompt so the agent never has
     * to rediscover the app by flailing: main chat is the default view, side
     * chats are separate threads, messaging = typing in the main chat input.
     */
    const val MUSE_LAYOUT_GUIDANCE: String =
        "Muse layout: the main chat with Sebastian is the default view when the app opens; " +
            "side chats are separate threads — leave them alone. " +
            "To message, type in the main chat's input field and send."

    /** The clarification question To show when the recipient is unknown. */
    fun askChannelQuestion(name: String): String =
        "I don't know who \"${name.capitalizeName()}\" is — they're not in my identity list or your " +
            "contacts. Which app should I use to reach them (e.g. the Muse app, WhatsApp, Messages)?"

    private fun normalize(name: String): String =
        name.trim().lowercase().trimEnd('.', ',', '!', '?', ':')

    private fun String.capitalizeName(): String =
        if (isEmpty()) this else this.replaceFirstChar { it.titlecase() }

    private val TEXT_COMMAND = Regex(
        """(?i)^\s*(?:text|message|msg|sms|texting|text message)\s+(?:to\s+)?([A-Za-z][A-Za-z0-9 .'’\-]{0,30}?)[:.,]?\s+(.+?)\s*$"""
    )
}