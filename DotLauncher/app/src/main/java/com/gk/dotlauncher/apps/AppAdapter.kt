package com.gk.dotlauncher.apps

import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.gk.dotlauncher.util.Fonts
import com.gk.dotlauncher.util.Palette
import com.gk.dotlauncher.util.dp

class AppAdapter(
    private val repo: AppRepository,
    private val mono: () -> Boolean,
    private val showLabels: () -> Boolean,
    private val onClick: (AppItem, View) -> Unit,
    private val onLong: (AppItem, View) -> Unit,
) : RecyclerView.Adapter<AppAdapter.VH>() {

    var items: List<AppItem> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }

    class VH(val root: LinearLayout, val icon: ImageView, val label: TextView) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val c = parent.context
        val icon = ImageView(c)
        val label = TextView(c).apply {
            typeface = Fonts.mono(c)
            textSize = 11f
            setTextColor(Palette.TEXT)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(c.dp(4), c.dp(10), c.dp(4), c.dp(10))
            isClickable = true
            isLongClickable = true
            background = c.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless)).let { a -> a.getDrawable(0).also { a.recycle() } }
            addView(icon, LinearLayout.LayoutParams(c.dp(54), c.dp(54)))
            addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(6) })
        }
        return VH(root, icon, label)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val item = items[position]
        h.icon.setImageDrawable(repo.icon(item, mono()))
        h.label.text = item.label
        h.label.visibility = if (showLabels()) View.VISIBLE else View.GONE
        h.root.contentDescription = item.label
        h.root.setOnClickListener { onClick(item, h.icon) }
        h.root.setOnLongClickListener { onLong(item, h.icon); true }
    }

    override fun getItemCount() = items.size
}
