/*
 * Copyright (C) 2014 The TeamEos Project
 * Author: Randall Rushing aka Bigrushdog
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
 * limitations under the License.
 *
 * Handles binding actions to events, and a simple public api for firing
 * events. Also handles observing user changes to actions and a callback
 * that's called action pre-execution. Let's motion handler know if double
 * tap is enabled in case of different touch handling
 *
 * Holds NX assigned action state and provides interface for executing actions
 *
 */

package com.android.systemui.nx;

import com.android.internal.util.actions.ActionHandler;

import android.os.Handler;
import android.text.TextUtils;

public class NxAction {
    public interface ActionReceiver {
        public void onActionDispatched(NxAction actionEvent, String task);
    }

    private String mAction = "";
    private ActionReceiver mActionReceiver;
    private Handler mHandler;

    private final String mUri;

    private Runnable mActionThread = new Runnable() {
        @Override
        public void run() {
            mActionReceiver.onActionDispatched(NxAction.this, mAction);
        }
    };

    public NxAction(String uri, ActionReceiver receiver, Handler h, String action) {
        this.mUri = uri;
        this.mActionReceiver = receiver;
        this.mHandler = h;
        this.mAction = action;
    }

    public String getUri() {
        return mUri;
    }

    public void setAction(String action) {
        this.mAction = action;
    }

    public String getAction() {
        return mAction;
    }

    public void fireAction() {
        mHandler.post(mActionThread);
    }

    public void cancelAction() {
        mHandler.removeCallbacks(mActionThread);
    }

    public boolean isEnabled() {
        return !isActionEmpty(mAction);
    }

    private boolean isActionEmpty(String action) {
        if (TextUtils.isEmpty(action)) {
            action = ActionHandler.SYSTEMUI_TASK_NO_ACTION;
        }
        return ActionHandler.SYSTEMUI_TASK_NO_ACTION.equals(action);
    }
}
