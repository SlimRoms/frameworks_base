# Copyright 2014 SlimRoms Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

ifneq ($(TARGET_BOARD_PLATFORM),omap4)
LOCAL_PATH := frameworks/base/data/sounds/slim
else
# use 44.1 kHz UI sounds
LOCAL_PATH := frameworks/base/data/sounds/slim_441
endif

define create-copy-media-files
$(strip $(foreach fp,\
  $(patsubst ./%,%, \
    $(shell cd $(LOCAL_PATH) ; \
            find -L -name "*.ogg" -and -not -name ".*") \
  ),\
  $(LOCAL_PATH)/$(fp):system/media/audio/$(fp)\
))
endef

PRODUCT_COPY_FILES += $(call create-copy-media-files)
