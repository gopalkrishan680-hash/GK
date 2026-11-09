# SpeakUp AI: AI English speaking coach (Android)

An Android app where you **speak English out loud** to an AI character. It listens in real time,
replies with its voice, and **corrects your mistakes as you talk**, with simple Hinglish explanations.
It runs on Claude (Anthropic).

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
3. You need an **Anthropic API key**: sign up at https://console.anthropic.com, add some credits under Billing,
   then go to **API Keys → Create Key**. Paste the key in the app during setup.
4. Allow microphone permission. Speech recognition uses Google's speech service, so keep the Google app updated.

## Cost / model

- Default model: **Claude Opus 5.5** (best corrections). Switch to **Claude Haiku 5.5** in Settings for faster and much cheaper replies.
- Conversation turns run at low effort so replies come back quickly. The level test grading runs at medium effort.
- Prompt caching is on, so long conversations cost less.
- With Opus, the app turns on Anthropic's server-side refusal fallback: if a safety filter declines a message, the API automatically retries on another model.

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
  ai/ClaudeService.kt      Anthropic Java SDK call, structured JSON output, errors, caching
  ai/Prompts.kt            Tutor / examiner / report / pronunciation prompts + JSON schemas
  data/                    Profile, stats, vocab, mistakes (SharedPreferences)
  speech/SpeechManager.kt  Live speech recognition (partial results) + text-to-speech
  ui/screens/              Onboarding, LevelTest, Home, Chat, Role-plays, Pronunciation,
                           Vocab, Mistakes, Progress, Settings
```

Note: the API key is stored only on your phone (app-private storage) and is sent only to Anthropic.
Don't share the APK with your key already entered.
