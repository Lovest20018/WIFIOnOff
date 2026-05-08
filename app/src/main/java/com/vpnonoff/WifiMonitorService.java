package com.vpnonoff;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import java.util.HashSet;
import java.util.Set;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

public class WifiMonitorService extends Service {

    private static final String TAG = "VPNOnOff";
    private static final String CHANNEL_ID = "vpnonoff_channel";
    private static final int NOTIFICATION_ID = 1;

    private static final String CLASH_PACKAGE = "com.github.metacubex.clash.meta";
    private static final String CLASH_CONTROL = "com.github.kr328.clash.ExternalControlActivity";
    private static final String ACTION_START = CLASH_PACKAGE + ".action.START_CLASH";
    private static final String ACTION_STOP = CLASH_PACKAGE + ".action.STOP_CLASH";

    // Client IDs — must match R.array.vpn_clients order
    private static final int CLIENT_CMFA = 0;
    private static final int CLIENT_BETTBOX = 1;
    private static final int CLIENT_FLCLASH = 2;
    private static final int CLIENT_SURFBOARD = 3;

    // Bettbox
    private static final String BETTBOX_PACKAGE = "com.appshub.bettbox";
    private static final String BETTBOX_ACTIVITY = "com.appshub.bettbox.TempActivity";

    // FlClash
    private static final String FLCLASH_PACKAGE = "com.follow.clash";

    // Surfboard
    private static final String SURFBOARD_PACKAGE = "com.getsurfboard";

    private ConnectivityManager connectivityManager;
    private WifiManager wifiManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private HandlerThread handlerThread;
    private Handler handler;
    private final Set<Network> wifiNetworks = new HashSet<>();
    private String targetSsid = "";
    private String connectedSsid = null;
    private int selectedClient = CLIENT_CMFA;
    private String lastDesiredAction = null;
    private static final long DEBOUNCE_MS = 500;
    private Runnable pendingAction;

    @Override
    public void onCreate() {
        super.onCreate();

        android.content.SharedPreferences prefs = getSharedPreferences("vpnonoff_prefs", MODE_PRIVATE);
        selectedClient = prefs.getInt("selected_client", CLIENT_CMFA);
        targetSsid = prefs.getString("target_ssid", "").trim();

        handlerThread = new HandlerThread("WifiMonitorThread");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());

        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("监听中..."));
        registerWifiCallback();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (connectivityManager != null && networkCallback != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        }
        if (handler != null && pendingAction != null) {
            handler.removeCallbacks(pendingAction);
        }
        if (handlerThread != null) {
            handlerThread.quitSafely();
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        try {
            Intent restartIntent = new Intent(this, WifiMonitorService.class);
            PendingIntent pendingIntent = PendingIntent.getService(
                    this, 0, restartIntent,
                    PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
            android.app.AlarmManager alarm = (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
            alarm.set(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    android.os.SystemClock.elapsedRealtime() + 3000, pendingIntent);
        } catch (Exception e) {
            // Android 12+: scheduling/restart may fail under background restrictions
            Log.e(TAG, "onTaskRemoved restart scheduling failed: " + e.getMessage());
        }
        super.onTaskRemoved(rootIntent);
    }

    private void registerWifiCallback() {
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(network);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    wifiNetworks.add(network);
                    scheduleAction();
                }
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    wifiNetworks.add(network);
                    android.net.TransportInfo transportInfo = caps.getTransportInfo();
                    if (transportInfo instanceof WifiInfo) {
                        String ssid = ((WifiInfo) transportInfo).getSSID();
                        if (ssid != null && !ssid.equals("<unknown ssid>")) {
                            connectedSsid = ssid.replace("\"", "");
                        }
                    }
                    if (connectedSsid == null) {
                        connectedSsid = getSsidViaWifiManager();
                    }
                } else {
                    wifiNetworks.remove(network);
                }
                scheduleAction();
            }

            @Override
            public void onLost(Network network) {
                wifiNetworks.remove(network);
                if (wifiNetworks.isEmpty()) {
                    connectedSsid = null;
                }
                scheduleAction();
            }
        };

        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();
        connectivityManager.registerNetworkCallback(request, networkCallback, handler);

        // Sync VPN state on startup, run on handler thread to avoid race with callbacks
        handler.post(() -> {
            Network[] networks = connectivityManager.getAllNetworks();
            for (Network network : networks) {
                NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(network);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    wifiNetworks.add(network);
                    if (connectedSsid == null) {
                        android.net.TransportInfo transportInfo = caps.getTransportInfo();
                        if (transportInfo instanceof WifiInfo) {
                            String ssid = ((WifiInfo) transportInfo).getSSID();
                            if (ssid != null && !ssid.equals("<unknown ssid>")) {
                                connectedSsid = ssid.replace("\"", "");
                            }
                        }
                    }
                }
            }
            if (connectedSsid == null && !wifiNetworks.isEmpty()) {
                connectedSsid = getSsidViaWifiManager();
            }
            boolean shouldStartVpn = shouldStartVpn();
            Log.i(TAG, "Initial WiFi state: " + (wifiNetworks.isEmpty() ? "disconnected" : "connected")
                    + ", SSID: " + connectedSsid + ", target: " + targetSsid);
            if (shouldStartVpn) {
                executeAction(ACTION_START);
            } else {
                // Target WiFi connected — just record state, don't send redundant STOP
                lastDesiredAction = ACTION_STOP;
                updateNotification("WiFi " + connectedSsid + " 已连接 - VPN 关闭");
            }
        });
    }

    private String getSsidViaWifiManager() {
        try {
            if (wifiManager != null) {
                WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                if (wifiInfo != null) {
                    String ssid = wifiInfo.getSSID();
                    if (ssid != null && !ssid.equals("<unknown ssid>")) {
                        return ssid.replace("\"", "");
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "WifiManager fallback failed: " + e.getMessage());
        }
        return null;
    }

    private boolean shouldStartVpn() {
        if (targetSsid.isEmpty()) {
            // No target SSID: any WiFi connected → VPN off
            return wifiNetworks.isEmpty();
        }
        // Target SSID set: only turn off VPN when connected to that specific SSID
        return !targetSsid.equals(connectedSsid);
    }

    private void scheduleAction() {
        if (pendingAction != null) {
            handler.removeCallbacks(pendingAction);
        }
        pendingAction = () -> executeAction(shouldStartVpn() ? ACTION_START : ACTION_STOP);
        handler.postDelayed(pendingAction, DEBOUNCE_MS);
    }

    private void executeAction(String action) {
        if (action.equals(lastDesiredAction)) {
            Log.i(TAG, "Skipping duplicate action: " + action);
            return;
        }

        boolean startVpn = shouldStartVpn();
        Log.i(TAG, "WiFi SSID: " + connectedSsid + ", target: " + targetSsid
                + ", action: " + (startVpn ? "START" : "STOP"));

        if (controlClash(action)) {
            lastDesiredAction = action;
            String notification;
            if (startVpn) {
                notification = connectedSsid != null
                        ? "WiFi " + connectedSsid + " (非目标) - VPN 开启"
                        : "WiFi 未连接 - VPN 开启";
            } else {
                notification = "WiFi " + connectedSsid + " 已连接 - VPN 关闭";
            }
            updateNotification(notification);
            sendStatusBroadcast(!startVpn);
        }
    }

    private boolean controlClash(String action) {
        Log.i(TAG, "Controlling VPN client (id=" + selectedClient + "): " + action);
        boolean success;
        switch (selectedClient) {
            case CLIENT_BETTBOX:
                success = controlBettboxViaShizuku(action);
                break;
            case CLIENT_FLCLASH:
                success = controlFlClashViaShizuku(action);
                break;
            case CLIENT_SURFBOARD:
                success = controlSurfboardViaShizuku(action);
                break;
            default:
                // CMFA — original logic, calling unmodified method
                success = controlClashViaShizuku(action);
                break;
        }
        if (!success) {
            Log.e(TAG, "Failed to control VPN client - check Shizuku is running and authorized");
        }
        return success;
    }

    private boolean controlClashViaShizuku(String action) {
        try {
            if (!Shizuku.pingBinder()) {
                Log.w(TAG, "Shizuku not available");
                return false;
            }

            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Shizuku permission not granted");
                return false;
            }

            if (ACTION_STOP.equals(action)) {
                return stopVpnWithRetry(CLASH_PACKAGE);
            }

            String cmd = "am start"
                    + " -a " + action
                    + " -n " + CLASH_PACKAGE + "/" + CLASH_CONTROL
                    + " --activity-multiple-task"
                    + " --activity-no-history"
                    + " --activity-no-animation"
                    + " --activity-exclude-from-recents";

            return executeShizukuCmd(cmd);
        } catch (Exception e) {
            Log.e(TAG, "Shizuku execution failed: " + e.getMessage());
            return false;
        }
    }

    private boolean controlBettboxViaShizuku(String action) {
        if (ACTION_STOP.equals(action)) {
            return stopVpnWithRetry(BETTBOX_PACKAGE);
        }
        String cmd = "am start"
                + " -a com.appshub.bettbox.action.START"
                + " -n " + BETTBOX_PACKAGE + "/" + BETTBOX_ACTIVITY
                + " --activity-multiple-task"
                + " --activity-no-history"
                + " --activity-no-animation"
                + " --activity-exclude-from-recents";
        return executeShizukuCmd(cmd);
    }

    private boolean controlFlClashViaShizuku(String action) {
        if (ACTION_STOP.equals(action)) {
            return stopVpnWithRetry(FLCLASH_PACKAGE);
        }
        String cmd = "am start"
                + " -a com.follow.clash.action.START"
                + " -n " + FLCLASH_PACKAGE + "/.TempActivity"
                + " --activity-multiple-task"
                + " --activity-no-history"
                + " --activity-no-animation"
                + " --activity-exclude-from-recents";
        return executeShizukuCmd(cmd);
    }

    private boolean controlSurfboardViaShizuku(String action) {
        if (ACTION_STOP.equals(action)) {
            return stopVpnWithRetry(SURFBOARD_PACKAGE);
        }
        // Restrict to Surfboard's package so other apps registering the
        // surfboard:// scheme cannot intercept the start intent.
        String cmd = "am start -a android.intent.action.VIEW -d surfboard:///start -p " + SURFBOARD_PACKAGE;
        return executeShizukuCmd(cmd);
    }

    private boolean executeShizukuCmd(String cmd) {
        try {
            if (!Shizuku.pingBinder()) {
                Log.w(TAG, "Shizuku not available");
                return false;
            }

            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Shizuku permission not granted");
                return false;
            }

            Log.i(TAG, "Executing via Shizuku: " + cmd);
            Method newProcess = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            newProcess.setAccessible(true);
            Process process = (Process) newProcess.invoke(null,
                    new String[]{"sh", "-c", cmd}, null, null);
            int exitCode = process.waitFor();
            Log.i(TAG, "Shizuku command exit code: " + exitCode);
            return exitCode == 0;
        } catch (Exception e) {
            Log.e(TAG, "Shizuku execution failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Stop VPN with retry and verification.
     * Samsung One UI 8.0 may report am force-stop success (exit 0) but leave VPN running.
     * We verify the process is actually dead, retrying with escalating methods if needed.
     */
    private boolean stopVpnWithRetry(String packageName) {
        String[] stopMethods = {
                "am force-stop " + packageName,
                "am force-stop " + packageName,
                "kill -9 $(pidof " + packageName + ") 2>/dev/null; am force-stop " + packageName
        };

        for (int i = 0; i < stopMethods.length; i++) {
            String cmd = stopMethods[i];
            Log.i(TAG, "Stop attempt " + (i + 1) + "/" + stopMethods.length + ": " + cmd);
            executeShizukuCmd(cmd);

            // Wait a bit then verify
            try { Thread.sleep(800); } catch (InterruptedException ignored) {}

            if (!isVpnProcessRunning(packageName)) {
                Log.i(TAG, "VPN process confirmed stopped after attempt " + (i + 1));
                return true;
            }
            Log.w(TAG, "VPN process still running after attempt " + (i + 1));
        }

        Log.e(TAG, "Failed to stop VPN after " + stopMethods.length + " attempts");
        return false;
    }

    private boolean isVpnProcessRunning(String packageName) {
        try {
            Method newProcess = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            newProcess.setAccessible(true);
            Process process = (Process) newProcess.invoke(null,
                    new String[]{"sh", "-c", "pidof " + packageName}, null, null);
            java.io.InputStream is = process.getInputStream();
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            int b;
            while ((b = is.read()) != -1) {
                baos.write(b);
            }
            process.waitFor();
            String output = baos.toString().trim();
            boolean running = !output.isEmpty();
            Log.i(TAG, "isVpnProcessRunning(" + packageName + "): " + running + " pid=" + output);
            return running;
        } catch (Exception e) {
            Log.w(TAG, "isVpnProcessRunning check failed: " + e.getMessage());
            return false;
        }
    }

    private void sendStatusBroadcast(boolean wifiConnected) {
        Intent intent = new Intent("com.vpnonoff.STATUS_CHANGED");
        intent.putExtra("wifi_connected", wifiConnected);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "VPN OnOff Service",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("WiFi 状态监听服务");
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("VPN OnOff")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(NOTIFICATION_ID, buildNotification(text));
    }
}
