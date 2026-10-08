package xyz.juicevault.studio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

/** Foreground service whose only job is to keep the process alive while songs download. */
public class KeepAliveService extends Service {
    private static final String CHANNEL = "jv_download";
    private static final int ID = 4711;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureChannel(this);
        Notification n = build(this, "Download laeuft ...", 0, 0);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(ID, n);
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    new NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW));
            }
        }
    }

    static Notification build(Context c, String text, int total, int done) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("JuiceVault Studio")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true);
        if (total > 0) b.setProgress(total, done, false);
        return b.build();
    }

    static void update(Context c, String text, int total, int done) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(ID, build(c, text, total, done));
        } catch (Exception ignored) { }
    }
}
