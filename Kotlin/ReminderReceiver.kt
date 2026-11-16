package com.alzubair.omri

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", 0)
        val kind = intent.getStringExtra("kind") ?: "custom"
        val title = intent.getStringExtra("title") ?: "عُمري | My Age"
        val message = intent.getStringExtra("message") ?: "لديك تذكير"
        ReminderScheduler.onAlarmDelivered(context, id, kind, title, message)
    }
}
