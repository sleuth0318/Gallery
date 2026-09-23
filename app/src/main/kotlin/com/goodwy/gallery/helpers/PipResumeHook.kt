/*
 * Copyright (C) 2026  Goodwy Gallery contributors
 * GPL v3, see LICENSE.
 *
 * Reopens a video in Theatre Mode when the user returns to the app after
 * dismissing the picture-in-picture pop-up.
 *
 * Dismissing the PiP window (the X button) removes the player activity from its
 * task, so there is nothing left to restore — the user lands back on the gallery.
 * The players persist a small "pending resume" point (video + exact position)
 * in onStop() when they leave via PiP dismissal. This hook observes activity
 * resumes and, when a pending point exists, relaunches TheatreModeActivity at
 * that position. It deliberately ignores the player activities themselves, so
 * expanding the PiP window (which resumes the player without onStop()) never
 * triggers a relaunch.
 */
package com.goodwy.gallery.helpers

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.goodwy.gallery.activities.SplashActivity
import com.goodwy.gallery.activities.TheatreModeActivity
import com.goodwy.gallery.activities.VideoPlayerActivity
import com.goodwy.gallery.extensions.config
import com.goodwy.gallery.extensions.launchTheatreResume

object PipResumeHook : Application.ActivityLifecycleCallbacks {

    fun install(app: Application) {
        // A pending resume point is only valid for the lifetime of the process that
        // wrote it: after dismissing the pop-up the user returns to the still-alive app
        // (same process) and Theatre Mode is reopened at the saved position. If the
        // process is gone — the user cleared the app from Recents or force-stopped it —
        // the stale point would otherwise relaunch Theatre Mode on the next launch, so
        // drain it on every cold start to open the gallery home instead.
        app.config.clearPendingPipResume()
        app.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        // Players handle their own resume (an expand of the pop-up resumes them without
        // onStop, so they must be ignored here). Splash is skipped so a cold start can
        // settle into the gallery before the video is reopened.
        if (activity is VideoPlayerActivity || activity is TheatreModeActivity || activity is SplashActivity) {
            return
        }
        if (activity.config.pendingPipResumePath.isNotEmpty()) {
            activity.launchTheatreResume()
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
