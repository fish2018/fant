package org.antjs.runtime.demo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.antjs.runtime.StorageLocation;

/** Foreground host for a running FAnt API. It persists typed locations, not URI-as-path strings. */
public final class BackendService extends Service {
    private static final String PREFS = "ant-api-demo";
    private static final String KEEP_ALIVE = "foreground-service-requested";
    private static final String PROJECT_KIND = "foreground-project-kind";
    private static final String PROJECT_LOCATION = "foreground-project-location";
    private static final String CACHE_KIND = "foreground-cache-kind";
    private static final String CACHE_LOCATION = "foreground-cache-location";
    private static final String ACTION_START = "org.antjs.runtime.demo.START";
    private static final String ACTION_STOP = "org.antjs.runtime.demo.STOP";
    private static final String CHANNEL_ID = "ant-api-runtime";
    private static final int NOTIFICATION_ID = 8787;
    private static final long WATCHDOG_INTERVAL_MS = 5_000L;

    private AntBackendController backend;
    private Handler watchdogHandler;
    private StorageLocation project;
    private StorageLocation cache;

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (backend != null && isKeepAliveRequested(BackendService.this)
                    && project != null) {
                backend.watchdog(project);
                watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS);
            }
        }
    };

    static void startForProject(Context context, StorageLocation project, StorageLocation cache) {
        if (project == null) throw new IllegalArgumentException("project location is required");
        Context app = context.getApplicationContext();
        android.content.SharedPreferences.Editor editor = app.getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putBoolean(KEEP_ALIVE, true)
                .putInt(PROJECT_KIND, project.kind().ordinal())
                .putString(PROJECT_LOCATION, project.value());
        if (cache == null) {
            editor.remove(CACHE_KIND).remove(CACHE_LOCATION);
        } else {
            editor.putInt(CACHE_KIND, cache.kind().ordinal())
                    .putString(CACHE_LOCATION, cache.value());
        }
        editor.apply();
        Intent intent = new Intent(app, BackendService.class).setAction(ACTION_START);
        putLocation(intent, "project", project);
        if (cache != null) putLocation(intent, "cache", cache);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent);
        else app.startService(intent);
    }

    static void stop(Context context) {
        Context app = context.getApplicationContext();
        app.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEEP_ALIVE, false).remove(PROJECT_KIND).remove(PROJECT_LOCATION)
                .remove(CACHE_KIND).remove(CACHE_LOCATION).apply();
        AntBackendController.shared(app).stop();
        app.stopService(new Intent(app, BackendService.class));
    }

    static boolean isKeepAliveRequested(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEEP_ALIVE, false);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        backend = AntBackendController.shared(getApplicationContext());
        watchdogHandler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
        startForegroundCompat(buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stop(this);
            return START_NOT_STICKY;
        }
        project = readLocation(intent, "project", PROJECT_KIND, PROJECT_LOCATION);
        cache = readLocation(intent, "cache", CACHE_KIND, CACHE_LOCATION);
        if (!isKeepAliveRequested(this) || project == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        backend.setLocations(project, cache);
        if (intent != null && ACTION_START.equals(intent.getAction())) backend.start(project);
        else backend.ensureStarted(project);
        watchdogHandler.removeCallbacks(watchdog);
        watchdogHandler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (watchdogHandler != null) watchdogHandler.removeCallbacks(watchdog);
        stopForegroundCompat();
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static void putLocation(Intent intent, String prefix, StorageLocation location) {
        intent.putExtra(prefix + ".kind", location.kind().ordinal())
                .putExtra(prefix + ".value", location.value());
    }

    private StorageLocation readLocation(Intent intent, String prefix, String kindKey, String valueKey) {
        int kind;
        String value;
        if (intent != null && intent.hasExtra(prefix + ".value")) {
            kind = intent.getIntExtra(prefix + ".kind", -1);
            value = intent.getStringExtra(prefix + ".value");
        } else {
            android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            kind = prefs.getInt(kindKey, -1);
            value = prefs.getString(valueKey, null);
        }
        if (value == null || (kind != 0 && kind != 1)) return null;
        try {
            return kind == StorageLocation.Kind.SAF_TREE.ordinal()
                    ? StorageLocation.safTree(value) : StorageLocation.filePath(value);
        } catch (RuntimeException error) {
            return null;
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "FAnt API 运行服务", NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent launch = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent content = PendingIntent.getActivity(this, 0, launch, flags);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, BackendService.class).setAction(ACTION_STOP), flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return builder.setSmallIcon(org.antjs.runtime.demo.R.drawable.app_icon)
                .setContentTitle("FAnt API 正在运行")
                .setContentText("后台服务已保持")
                .setContentIntent(content)
                .addAction(org.antjs.runtime.demo.R.drawable.ic_stop, "停止", stop)
                .setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
                .setShowWhen(false).build();
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else startForeground(NOTIFICATION_ID, notification);
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
    }
}
