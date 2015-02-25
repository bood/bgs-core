package com.red_folder.phonegap.plugin.backgroundservice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Created by bood on 2015/2/25.
 */
public class AlarmReceiver extends BroadcastReceiver {
    private static final String TAG = AlarmReceiver.class.getSimpleName();
    @Override
    public void onReceive(Context context, Intent intent) {
        String serviceName = intent.getStringExtra("ServiceName");

        Intent serviceIntent = new Intent(serviceName);
        serviceIntent.putExtra("DoWork", true);
        context.startService(serviceIntent);
    }
}
