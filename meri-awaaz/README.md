# Meri Awaaz — voiceover ko apni awaaz me badlo (Android, 100% offline)

Ek Android app jo kisi bhi voiceover ko aapki awaaz me convert karta hai.
Sab kaam phone ke andar hota hai: **koi API key nahi, koi server nahi, koi Colab nahi,
koi doosra app nahi, internet bhi nahi chahiye.** App koi internet permission maangta hi nahi.

## Kaise use kare

1. **Voiceover** chuno — koi bhi audio ya video file (mp3, wav, m4a, mp4 …).
2. **Apni awaaz ka sample** chuno — 30 second se 2 minute ki recording.
   Ye ek baar dena hai; app use yaad rakhta hai aur batata hai sample kitna accha hai.
3. **Convert karo** dabao. Pura voiceover aapki awaaz me ban jayega.
4. **Suno / Save / Share.**

Output 22.05 kHz mono WAV hota hai. Speed phone par depend karti hai — lagbhag
utna hi time lagta hai jitna lamba audio hai (purane phone par zyada).

## Text → My voice (demo)

Script likho, aur app use aapki awaaz me bol dega — ye bhi poora offline.

1. Upar **Text → My voice** chuno.
2. Script likho — Roman Hinglish ("aaj hum baat karenge") ya seedha Hindi lipi me.
3. **Convert to Hindi script** dabao. Neeche wale box me wahi dikhega jo bola jayega;
   koi shabd galat bana ho to wahin theek kar do.
4. Speed chuno aur **Generate voice** dabao.

Kaise kaam karta hai: `Hinglish.kt` Roman ko Devanagari me badalta hai (aam shabdon ki dictionary +
spelling ke niyam), phir Meta ka MMS-TTS Hindi model use padhta hai, aur wahi awaaz upar wale
converter se aapki awaaz me badalti hai.

Abhi ki seemayein:
- Roman Hindi me "a" chhota hai ya lamba, "t/d" kaun sa hai — ye likha nahi hota, isliye naam aur
  kam aam shabd galat ban sakte hain (jaise "Gopal" → गोपल; "Gopaal" likho ya Hindi box me theek karo).
- Padhne ka andaaz seedha-saada hai: feelings/expressions abhi nahi hain, sirf punctuation se pause.
- **Licence:** MMS-TTS ke weights CC-BY-NC 4.0 hain, yani **non-commercial** use ke liye.
  Monetized channel ke liye ye model theek nahi — us haalat me doosra model lagana padega.

## Quality ke liye app khud kya karta hai

**Sample par (hamesha, automatic):**
- 70 Hz se neeche ki ghar-ghar (rumble/hum) hatata hai
- background shor (fan, hiss) hatata hai — shor ka namoona recording ke sabse shaant palon se seekhta hai
- chuppi aur lambe pause nikaal deta hai, sirf bolne wala hissa leta hai
- loudness ek standard level par lata hai (dheemi recording bhi chalegi)
- sample ko tukdo me sunta hai aur jo tukde baaki se alag hain (khaansi, shor) unhe chhod deta hai
- aapki bolne ki pitch naapta hai
- report dikhata hai: kitni saaf awaaz mili, shor kitna hai, aur zaroorat ho to tip

**Voiceover par:**
- wahi safai (rumble, shor, loudness) — "Voiceover ka shor hatao" option
- pitch: model khud hi pitch ko aapki awaaz ke register me le aata hai. "Extra pitch shift"
  option isliye band rehta hai — asli awaazon par naapne par usse awaaz bhari/robotic hui
- **Natural tone** — agar sample patla, tez ya dabi awaaz wala ho (jaise WhatsApp recording) to model
  wahi rang copy kar leta hai; ye option 600 Hz se upar ke un hisso ko wapas natural balance ki taraf lata hai
- convert ke baad: original ke pause shaant rakhta hai (saans jaisi awaaz nahi aati) aur
  final loudness poori, bina clipping ke

Build ke tests har option ka asar asli (insaani) awaazon par naapte hain: target se similarity
aur voiceover ka utaar-chadhaav (intonation) kitna bacha.

## APK kahan se milega

Har push par GitHub Actions APK banata hai:
repo → **Actions** → **Meri Awaaz APK** → sabse upar wala green run → **Artifacts** →
`MeriAwaaz-apk` download karo, zip kholo, `MeriAwaaz.apk` install karo
("unknown apps" install allow karna padega). APK ~150 MB ka hai kyunki model uske andar hai.

## Ye kaise kaam karta hai

- Model: [OpenVoice V2](https://github.com/myshell-ai/OpenVoice) ka *tone colour converter*
  (MyShell.ai, MIT licence). Ye bolne ka andaaz, shabd aur speed wahi rakhta hai aur
  awaaz ka rang (timbre) aapke sample jaisa kar deta hai. Kisi bhi bhasha par chalta hai.
- `tools/export_onnx.py` model ko ONNX me badalta hai; app use
  [ONNX Runtime](https://onnxruntime.ai) se phone ke CPU par chalata hai.
- Build ke waqt sirf ek cheez download hoti hai: model ke weights. Wo APK ke andar
  pack ho jate hain, isliye app ko baad me kuch download nahi karna padta.

| Folder | Kya hai |
| --- | --- |
| `engine/` | Pure Kotlin: cleaner (noise removal), tone balance, pitch tools, resampler, lambi audio ko tukdo me process karna, model runner (+ tests) |
| `app/` | Android UI, audio decoder (MediaCodec), save/share |
| `tools/export_onnx.py` | Voice converter model → ONNX export aur jaanch |
| `tools/fetch_tts.py` | Hindi text-to-speech model download aur jaanch |

## Khud build karna

```bash
pip install torch onnx onnxruntime numpy
curl -LO https://huggingface.co/myshell-ai/OpenVoiceV2/resolve/main/converter/checkpoint.pth
curl -LO https://huggingface.co/myshell-ai/OpenVoiceV2/resolve/main/converter/config.json
python tools/export_onnx.py --checkpoint checkpoint.pth --config config.json --out app/src/main/assets
python tools/fetch_tts.py --out app/src/main/assets
./gradlew :engine:test :app:assembleRelease
```

## Seemayein (imaandari se)

- Ye awaaz ka *rang* badalta hai; lehja (accent) aur bolne ka style original voiceover ka hi rehta hai.
  Isliye result "aap jaisa" lagta hai, par 100% aapki copy nahi.
- **Sample sabse zaroori hai.** Phone ke Recorder app se seedha record karo (WhatsApp/call recording nahi — wo awaaz ko compress karke ajeeb bana deti hai).
- App sample saaf zaroor karta hai, par bahut zyada shor, echo ya music wala sample phir bhi kharab result dega.
  Shaant kamre me 1–2 minute aaram se bol kar record karo.
- Sample me atakna/rukna koi dikkat nahi — model sirf awaaz ka rang leta hai, shabd nahi; pause app khud hata deta hai.
- Voiceover me background music ho to wo bhi bigad jayega — sirf awaaz wali file do.
- Signing key (`signing/`) repo me rakhi hai taki har naya build purane ke upar install ho sake;
  ye Play Store release ke liye nahi hai.

Sirf apni awaaz, ya jiski ijazat ho uski awaaz use kare.
