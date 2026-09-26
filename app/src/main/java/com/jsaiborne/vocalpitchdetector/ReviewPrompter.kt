package com.jsaiborne.vocalpitchdetector

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import com.google.android.play.core.review.ReviewManagerFactory

/**
 * Asks for a Play Store rating with Google Play's in-app review sheet, only after something good
 * has happened (a take was saved, a range test was finished) and never often. Play also applies
 * its own quota, so the sheet may not appear even when this asks; that's expected.
 */
object ReviewPrompter {
    private const val PREFS = "AppPreferences"
    private const val KEY_POSITIVE_EVENTS = "ReviewPositiveEvents"
    private const val KEY_LAST_ASKED_MS = "ReviewLastAskedMs"
    private const val KEY_FIRST_SEEN_MS = "ReviewFirstSeenMs"

    /** Don't ask on someone's very first good moment: wait until they've come back a few times. */
    private const val MIN_POSITIVE_EVENTS = 3
    private const val MIN_DAYS_SINCE_FIRST_USE = 2L
    private const val MIN_DAYS_BETWEEN_ASKS = 60L
    private const val DAY_MS = 24L * 60L * 60L * 1000L

    /** Call after a positive moment; shows the review sheet if it's a good time to ask. */
    fun onPositiveMoment(context: Context) {
        val activity = context.findActivity() ?: return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        val firstSeen = prefs.getLong(KEY_FIRST_SEEN_MS, 0L).takeIf { it > 0L }
            ?: now.also { prefs.edit().putLong(KEY_FIRST_SEEN_MS, it).apply() }
        val events = prefs.getInt(KEY_POSITIVE_EVENTS, 0) + 1
        val lastAsked = prefs.getLong(KEY_LAST_ASKED_MS, 0L)
        prefs.edit().putInt(KEY_POSITIVE_EVENTS, events).apply()

        val readyToAsk = events >= MIN_POSITIVE_EVENTS &&
            now - firstSeen >= MIN_DAYS_SINCE_FIRST_USE * DAY_MS &&
            now - lastAsked >= MIN_DAYS_BETWEEN_ASKS * DAY_MS
        if (!readyToAsk) return

        prefs.edit().putLong(KEY_LAST_ASKED_MS, now).apply()
        val manager = ReviewManagerFactory.create(activity)
        manager.requestReviewFlow().addOnCompleteListener { request ->
            if (request.isSuccessful && !activity.isFinishing) {
                manager.launchReviewFlow(activity, request.result)
            } else {
                Log.w("ReviewPrompter", "In-app review not available", request.exception)
            }
        }
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
