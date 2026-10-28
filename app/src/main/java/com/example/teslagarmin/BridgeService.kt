package com.example.teslagarmin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** 가벼운 상주 서비스: Connect IQ SDK 리스너를 유지해 앱을 열지 않아도 워치 요청 처리. */
class BridgeService : Service() {
    override fun onBind(i: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Tesla Garmin 브리지", NotificationManager.IMPORTANCE_MIN))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CH).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Tesla 워치 연결 대기 중").setOngoing(true).setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_MIN).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
        Bridge.start(this)
    }
    override fun onStartCommand(i: Intent?, f: Int, id: Int) = START_STICKY
    override fun onDestroy() { Bridge.stop(this); super.onDestroy() }
    companion object {
        const val CH = "bridge"
        fun start(c: Context) = ContextCompat.startForegroundService(c, Intent(c, BridgeService::class.java))
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { runCatching { BridgeService.start(c) } }
}
