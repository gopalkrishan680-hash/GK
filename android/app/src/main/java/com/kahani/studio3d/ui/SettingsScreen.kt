package com.kahani.studio3d.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kahani.studio3d.StudioViewModel
import com.kahani.studio3d.data.AspectRatio
import com.kahani.studio3d.data.VisualStyle

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: StudioViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var key by remember { mutableStateOf(vm.prefs.geminiKey) }
    var model by remember { mutableStateOf(vm.prefs.geminiModel) }
    var wm by remember { mutableStateOf(vm.prefs.defaultWatermark) }
    var style by remember { mutableStateOf(vm.prefs.defaultStyle) }
    var aspect by remember { mutableStateOf(vm.prefs.defaultAspect) }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
            Text("Settings", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        SectionCard("AI Prompt Writer (optional)", "Free Gemini API key se Hindi/Hinglish script bhi best English prompts me badal jati hai. Bina key ke bhi app offline prompts banata hai.") {
            OutlinedTextField(
                key, { key = it; vm.prefs.geminiKey = it }, label = { Text("Gemini API key") },
                leadingIcon = { Icon(Icons.Default.Key, null) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(model, { model = it; vm.prefs.geminiModel = it }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            SoftButton("Free key yahan se lo (aistudio.google.com)") {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
            }
        }
        SectionCard("Default style (naye projects ke liye)") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                VisualStyle.entries.forEach { s -> Pill(s.label, s == style) { style = s; vm.prefs.defaultStyle = s } }
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AspectRatio.entries.forEach { a -> Pill(a.label, a == aspect) { aspect = a; vm.prefs.defaultAspect = a } }
            }
        }
        SectionCard("Default watermark / channel name") {
            OutlinedTextField(wm, { wm = it; vm.prefs.defaultWatermark = it }, label = { Text("e.g. @MeraChannel") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        SectionCard("Kaise kaam karta hai?") {
            Text(
                "1. Script likho/paste karo, voiceover (MP3/M4A/WAV) upload karo.\n" +
                    "2. App har scene ka 3D image prompt banata hai — 'Copy All' karke kisi bhi image generator (Gemini, Ideogram, Leonardo, Bing, Meta AI…) me daalo.\n" +
                    "3. Images ko 1, 2, 3… naam se save karo aur 'Bulk Upload' se ek saath daalo — order apne aap lag jayega.\n" +
                    "4. Edit me motion, captions, music, filters set karo.\n" +
                    "5. Export — MP4 seedha Gallery (Movies/Kahani3D) me save hogi. Sab kuch phone par, bina watermark ke.",
                color = K.TextDim, fontSize = 13.sp, lineHeight = 19.sp
            )
        }
        Spacer(Modifier.height(40.dp))
    }
}
