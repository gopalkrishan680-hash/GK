package com.kahani.studio3d.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.kahani.studio3d.StudioViewModel
import com.kahani.studio3d.data.Project
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImagesStep(vm: StudioViewModel, p: Project) {
    var target by remember { mutableStateOf(-1) }
    var bulkFrom by remember { mutableStateOf<Int?>(null) }
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && target >= 0) vm.setImage(target, uri)
    }
    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        vm.bulkImages(uris, bulkFrom)
    }
    val ratio = p.aspect.w.toFloat() / p.aspect.h

    LazyVerticalGrid(
        columns = GridCells.Fixed(if (ratio < 1f) 3 else 2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionCard("Images upload karo", "Prompts se bani images yahan daalo. Tap = ek image, long-press = options.") {
                val done = p.imagesDone
                LinearProgressIndicator(
                    progress = { if (p.scenes.isEmpty()) 0f else done.toFloat() / p.scenes.size },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(6.dp)), color = K.Green, trackColor = K.Stroke
                )
                Text("$done / ${p.scenes.size} images ready", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
                GradientButton("Bulk Upload (sabhi ek saath)", Modifier.fillMaxWidth(), icon = Icons.Default.Collections) {
                    bulkFrom = null; pickMany.launch("image/*")
                }
                Spacer(Modifier.height(6.dp))
                Text("File names ke number se order lagega (1, 2, 3…). Khali scenes pehle bharenge.", color = K.TextDim, fontSize = 12.sp)
            }
        }
        itemsIndexed(p.scenes, key = { _, s -> s.id }) { i, s ->
            var menu by remember { mutableStateOf(false) }
            Column {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(12.dp))
                        .background(K.Surface2)
                        .border(1.dp, if (s.imagePath == null) K.Violet.copy(alpha = 0.6f) else K.Stroke, RoundedCornerShape(12.dp))
                        .combinedClickable(onClick = { target = i; pickOne.launch("image/*") }, onLongClick = { menu = true }),
                    contentAlignment = Alignment.Center
                ) {
                    if (s.imagePath != null) {
                        val f = File(s.imagePath)
                        AsyncImage(
                            model = ImageRequest.Builder(ctx).data(f).memoryCacheKey(s.imagePath + f.lastModified()).build(),
                            contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(Icons.Default.AddPhotoAlternate, null, tint = K.Violet)
                    }
                    Text(
                        "${i + 1}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                        modifier = Modifier.align(Alignment.TopStart).padding(4.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xAA000000)).padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Image badlo") }, leadingIcon = { Icon(Icons.Default.SwapHoriz, null) }, onClick = { menu = false; target = i; pickOne.launch("image/*") })
                        DropdownMenuItem(text = { Text("Yahan se bulk upload") }, leadingIcon = { Icon(Icons.Default.Collections, null) }, onClick = { menu = false; bulkFrom = i; pickMany.launch("image/*") })
                        DropdownMenuItem(text = { Text("Prompt copy karo") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, onClick = { menu = false; clip.setText(AnnotatedString(s.prompt)); vm.toast("Prompt copied") })
                        if (s.imagePath != null) DropdownMenuItem(text = { Text("Image hatao") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; vm.removeImage(i) })
                    }
                }
                Text(s.text, color = K.TextDim, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 12.sp, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}
