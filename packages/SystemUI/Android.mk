LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := $(call all-java-files-under, src) \
    $(call all-java-files-under, ../../../opt/slim/packages/SlimSystemUI/src) \
    src/com/android/systemui/EventLogTags.logtags

LOCAL_STATIC_JAVA_LIBRARIES := Keyguard \
    android-opt-cards

LOCAL_JAVA_LIBRARIES := telephony-common

# Slim Framework
LOCAL_JAVA_LIBRARIES += org.slim.framework
LOCAL_FULL_LIBS_MANIFEST_FILES := frameworks/opt/slim/packages/SlimSystemUI/AndroidManifest.xml

LOCAL_PACKAGE_NAME := SystemUI
LOCAL_CERTIFICATE := platform
LOCAL_PRIVILEGED_MODULE := true

LOCAL_PROGUARD_FLAG_FILES := proguard.flags

LOCAL_RESOURCE_DIR := \
    frameworks/base/packages/Keyguard/res \
    $(LOCAL_PATH)/res \
    frameworks/opt/slim/packages/SlimSystemUI/res \
    $(LOCAL_PATH)/../../../../frameworks/opt/cards/res

LOCAL_AAPT_FLAGS := --auto-add-overlay --extra-packages com.android.keyguard
LOCAL_AAPT_FLAGS += --extra-packages com.android.cards

ifneq ($(SYSTEM_UI_INCREMENTAL_BUILDS),)
    LOCAL_PROGUARD_ENABLED := disabled
    LOCAL_JACK_ENABLED := incremental
endif

include frameworks/base/packages/SettingsLib/common.mk

include $(BUILD_PACKAGE)

#ifeq ($(EXCLUDE_SYSTEMUI_TESTS),)
#    include $(call all-makefiles-under,$(LOCAL_PATH))
#endif
