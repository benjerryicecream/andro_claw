# AndroClaw 🐾

AndroClaw is an open-source, on-device AI agent for Android. It takes over your phone to perform tasks from natural-language commands by reading the screen and performing taps, swipes, and typing — just like a human would. 

Inspired by Mobilerun, AndroClaw requires **no per-app integrations**. It uses the Android Accessibility Service to observe the UI and dispatch actions, with a vision fallback for games and WebViews.

## Features

- **Any App**: Works across any installed app using the Accessibility tree.
- **Chat-First UI**: See exactly what the agent is planning, doing, and thinking in real-time.
- **Pluggable LLMs**: Bring your own API key for OpenAI, Anthropic, or Google Gemini. 
- **Self-hosted inference**: Connect to your own Ollama server — nothing leaves your network.
- **Safety First**: 
  - Explicit confirmation gates for sensitive actions (sending messages, making calls, payments, deletions).
  - Configurable allowlist/blocklist to restrict where the agent can operate.
  - No analytics, no telemetry. API keys are stored in encrypted preferences.

## Setup

1. Build and install the app using Android Studio, or from the command line:
   ```bash
   # macOS / Linux
   ./gradlew assembleDebug
   # Windows
   .\gradlew.bat assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```
   First-time users: if the Gradle wrapper (`gradlew` / `gradlew.bat`) is missing in a fresh clone, open the project in Android Studio once — the initial sync generates it automatically.
2. Open the app and follow the onboarding to **enable the Accessibility Service** (Settings → Accessibility → AndroClaw Agent). If the toggle doesn't appear on a sideloaded build, open the app's info page (long-press the icon → App info), enable **"Allow restricted settings"**, then try again.
3. Open **Settings** (gear icon) and configure your LLM provider.
   - For cloud models, enter your API key.
   - For self-hosted Ollama, point the Base URL at your server. From the Android Emulator, `http://10.0.2.2:11434` reaches Ollama running on the host machine.
   - For a phone connected over USB, run `adb reverse tcp:11434 tcp:11434` on the host machine, then use `http://localhost:11434` as the Base URL in the app. This keeps the connection on localhost, which the app's network security config already permits — no LAN IP over plain HTTP needed.
4. If the screenshot/vision fallback is used, grant the **MediaProjection / screenshot permission** when prompted — this must be re-granted on each new session; it is not a one-time grant.

## Requirements

- **Android**: Android 9.0 (API 28) or newer, building against SDK 35 (compileSdk/targetSdk 35). The screenshot/vision fallback additionally requires Android 11 (API 30)+.
- **Android Studio**: Narwhal 3 Feature Drop (2025.1.3) or later — this project pins AGP 8.13.2 and Gradle 9.5.0.
- **Kotlin**: 2.0.21.
- **JDK**: 17 (the project compiles Java and Kotlin bytecode to 17).

## Usage Examples

Type a command in the chat bar and hit Send:

- *"Turn on dark mode"*
- *"Set an alarm for 7 AM tomorrow"*
- *"Text Tom I'm running 10 minutes late"*
- *"Open Spotify and play my Liked Songs"*

If the agent attempts a sensitive action (like hitting "Send" in a messaging app), it will pause and ask for your confirmation.

## Architecture

- **Perception Layer (`perception/`)**: Converts Android `AccessibilityNodeInfo` trees into a compact JSON state. Falls back to `MediaProjection` screenshots for opaque UIs.
- **Agent Loop (`agent/`)**: Feeds the UI state and goal to the LLM. Parses the JSON response into an `AgentAction` (click, set_text, scroll, etc.).
- **Execution**: Maps the `AgentAction` back to accessibility node clicks or global gestures. Polls the UI tree hash to wait for animations/loading to settle before the next step.

## Security & Privacy

AndroClaw has profound access to your device. By design, **it only communicates with the LLM provider you configure** — but note that in cloud mode the on-screen content the agent reads is sent to that provider, including screenshots when the vision fallback is used. That can include private messages, account details, or anything else visible on screen at the time. No screen data is logged to disk or sent to any other servers. We recommend using the **Allowlist Mode** in Settings to restrict the agent to specific, low-risk apps while testing.

## Safety

The agent passes on-screen text to the LLM on every step, including content from web pages and incoming messages. That text is **untrusted input** and could contain instructions designed to steer the agent's behavior (prompt injection). Confirmation gates and the allow/blocklist reduce this risk but do not eliminate it. We recommend testing in an emulator or with a throwaway account before using AndroClaw with sensitive apps or data.
