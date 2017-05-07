LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := \
        $(call all-java-files-under, src)

LOCAL_PACKAGE_NAME := BimDroid

LOCAL_CERTIFICATE := platform
LOCAL_PRIVILEGED_MODULE := true
LOCAL_PROGUARD_ENABLED := disabled

LOCAL_RESOURCE_DIR += $(LOCAL_PATH)/res 

LOCAL_STATIC_JAVA_LIBRARIES := BimDroidUsbSerial

include $(BUILD_PACKAGE)

include $(call all-makefiles-under,$(LOCAL_PATH))
