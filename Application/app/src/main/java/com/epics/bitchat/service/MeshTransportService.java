package com.epics.bitchat.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import com.epics.bitchat.MainActivity;
import com.epics.bitchat.R;
import com.epics.bitchat.mesh.MeshManager;

/**
 * Foreground service that keeps the mesh stack alive while the user navigates
 * tabs (and after the app is backgrounded).
 */
public class MeshTransportService extends Service {

    public static final String CHANNEL_ID = "bitchat_mesh_channel";
    private static final int NOTIFICATION_ID = 1;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                    ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    : 0;
            startForeground(NOTIFICATION_ID, notification, type);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        MeshManager.get(this).start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        MeshManager.get(this).stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_mesh)
                .setContentTitle("BitChat Mesh")
                .setContentText("Mesh traffic engine running")
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Mesh engine", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps the BitChat mesh engine running");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    public static void start(Context context) {
        Intent intent = new Intent(context, MeshTransportService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }
}
