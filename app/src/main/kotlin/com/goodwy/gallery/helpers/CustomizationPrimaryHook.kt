/*
 * Copyright (C) 2026  Goodwy Gallery contributors
 * GPL v3, see LICENSE.
 *
 * Makes the "Primary color" row of commons' Customize appearance screen open
 * the SAME HSV + editable-hex ColorPickerDialog as the "Accent color" row.
 *
 * Upstream already does this itself — CustomizationActivity.pickPrimaryColor()
 * uses the hex dialog when isNewApp() is true — but isNewApp() is literally
 * packageName.startsWith("dev.goodwy."), so release packages get the preset
 * grid dialog instead. commons' CustomizationActivity is final and its apply
 * path is private, so this hook:
 *  1) attaches an OnTouchListener to the primary row (a plain click listener
 *     would be dropped every time setupColorsPickers() rebinds listeners —
 *     the touch slot survives that), intercepting only short taps;
 *  2) shows ColorPickerDialog with the exact parameters of upstream's own
 *     isNewApp() branch (Default button restoring default_primary_color);
 *  3) mirrors upstream's accept/cancel handler step-for-step by reflection
 *     (members kept by name in proguard-rules.pro), with a config+fallback.
 *
 * Verified against goodwy-commons 3e4d22538b. Re-verify after version bumps.
 */
package com.goodwy.gallery.helpers

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.dialogs.ColorPickerDialog
import com.goodwy.commons.extensions.baseConfig
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.getThemeId
import com.goodwy.commons.extensions.isCollection
import com.goodwy.commons.extensions.isPro
import com.goodwy.commons.helpers.NavigationIcon
import com.goodwy.commons.views.MyAppBarLayout
import kotlin.math.abs

object CustomizationPrimaryHook : Application.ActivityLifecycleCallbacks {

    private const val CUSTOMIZATION_ACTIVITY = "com.goodwy.commons.activities.CustomizationActivity"

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = wire(activity)
    override fun onActivityResumed(activity: Activity) = wire(activity)
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}

    @SuppressLint("ClickableViewAccessibility")
    private fun wire(activity: Activity) {
        if (activity.javaClass.name != CUSTOMIZATION_ACTIVITY) return
        val holder = activity.findViewById<View>(com.goodwy.commons.R.id.customizationPrimaryColorHolder) ?: return
        var downX = 0f
        var downY = 0f
        holder.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    false
                }
                MotionEvent.ACTION_UP -> {
                    val slop = ViewConfiguration.get(v.context).scaledTouchSlop
                    val isTap = abs(event.x - downX) <= slop && abs(event.y - downY) <= slop
                    if (isTap && isProLike(activity)) {
                        showPrimaryColorPicker(activity)
                        true // consume so upstream's grid-opening click never fires
                    } else {
                        false // scrolls and non-pro taps follow upstream behavior
                    }
                }
                else -> false
            }
        }
    }

    /** Mirrors upstream's private isProVersion(): intent IS_COLLECTION || isPro(). */
    private fun isProLike(activity: Activity): Boolean =
        activity.isCollection() || activity.isPro()

    /** Verbatim parameters of upstream pickPrimaryColor()'s isNewApp() branch. */
    private fun showPrimaryColorPicker(activity: Activity) {
        ColorPickerDialog(
            activity,
            Mirror.curPrimaryColor(activity) ?: activity.getProperPrimaryColor(),
            addDefaultColorButton = true,
            colorDefault = activity.resources.getColor(com.goodwy.commons.R.color.default_primary_color),
            title = activity.resources.getString(com.goodwy.commons.R.string.primary_color),
        ) { wasPositivePressed, color, wasDefaultPressed ->
            if (wasPositivePressed || wasDefaultPressed) {
                Mirror.onAccepted(activity, color)
            } else {
                Mirror.onCancelled(activity)
            }
        }
    }

    /**
     * Reflective access to commons' CustomizationActivity private internals —
     * exact mirror of its own handlers. Member names are protected from R8 by
     * the keep rules in app/proguard-rules.pro; on ANY failure we degrade to
     * writing the public config and rebuilding the screen.
     */
    private object Mirror {
        private val cls = Class.forName(CUSTOMIZATION_ACTIVITY)

        private val fCurPrimary by lazy {
            cls.getDeclaredField("curPrimaryColor").apply { isAccessible = true }
        }
        private val fCurBackground by lazy {
            cls.getDeclaredField("curBackgroundColor").apply { isAccessible = true }
        }
        private val fUnsaved by lazy {
            cls.getDeclaredField("hasUnsavedChanges").apply { isAccessible = true }
        }
        private val mColorChanged by lazy {
            cls.getDeclaredMethod("colorChanged").apply { isAccessible = true }
        }
        private val mUpdateColorTheme by lazy {
            cls.getDeclaredMethod("updateColorTheme", Integer.TYPE, java.lang.Boolean.TYPE).apply { isAccessible = true }
        }
        private val mGetCurrentThemeId by lazy {
            cls.getDeclaredMethod("getCurrentThemeId").apply { isAccessible = true }
        }
        private val mUpdateTopBarColors by lazy {
            cls.getDeclaredMethod("updateTopBarColors").apply { isAccessible = true }
        }
        private val mGetCurrentBackground by lazy {
            cls.getDeclaredMethod("getCurrentBackgroundColor").apply { isAccessible = true }
        }

        fun curPrimaryColor(a: Activity): Int? = try {
            fCurPrimary.getInt(a)
        } catch (t: Throwable) {
            null
        }

        /** Mirrors the accept branch (color change → full theme refresh). */
        fun onAccepted(a: Activity, color: Int) {
            val ok = try {
                if (abs(fCurPrimary.getInt(a) - color) > 1) {
                    fCurPrimary.setInt(a, color) // setCurrentPrimaryColor(color)
                    mColorChanged.invoke(a)
                    val themeId = mGetCurrentThemeId.invoke(a) as Int
                    mUpdateColorTheme.invoke(a, themeId, false)
                    a.setTheme(a.getThemeId())
                }
                val nav = if (fUnsaved.getBoolean(a)) NavigationIcon.Cross else NavigationIcon.Arrow
                topBar(a, nav, mGetCurrentBackground.invoke(a) as Int)
                mUpdateTopBarColors.invoke(a)
                true
            } catch (t: Throwable) {
                false
            }
            if (!ok) fallback(a, color)
        }

        /** Mirrors the cancel/close branch. */
        fun onCancelled(a: Activity) {
            try {
                a.setTheme(a.getThemeId())
                topBar(a, NavigationIcon.Arrow, fCurBackground.getInt(a))
                mUpdateTopBarColors.invoke(a)
            } catch (t: Throwable) {
                // cancel of a color preview is purely cosmetic; nothing to repair
            }
        }

        private fun topBar(a: Activity, nav: NavigationIcon, color: Int) {
            val bar = a.findViewById<MyAppBarLayout>(com.goodwy.commons.R.id.appBar) ?: return
            (a as? BaseSimpleActivity)?.setupTopAppBar(bar, nav, topBarColor = color)
        }

        private fun fallback(a: Activity, color: Int) {
            try {
                a.baseConfig.primaryColor = color
                a.baseConfig.customPrimaryColor = color
            } catch (t: Throwable) {
                // keep going — recreate still refreshes from whatever stuck
            }
            a.recreate()
        }
    }
}
