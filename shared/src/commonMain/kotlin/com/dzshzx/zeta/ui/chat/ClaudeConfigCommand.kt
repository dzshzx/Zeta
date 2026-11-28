package com.dzshzx.zeta.ui.chat

import com.dzshzx.zeta.core.Harnesses
import com.dzshzx.zeta.core.SlashCommand
import com.dzshzx.zeta.i18n.Strings

/** 裸命令打开 Zeta 的现有页面；带参数的命令仍交给 CLI。 */
enum class ClaudeConfigCommand(val commandName: String) {
    Config("config"),
    Permissions("permissions");

    private fun description(harness: String): String =
        when (this) {
            Config ->
                if (Harnesses.normalize(harness) == Harnesses.AGY) Strings.settingsHostAgyConfig()
                else Strings.settingsHostClaudeConfig()
            Permissions -> Strings.settingsHostToolPermissions()
        }

    companion object {
        internal fun from(text: String): ClaudeConfigCommand? {
            val command = text.trim()
            return entries.firstOrNull { command == "/${it.commandName}" }
        }

        internal fun suggestions(
            commands: List<SlashCommand>,
            enabled: Boolean,
            harness: String = Harnesses.CLAUDE,
        ): List<SlashCommand> {
            if (!enabled) return commands
            val native = commands.filterNot { command ->
                entries.any { it.commandName.equals(command.name, ignoreCase = true) }
            }
            return native +
                entries.map { SlashCommand(it.commandName, it.description(harness), "") }
        }
    }
}
