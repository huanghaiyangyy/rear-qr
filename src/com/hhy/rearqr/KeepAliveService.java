package com.hhy.rearqr;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.util.Log;

/**
 * Keeps the process out of HyperOS's background freezer (GreezeManager freezes the uid ~5 s after the app goes
 * to the background; Quick Settings tile clicks then queue up in the frozen process and the trampoline launch is
 * lost). A foreground service is exempt. Without the notification permission its notification stays hidden.
 */
public final class KeepAliveService extends Service {
    private static final String CH = "keepalive";

    /** Starts the service if 「后台常驻」 is on, stops it otherwise. */
    static void sync(Context c) {
        if (!App.resident(c)) {
            c.getApplicationContext().stopService(new Intent(c.getApplicationContext(), KeepAliveService.class));
            return;
        }
        try {
            c.getApplicationContext().startForegroundService(new Intent(c.getApplicationContext(), KeepAliveService.class));
        } catch (Throwable t) {
            Log.w(Capture.TAG, "keepalive start failed: " + t);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!App.resident(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CH, "后台保持", NotificationManager.IMPORTANCE_MIN));
            Notification n = new Notification.Builder(this, CH)
                    .setSmallIcon(R.drawable.ic_tile)
                    .setContentTitle("二维码贴背屏待命中")
                    .setOngoing(true)
                    .build();
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            Log.i(Capture.TAG, "keepalive foreground ok");
        } catch (Throwable t) {
            Log.w(Capture.TAG, "keepalive startForeground failed: " + t);
            stopSelf();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }
}
