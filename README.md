# AndroClaw 🐾

AndroClaw is an open-source, on-device AI agent for Android. It takes over your phone to perform tasks from natural-language commands by reading the screen and performing taps, swipes, and typing — just like a human would. 

Inspired by Mobilerun, AndroClaw requires **no per-app integrations**. It uses the Android Accessibility Service to observe the UI and dispatch actions, with a vision fallback for games and WebViews.

## Features

- **Any App**: Works across any installed app using the Accessibility tree.
- **Chat-First UI**: See exactly what the agent is planning, doing, and thinking in real-time.
- **Pluggable LLMs**: Bring your own API key for OpenAI, Anthropic, or Google Gemini. 
- **Fully Local Mode**: Connect to a local Ollama instance for 100% on-device, private inference.
- **Safety First**: 
  - Explicit confirmation gates for sensitive actions (sending messages, making calls, payments, deletions).
  - Configurable allowlist/blocklist to restrict where the agent can operate.
  - No analytics, no telemetry. API keys are stored in encrypted preferences.

## Setup

1. Build and install the app using Android Studio, or from the command line:
   ```bash
   ./gradlew assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```
2. Open the app and follow the onboarding to **enable the Accessibility Service** (Settings → Accessibility → AndroClaw Agent).
3. Open **Settings** (gear icon) and configure your LLM provider.
   - For cloud models, enter your API key.
   - For local Ollama via Android Emulator, use `http://10.0.2.2:11434` as the Base URL.

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

AndroClaw has profound access to your device. By design, **it only communicates with the LLM provider you configure**. No screen data is logged to disk or sent to any other servers. We recommend using the **Allowlist Mode** in Settings to restrict the agent to specific, low-risk apps while testing.
