package com.gk.dotlauncher

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.app.AlertDialog
import android.app.SearchManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.UserHandle
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gk.dotlauncher.apps.AppAdapter
import com.gk.dotlauncher.apps.AppItem
import com.gk.dotlauncher.apps.AppRepository
import com.gk.dotlauncher.util.Fonts
import com.gk.dotlauncher.util.Palette
import com.gk.dotlauncher.util.Prefs
import com.gk.dotlauncher.util.buzz
import com.gk.dotlauncher.util.dp
import com.gk.dotlauncher.widgets.AnalogCard
import com.gk.dotlauncher.widgets.BatteryCard
import com.gk.dotlauncher.widgets.CalendarCard
import com.gk.dotlauncher.widgets.ClockCard
import com.gk.dotlauncher.widgets.DotCard
import com.gk.dotlauncher.widgets.DotGridBackground
import com.gk.dotlauncher.widgets.NoteCard
import com.gk.dotlauncher.widgets.StorageCard
import com.gk.dotlauncher.widgets.TorchCard

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var repo: AppRepository
    private lateinit var widgetHost: AppWidgetHost
    private lateinit var awm: AppWidgetManager

    private lateinit var root: SwipeFrame
    private lateinit var bg: DotGridBackground
    private lateinit var home: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var column: LinearLayout
    private lateinit var dock: LinearLayout
    private lateinit var drawer: SwipeFrame
    private lateinit var search: EditText
    private lateinit var grid: RecyclerView
    private lateinit var frequentRow: LinearLayout
    private lateinit var countLabel: TextView
    private lateinit var adapter: AppAdapter

    private var drawerOpen = false
    private var pendingWidget: Pair<Int, AppWidgetProviderInfo>? = null
    private var lastSettingsStamp = ""

    private val appsCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(p: String, u: UserHandle) = reload()
        override fun onPackageAdded(p: String, u: UserHandle) = reload()
        override fun onPackageChanged(p: String, u: UserHandle) = reload()
        override fun onPackagesAvailable(p: Array<out String>, u: UserHandle, r: Boolean) = reload()
        override fun onPackagesUnavailable(p: Array<out String>, u: UserHandle, r: Boolean) = reload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        repo = AppRepository(this)
        awm = AppWidgetManager.getInstance(this)
        widgetHost = AppWidgetHost(this, HOST_ID)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        buildUi()
        setContentView(root)
        repo.launcherApps.registerCallback(appsCallback)
        lastSettingsStamp = settingsStamp()
        reload()
    }

    override fun onStart() {
        super.onStart()
        runCatching { widgetHost.startListening() }
    }

    override fun onStop() {
        super.onStop()
        runCatching { widgetHost.stopListening() }
    }

    override fun onResume() {
        super.onResume()
        refreshIfChanged()
    }

    /** Settings badli ho to UI refresh */
    private fun refreshIfChanged() {
        val stamp = settingsStamp()
        if (stamp != lastSettingsStamp) {
            lastSettingsStamp = stamp
            repo.clearIcons()
            (grid.layoutManager as GridLayoutManager).spanCount = prefs.drawerColumns
            bg.invalidate()
            reload()
            buildHome()
        }
    }

    override fun onDestroy() {
        repo.launcherApps.unregisterCallback(appsCallback)
        super.onDestroy()
    }

    /** Home button dabane par drawer band karo */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (drawerOpen) closeDrawer() else scroll.smoothScrollTo(0, 0)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (drawerOpen) closeDrawer()
    }

    private fun settingsStamp() = listOf(
        prefs.monoIcons, prefs.dotWallpaper, prefs.drawerColumns, prefs.showLabels, prefs.accentIndex,
        prefs.use24h, prefs.showSeconds, prefs.hidden.sorted(), Prefs.CARDS.map { prefs.isCardEnabled(it.first) },
    ).toString()

    // ------------------------------------------------------------------ UI

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        root = SwipeFrame(this)
        bg = DotGridBackground(this)
        root.addView(bg, FrameLayout.LayoutParams(-1, -1))

        // ---- Home
        home = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(16))
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(column, ViewGroup.LayoutParams(-1, -2))
        }
        home.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val searchPill = TextView(this).apply {
            text = "●  Search"
            typeface = Fonts.mono(context)
            textSize = 13f
            setTextColor(Palette.DIM)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            setBackgroundResource(R.drawable.search_bg)
            setOnClickListener { openDrawer(forceKeyboard = true) }
        }
        home.addView(searchPill, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(dp(20), dp(4), dp(20), dp(12)) })

        dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(4), dp(12), dp(12))
        }
        home.addView(dock, LinearLayout.LayoutParams(-1, -2))
        root.addView(home, FrameLayout.LayoutParams(-1, -1))

        // Khaali jagah par double-tap = lock, long-press = menu
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onDoubleTap(e: MotionEvent): Boolean { doubleTapLock(); return true }
            override fun onLongPress(e: MotionEvent) { column.buzz(); homeMenu(column, e) }
        })
        column.setOnTouchListener { _, e -> detector.onTouchEvent(e); true }

        root.onSwipeUp = { openDrawer() }
        root.canSwipeUp = { !drawerOpen && !scroll.canScrollVertically(1) }
        root.onSwipeDown = { expandNotifications() }
        root.canSwipeDown = { !drawerOpen && prefs.swipeDownNotifications && !scroll.canScrollVertically(-1) }

        // ---- Drawer
        drawer = SwipeFrame(this).apply {
            setBackgroundColor(0xF2000000.toInt())
            visibility = View.GONE
        }
        val dcol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(8), dp(22), dp(4))
        }
        val title = TextView(this).apply {
            text = "APPS"
            typeface = Fonts.doto(context)
            textSize = 30f
            setTextColor(Palette.TEXT)
        }
        countLabel = TextView(this).apply {
            typeface = Fonts.mono(context); textSize = 11f; setTextColor(Palette.DIM)
        }
        val settingsBtn = TextView(this).apply {
            text = "⚙"
            textSize = 22f
            setTextColor(Palette.TEXT)
            setPadding(dp(12), dp(4), 0, dp(4))
            setOnClickListener { openSettings() }
        }
        header.addView(title)
        header.addView(countLabel, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
        header.addView(settingsBtn)
        dcol.addView(header)

        search = EditText(this).apply {
            hint = getString(R.string.search_hint)
            typeface = Fonts.mono(context)
            textSize = 14f
            setTextColor(Palette.TEXT)
            setHintTextColor(Palette.DIM)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_GO
            setBackgroundResource(R.drawable.search_bg)
            setPadding(dp(20), 0, dp(20), 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = applyFilter()
            })
            setOnEditorActionListener { _, _, _ -> launchFirstOrSearch(); true }
        }
        dcol.addView(search, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(dp(18), dp(8), dp(18), dp(8)) })

        frequentRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), 0, dp(10), dp(6))
        }
        dcol.addView(frequentRow, LinearLayout.LayoutParams(-1, -2))

        adapter = AppAdapter(repo, { prefs.monoIcons }, { prefs.showLabels }, { a, v -> launch(a, v) }, { a, v -> appMenu(a, v) })
        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(context, prefs.drawerColumns)
            adapter = this@MainActivity.adapter
            setPadding(dp(8), 0, dp(8), dp(24))
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(rv: RecyclerView, state: Int) {
                    if (state == RecyclerView.SCROLL_STATE_DRAGGING) hideKeyboard()
                }
            })
        }
        dcol.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        drawer.addView(dcol, FrameLayout.LayoutParams(-1, -1))
        drawer.onSwipeDown = { closeDrawer() }
        drawer.canSwipeDown = { !grid.canScrollVertically(-1) }
        root.addView(drawer, FrameLayout.LayoutParams(-1, -1))

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            home.setPadding(0, b.top, 0, b.bottom)
            dcol.setPadding(0, b.top, 0, maxOf(b.bottom, ime.bottom))
            insets
        }
        buildHome()
    }

    /** Built-in cards + system widgets ko column me lagata hai. */
    private fun buildHome() {
        column.removeAllViews()
        val gap = dp(12)

        if (prefs.isCardEnabled("clock")) {
            column.addView(setupCard(ClockCard(this)), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = gap })
        }
        val halves = mutableListOf<DotCard>()
        if (prefs.isCardEnabled("battery")) halves += BatteryCard(this)
        if (prefs.isCardEnabled("calendar")) halves += CalendarCard(this)
        if (prefs.isCardEnabled("torch")) halves += TorchCard(this)
        if (prefs.isCardEnabled("storage")) halves += StorageCard(this)
        if (prefs.isCardEnabled("analog")) halves += AnalogCard(this)
        if (prefs.isCardEnabled("note")) halves += NoteCard(this)

        halves.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, card ->
                row.addView(setupCard(card), LinearLayout.LayoutParams(0, -2, 1f).apply { if (i == 0) rightMargin = gap / 2 else leftMargin = gap / 2 })
            }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = gap / 2 })
            column.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = gap })
        }

        // System (Android) widgets
        val valid = prefs.widgetIds.filter { awm.getAppWidgetInfo(it) != null }
        if (valid.size != prefs.widgetIds.size) prefs.widgetIds = valid
        valid.forEach { id -> addHostView(id) }

        if (column.childCount == 0) {
            column.addView(TextView(this).apply {
                text = "Long-press karke widget add karein"
                typeface = Fonts.mono(context); setTextColor(Palette.DIM); gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, dp(200)))
        }
    }

    private fun setupCard(card: DotCard): DotCard {
        card.setOnLongClickListener { v ->
            v.buzz()
            PopupMenu(this, v).apply {
                menu.add("Hide widget").setOnMenuItemClickListener { prefs.setCardEnabled(card.key, false); refreshIfChanged(); true }
                menu.add("Add widget").setOnMenuItemClickListener { pickWidget(); true }
                menu.add("Settings").setOnMenuItemClickListener { openSettings(); true }
            }.show()
            true
        }
        if (card is ClockCard) card.setOnClickListener { safeStart(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)) }
        if (card is CalendarCard) card.setOnClickListener {
            safeStart(Intent(Intent.ACTION_VIEW, android.provider.CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build()))
        }
        if (card is BatteryCard) card.setOnClickListener { safeStart(Intent(Intent.ACTION_POWER_USAGE_SUMMARY)) }
        if (card is StorageCard) card.setOnClickListener { safeStart(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) }
        return card
    }

    private fun addHostView(id: Int) {
        val info = awm.getAppWidgetInfo(id) ?: return
        val hv = widgetHost.createView(applicationContext, id, info)
        hv.setPadding(0, 0, 0, 0)
        val frame = LongPressFrame(this).apply {
            setBackgroundResource(R.drawable.card_bg)
            clipToOutline = true
            addView(hv, FrameLayout.LayoutParams(-1, -1))
            onLong = {
                buzz()
                PopupMenu(this@MainActivity, this).apply {
                    menu.add("Remove widget").setOnMenuItemClickListener {
                        widgetHost.deleteAppWidgetId(id)
                        prefs.widgetIds = prefs.widgetIds - id
                        buildHome(); true
                    }
                    menu.add("Add widget").setOnMenuItemClickListener { pickWidget(); true }
                }.show()
            }
        }
        val h = maxOf(info.minHeight, dp(110))
        column.addView(frame, LinearLayout.LayoutParams(-1, h).apply { bottomMargin = dp(12) })
    }

    // ------------------------------------------------------------------ Apps

    private fun reload() {
        repo.load(prefs.monoIcons) { list ->
            ensureDefaultDock(list)
            applyFilter()
            buildDock()
        }
    }

    private fun visibleApps(): List<AppItem> {
        val hidden = prefs.hidden
        return repo.apps.filter { it.key !in hidden }
    }

    private fun applyFilter() {
        val q = search.text.toString().trim().lowercase()
        val all = visibleApps()
        val res = if (q.isEmpty()) all else {
            all.filter { it.label.lowercase().contains(q) || initials(it.label).startsWith(q) }
                .sortedBy { if (it.label.lowercase().startsWith(q)) 0 else 1 }
        }
        adapter.items = res
        countLabel.text = if (q.isEmpty()) "${all.size} apps" else "${res.size} found"
        buildFrequent(q.isEmpty())
    }

    private fun initials(s: String) = s.split(' ', '-', '_').filter { it.isNotEmpty() }.joinToString("") { it.take(1) }.lowercase()

    /** Sabse zyada khole gaye 5 apps drawer ke top par */
    private fun buildFrequent(show: Boolean) {
        frequentRow.removeAllViews()
        val top = visibleApps().filter { prefs.launchCount(it.key) > 0 }
            .sortedByDescending { prefs.launchCount(it.key) }.take(5)
        if (!show || top.isEmpty()) { frequentRow.visibility = View.GONE; return }
        frequentRow.visibility = View.VISIBLE
        top.forEach { app ->
            frequentRow.addView(iconView(app, 48), LinearLayout.LayoutParams(0, dp(64), 1f))
        }
        repeat(5 - top.size) { frequentRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
    }

    private fun iconView(app: AppItem, sizeDp: Int): View {
        val iv = ImageView(this).apply {
            setImageDrawable(repo.icon(app, prefs.monoIcons))
            contentDescription = app.label
        }
        return FrameLayout(this).apply {
            addView(iv, FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp), Gravity.CENTER))
            isClickable = true
            setOnClickListener { launch(app, iv) }
            setOnLongClickListener { appMenu(app, iv); true }
        }
    }

    private fun ensureDefaultDock(list: List<AppItem>) {
        if (prefs.dock != null || list.isEmpty()) return
        val intents = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER),
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
        )
        val keys = intents.mapNotNull { i ->
            val pkg = packageManager.resolveActivity(i, 0)?.activityInfo?.packageName ?: return@mapNotNull null
            list.firstOrNull { it.info.componentName.packageName == pkg }?.key
        }.distinct()
        prefs.dock = keys
    }

    private fun buildDock() {
        dock.removeAllViews()
        val apps = (prefs.dock ?: emptyList()).mapNotNull { repo.find(it) }
        apps.forEach { dock.addView(iconView(it, 52), LinearLayout.LayoutParams(0, dp(68), 1f)) }
        dock.visibility = if (apps.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun launch(app: AppItem, v: View) {
        val r = Rect().also { v.getGlobalVisibleRect(it) }
        val opts = ActivityOptions.makeClipRevealAnimation(v, 0, 0, v.width, v.height).toBundle()
        try {
            repo.launcherApps.startMainActivity(app.info.componentName, app.info.user, r, opts)
            prefs.recordLaunch(app.key)
            if (drawerOpen) v.postDelayed({ closeDrawer(animate = false) }, 350)
        } catch (e: Exception) {
            Toast.makeText(this, "App nahi khul paya", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchFirstOrSearch() {
        val first = adapter.items.firstOrNull()
        val q = search.text.toString().trim()
        if (first != null && q.isNotEmpty()) {
            launch(first, grid.getChildAt(0) ?: grid)
        } else if (q.isNotEmpty()) {
            val web = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q)
            if (!safeStart(web)) safeStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
        }
    }

    private fun appMenu(app: AppItem, v: View) {
        v.buzz()
        val dockList = prefs.dock ?: emptyList()
        val pkg = app.info.componentName.packageName
        PopupMenu(this, v).apply {
            if (app.key in dockList) {
                menu.add("Remove from dock").setOnMenuItemClickListener { prefs.dock = dockList - app.key; buildDock(); true }
            } else {
                menu.add("Add to dock").setOnMenuItemClickListener {
                    if (dockList.size >= 5) Toast.makeText(this@MainActivity, "Dock me max 5 apps", Toast.LENGTH_SHORT).show()
                    else { prefs.dock = dockList + app.key; buildDock() }
                    true
                }
            }
            menu.add("App info").setOnMenuItemClickListener {
                repo.launcherApps.startAppDetailsActivity(app.info.componentName, app.info.user, null, null); true
            }
            menu.add("Hide app").setOnMenuItemClickListener {
                prefs.hidden = prefs.hidden + app.key
                prefs.dock = dockList - app.key
                lastSettingsStamp = settingsStamp()
                applyFilter(); buildDock()
                Toast.makeText(this@MainActivity, "${app.label} hidden (Settings se wapas la sakte ho)", Toast.LENGTH_SHORT).show()
                true
            }
            menu.add("Uninstall").setOnMenuItemClickListener {
                safeStart(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))); true
            }
        }.show()
    }

    // ------------------------------------------------------------------ Drawer

    private fun openDrawer(forceKeyboard: Boolean = false) {
        if (drawerOpen) return
        drawerOpen = true
        drawer.visibility = View.VISIBLE
        drawer.translationY = root.height * 0.25f
        drawer.alpha = 0f
        drawer.animate().translationY(0f).alpha(1f).setDuration(220).start()
        home.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(220).start()
        grid.scrollToPosition(0)
        if (forceKeyboard || prefs.autoKeyboard) {
            search.requestFocus()
            search.postDelayed({
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
            }, 150)
        }
    }

    private fun closeDrawer(animate: Boolean = true) {
        if (!drawerOpen) return
        drawerOpen = false
        hideKeyboard()
        search.setText("")
        search.clearFocus()
        val d = if (animate) 200L else 0L
        drawer.animate().translationY(root.height * 0.25f).alpha(0f).setDuration(d)
            .withEndAction { drawer.visibility = View.GONE }.start()
        home.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(d).start()
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0)
    }

    // ------------------------------------------------------------------ Gestures / menus

    private fun doubleTapLock() {
        if (!prefs.doubleTapLock) return
        if (LockService.lock()) return
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Double-tap to lock")
            .setMessage("Screen lock karne ke liye Accessibility me \"Dot Launcher\" service ON karni hogi. Ye service koi data nahi padhti.")
            .setPositiveButton("Open settings") { _, _ -> safeStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .setNegativeButton("Feature off") { _, _ -> prefs.doubleTapLock = false }
            .show()
    }

    @SuppressLint("WrongConstant", "PrivateApi")
    private fun expandNotifications() {
        if (LockService.notifications()) return
        runCatching {
            val sbm = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sbm)
        }
    }

    private fun homeMenu(anchor: View, e: MotionEvent) {
        val tmp = View(this)
        root.addView(tmp, FrameLayout.LayoutParams(1, 1).apply {
            leftMargin = e.rawX.toInt(); topMargin = e.rawY.toInt()
        })
        tmp.post {
            PopupMenu(this, tmp).apply {
                menu.add("Add widget").setOnMenuItemClickListener { pickWidget(); true }
                menu.add("Wallpaper").setOnMenuItemClickListener {
                    safeStart(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper")); true
                }
                menu.add("Launcher settings").setOnMenuItemClickListener { openSettings(); true }
                setOnDismissListener { root.removeView(tmp) }
            }.show()
        }
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    private fun safeStart(i: Intent): Boolean = try {
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: Exception) {
        Toast.makeText(this, "Koi app nahi mila", Toast.LENGTH_SHORT).show(); false
    }

    // ------------------------------------------------------------------ System widget picker

    /** Apna khud ka picker (kuch phones me system picker hota hi nahi). Built-in cards bhi wapas yahin se. */
    private fun pickWidget() {
        val hiddenCards = Prefs.CARDS.filter { !prefs.isCardEnabled(it.first) }
        val providers = awm.installedProviders.sortedBy { it.loadLabel(packageManager).lowercase() }
        val labels = hiddenCards.map { "● ${it.second} (Dot)" } +
            providers.map { p ->
                val app = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(p.provider.packageName, 0)) }.getOrDefault("")
                "${p.loadLabel(packageManager)}  ·  $app"
            }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Add widget")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < hiddenCards.size) {
                    prefs.setCardEnabled(hiddenCards[which].first, true)
                    refreshIfChanged()
                } else bindWidget(providers[which - hiddenCards.size])
            }
            .show()
    }

    private fun bindWidget(info: AppWidgetProviderInfo) {
        val id = widgetHost.allocateAppWidgetId()
        pendingWidget = id to info
        if (awm.bindAppWidgetIdIfAllowed(id, info.provider)) {
            configureOrAdd(id, info)
        } else {
            val i = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_BIND)
        }
    }

    private fun configureOrAdd(id: Int, info: AppWidgetProviderInfo) {
        if (info.configure != null) {
            try {
                widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIGURE, null)
                return
            } catch (_: Exception) { }
        }
        commitWidget(id)
    }

    private fun commitWidget(id: Int) {
        prefs.widgetIds = prefs.widgetIds + id
        pendingWidget = null
        buildHome()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val (id, info) = pendingWidget ?: return
        when (requestCode) {
            REQ_BIND -> if (resultCode == RESULT_OK) configureOrAdd(id, info) else { widgetHost.deleteAppWidgetId(id); pendingWidget = null }
            REQ_CONFIGURE -> if (resultCode == RESULT_OK) commitWidget(id) else { widgetHost.deleteAppWidgetId(id); pendingWidget = null }
        }
    }

    companion object {
        private const val HOST_ID = 0x0D07
        private const val REQ_BIND = 11
        private const val REQ_CONFIGURE = 12
    }
}
