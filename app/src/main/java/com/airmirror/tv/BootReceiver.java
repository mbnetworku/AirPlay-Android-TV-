package com.airmirror.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Starts the AirPlay receiver in the background when the TV turns on and right after the app is
 * updated, so an iPhone can find the TV without anyone opening the app first. Can be turned off
 * in Settings ("Start when the TV turns on").
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }
        if (!MirrorService.startOnBoot(context) || !MirrorService.usedAsReceiver(context)) {
            return; // e.g. a phone that only uses "Share to TV" never runs the TV receiver
        }
        Intent service = new Intent(context, MirrorService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
        } catch (RuntimeException e) {
            // Some vendor builds refuse background starts; the app still starts when opened.
            Log.w("BootReceiver", "Could not start the receiver at boot", e);
        }
    }
}
