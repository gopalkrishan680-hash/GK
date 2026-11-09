package com.gk.speakup.data

data class Profile(
    val name: String = "",
    val goal: String = "",
    val apiKey: String = "",
    val level: String = "",            // CEFR level, blank until the level test is done
    val hinglish: Boolean = true,      // explain mistakes in Hinglish
    val personaId: String = "emma",
    val model: String = "claude-opus-5-5",
    val accent: String = "en-US",      // TTS + speech recognition locale
    val speechRate: Float = 0.95f,
    val handsFree: Boolean = true,     // auto-listen again after the tutor speaks
    val dailyGoalMin: Int = 10,
)

data class Correction(
    val original: String,
    val corrected: String,
    val explanation: String,
    val type: String,
    val at: Long = System.currentTimeMillis(),
)

data class VocabWord(
    val word: String,
    val meaning: String,
    val example: String,
    val box: Int = 0,                  // Leitner box: 0 = new, 5 = mastered
    val at: Long = System.currentTimeMillis(),
)

data class SessionRecord(
    val title: String,
    val summary: String,
    val score: Int,
    val minutes: Int,
    val at: Long = System.currentTimeMillis(),
)

data class LevelResult(
    val level: String,
    val overall: Int,
    val grammar: Int,
    val vocabulary: Int,
    val fluency: Int,
    val pronunciation: Int,
    val strengths: List<String>,
    val weaknesses: List<String>,
    val focusAreas: List<String>,
    val summary: String,
    val adviceHinglish: String,
    val at: Long = System.currentTimeMillis(),
)

data class Stats(
    val xp: Int = 0,
    val streak: Int = 0,
    val lastDay: Long = -1,            // epoch day of last practice
    val sessions: Int = 0,
    val words: Int = 0,
    val seconds: Long = 0,
    val todayDay: Long = -1,
    val todaySeconds: Long = 0,
)

data class Persona(
    val id: String,
    val name: String,
    val tagline: String,
    val personality: String,
    val accent: String,
    val emoji: String,
)

data class Scenario(
    val id: String,
    val title: String,
    val emoji: String,
    val description: String,
    val instructions: String,
    val opener: String,
)

object Catalog {
    val personas = listOf(
        Persona(
            "emma", "Emma", "Friendly American friend",
            "Warm, cheerful and curious, like a supportive friend from California. Uses casual, natural American English and laughs easily.",
            "en-US", "👩🏼",
        ),
        Persona(
            "oliver", "Oliver", "Calm British teacher",
            "Patient, polite and encouraging British teacher from London. Clear, well-structured British English with a dry sense of humour.",
            "en-GB", "👨🏻‍🏫",
        ),
        Persona(
            "priya", "Priya", "Patient Indian mentor",
            "A kind Indian corporate trainer from Bengaluru who completely understands Hindi speakers' struggles. Very patient, motivating, uses clear international English.",
            "en-IN", "👩🏽‍💼",
        ),
        Persona(
            "jake", "Jake", "Energetic interview coach",
            "Energetic, direct and motivating career coach from New York. Pushes the learner to give longer, more confident answers.",
            "en-US", "🧑🏻‍💼",
        ),
    )

    fun persona(id: String) = personas.firstOrNull { it.id == id } ?: personas.first()

    val freeTalk = Scenario(
        "free", "Free Talk", "💬", "Chat about anything - your day, hobbies, dreams.",
        "Have a relaxed, friendly conversation. Ask about the learner's life, interests and opinions. Change topics naturally when one runs out.",
        "Hi! It's so nice to talk to you. How has your day been so far?",
    )

    val scenarios = listOf(
        freeTalk,
        Scenario(
            "interview", "Job Interview", "💼", "Practise a real HR + technical style interview.",
            "Role-play: you are the interviewer at a good company and the learner is the candidate. Ask typical interview questions one at a time (tell me about yourself, strengths/weaknesses, why this job, a challenge you faced, salary expectations, questions for us). React like a real interviewer. In corrections also flag answers that sound unprofessional.",
            "Good morning, and thank you for coming in today. Please have a seat. To start, could you tell me a little about yourself?",
        ),
        Scenario(
            "restaurant", "Restaurant", "🍝", "Order food, ask questions, handle a wrong order.",
            "Role-play: you are a waiter at a busy restaurant. Take the learner's order, suggest dishes, answer questions about the menu, and at some point make a small mistake with the order so they must politely complain.",
            "Good evening, welcome to Olive Garden Bistro! Table for one? Here is the menu. Can I get you something to drink first?",
        ),
        Scenario(
            "airport", "Airport & Travel", "✈️", "Check-in, immigration, lost baggage.",
            "Role-play: you are first an airline check-in agent, later an immigration officer. Ask realistic questions (passport, luggage, purpose of visit, where you'll stay). Create a small problem like overweight baggage.",
            "Good afternoon! Where are you flying to today? May I see your passport and ticket, please?",
        ),
        Scenario(
            "doctor", "Doctor Visit", "🩺", "Describe symptoms and understand advice.",
            "Role-play: you are a friendly doctor. Ask the learner about their symptoms, how long they've had them, and give simple advice. Teach useful health vocabulary.",
            "Hello, please come in and take a seat. So, what brings you here today?",
        ),
        Scenario(
            "shopping", "Shopping", "🛍️", "Ask for sizes, bargain, return an item.",
            "Role-play: you are a shop assistant in a clothes store. Help the learner find something, talk about sizes, colours and price, and later handle a return or exchange.",
            "Hi there! Welcome in. Are you looking for anything in particular today?",
        ),
        Scenario(
            "office", "Office & Meetings", "🏢", "Small talk with colleagues, give updates, disagree politely.",
            "Role-play: you are the learner's colleague / manager. Start with small talk, then ask for a project update, then disagree politely with one of their ideas so they practise professional language.",
            "Morning! Did you have a good weekend? Oh, and before the meeting, can you give me a quick update on your project?",
        ),
        Scenario(
            "phone", "Phone Call", "📞", "Customer care call, booking, complaining.",
            "Role-play: you are a customer service agent on a phone call. The learner has a problem with an internet connection or a wrong bill. Use typical phone phrases (Could you hold on a moment, May I have your account number...).",
            "Thank you for calling SkyNet customer support, my name is Sam. How may I help you today?",
        ),
        Scenario(
            "smalltalk", "Making Friends", "🤝", "Meet someone new at a party.",
            "Role-play: you meet the learner for the first time at a friend's party. Make small talk: where they're from, work, hobbies, films, food, travel. Teach them how to keep a conversation going.",
            "Hey! I don't think we've met. I'm a friend of Rahul's. How do you know him?",
        ),
        Scenario(
            "debate", "Debate", "⚖️", "Defend your opinion on hot topics.",
            "Pick an interesting debate topic suitable for their level (e.g. work from home vs office, online vs offline classes, city vs village life). Take the opposite side of the learner, push back politely, and ask them to justify their opinion with reasons and examples. Teach linking words (however, on the other hand, in my opinion).",
            "Let's have a friendly debate! Here's the topic: working from home is better than working in an office. Do you agree or disagree, and why?",
        ),
        Scenario(
            "story", "Storytelling", "📖", "Tell stories using past tenses.",
            "Ask the learner to tell stories from their life (a trip, a funny incident, their best day, a festival). Ask follow-up questions (what happened next? how did you feel?). Focus corrections on past tenses and linking words (then, after that, suddenly, finally).",
            "I love stories! Tell me about a trip you really enjoyed. Where did you go, and what happened?",
        ),
        Scenario(
            "ielts", "IELTS Speaking", "🎓", "Exam-style Part 1, 2 and 3 questions.",
            "Act as an IELTS speaking examiner. Do Part 1 (short personal questions), then Part 2 (give a cue card topic and ask them to speak for 1-2 minutes), then Part 3 (abstract discussion). In praise, mention an estimated band score for that answer.",
            "Good morning. My name is Oliver and I'll be your examiner today. Can you tell me your full name, please? And where are you from?",
        ),
    )

    val mistakeDrill = Scenario(
        "drill", "Mistake Drill", "🎯", "Practise the mistakes you made before.",
        "This is a focused drill on the learner's own past mistakes (listed below). Ask questions designed so that they must use those exact structures correctly. Praise them when they get it right.",
        "Let's work on the mistakes you've made before, so you never make them again. Ready? First question coming up!",
    )

    val dailyTopics = listOf(
        "your favourite food and how to cook it", "a movie you watched recently", "your dream job",
        "your best friend", "a festival you love", "your morning routine", "a place you want to visit",
        "a skill you want to learn", "your childhood memories", "cricket or any sport you like",
        "how you spend your weekends", "a book or web series you like", "your hometown",
        "your biggest achievement", "something that made you happy this week",
    )
}
