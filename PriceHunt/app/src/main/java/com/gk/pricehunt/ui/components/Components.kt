package com.gk.pricehunt.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.gk.pricehunt.data.Store
import com.gk.pricehunt.ui.theme.DealGreen
import com.gk.pricehunt.util.openInStore
import com.gk.pricehunt.util.rupees

@Composable
fun StoreBadge(store: Store?, name: String? = null) {
    val c = Color(store?.color ?: 0xFF666666)
    Text(
        text = store?.displayName ?: name ?: "?",
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(c)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
fun ProductImage(url: String?, size: Dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(size))
        } else {
            Icon(Icons.Filled.ShoppingCart, null, tint = Color.LightGray)
        }
    }
}

@Composable
fun PriceLine(price: Double, mrp: Double?, big: Boolean = false) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            rupees(price),
            fontWeight = FontWeight.Bold,
            fontSize = if (big) 26.sp else 17.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (mrp != null && mrp > price) {
            Text(
                rupees(mrp), textDecoration = TextDecoration.LineThrough,
                fontSize = if (big) 15.sp else 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 2.dp),
            )
            Text(
                "${(((mrp - price) / mrp) * 100).toInt()}% off", color = DealGreen,
                fontSize = if (big) 15.sp else 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
    }
}

@Composable
fun RatingText(rating: Double?) {
    if (rating == null) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Star, null, tint = Color(0xFFF59E0B), modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(2.dp))
        Text(String.format("%.1f", rating), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ProductRow(
    store: Store?,
    title: String,
    imageUrl: String?,
    price: Double,
    mrp: Double?,
    rating: Double?,
    url: String,
    highlight: String? = null,
    onClick: () -> Unit,
) {
    val ctx = LocalContext.current
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ProductImage(imageUrl, 76.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StoreBadge(store)
                    if (highlight != null) {
                        Text(
                            highlight, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(DealGreen)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    RatingText(rating)
                }
                Spacer(Modifier.size(4.dp))
                Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, lineHeight = 18.sp)
                Spacer(Modifier.size(4.dp))
                PriceLine(price, mrp)
            }
            Spacer(Modifier.width(6.dp))
            FilledTonalButton(
                onClick = { openInStore(ctx, url, store) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) { Text("Open", fontSize = 13.sp) }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun Pill(text: String, bg: Color, fg: Color, onClick: (() -> Unit)? = null) {
    Text(
        text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}
