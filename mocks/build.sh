#!/bin/sh
set -xe
if [ "$ANDROID_SDK_ROOT" = "" ]; then
	ANDROID_SDK_ROOT="$(awk -F = '/sdk\.dir=/ {print $2}' ../local.properties | head -n1)"
fi
#javac android/content/pm/PackageManagerInternal.java
#javac com/example/MockPackageManager.java
#dx --dex --output=../app/src/main/res/raw/mock_system.dex com/example/MockPackageManager.class
mkdir -p ../app/src/main/assets/mock_system
d8 --output ../app/src/main/assets/mock_system com/example/MockPackageManager.class
