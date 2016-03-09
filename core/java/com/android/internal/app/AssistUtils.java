/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.internal.app;

import android.app.SearchManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Log;


/**
 * Utility method for dealing with the assistant aspects of
 * {@link com.android.internal.app.IVoiceInteractionManagerService IVoiceInteractionManagerService}.
 */
public class AssistUtils {

    private static final String TAG = "AssistUtils";

    private final Context mContext;
    private final IVoiceInteractionManagerService mVoiceInteractionManagerService;

    public AssistUtils(Context context) {
        mContext = context;
        mVoiceInteractionManagerService = IVoiceInteractionManagerService.Stub.asInterface(
                ServiceManager.getService(Context.VOICE_INTERACTION_MANAGER_SERVICE));
    }

    public boolean showSessionForActiveService(Bundle args, int sourceFlags,
            IVoiceInteractionSessionShowCallback showCallback, IBinder activityToken) {
        try {
            if (mVoiceInteractionManagerService != null) {
                return mVoiceInteractionManagerService.showSessionForActiveService(args,
                        sourceFlags, showCallback, activityToken);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to call showSessionForActiveService", e);
        }
        return false;
    }

    public void launchVoiceAssistFromKeyguard() {
        try {
            if (mVoiceInteractionManagerService != null) {
                mVoiceInteractionManagerService.launchVoiceAssistFromKeyguard();
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to call launchVoiceAssistFromKeyguard", e);
        }
    }

    public boolean activeServiceSupportsAssistGesture() {
        try {
            return mVoiceInteractionManagerService != null
                    && mVoiceInteractionManagerService.activeServiceSupportsAssist();
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to call activeServiceSupportsAssistGesture", e);
            return false;
        }
    }

    public boolean activeServiceSupportsLaunchFromKeyguard() {
        try {
            return mVoiceInteractionManagerService != null
                    && mVoiceInteractionManagerService.activeServiceSupportsLaunchFromKeyguard();
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to call activeServiceSupportsLaunchFromKeyguard", e);
            return false;
        }
    }

    public ComponentName getActiveServiceComponentName() {
        try {
            if (mVoiceInteractionManagerService != null) {
                return mVoiceInteractionManagerService.getActiveServiceComponentName();
            } else {
                return null;
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to call getActiveServiceComponentName", e);
            return null;
        }
    }

    public boolean isSessionRunning() {
        try {
            return mVoiceInteractionManagerService != null
                    && mVoiceInteractionManagerService.isSessionRunning();
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to call isSessionRunning", e);
            return false;
        }
    }

    public void hideCurrentSession() {
        try {
            if (mVoiceInteractionManagerService != null) {
                mVoiceInteractionManagerService.hideCurrentSession();
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to call hideCurrentSession", e);
        }
    }

    public void onLockscreenShown() {
        try {
            if (mVoiceInteractionManagerService != null) {
                mVoiceInteractionManagerService.onLockscreenShown();
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to call onLockscreenShown", e);
        }
    }

    public ComponentName getAssistComponentForUser(int userId) {
        final String setting = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                Settings.Secure.ASSISTANT, userId);
        if (setting != null) {
            return ComponentName.unflattenFromString(setting);
        }

        // Fallback to keep backward compatible behavior when there is no user setting.
        if (activeServiceSupportsAssistGesture()) {
            return getActiveServiceComponentName();
        }

        Intent intent = ((SearchManager) mContext.getSystemService(Context.SEARCH_SERVICE))
                .getAssistIntent(false);
        PackageManager pm = mContext.getPackageManager();
        ResolveInfo info = pm.resolveActivityAsUser(intent, PackageManager.MATCH_DEFAULT_ONLY,
                userId);
        if (info != null) {
            return new ComponentName(info.activityInfo.applicationInfo.packageName,
                    info.activityInfo.name);
        }
        return null;
    }

}
