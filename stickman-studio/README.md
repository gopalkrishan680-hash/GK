# Stickman Studio

Script + voiceover se stickman video banane ka app. Ek hi file (`index.html`), koi install nahi.
**Chrome ya Edge** mein kholo (file par double-click). Sab kuch aapke computer par hi chalta hai.

## Kaise use karein

1. **Script + Voice:** Script paste karo, voiceover (mp3/wav/m4a) drop karo, **Scenes banao** dabao.
   Script har `,` `.` `।` `?` `!` `;` par toot-ti hai. Har tukda ek scene (ek image) banta hai.
   - Bahut chhote tukde (jaise "aur bole,") agle scene se jud jaate hain (*Kam se kam shabd*).
   - Bahut lambe tukde barabar hisson mein toot jaate hain (*Zyada se zyada shabd*).
   - Caption mein kisi shabd ko peela karna ho to `*58 percent*` jaise star ke beech likho.
2. **Image Prompts:** Style aur characters chuno. Har prompt **ek image mein 3×3 = 9 scenes** ka grid banwata hai.
   Prompt copy karke ChatGPT ya Gemini mein do aur image download karo.
   - *AI Scene Director:* poori script ka ek prompt ChatGPT ko do. Woh har scene ka visual likh dega.
     Jawab wapas paste karke **Apply** dabao, to prompts aur behtar ho jaate hain.
3. **Grid Upload:** Saari grid images ek saath drop karo. App panels ke beech ki lines dhoondh kar
   har panel ko alag kaat leta hai. Agar kaat galat lage to *Auto-detect* band karo ya *Trim* badhao.
   Koi ek panel kharab ho to **Single image** se sirf us scene ki image daal do.
4. **Voice Sync:**
   - **Smart Sync:** voiceover mein ruk (pause) wali jagah dhoondh kar scene wahin badalta hai.
   - **Tap Sync:** awaaz sunte hue har naye scene par `Space` dabao. Ye sabse accurate hai.
   - Timeline par lines kheench kar ya number badal kar time theek karo.
   - **SRT import/export:** SRT file ho to usse timing lo. YouTube ke liye SRT bhi nikal sakte ho.
5. **Preview + Export:** captions, transition, camera zoom/pan aur background music set karo, phir
   **Video export karo**. Export real-time hota hai, isliye tab khula rakho.
   Chrome mein MP4 milta hai. Kisi browser mein WebM mile to:
   `ffmpeg -i video.webm -c:v libx264 -c:a aac video.mp4`

## Extra features
- 16:9, 9:16 (Shorts) aur 1:1. 3×3 ya 2×2 grid (2×2 = har scene ki quality zyada).
- Aakhri batch mein kam scenes hon to 2×2 ya single image ka prompt banta hai.
- Har scene par halka Ken Burns zoom/pan, taaki video kabhi ruki hui na lage (no-freeze rule).
- Bade caption (Baloo 2, safed + kaala outline, pop animation), upar/beech/neeche ya band.
- Transitions: quick fade, hard cut, white flash, slide, zoom punch.
- Autosave browser mein hota hai. **Project save** se poora project (images + audio) ek file mein bachao.
- "Channel characters daalo" button se Rohan, Maa, Papa aur Aman ka look prompt mein aa jata hai.

## Tips
- ChatGPT ki image aksar 1536×1024 hoti hai, to 3×3 mein har scene ~512px ka hota hai.
  Zyada sharp chahiye to 2×2 grid lo, ya Gemini se badi image banwao.
- Safed background wale styles mein panels ke beech **kaali line** maangi jaati hai, aur dark styles
  mein **safed line**, taaki app lines aasani se dhoondh sake.
