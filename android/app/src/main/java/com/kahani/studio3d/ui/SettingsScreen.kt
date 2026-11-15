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
        SectionCard("AI Prompt Writer (optional)", "With a free Gemini API key, any script (even Hindi/Hinglish) becomes detailed English prompts. Without a key the app still writes offline prompts.") {
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
            SoftButton("Get a free key (aistudio.google.com)") {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
            }
        }
        SectionCard("Default style (for new projects)") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                VisualStyle.entries.forEach { s -> Pill(s.label, s == style) { style = s; vm.prefs.defaultStyle = s } }
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AspectRatio.entries.forEach { a -> Pill(a.label, a == aspect) { aspect = a; vm.prefs.defaultAspect = a } }
            }
        }
        SectionCard("Default watermark / channel name") {
            OutlinedTextField(wm, { wm = it; vm.prefs.defaultWatermark = it }, label = { Text("e.g. @MyChannel") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        SectionCard("How it works") {
            Text(
                "1. Write or paste your script and upload a voiceover (MP3/M4A/WAV).\n" +
                    "2. The app writes a 3D image prompt for every scene — use 'Copy All' and paste them into any image generator (Gemini, Ideogram, Leonardo, Bing, Meta AI…).\n" +
                    "3. Save the images as 1, 2, 3… and add them all with 'Bulk Upload' — they are ordered automatically.\n" +
                    "4. In Edit, set motion, captions, music and filters.\n" +
                    "5. Export — the MP4 is saved to your Gallery (Movies/Kahani3D). Everything runs on your phone, with no watermark.",
                color = K.TextDim, fontSize = 13.sp, lineHeight = 19.sp
            )
        }
        Spacer(Modifier.height(40.dp))
    }
}
