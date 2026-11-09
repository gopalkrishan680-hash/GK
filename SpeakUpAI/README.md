# SpeakUp AI: AI English speaking coach (Android)

An Android app where you **speak English out loud** to an AI character. It listens in real time,
replies with its voice, and **corrects your mistakes as you talk**, with simple Hinglish explanations.
It works **free with a Google Gemini API key** (default), or with Claude (Anthropic, paid) for the best corrections.

## Features

| Feature | What it does |
|---|---|
| 🎯 **Speaking level test** | 1 self-assessment question + 8 spoken questions (easy → hard). The AI grades grammar, vocabulary, fluency (uses your speaking speed) and pronunciation, and gives you a CEFR level **A1–C2**, strengths, weaknesses and focus areas. |
| 🧠 **Level-adaptive coaching** | Beginners get simple words, short sentences and sentence starters. Intermediate learners get natural English and up to 3 corrections. Advanced learners get idioms, nuance and challenging follow-ups. |
| 🎙️ **Live voice conversation** | Live transcript while you speak, the AI replies out loud, and **hands-free mode** listens again automatically, like a phone call. |
| ✍️ **Instant corrections** | Every message gets: wrong → right with an explanation (Hinglish or English), a "better way to say it", a score out of 100, and praise. |
| 👩🏼 **4 AI characters** | Emma (American friend), Oliver (British teacher), Priya (Indian mentor), Jake (interview coach), each with a matching accent. |
| 🎭 **11 role-plays** | Job interview, restaurant, airport, doctor, shopping, office meetings, phone call, making friends, debate, storytelling, IELTS speaking. |
| 🗓️ **Daily topic + word of the day** | A new conversation topic and a useful word every day, with a "practise this word" chat. |
| 🔊 **Pronunciation trainer** | Level-based sentences that target sounds Hindi speakers find hard (v/w, th, z/j…). Listen normal/slow, speak, and get a word-by-word check (green/red). Tap a red word to hear it. |
| 📘 **Vocabulary notebook** | Useful words from your chats are saved automatically. Flashcards with spaced repetition. |
| 📝 **Mistake journal + Mistake drill** | All corrections are saved. One tap starts a drill where the AI makes you practise *your own* past mistakes. |
| 📊 **Progress** | Streak, XP, daily goal, minutes, words spoken, session scores chart, and an AI session report (summary, top mistakes, tips, estimated level) after every conversation. |
| ⚙️ **Settings** | Voice accent (US/UK/India), speaking speed, Hinglish on/off, hands-free, daily goal, AI model, retake test. |
| 💡 Extras | Hint button when you're stuck, type instead of speaking, replay or slow-replay any AI message. |

## Install on your phone

1. Open the repo's **Releases** page on GitHub → **SpeakUp AI (latest build)** → download `SpeakUpAI.apk`.
   (Or **Actions** → *Build SpeakUp AI APK* → the latest run → artifact `SpeakUpAI-apk`.)
2. Open the APK. Allow "Install unknown apps" if Android asks.
3. Get a **free Gemini API key**: open https://aistudio.google.com/app/apikey, log in with your Google account,
   click **Create API key** and paste it in the app during setup. No card needed.
   (Optional: choose **Claude** instead and use an Anthropic API key from https://console.anthropic.com, which needs prepaid credits.)
4. Allow microphone permission. Speech recognition uses Google's speech service, so keep the Google app updated.

## AI providers

**Google Gemini (free, default)**
- The free tier has per-minute and per-day request limits. If you hit one, the app says so; wait a minute (or until the next day).
- The app picks the best free **Flash** model your key can use and switches to another Flash model if one hits its limit.
- On the free tier Google may use your conversations to improve its products, so don't say anything private.

**Claude (Anthropic, paid)**
- Prepaid credits. Default model **Claude Opus 5.5** (best corrections); **Claude Haiku 5.5** is faster and much cheaper.
- Prompt caching is on, and with Opus the app turns on Anthropic's server-side refusal fallback.

Switch provider anytime in **Settings → AI provider & API key**.

## Build it yourself

Requirements: Android Studio (or JDK 17 + Android SDK 35).

```bash
cd SpeakUpAI
./gradlew assembleRelease      # → app/build/outputs/apk/release/app-release.apk
```

Every push that changes `SpeakUpAI/` also builds the APK on GitHub Actions (`.github/workflows/speakup-apk.yml`).

## Code layout

```
app/src/main/java/com/gk/speakup/
  ai/AiService.kt          Common interface for both providers
  ai/GeminiService.kt      Google Gemini REST call (free tier), model auto-pick, JSON schema
  ai/ClaudeService.kt      Anthropic Java SDK call, structured JSON output, errors, caching
  ai/Prompts.kt            Tutor / examiner / report / pronunciation prompts + JSON schemas
  data/                    Profile, stats, vocab, mistakes (SharedPreferences)
  speech/SpeechManager.kt  Live speech recognition (partial results) + text-to-speech
  ui/screens/              Onboarding, LevelTest, Home, Chat, Role-plays, Pronunciation,
                           Vocab, Mistakes, Progress, Settings
```

Note: API keys are stored only on your phone (app-private storage) and are sent only to Google or Anthropic.
Don't share the APK with your key already entered.
