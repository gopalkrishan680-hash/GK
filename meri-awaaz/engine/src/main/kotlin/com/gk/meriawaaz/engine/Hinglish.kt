package com.gk.meriawaaz.engine

/**
 * Turns Hindi typed in Latin letters ("aaj hum baat karenge") into Devanagari
 * ("आज हम बात करेंगे") so that a Hindi speech model can read it.
 *
 * Roman Hindi has no fixed spelling and drops information (long/short "a", dental/retroflex
 * "t" and "d", nasal vowels), so this is a best guess: a dictionary of common words first,
 * spelling rules for everything else. Text already in Devanagari passes through unchanged.
 */
object Hinglish {

    fun toDevanagari(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isAsciiLetter() -> {
                    var j = i
                    while (j < text.length && (text[j].isAsciiLetter() || (text[j] == '\'' && j + 1 < text.length && text[j + 1].isAsciiLetter()))) j++
                    out.append(word(text.substring(i, j)))
                    i = j
                }
                c in '0'..'9' -> {
                    var j = i
                    while (j < text.length && text[j] in '0'..'9') j++
                    val digits = text.substring(i, j)
                    out.append(if (digits.length <= 9) number(digits.toLong()) else digits.map { ONES[it - '0'] }.joinToString(" "))
                    i = j
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

    private fun word(raw: String): String {
        val w = raw.lowercase().replace("'", "")
        LEXICON[w]?.let { return it }
        if (raw.length in 2..4 && raw.all { it in 'A'..'Z' }) return raw.map { LETTERS[it - 'A'] }.joinToString(" ")
        // Plural / oblique endings of dictionary words: "baaton", "ladkiyan", "logo".
        for ((suffix, ending) in SUFFIXES) {
            if (w.length > suffix.length + 1 && w.endsWith(suffix)) {
                val stem = LEXICON[w.dropLast(suffix.length)] ?: continue
                if (stem.last() in CONSONANT_RANGE || stem.last() == '़') return stem + ending
            }
        }
        return spell(w)
    }

    private class Unit(val text: String, val consonant: Boolean, val roman: String)

    /** Rule-based spelling of one lower-case word. */
    private fun spell(w: String): String {
        // 1. Cut into consonant and vowel units, longest match first.
        val units = ArrayList<Unit>()
        var i = 0
        while (i < w.length) {
            var matched = false
            for (len in 3 downTo 1) {
                if (i + len > w.length) continue
                val piece = w.substring(i, i + len)
                val cons = CONSONANTS[piece]
                if (cons != null) {
                    units.add(Unit(cons, true, piece)); i += len; matched = true; break
                }
                if (piece in VOWELS) {
                    units.add(Unit(piece, false, piece)); i += len; matched = true; break
                }
            }
            if (!matched) i++
        }
        if (units.isEmpty()) return w

        // 2. Nasal endings: "kitabon" -> ों, "baaten" -> ें.
        var nasalEnd = ""
        if (units.size >= 4 && units.last().roman == "n" && !units[units.size - 2].consonant) {
            val v = units[units.size - 2].roman
            if (v == "o" || v == "e" || v == "ei") {
                units.removeAt(units.size - 1)
                nasalEnd = "ं"
            }
        }

        // 3. Verb and noun endings whose "a" is long: dabana, sikhane, kahani, batao, banaya, dikhata.
        val n = units.size
        fun lengthen(at: Int) {
            if (at >= 3 && units[at].roman == "a") units[at] = Unit("aa", false, "aa")
        }
        if (n >= 2 && units[n - 1].roman == "o") lengthen(n - 2)
        if (n >= 3 && !units[n - 1].consonant && units[n - 2].consonant) {
            val c = units[n - 2].roman
            val v = units[n - 1].roman
            if ((c == "n" || c == "t") && v in setOf("a", "e", "i") || (c == "y" && v == "a")) lengthen(n - 3)
        }

        // 4. Write the units out.
        val out = StringBuilder()
        for (k in units.indices) {
            val u = units[k]
            val prev = units.getOrNull(k - 1)
            val next = units.getOrNull(k + 1)
            val last = k == units.size - 1
            if (!u.consonant) {
                var v = u.roman
                if (last && prev != null && prev.consonant) {
                    // Word-final short vowels are almost always long in Hindi: "mera", "pani", "guru".
                    v = when (v) { "a" -> "aa"; "i" -> "ee"; "u" -> "oo"; else -> v }
                }
                val forms = VOWELS.getValue(v)
                out.append(if (prev != null && prev.consonant) forms.second else forms.first)
                continue
            }
            if (prev != null && prev.consonant) {
                // Two consonants in a row: nasal, real conjunct, or just a silent "a" between them?
                val p = prev.roman
                val c = u.roman
                val start = k == 1
                val nasal = when {
                    start || p == c -> false
                    p == "n" -> c !in NO_ANUSVARA_AFTER_N && !(c == "t" && next != null && !next.consonant && k + 1 == units.size - 1)
                    p == "m" -> c in LABIALS
                    else -> false
                }
                when {
                    nasal -> {
                        out.setLength(out.length - prev.text.length)
                        out.append('ं')
                    }
                    start || last || c in GLIDES || (p == c && p != "n") -> out.append('्')
                }
            }
            out.append(u.text)
        }
        return out.append(nasalEnd).toString()
    }

    // ---- numbers -------------------------------------------------------------------------

    private fun number(n: Long): String {
        if (n < 100) return ONES[n.toInt()]
        val parts = ArrayList<String>()
        var rest = n
        for ((value, name) in listOf(10_000_000L to "करोड़", 100_000L to "लाख", 1000L to "हज़ार", 100L to "सौ")) {
            val q = rest / value
            if (q > 0) {
                parts.add(number(q) + " " + name)
                rest %= value
            }
        }
        if (rest > 0) parts.add(ONES[rest.toInt()])
        return parts.joinToString(" ")
    }

    private val ONES = ("शून्य एक दो तीन चार पाँच छह सात आठ नौ दस ग्यारह बारह तेरह चौदह पंद्रह सोलह सत्रह अठारह उन्नीस " +
        "बीस इक्कीस बाईस तेईस चौबीस पच्चीस छब्बीस सत्ताईस अट्ठाईस उनतीस तीस इकतीस बत्तीस तैंतीस चौंतीस पैंतीस छत्तीस सैंतीस अड़तीस उनतालीस " +
        "चालीस इकतालीस बयालीस तैंतालीस चवालीस पैंतालीस छियालीस सैंतालीस अड़तालीस उनचास पचास इक्यावन बावन तिरपन चौवन पचपन छप्पन सत्तावन अट्ठावन उनसठ " +
        "साठ इकसठ बासठ तिरसठ चौंसठ पैंसठ छियासठ सड़सठ अड़सठ उनहत्तर सत्तर इकहत्तर बहत्तर तिहत्तर चौहत्तर पचहत्तर छिहत्तर सतहत्तर अठहत्तर उनासी " +
        "अस्सी इक्यासी बयासी तिरासी चौरासी पचासी छियासी सत्तासी अट्ठासी नवासी नब्बे इक्यानवे बानवे तिरानवे चौरानवे पचानवे छियानवे सत्तानवे अट्ठानवे निन्यानवे").split(" ")

    private val LETTERS = "ए बी सी डी ई एफ़ जी एच आई जे के एल एम एन ओ पी क्यू आर एस टी यू वी डब्ल्यू एक्स वाई ज़ेड".split(" ")

    // ---- spelling tables -----------------------------------------------------------------

    private val CONSONANT_RANGE = 'क'..'ह'

    private val CONSONANTS = mapOf(
        "chh" to "छ", "cch" to "च्छ", "ksh" to "क्ष", "kh" to "ख", "gh" to "घ", "ch" to "च", "jh" to "झ", "th" to "थ", "dh" to "ध",
        "ph" to "फ", "bh" to "भ", "sh" to "श", "k" to "क", "g" to "ग", "j" to "ज", "t" to "त", "d" to "द",
        "n" to "न", "p" to "प", "b" to "ब", "m" to "म", "y" to "य", "r" to "र", "l" to "ल", "v" to "व",
        "w" to "व", "s" to "स", "h" to "ह", "f" to "फ़", "z" to "ज़", "q" to "क़", "c" to "क", "x" to "क्स",
    )

    /** Roman vowel -> (independent letter, sign after a consonant). */
    private val VOWELS = mapOf(
        "aa" to ("आ" to "ा"), "ai" to ("ऐ" to "ै"), "au" to ("औ" to "ौ"), "ou" to ("औ" to "ौ"),
        "ee" to ("ई" to "ी"), "ii" to ("ई" to "ी"), "oo" to ("ऊ" to "ू"), "uu" to ("ऊ" to "ू"),
        "ei" to ("ए" to "े"), "a" to ("अ" to ""), "i" to ("इ" to "ि"), "u" to ("उ" to "ु"),
        "e" to ("ए" to "े"), "o" to ("ओ" to "ो"),
    )

    private val GLIDES = setOf("y", "r", "v", "w")
    private val LABIALS = setOf("p", "b", "ph", "bh")
    private val NO_ANUSVARA_AFTER_N = setOf("y", "r", "v", "w", "h", "l", "m", "n")

    private val SUFFIXES = listOf("on" to "ों", "o" to "ों", "en" to "ें", "ein" to "ें")

    /** Common Hindi words (and English words Hindi speakers use) in their usual Roman spellings. */
    private val LEXICON: Map<String, String> = HashMap<String, String>().apply {
        val entries = """
        main मैं|mai मैं|mein में|me में|hum हम|ham हम|tum तुम|aap आप|ap आप|tu तू|yeh यह|ye ये|yah यह|woh वह|wo वो|voh वह|vo वो
        mera मेरा|meri मेरी|mere मेरे|hamara हमारा|hamari हमारी|hamare हमारे|humara हमारा|humari हमारी|humare हमारे
        tumhara तुम्हारा|tumhari तुम्हारी|tumhare तुम्हारे|aapka आपका|aapki आपकी|aapke आपके|apna अपना|apni अपनी|apne अपने
        iska इसका|iski इसकी|iske इसके|uska उसका|uski उसकी|uske उसके|inka इनका|unka उनका|unki उनकी|unke उनके
        mujhe मुझे|mujhko मुझको|mujh मुझ|tumhe तुम्हें|tumhen तुम्हें|tujhe तुझे|hume हमें|humein हमें|hamein हमें|hamen हमें|unhe उन्हें|unhen उन्हें|inhe इन्हें
        usse उससे|isse इससे|unse उनसे|inse इनसे|mujhse मुझसे|tumse तुमसे|aapse आपसे|humse हमसे|use उसे|ise इसे|usko उसको|isko इसको|unko उनको|inko इनको
        is इस|us उस|in इन|un उन|unhone उन्होंने|inhone इन्होंने|maine मैंने|tumne तुमने|humne हमने|aapne आपने|usne उसने|isne इसने|kisne किसने|kisi किसी|kis किस
        hai है|hain हैं|hein हैं|hu हूँ|hun हूँ|hoon हूँ|ho हो|tha था|thi थी|the थे|thin थीं|hoga होगा|hogi होगी|honge होंगे|hota होता|hoti होती|hote होते|hua हुआ|hui हुई|hue हुए|huye हुए
        ka का|ki की|ke के|ko को|se से|par पर|pe पे|ne ने|tak तक|liye लिए|liya लिया|saath साथ|sath साथ|bina बिना|andar अंदर|bahar बाहर|upar ऊपर|neeche नीचे|niche नीचे|paas पास|pas पास|door दूर|dur दूर
        aur और|ya या|lekin लेकिन|magar मगर|kyunki क्योंकि|kyonki क्योंकि|isliye इसलिए|isiliye इसीलिए|phir फिर|fir फिर|to तो|toh तो|bhi भी|hi ही|sirf सिर्फ़|bas बस|agar अगर|jab जब|tab तब|jaise जैसे|waise वैसे|vaise वैसे|aise ऐसे|kaise कैसे|kaisa कैसा|kaisi कैसी|aisa ऐसा|aisi ऐसी|jaisa जैसा|waisa वैसा
        kya क्या|kyun क्यों|kyu क्यों|kyon क्यों|kab कब|kahan कहाँ|kaha कहाँ|kahaan कहाँ|kaun कौन|kon कौन|kitna कितना|kitni कितनी|kitne कितने|kuch कुछ|kuchh कुछ|koi कोई|sab सब|sabhi सभी|har हर|kai कई|bahut बहुत|bohot बहुत|bahot बहुत|zyada ज़्यादा|jyada ज़्यादा|kam कम|thoda थोड़ा|thodi थोड़ी|thode थोड़े|itna इतना|itni इतनी|itne इतने|utna उतना|jitna जितना
        nahi नहीं|nahin नहीं|nhi नहीं|nai नहीं|na ना|mat मत|haan हाँ|han हाँ|ha हाँ|ji जी|accha अच्छा|acha अच्छा|achha अच्छा|achchha अच्छा|acchi अच्छी|achi अच्छी|achhi अच्छी|acche अच्छे|ache अच्छे|achhe अच्छे|theek ठीक|thik ठीक|sahi सही|galat ग़लत|bura बुरा|buri बुरी
        yahan यहाँ|yaha यहाँ|wahan वहाँ|waha वहाँ|vahan वहाँ|idhar इधर|udhar उधर|ab अब|abhi अभी|kabhi कभी|hamesha हमेशा|aaj आज|aj आज|kal कल|parso परसों|pehle पहले|pahle पहले|baad बाद|bad बाद|jaldi जल्दी|der देर|dheere धीरे|turant तुरंत|roz रोज़|din दिन|raat रात|subah सुबह|shaam शाम|sham शाम|saal साल|mahina महीना|hafta हफ़्ता|samay समय|waqt वक़्त|time टाइम
        ek एक|do दो|teen तीन|char चार|chaar चार|paanch पाँच|panch पाँच|chhe छह|che छह|saat सात|aath आठ|nau नौ|das दस|sau सौ|hazaar हज़ार|hazar हज़ार|lakh लाख|crore करोड़|pehla पहला|pahla पहला|pehli पहली|doosra दूसरा|dusra दूसरा|doosri दूसरी|dusri दूसरी|teesra तीसरा|dono दोनों|teeno तीनों
        karna करना|karne करने|karta करता|karti करती|karte करते|karo करो|kar कर|karke करके|kiya किया|kiye किए|ki की|karenge करेंगे|karega करेगा|karegi करेगी|karunga करूँगा|karungi करूँगी|karu करूँ|karun करूँ|karein करें|karen करें|kijiye कीजिए
        hona होना|hone होने|jana जाना|jaana जाना|jane जाने|jaane जाने|jata जाता|jaata जाता|jati जाती|jate जाते|jao जाओ|ja जा|gaya गया|gayi गई|gaye गए|gai गई|gae गए|jayega जाएगा|jayegi जाएगी|jayenge जाएँगे|jaunga जाऊँगा
        aana आना|ana आना|aane आने|aata आता|aati आती|aate आते|aao आओ|aa आ|aaya आया|aayi आई|aaye आए|aayega आएगा|aayegi आएगी|aayenge आएँगे
        dena देना|dene देने|deta देता|deti देती|dete देते|do दो|de दे|diya दिया|diye दिए|di दी|dega देगा|degi देगी|denge देंगे|dunga दूँगा|lena लेना|lene लेने|leta लेता|leti लेती|lete लेते|lo लो|le ले|liye लिए|li ली|lega लेगा|lenge लेंगे|lunga लूँगा
        dekhna देखना|dekhne देखने|dekhta देखता|dekhte देखते|dekho देखो|dekh देख|dekha देखा|dekhi देखी|dekhe देखे|dekhenge देखेंगे|dekhiye देखिए|sunna सुनना|sunne सुनने|suno सुनो|sun सुन|suna सुना|sunta सुनता|sunte सुनते|suniye सुनिए
        bolna बोलना|bolne बोलने|bolta बोलता|bolte बोलते|bolo बोलो|bol बोल|bola बोला|boli बोली|kehna कहना|kahna कहना|kehta कहता|kehte कहते|kaho कहो|keh कह|kaha कहा|kahi कही|batana बताना|batao बताओ|bataya बताया|bata बता|batata बताता|bataunga बताऊँगा|batayenge बताएँगे
        samajh समझ|samajhna समझना|samjha समझा|samjhe समझे|samjho समझो|samajhte समझते|sochna सोचना|socho सोचो|socha सोचा|soch सोच|jaanna जानना|janna जानना|jaante जानते|jante जानते|jaanta जानता|janta जानता|pata पता|maloom मालूम|malum मालूम
        chahiye चाहिए|chahie चाहिए|chahta चाहता|chahti चाहती|chahte चाहते|chaho चाहो|sakta सकता|sakti सकती|sakte सकते|sako सको|saka सका|paya पाया|pata पता|raha रहा|rahi रही|rahe रहे|rehna रहना|rahna रहना|rehta रहता|rehte रहते|raho रहो|reh रह
        banana बनाना|banane बनाने|banaya बनाया|banao बनाओ|banta बनता|bana बना|bani बनी|bane बने|banega बनेगा|chalna चलना|chalo चलो|chal चल|chala चला|chalte चलते|chalta चलता|chalega चलेगा|rukna रुकना|ruko रुको|ruk रुक|milna मिलना|mila मिला|mili मिली|mile मिले|milta मिलता|milega मिलेगा|milte मिलते
        khana खाना|khane खाने|khaya खाया|khao खाओ|peena पीना|pina पीना|piya पिया|sona सोना|uthna उठना|utho उठो|baithna बैठना|baitho बैठो|likhna लिखना|likho लिखो|likha लिखा|padhna पढ़ना|padho पढ़ो|padha पढ़ा|padhai पढ़ाई|seekhna सीखना|sikhna सीखना|seekho सीखो|seekha सीखा|khelna खेलना|kholna खोलना|kholo खोलो|lagta लगता|lagti लगती|lagte लगते|laga लगा|lagi लगी|lage लगे|lagao लगाओ|lagana लगाना|rakhna रखना|rakho रखो|rakha रखा|daalna डालना|dalna डालना|daalo डालो|dalo डालो|daala डाला|dala डाला|nikalna निकलना|nikalo निकालो|nikla निकला|badalna बदलना|badlo बदलो|badal बदल|shuru शुरू|khatam ख़त्म|khatm ख़त्म|band बंद|chalu चालू
        aadmi आदमी|admi आदमी|aurat औरत|ladka लड़का|ladki लड़की|ladke लड़के|bachcha बच्चा|baccha बच्चा|bachche बच्चे|bacche बच्चे|log लोग|dost दोस्त|bhai भाई|behen बहन|behan बहन|maa माँ|papa पापा|pitaji पिताजी|beta बेटा|beti बेटी|dada दादा|dadi दादी|chacha चाचा|parivar परिवार|ghar घर|kamra कमरा|darwaza दरवाज़ा|gaadi गाड़ी|gadi गाड़ी|sadak सड़क|shehar शहर|sheher शहर|gaon गाँव|desh देश|duniya दुनिया|bharat भारत|hindi हिंदी|naam नाम|nam नाम|kaam काम|baat बात|bat बात|baatein बातें|baaten बातें|cheez चीज़|chiz चीज़|jagah जगह|tarah तरह|tarika तरीक़ा|tareeka तरीक़ा|wajah वजह|matlab मतलब|sawal सवाल|sawaal सवाल|jawab जवाब|jawaab जवाब
        pani पानी|paani पानी|khaana खाना|chai चाय|doodh दूध|paisa पैसा|paise पैसे|rupaye रुपये|rupay रुपये|rupee रुपये|dukan दुकान|kitab किताब|kitaab किताब|kahani कहानी|kahaani कहानी|awaaz आवाज़|awaz आवाज़|aawaz आवाज़|aawaaz आवाज़|shor शोर|gaana गाना|gana गाना|tasveer तस्वीर|rang रंग|zindagi ज़िंदगी|jindagi ज़िंदगी|jeevan जीवन|pyaar प्यार|pyar प्यार|dil दिल|man मन|dimag दिमाग़|dimaag दिमाग़|aankh आँख|ankh आँख|aankhen आँखें|haath हाथ|hath हाथ|pair पैर|sar सर|chehra चेहरा|khushi ख़ुशी|khush ख़ुश|dukh दुख|dard दर्द|gussa ग़ुस्सा|darr डर|dar डर|umeed उम्मीद|sapna सपना|sapne सपने|mehnat मेहनत|kismat क़िस्मत|mauka मौक़ा|galti ग़लती|sach सच|jhooth झूठ|jhoot झूठ|mushkil मुश्किल|aasan आसान|asaan आसान|zaroori ज़रूरी|jaruri ज़रूरी|zaroor ज़रूर|jarur ज़रूर|zarurat ज़रूरत|jarurat ज़रूरत|shayad शायद|bilkul बिल्कुल|ekdum एकदम|sachmuch सचमुच|waqai वाक़ई
        bada बड़ा|badi बड़ी|bade बड़े|chhota छोटा|chota छोटा|chhoti छोटी|choti छोटी|chhote छोटे|chote छोटे|naya नया|nayi नई|nai नई|naye नए|purana पुराना|purani पुरानी|purane पुराने|lamba लंबा|lambi लंबी|lambe लंबे|saaf साफ़|saf साफ़|ganda गंदा|sundar सुंदर|khoobsurat ख़ूबसूरत|pyara प्यारा|pyari प्यारी|mazedaar मज़ेदार|mazedar मज़ेदार|maza मज़ा|maja मज़ा|badhiya बढ़िया|shaandaar शानदार|shandar शानदार|kamaal कमाल|kamal कमाल|tez तेज़|dheema धीमा|garam गरम|thanda ठंडा|bhari भारी|halka हल्का|poora पूरा|pura पूरा|poori पूरी|puri पूरी|poore पूरे|pure पूरे|aadha आधा|khali ख़ाली|akela अकेला|alag अलग|khaas ख़ास|khas ख़ास|aam आम|asli असली|nakli नक़ली|pakka पक्का|taiyaar तैयार|taiyar तैयार|tayyar तैयार
        namaste नमस्ते|namaskar नमस्कार|dhanyavaad धन्यवाद|dhanyawad धन्यवाद|shukriya शुक्रिया|swagat स्वागत|doston दोस्तों|dosto दोस्तों|bhaiyo भाइयों|kripya कृपया|maaf माफ़|maf माफ़|alvida अलविदा|chaliye चलिए|chalte चलते|aaiye आइए|aiye आइए|dekhte देखते|milte मिलते|arre अरे|are अरे|wah वाह|waah वाह|yaar यार|yar यार|oh ओह|hey हे|hello हेलो|hi हाय|bye बाय|ok ओके|okay ओके|sorry सॉरी|please प्लीज़|thank थैंक|thanks थैंक्स|you यू|welcome वेलकम
        video वीडियो|videos वीडियोज़|channel चैनल|subscribe सब्सक्राइब|like लाइक|share शेयर|comment कमेंट|comments कमेंट्स|button बटन|bell बेल|icon आइकन|notification नोटिफ़िकेशन|youtube यूट्यूब|google गूगल|whatsapp व्हाट्सऐप|instagram इंस्टाग्राम|facebook फ़ेसबुक|internet इंटरनेट|online ऑनलाइन|offline ऑफ़लाइन|phone फ़ोन|mobile मोबाइल|app ऐप|apps ऐप्स|computer कंप्यूटर|laptop लैपटॉप|camera कैमरा|screen स्क्रीन|file फ़ाइल|photo फ़ोटो|audio ऑडियो|voice वॉइस|music म्यूज़िक|game गेम|movie मूवी|film फ़िल्म|story स्टोरी|part पार्ट|episode एपिसोड|series सीरीज़|link लिंक|description डिस्क्रिप्शन|download डाउनलोड|upload अपलोड|click क्लिक|start स्टार्ट|stop स्टॉप|free फ़्री|best बेस्ट|top टॉप|new न्यू|super सुपर|simple सिंपल|easy ईज़ी|problem प्रॉब्लम|solution सॉल्यूशन|idea आइडिया|plan प्लान|trick ट्रिक|tips टिप्स|tip टिप|fact फ़ैक्ट|facts फ़ैक्ट्स|life लाइफ़|love लव|family फ़ैमिली|friend फ़्रेंड|friends फ़्रेंड्स|school स्कूल|college कॉलेज|class क्लास|teacher टीचर|student स्टूडेंट|office ऑफ़िस|job जॉब|business बिज़नेस|company कंपनी|money मनी|bank बैंक|market मार्केट|doctor डॉक्टर|hospital हॉस्पिटल|police पुलिस|sir सर|madam मैडम|mr मिस्टर|ai एआई|tv टीवी|number नंबर|minute मिनट|second सेकंड|percent परसेंट|level लेवल|power पावर|energy एनर्जी|science साइंस|technology टेक्नोलॉजी|update अपडेट|feature फ़ीचर|quality क्वालिटी|result रिज़ल्ट|test टेस्ट|example एग्ज़ाम्पल|actually एक्चुअली|basically बेसिकली|really रियली|so सो|but बट|and एंड|the द|of ऑफ़|for फ़ॉर|with विद|very वेरी|good गुड|bad बैड|nice नाइस|great ग्रेट|amazing अमेज़िंग|important इम्पॉर्टेंट|special स्पेशल|different डिफ़रेंट|same सेम|first फ़र्स्ट|last लास्ट|next नेक्स्ट|final फ़ाइनल|today टुडे|now नाउ|yes यस|no नो
        """
        for (entry in entries.split('|', '\n')) {
            val e = entry.trim()
            if (e.isEmpty()) continue
            val (roman, deva) = e.split(' ')
            putIfAbsent(roman, deva)
        }
    }
}
