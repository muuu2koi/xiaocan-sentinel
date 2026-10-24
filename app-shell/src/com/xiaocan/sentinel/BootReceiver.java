package com.xiaocan.sentinel;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 开机自启: 仅当之前在监控中时恢复 */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        boolean wasRunning = context.getSharedPreferences(context.getPackageName(), Context.MODE_PRIVATE)
                .getBoolean(PollService.PREF_RUNNING, false);
        if (!wasRunning) return;
        Intent i = new Intent(context, PollService.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i);
        else context.startService(i);
    }
}
