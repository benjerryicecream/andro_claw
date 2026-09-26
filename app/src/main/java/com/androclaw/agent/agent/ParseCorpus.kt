package com.androclaw.agent.agent

/**
 * Parser-sanity corpus. Every real regression the planner must keep passing is
 * listed here with exact expected steps, the expected [PlanGoal] (for exit
 * verification), and the expected sensitive-action confirmation flag. The
 * corpus is scored by [CommandPlanScorer] so parsing quality is measured, not
 * eyeballed — see app/src/test CommandPlanParseTest.
 *
 * Cases marked "regress" are standing regression tests from real failures;
 * the rest broaden coverage of step/goal/confirmation correctness.
 */
object ParseCorpus {

    data class Case(
        val id: String,
        val command: String,
        val expectedSteps: List<PlanStep>,
        val expectedGoal: PlanGoal?,
        val expectedNeedsConfirmation: Boolean,
        val viaFastPath: Boolean = false
    )

    val cases: List<Case> = listOf(
        // --- Standing regression commands (real failures) ---
        Case(
            id = "regress-chrome-hilo",
            command = "open chrome and search for the weather in Hilo",
            expectedSteps = listOf(
                PlanStep.OpenApp("chrome"),
                PlanStep.WebSearch("weather in Hilo", "https://www.google.com/search?q=weather+in+Hilo")
            ),
            expectedGoal = PlanGoal(PlanGoal.SCREEN_SHOWS, "Hilo"),
            expectedNeedsConfirmation = false
        ),
        Case(
            id = "regress-store-temu",
            command = "open play store and download temu",
            expectedSteps = listOf(
                PlanStep.OpenApp("play store"),
                PlanStep.ScopedSearch("play store", "temu", installGoal = true)
            ),
            expectedGoal = PlanGoal(PlanGoal.APP_INSTALLED, "temu"),
            expectedNeedsConfirmation = true
        ),
        Case(
            id = "regress-youtube-cat",
            command = "open youtube and search for cat videos",
            expectedSteps = listOf(
                PlanStep.OpenApp("youtube"),
                PlanStep.ScopedSearch("youtube", "cat videos", installGoal = false)
            ),
            expectedGoal = PlanGoal(PlanGoal.SCREEN_SHOWS, "cat videos"),
            expectedNeedsConfirmation = false
        ),

        // --- Fast-path (exact ^open <single app>$): regex only, zero LLM tokens ---
        Case(
            id = "fastpath-settings",
            command = "open settings",
            expectedSteps = listOf(PlanStep.OpenApp("settings")),
            expectedGoal = PlanGoal(PlanGoal.APP_IN_FOREGROUND, "settings"),
            expectedNeedsConfirmation = false,
            viaFastPath = true
        ),
        Case(
            id = "fastpath-gmail",
            command = "open gmail",
            expectedSteps = listOf(PlanStep.OpenApp("gmail")),
            expectedGoal = PlanGoal(PlanGoal.APP_IN_FOREGROUND, "gmail"),
            expectedNeedsConfirmation = false,
            viaFastPath = true
        ),
        Case(
            id = "fastpath-spotify",
            command = "open spotify",
            expectedSteps = listOf(PlanStep.OpenApp("spotify")),
            expectedGoal = PlanGoal(PlanGoal.APP_IN_FOREGROUND, "spotify"),
            expectedNeedsConfirmation = false,
            viaFastPath = true
        ),

        // --- Structured-planner coverage ---
        Case(
            id = "web-search-generic",
            command = "search for best coffee shops",
            expectedSteps = listOf(
                PlanStep.WebSearch("best coffee shops", "https://www.google.com/search?q=best+coffee+shops")
            ),
            expectedGoal = PlanGoal(PlanGoal.SCREEN_SHOWS, "coffee shops"),
            expectedNeedsConfirmation = false
        ),
        Case(
            id = "browser-open-then-web-search",
            command = "open google and search for russian space probe",
            expectedSteps = listOf(
                PlanStep.OpenApp("google"),
                PlanStep.WebSearch("russian space probe", "https://www.google.com/search?q=russian+space+probe")
            ),
            expectedGoal = PlanGoal(PlanGoal.SCREEN_SHOWS, "space probe"),
            expectedNeedsConfirmation = false
        ),
        Case(
            id = "uninstall-app",
            command = "uninstall temu",
            expectedSteps = listOf(PlanStep.SubTask("uninstall temu")),
            expectedGoal = PlanGoal(PlanGoal.APP_REMOVED, "temu"),
            expectedNeedsConfirmation = true
        ),
        Case(
            id = "download-without-store-named",
            command = "download the calculator app",
            expectedSteps = listOf(
                PlanStep.ScopedSearch("play store", "calculator app", installGoal = true)
            ),
            expectedGoal = PlanGoal(PlanGoal.APP_INSTALLED, "calculator app"),
            expectedNeedsConfirmation = true
        ),
        Case(
            id = "site-task-second-step",
            command = "open youtube and play lofi playlist",
            expectedSteps = listOf(
                PlanStep.OpenApp("youtube"),
                PlanStep.SubTask("play lofi playlist")
            ),
            expectedGoal = PlanGoal(PlanGoal.SCREEN_SHOWS, "lofi"),
            expectedNeedsConfirmation = false
        ),
        Case(
            id = "send-message-sensitive",
            command = "send a text message to mom",
            expectedSteps = listOf(PlanStep.SubTask("send a text message to mom")),
            expectedGoal = PlanGoal(PlanGoal.SCREEN_SHOWS, "sent"),
            expectedNeedsConfirmation = true
        ),
        Case(
            id = "system-setting-sensitive",
            command = "set night light from 9pm to 6am",
            expectedSteps = listOf(PlanStep.SubTask("set night light from 9pm to 6am")),
            expectedGoal = PlanGoal(PlanGoal.SCREEN_SHOWS, "night light"),
            expectedNeedsConfirmation = true
        )
    )
}