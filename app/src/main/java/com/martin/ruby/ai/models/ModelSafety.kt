package com.martin.ruby.ai.models

import com.martin.ruby.reminders.ReminderTools

/**
 * Ruby's safety rules for every model that isn't Claude, enforced by the phone in code, not left
 * to the model's instructions. That matters most for an uncensored model, which won't refuse
 * anything. A model may only run low-risk actions by itself; anything that spends money, unlocks
 * or opens something, sends messages, saves to the calendar or controls a device needs the user's
 * yes on the phone, so a model's tool call for it is never run, only reported.
 */
object ModelSafety {

    /** The permission levels from the build plan. */
    enum class Risk { FREE, LOW, HIGH }

    /** Low-risk tools a model may run without asking (logged like every action). */
    private val LOW_RISK = setOf(ReminderTools.SET, ReminderTools.LIST, ReminderTools.CANCEL)

    /**
     * Tools that always need the user's yes, whatever the model: they're never run straight from a
     * model's tool call. (None of these are offered to models yet; this is the guard for when they come.)
     */
    private val NEEDS_YES = setOf(
        "start_car", "unlock_door", "unlock_phone", "spend_money", "send_message", "send_sms", "make_call",
        "save_calendar_event", "change_calendar_event", "delete_calendar_event", "control_device", "pc_command",
    )

    fun risk(tool: String): Risk = when (tool) {
        in LOW_RISK -> Risk.LOW
        else -> Risk.HIGH // unknown tools count as high risk
    }

    /** Whether a non-Claude model's call to [tool] may run. Only known low-risk tools may. */
    fun mayRun(tool: String): Boolean = risk(tool) != Risk.HIGH

    /** What the model is told when a call isn't run. */
    fun refusal(tool: String): String =
        if (tool in NEEDS_YES) "Not done: \"$tool\" needs the user's yes on the phone, and this model can't ask for it. Tell the user to do it with Claude or by hand."
        else "Not done: \"$tool\" isn't something Ruby can do with this model."

    /** The tools offered to a non-Claude model: only those it may run. */
    fun offered(tools: List<String>): List<String> = tools.filter(::mayRun)

    /** What Claude can do that other models can't; shown as unavailable on the models screen. */
    val CLAUDE_ONLY = listOf("Web search", "Skills", "Thinking")
}
