package com.androclaw.agent.data

import org.json.JSONObject
import java.io.File

data class CommandHistoryEntry(val timestampMs: Long, val command: String)

class CommandHistory(private val dir: File) {

    private val historyFile: File
        get() = File(dir, FILE_NAME)

    @Synchronized
    fun record(command: String): List<CommandHistoryEntry> {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return entries()
        val all = readAll()
        val updated = if (all.isNotEmpty() && all.last().command == trimmed) {
            all.dropLast(1) + CommandHistoryEntry(System.currentTimeMillis(), trimmed)
        } else {
            all + CommandHistoryEntry(System.currentTimeMillis(), trimmed)
        }
        val capped = updated.takeLast(MAX_ENTRIES)
        writeAll(capped)
        return capped.reversed()
    }

    @Synchronized
    fun entries(): List<CommandHistoryEntry> = readAll().reversed()

    @Synchronized
    fun delete(timestampMs: Long): List<CommandHistoryEntry> {
        val remaining = readAll().filterNot { it.timestampMs == timestampMs }
        writeAll(remaining)
        return remaining.reversed()
    }

    private fun readAll(): List<CommandHistoryEntry> {
        if (!historyFile.exists()) return emptyList()
        return runCatching {
            historyFile.readLines().mapNotNull { line ->
                runCatching {
                    val obj = JSONObject(line.trim())
                    CommandHistoryEntry(obj.optLong("ts", 0L), obj.optString("command", ""))
                }.getOrNull()?.takeIf { it.command.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeAll(entries: List<CommandHistoryEntry>) {
        if (entries.isEmpty()) {
            historyFile.delete()
            return
        }
        runCatching {
            historyFile.writeText(buildString {
                entries.forEach { e ->
                    append(
                        JSONObject()
                            .put("ts", e.timestampMs)
                            .put("command", e.command)
                            .toString()
                    ).append('\n')
                }
            })
        }
    }

    companion object {
        const val FILE_NAME = "history.jsonl"
        const val MAX_ENTRIES = 50
    }
}