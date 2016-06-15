LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := services.core

LOCAL_SRC_FILES += \
    $(call all-java-files-under,java) \
    java/com/android/server/EventLogTags.logtags \
    java/com/android/server/am/EventLogTags.logtags

LOCAL_JAVA_LIBRARIES := services.net telephony-common

## Slim Framework
LOCAL_JAVA_LIBRARIES += org.slim.framework

LOCAL_STATIC_JAVA_LIBRARIES := tzdata_update

include $(BUILD_STATIC_JAVA_LIBRARY)
