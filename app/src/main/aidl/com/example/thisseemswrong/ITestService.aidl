// ITestService.aidl
package com.example.thisseemswrong;

// Declare any non-default types here with import statements

interface ITestService {
    IBinder getMediaSessionService();
    void openSomeFds();
    int getTestServicePid();
}