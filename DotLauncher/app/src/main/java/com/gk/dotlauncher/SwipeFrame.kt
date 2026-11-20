package com.gk.dotlauncher

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/** Vertical swipe pakadta hai (bina taps/scroll tode). */
class SwipeFrame(context: Context) : FrameLayout(context) {
    var onSwipeUp: (() -> Unit)? = null
    var onSwipeDown: (() -> Unit)? = null
    var canSwipeUp: () -> Boolean = { true }
    var canSwipeDown: () -> Boolean = { true }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop * 2.5f
    private var downX = 0f
    private var downY = 0f
    private var handled = false

    private fun track(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; handled = false }
            MotionEvent.ACTION_MOVE -> if (!handled) {
                val dy = e.y - downY
                val dx = e.x - downX
                if (abs(dy) > slop && abs(dy) > abs(dx) * 1.4f) {
                    if (dy < 0 && onSwipeUp != null && canSwipeUp()) { handled = true; onSwipeUp?.invoke(); return true }
                    if (dy > 0 && onSwipeDown != null && canSwipeDown()) { handled = true; onSwipeDown?.invoke(); return true }
                }
            }
        }
        return false
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = track(ev)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        track(event)
        return true
    }
}

/** Hosted (system) widgets par long-press pakadne ke liye, kyunki wo khud touch kha jaate hain. */
class LongPressFrame(context: Context) : FrameLayout(context) {
    var onLong: (() -> Unit)? = null
    private var fired = false
    private var sx = 0f
    private var sy = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val trigger = Runnable { fired = true; onLong?.invoke() }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fired = false; sx = ev.x; sy = ev.y
                postDelayed(trigger, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (abs(ev.x - sx) > slop || abs(ev.y - sy) > slop) removeCallbacks(trigger)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(trigger)
        }
        return fired
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent) = fired
}
