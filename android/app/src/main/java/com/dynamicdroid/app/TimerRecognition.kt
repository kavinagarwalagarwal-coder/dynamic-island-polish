package com.dynamicdroid.app

/** Do not require FLAG_ONGOING_EVENT: several Clock apps omit it, particularly when paused. */
object TimerRecognition {
    fun matches(known: Boolean, chronometer: Boolean, countDown: Boolean, ongoing: Boolean,
                alarmCategory: Boolean, clockPackage: Boolean, duration: Boolean, controls: Boolean): Boolean =
        known || chronometer || countDown || ongoing && alarmCategory || clockPackage && duration && controls
}
