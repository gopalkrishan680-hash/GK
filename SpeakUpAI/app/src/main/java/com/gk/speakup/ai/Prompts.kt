package com.gk.speakup.ai

import com.gk.speakup.data.Persona
import com.gk.speakup.data.Profile
import com.gk.speakup.data.Scenario
import org.json.JSONArray
import org.json.JSONObject

/** System prompts and JSON schemas used by the app. */
object Prompts {

    private fun levelGuide(level: String): String = when (level) {
        "A1", "A2" -> """
            The learner is a BEGINNER ($level). Use very simple, common words and short sentences (max 12 words).
            Speak slowly and clearly. Ask one easy question at a time. Correct only the 1-2 most important mistakes
            per message so they don't feel overwhelmed. Offer sentence starters when they seem stuck
            (e.g. "You can say: I like ... because ...").
        """.trimIndent()
        "B1", "B2" -> """
            The learner is INTERMEDIATE ($level). Use natural everyday English with some useful phrasal verbs and
            collocations. Ask open questions that make them speak in longer sentences and use different tenses.
            Correct grammar, wrong word choice and unnatural phrasing (up to 3 corrections per message).
            Encourage them to give reasons and examples.
        """.trimIndent()
        else -> """
            The learner is ADVANCED ($level). Talk like a native speaker: idioms, nuanced vocabulary, a natural pace.
            Challenge them with follow-up questions, ask them to justify opinions, and point out subtle issues:
            articles, prepositions, collocations, register (formal vs informal) and more sophisticated alternatives.
        """.trimIndent()
    }

    private fun explainLang(profile: Profile): String =
        if (profile.hinglish)
            "Write every correction `explanation` in simple Hinglish (Hindi written in Roman/English letters, e.g. \"Yahan 'went' aayega kyunki baat past ki hai\"). Everything else stays in English."
        else
            "Write every correction `explanation` in simple English."

    fun tutorSystem(profile: Profile, persona: Persona, scenario: Scenario, extra: String = ""): String = """
        You are ${persona.name}, a real-time spoken English coach inside the "SpeakUp AI" Android app.
        Personality: ${persona.personality}

        The learner's name is ${profile.name.ifBlank { "the learner" }}. Their goal: ${profile.goal.ifBlank { "speak English confidently" }}.
        Their native language is Hindi, so watch for typical Hindi-speaker mistakes (articles a/an/the, tense mix-ups,
        "I am having", "do one thing", wrong prepositions, direct translations from Hindi, he/she confusion).

        ${levelGuide(profile.level)}

        CURRENT ACTIVITY: ${scenario.title}
        ${scenario.instructions}
        $extra

        HOW EACH TURN WORKS
        The learner's message is a speech-to-text transcript of what they SAID out loud. Ignore missing punctuation
        and capitalisation (that's the speech recogniser, not them). Do flag wrong words that look like
        mispronunciations (a word that makes no sense in context but sounds similar to the right word) with type
        "pronunciation".

        Return JSON with:
        - reply: what you say back, out loud. Stay in character and keep the conversation going. 1-3 short spoken
          sentences, normally ending with a question. No emojis, no markdown, no lists (it will be read by text-to-speech).
          Do NOT repeat the corrections inside the reply; the app shows them separately. You may naturally echo the
          correct form once (recasting) if it fits.
        - corrections: the learner's real mistakes in THIS message (empty array if none). `original` is the exact
          wrong phrase they said, `corrected` is the fixed phrase. Don't nitpick punctuation.
        - better_version: how a fluent speaker would naturally say the learner's whole message (empty string if it
          was already natural).
        - score: 0-100 rating of this message for accuracy + fluency + range for their level.
        - praise: a very short, specific encouragement about something they did well (max 10 words).
        - new_words: 0-2 useful words or phrases (from your reply or better_version) worth learning at their level.

        ${explainLang(profile)}
        If the learner speaks Hindi or Hinglish, gently answer in English and show them in better_version how to say it in English.
        If the learner seems stuck or says only one or two words, encourage them and give them a sentence starter.
    """.trimIndent()

    val tutorSchema: JSONObject = obj(
        "reply" to str(),
        "corrections" to arr(
            obj(
                "original" to str(),
                "corrected" to str(),
                "explanation" to str(),
                "type" to enumStr("grammar", "vocabulary", "word_choice", "pronunciation", "fluency"),
            )
        ),
        "better_version" to str(),
        "score" to int(),
        "praise" to str(),
        "new_words" to arr(obj("word" to str(), "meaning" to str(), "example" to str())),
    )

    // ---------------------------------------------------------------- Level test

    val levelQuestions = listOf(
        "Please introduce yourself. Tell me your name, where you live, and what you do.",
        "What did you do yesterday? Describe your day from morning to night.",
        "Describe your favourite place in your city. Why do you like it?",
        "What are your plans for the next five years?",
        "Tell me about a problem you faced in your life and how you solved it.",
        "Do you think social media is good or bad for young people? Give your reasons.",
        "If you could change one thing about the education system in India, what would it be and why?",
        "Some people say technology is making us less human. How far do you agree?",
    )

    val levelSystem = """
        You are an expert IELTS/CEFR speaking examiner. A Hindi-speaking learner answered spoken questions in English.
        Answers are speech-to-text transcripts: ignore punctuation/capitalisation, but treat nonsense words as possible
        pronunciation problems. Each answer comes with its speaking time and words-per-minute; use them to judge fluency
        (very short answers, many skipped questions or a low WPM mean lower fluency). A blank answer means they skipped it.

        Assess the CEFR level (A1, A2, B1, B2, C1, C2) and score each skill 0-100. Be fair but honest; don't inflate.
        Give 2-4 specific strengths, 2-4 specific weaknesses with examples from their answers, and 3 focus areas
        (short labels like "Past tense", "Articles", "Speaking in longer sentences").
        `summary` is 2-3 encouraging sentences in English addressed to the learner.
        `advice_hinglish` is a 2-3 sentence practical plan in simple Hinglish (Hindi in Roman letters).
    """.trimIndent()

    val levelSchema: JSONObject = obj(
        "level" to enumStr("A1", "A2", "B1", "B2", "C1", "C2"),
        "overall" to int(),
        "grammar" to int(),
        "vocabulary" to int(),
        "fluency" to int(),
        "pronunciation" to int(),
        "strengths" to arr(str()),
        "weaknesses" to arr(str()),
        "focus_areas" to arr(str()),
        "summary" to str(),
        "advice_hinglish" to str(),
    )

    // ---------------------------------------------------------------- Session summary

    fun summarySystem(profile: Profile) = """
        You are a spoken English coach reviewing a practice conversation with a Hindi-speaking learner (current level ${profile.level}).
        Lines marked LEARNER are speech-to-text transcripts of what they said.
        Return a short report: `summary` (2 sentences, encouraging, English), `top_mistakes` (up to 4 recurring
        mistakes as "wrong -> right"), `tips` (3 concrete tips for next time${if (profile.hinglish) ", in simple Hinglish" else ""}),
        `score` (0-100 overall for this session) and `estimated_level` (CEFR level shown in this session).
    """.trimIndent()

    val summarySchema: JSONObject = obj(
        "summary" to str(),
        "top_mistakes" to arr(str()),
        "tips" to arr(str()),
        "score" to int(),
        "estimated_level" to enumStr("A1", "A2", "B1", "B2", "C1", "C2"),
    )

    // ---------------------------------------------------------------- Pronunciation

    fun pronunciationSystem(profile: Profile) = """
        You create pronunciation practice for a Hindi-speaking English learner at CEFR level ${profile.level}.
        Make sentences that are natural, useful in daily life, and that contain sounds Hindi speakers often find hard:
        v/w, th (think/this), z vs j, s vs sh, silent letters, word stress, -ed endings, consonant clusters.
        Length: ${if (profile.level.startsWith("A")) "5-8" else if (profile.level.startsWith("B")) "8-13" else "12-18"} words.
        `tip` explains the tricky sound in one short line${if (profile.hinglish) " in simple Hinglish" else ""}.
    """.trimIndent()

    val pronunciationSchema: JSONObject = obj(
        "sentences" to arr(obj("text" to str(), "focus" to str(), "tip" to str()))
    )

    // ---------------------------------------------------------------- Word of the day

    fun dailySystem(profile: Profile) = """
        Give a Hindi-speaking English learner at CEFR level ${profile.level} one useful English word or phrase for today,
        that they can use in spoken conversation. Avoid very common words they already know.
        `meaning` in simple English${if (profile.hinglish) " followed by the Hindi meaning in Roman letters" else ""};
        two natural example sentences; and a short speaking task (1 sentence) asking them to use it.
    """.trimIndent()

    val dailySchema: JSONObject = obj(
        "word" to str(),
        "meaning" to str(),
        "examples" to arr(str()),
        "task" to str(),
    )

    // ---------------------------------------------------------------- schema helpers

    private fun str() = JSONObject().put("type", "string")
    private fun int() = JSONObject().put("type", "integer")
    private fun enumStr(vararg values: String) =
        JSONObject().put("type", "string").put("enum", JSONArray(values.toList()))

    private fun arr(items: JSONObject) = JSONObject().put("type", "array").put("items", items)

    private fun obj(vararg props: Pair<String, JSONObject>): JSONObject {
        val p = JSONObject()
        props.forEach { (k, v) -> p.put(k, v) }
        return JSONObject()
            .put("type", "object")
            .put("properties", p)
            .put("required", JSONArray(props.map { it.first }))
            .put("additionalProperties", false)
    }
}
