package com.example.thisseemswrong;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.BaseBundle;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.os.UserManager;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.SparseArray;
import android.util.SparseIntArray;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dalvik.system.PathClassLoader;

public class TestService extends Service {
    IBinder mMediaSessionServiceBinder;
    File mFilesDir;

    @Override
    public void onCreate() {
        super.onCreate();
        mFilesDir = getFilesDir();
        try {
            MiscUtils.allowHiddenApis();

            // Enable Bundle defusing
            BaseBundle.class
                    .getMethod("setShouldDefuse", boolean.class)
                    .invoke(null, true);

            // Prepare ClassLoader for system_server
            ClassLoader loader = (ClassLoader) Class.forName("com.android.internal.os.ClassLoaderFactory")
                    .getMethod(
                            "createClassLoader",
                            String.class,
                            String.class,
                            String.class,
                            ClassLoader.class,
                            int.class,
                            boolean.class,
                            String.class,
                            List.class,
                            List.class,
                            List.class
                    )
                    .invoke(
                            null,
                            Os.getenv("SYSTEMSERVERCLASSPATH"),
                            null,
                            null,
                            Service.class.getClassLoader(),
                            10000,
                            true,
                            null,
                            null,
                            new ArrayList<>(Collections.singletonList("ALL")),
                            null
                    );

            // Prepare ClassLoader for mock classes
            File mockSystemClassesDir = getDir("mock_system", MODE_PRIVATE);
            File mockSystemClassesDex = new File(mockSystemClassesDir, "classes.dex");
            if (!mockSystemClassesDex.exists()) {
                try (
                        InputStream inputStream = getAssets().open("mock_system/classes.dex");
                        OutputStream outputStream = new FileOutputStream(mockSystemClassesDex)
                ) {
                    FileUtils.copy(inputStream, outputStream);
                }
                mockSystemClassesDex.setReadOnly();
            }
            PathClassLoader mockLoader = new PathClassLoader(
                    mockSystemClassesDex.getAbsolutePath(),
                    loader
            );

            // Mock Package Manager
            Class<?> localServicesClass = loader.loadClass("com.android.server.LocalServices");
            Class<?> packageManagerInternalClass = loader.loadClass("android.content.pm.PackageManagerInternal");
            localServicesClass
                    .getMethod("addService", Class.class, Object.class)
                    .invoke(
                            null,
                            packageManagerInternalClass,
                            mockLoader
                                    .loadClass("com.example.MockPackageManager")
                                    .getConstructor(int.class)
                                    .newInstance(Process.myUid())
                    )
            ;

            // Instantiate MediaSessionService
            Class<?> mediaSessionServiceClass = loader.loadClass("com.android.server.media.MediaSessionService");
            Object mediaSessionService = mediaSessionServiceClass.getConstructor(Context.class).newInstance(this);

            // Prepare mock Context, including mock AudioPlayerStateMonitor
            ContextWrapper mockContext = new ContextWrapper(null) {
                @Override
                public Object getSystemService(String name) {
                    if (AUDIO_SERVICE.equals(name)) {
                        try {
                            AudioManager audioManager = AudioManager.class.newInstance();
                            Field playbackCallbackListField = AudioManager.class.getDeclaredField("mPlaybackCallbackList");
                            playbackCallbackListField.setAccessible(true);
                            ArrayList<Object> arrayList = new ArrayList<>();
                            Constructor<?> itemConstructor =
                                    Class.forName("android.media.AudioManager$AudioPlaybackCallbackInfo")
                                            .getDeclaredConstructor(AudioManager.AudioPlaybackCallback.class, Handler.class);
                            itemConstructor.setAccessible(true);
                            arrayList.add(itemConstructor.newInstance(new Object[2]));
                            playbackCallbackListField.set(audioManager, arrayList);
                            return audioManager;
                        } catch (ReflectiveOperationException e) {
                            throw new RuntimeException(e);
                        }
                    }
                    if (USER_SERVICE.equals(name)) {
                        return null;
                    }
                    throw new IllegalArgumentException();
                }

                @Override
                public String getSystemServiceName(Class<?> serviceClass) {
                    if (serviceClass == UserManager.class) {
                        return USER_SERVICE;
                    }
                    throw new IllegalArgumentException();
                }

                @Override
                public Intent registerReceiver(@Nullable BroadcastReceiver receiver, IntentFilter filter, @Nullable String broadcastPermission, @Nullable Handler scheduler) {
                    return null;
                }
            };
            Class<?> stateMonitorClass = loader.loadClass("com.android.server.media.AudioPlayerStateMonitor");
            Constructor<?> stateMonitorConstructor = stateMonitorClass.getDeclaredConstructor(Context.class);
            stateMonitorConstructor.setAccessible(true);
            Field stateMonitorField = mediaSessionServiceClass.getDeclaredField("mAudioPlayerStateMonitor");
            stateMonitorField.setAccessible(true);
            stateMonitorField.set(mediaSessionService, stateMonitorConstructor.newInstance(mockContext));

            // Prepare user record
            Class<?> userRecordClass = loader.loadClass("com.android.server.media.MediaSessionService$FullUserRecord");
            Constructor<?> userRecordConstructor = userRecordClass.getDeclaredConstructor(mediaSessionServiceClass, int.class);
            userRecordConstructor.setAccessible(true);

            int userId = MiscUtils.myUserId();
            Field fullUserIdsField = mediaSessionServiceClass.getDeclaredField("mFullUserIds");
            fullUserIdsField.setAccessible(true);
            ((SparseIntArray) fullUserIdsField.get(mediaSessionService)).put(userId, userId);
            Field userRecordsField = mediaSessionServiceClass.getDeclaredField("mUserRecords");
            userRecordsField.setAccessible(true);
            ((SparseArray) userRecordsField.get(mediaSessionService)).put(userId, userRecordConstructor.newInstance(mediaSessionService, userId));

            // Start thread
            Field recordThreadField = mediaSessionServiceClass.getDeclaredField("mRecordThread");
            recordThreadField.setAccessible(true);
            ((HandlerThread) recordThreadField.get(mediaSessionService)).start();

            // Publish Binder
            Field implField = mediaSessionServiceClass.getDeclaredField("mSessionManagerImpl");
            mMediaSessionServiceBinder = (IBinder) implField.get(mediaSessionService);
        } catch (ReflectiveOperationException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return new ITestService.Stub() {
            @Override
            public IBinder getMediaSessionService() {
                return mMediaSessionServiceBinder;
            }

            @Override
            public int getTestServicePid() {
                return Process.myPid();
            }

            @Override
            public void openSomeFds() {
                try {
                    for (int i = 0; i < 3; i++) {
                        //noinspection OctalInteger
                        MiscUtils.sLeakedStuff.add(Os.open(
                                new File(mFilesDir, "some_fd_" + i).getAbsolutePath(),
                                OsConstants.O_RDWR | OsConstants.O_CREAT | OsConstants.O_TRUNC,
                                0600
                        ));
                    }
                } catch (ErrnoException e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }
}
