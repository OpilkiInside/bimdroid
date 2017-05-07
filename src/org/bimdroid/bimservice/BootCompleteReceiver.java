package org.bimdroid.bimservice;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Boot-complete broadcast event receiver.
 */
public class BootCompleteReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(DebugUtils.TAG, "BootCompleteReceiver#onReceive, intent: " + intent);

        ComponentName service = context.startService(new Intent(context, BmwIBusService.class));
        Log.d(DebugUtils.TAG, "BootCompleteReceiver#onReceive, service started: " + service);
    }
}
