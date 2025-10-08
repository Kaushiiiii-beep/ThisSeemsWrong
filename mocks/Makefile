OUTPUT_DEX=../app/src/main/assets/mock_system/classes.dex
PROVIDED_OBJECTS = \
				   android/content/pm/PackageManagerInternal.class
TARGET_OBJECTS = \
				 com/example/MockPackageManager.class

D8 := /home/mb/Android/Sdk/build-tools/34.0.0/d8
JAVAC := /usr/lib/jvm/java-8-openjdk/bin/javac

${OUTPUT_DEX}: ${PROVIDED_OBJECTS} ${TARGET_OBJECTS}
	mkdir -p $(dir ${OUTPUT_DEX})
	${D8} --output $(dir ${OUTPUT_DEX}) --no-desugaring ${TARGET_OBJECTS}

%.class: %.java
	${JAVAC} $<

clean:
	rm -f ${OUTPUT_DEX} ${PROVIDED_OBJECTS} ${TARGET_OBJECTS}
