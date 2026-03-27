package com.zensee.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        ReminderManager.initialize(context)
        ReminderManager.rescheduleFromReceiver()
    }
}
