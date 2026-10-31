package io.github.norlive.remotecontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/** Deliberately not restarted: process death must never restore an armed controller. */
class ConnectionService : Service() {
    private val model get()=(application as RemoteApplication).controller
    private var generation: Int?=null
    private var wakeLock: PowerManager.WakeLock?=null
    override fun onBind(intent: Intent?): IBinder?=null
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        val token=intent?.getIntExtra("generation",-1) ?: -1
        if(intent?.action=="disconnect") {
            model.disconnectFromService(token)
            if(generation==token)stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if(!model.ownsService(token)) {
            if(generation?.let(model::ownsService)!=true)stopSelfResult(startId)
            return START_NOT_STICKY
        }
        generation=token
        try {
            val manager=getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("connection","后台蓝牙连接",NotificationManager.IMPORTANCE_LOW))
            val disconnect=PendingIntent.getService(this,token,Intent(this,ConnectionService::class.java)
                .setAction("disconnect").putExtra("generation",token),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification=Notification.Builder(this,"connection").setSmallIcon(R.drawable.ic_remote)
                .setContentTitle("遥控面板 · 后台保持连接").setContentText("保留使能和当前控制值；点击断开停止控制")
                .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null,"断开",disconnect).build()).build()
            if(Build.VERSION.SDK_INT>=29)startForeground(1,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(1,notification)
            if(!model.backgroundServiceStarted(token)) { stopSelfResult(startId);return START_NOT_STICKY }
            if(wakeLock==null)wakeLock=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"remotecontrol:connection").apply { acquire() }
        } catch(e: RuntimeException) {
            releaseWakeLock()
            model.backgroundServiceFailed(token,e.message ?: "无法保持后台连接")
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }
    private fun releaseWakeLock() { wakeLock?.let { if(it.isHeld)it.release() };wakeLock=null }
    override fun onDestroy() {
        releaseWakeLock()
        generation?.let { model.backgroundServiceFailed(it,"后台服务已停止") }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
