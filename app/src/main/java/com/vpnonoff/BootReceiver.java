package com.vpnonoff;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import android.util.Log;

import androidx.core.content.ContextCompat;

public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "VPNOnOff";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            SharedPreferences prefs = context.getSharedPreferences("vpnonoff_prefs", Context.MODE_PRIVATE);
            boolean enabled = prefs.getBoolean("service_enabled", false);
            if (enabled) {
                try {
                    Intent serviceIntent = new Intent(context, WifiMonitorService.class);
                    ContextCompat.startForegroundService(context, serviceIntent);
                } catch (Exception e) {
                    // Android 12+: ForegroundServiceStartNotAllowedException if
                    // background-start exemptions are not satisfied.
                    Log.e(TAG, "BootReceiver failed to start service: " + e.getMessage());
                }
            }
        }
    }
}
