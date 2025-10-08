package com.example.thisseemswrong;

import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.view.KeyEvent;

import java.io.FileDescriptor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class InputSender {
    ParcelFileDescriptor mPfd;
    int mNextSeq = 1;

    public InputSender(ParcelFileDescriptor pfd) {
        mPfd = pfd;
    }

    void sendText(String text) throws Exception {
        for (char c : text.toCharArray()) {
            int keyCode;
            if ('a' <= c && c <= 'z') {
                keyCode = KeyEvent.KEYCODE_A + (c - 'a');
            } else {
                keyCode = KeyEvent.KEYCODE_SPACE;
            }
            sendDownAndUp(keyCode, 10);
        }
    }

    void sendDownAndUp(int keycode, int sleepAfter) throws Exception {
        sendKeyEvent(keycode, KeyEvent.ACTION_DOWN);
        Thread.sleep(10);
        sendKeyEvent(keycode, KeyEvent.ACTION_UP);
        Thread.sleep(sleepAfter);
    }

    void sendKeyEvent(int keycode, int action) throws Exception {
        // https://cs.android.com/android/platform/superproject/main/+/main:frameworks/native/libs/input/InputTransport.cpp;l=606;drc=efb735f4d5a2f04550e33e8aa9485f906018fe4e
        // https://cs.android.com/android/platform/superproject/main/+/main:frameworks/native/libs/input/tests/StructLayout_test.cpp;drc=afb312889849d435e59f8e1014e1385ac419c4ea
        ByteBuffer byteBuffer = ByteBuffer.allocate(104);
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
        byteBuffer.putInt(4, mNextSeq++); // header.seq, will crash if we quickly send many events with same seq or seq==0
        byteBuffer.putLong(8 + 8, SystemClock.uptimeMillis() * 1000L);
        byteBuffer.putInt(8 + 60, action);
        byteBuffer.putInt(8 + 68, keycode);
        Os.write(mPfd.getFileDescriptor(), byteBuffer);
    }

    static boolean looksLikeInputChannel(ParcelFileDescriptor candidate) {
        try {
            Method getsockoptInt = Os.class.getMethod("getsockoptInt", FileDescriptor.class, int.class, int.class);
            return (
                    (int) getsockoptInt.invoke(null, candidate.getFileDescriptor(), OsConstants.SOL_SOCKET, OsConstants.class.getField("SO_DOMAIN").getInt(null)) == OsConstants.AF_UNIX &&
                            (int) getsockoptInt.invoke(null, candidate.getFileDescriptor(), OsConstants.SOL_SOCKET, OsConstants.SO_TYPE) == OsConstants.SOCK_SEQPACKET
            );
        } catch (Exception e) {
            return false;
        }
    }
}
