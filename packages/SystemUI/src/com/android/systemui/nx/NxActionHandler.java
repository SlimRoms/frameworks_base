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
 * Split bar actions: if only one side is enabled, the full bar executes the
 * enabled side action
 *
 */

package com.android.systemui.nx;

import java.util.HashMap;
import java.util.Map;

import com.android.internal.util.actions.ActionHandler;

import com.android.systemui.nx.NxAction.ActionReceiver;
import com.android.systemui.nx.NxGestureHandler.Swipeable;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.SoundEffectConstants;
import android.view.View;

public class NxActionHandler extends ActionHandler implements ActionReceiver, Swipeable {
    final static String TAG = NxActionHandler.class.getSimpleName();

    static final int EVENT_SINGLE_LEFT_TAP = 1;
    static final int EVENT_SINGLE_RIGHT_TAP = 2;
    static final int EVENT_DOUBLE_LEFT_TAP = 3;
    static final int EVENT_DOUBLE_RIGHT_TAP = 4;
    static final int EVENT_LONG_LEFT_PRESS = 5;
    static final int EVENT_LONG_RIGHT_PRESS = 6;
    static final int EVENT_FLING_SHORT_LEFT = 7;
    static final int EVENT_FLING_SHORT_RIGHT = 8;
    static final int EVENT_FLING_LONG_LEFT = 9;
    static final int EVENT_FLING_LONG_RIGHT = 10;

    private Map<Integer, NxAction> mActionMap = new HashMap<Integer, NxAction>();
    private ActionObserver mObserver;
    private View mHost;
    private Handler H = new Handler();
    private boolean isDoubleTapEnabled;
    private boolean mKeyguardShowing;

    public NxActionHandler(Context context, View host) {
        super(context);
        mHost = host;
        mActionMap = new HashMap<Integer, NxAction>();
        loadActionMap();
        mObserver = new ActionObserver(H);
        mObserver.register();
    }

    private void loadActionMap() {
        mActionMap.clear();

        mActionMap.put(EVENT_SINGLE_RIGHT_TAP, new NxAction(Settings.System.NX_SINGLETAP_RIGHT, this,
                H, getAction(Settings.System.NX_SINGLETAP_RIGHT, ActionHandler.SYSTEMUI_TASK_HOME)));

        mActionMap.put(EVENT_SINGLE_LEFT_TAP, new NxAction(Settings.System.NX_SINGLETAP_LEFT, this,
                H, getAction(Settings.System.NX_SINGLETAP_LEFT, null)));

        mActionMap.put(EVENT_DOUBLE_RIGHT_TAP, new NxAction(Settings.System.NX_DOUBLETAP_RIGHT, this,
                H, getAction(Settings.System.NX_DOUBLETAP_RIGHT, null)));

        mActionMap.put(EVENT_DOUBLE_LEFT_TAP, new NxAction(Settings.System.NX_DOUBLETAP_LEFT, this,
                H, getAction(Settings.System.NX_DOUBLETAP_LEFT, null)));

        mActionMap.put(EVENT_LONG_RIGHT_PRESS, new NxAction(Settings.System.NX_LONGPRESS_RIGHT, this,
                H, getAction(Settings.System.NX_LONGPRESS_RIGHT, ActionHandler.SYSTEMUI_TASK_MENU)));

        mActionMap.put(EVENT_LONG_LEFT_PRESS, new NxAction(Settings.System.NX_LONGPRESS_LEFT, this,
                H, getAction(Settings.System.NX_LONGPRESS_LEFT, null)));

        mActionMap.put(EVENT_FLING_SHORT_LEFT, new NxAction(Settings.System.NX_SHORT_FLING_LEFT,
                this, H, getAction(Settings.System.NX_SHORT_FLING_LEFT, ActionHandler.SYSTEMUI_TASK_BACK)));

        mActionMap.put(EVENT_FLING_SHORT_RIGHT, new NxAction(Settings.System.NX_SHORT_FLING_RIGHT,
                this, H, getAction(Settings.System.NX_SHORT_FLING_RIGHT, ActionHandler.SYSTEMUI_TASK_RECENTS)));

        mActionMap.put(EVENT_FLING_LONG_LEFT, new NxAction(Settings.System.NX_LONG_FLING_LEFT,
                this, H, getAction(Settings.System.NX_LONG_FLING_LEFT, null)));

        mActionMap.put(EVENT_FLING_LONG_RIGHT, new NxAction(Settings.System.NX_LONG_FLING_RIGHT,
                this, H, getAction(Settings.System.NX_LONG_FLING_RIGHT, ActionHandler.SYSTEMUI_TASK_ASSIST)));

        isDoubleTapEnabled = ((NxAction) mActionMap.get(EVENT_DOUBLE_RIGHT_TAP))
                .isEnabled() || ((NxAction) mActionMap.get(EVENT_DOUBLE_LEFT_TAP))
                .isEnabled();
    }

    public void setKeyguardShowing(boolean showing) {
        if (mKeyguardShowing == showing) {
            return;
        }
        mKeyguardShowing = showing;
    }

    private String getAction(String uri, String defAction) {
        String action = Settings.System.getStringForUser(
                mContext.getContentResolver(), uri, UserHandle.USER_CURRENT);
        if (TextUtils.isEmpty(action)) {
            if (defAction == null) {
                action = ActionHandler.SYSTEMUI_TASK_NO_ACTION;
            } else {
                action = defAction;
            }
        }
        return action;
    }

    public void fireAction(int type) {
        NxAction action = ((NxAction) mActionMap.get(type));
        // only back is allowed in keyguard
        if (mKeyguardShowing && (action.getAction() != ActionHandler.SYSTEMUI_TASK_BACK)) {
            return;
        }
        action.fireAction();
    }

    public void cancelAction(int type) {
        ((NxAction) mActionMap.get(type)).cancelAction();
    }

    public void unregister() {
        mObserver.unregister();
    }

    private class ActionObserver extends ContentObserver {

        public ActionObserver(Handler handler) {
            super(handler);
        }

        void register() {
            for (int i = 1; i < mActionMap.size() + 1; i++) {
                NxAction action = mActionMap.get(i);
                mContext.getContentResolver().registerContentObserver(
                        Settings.System.getUriFor(action.getUri()), false,
                        ActionObserver.this, UserHandle.USER_ALL);
            }
        }

        void unregister() {
            mContext.getContentResolver().unregisterContentObserver(
                    ActionObserver.this);
        }

        public void onChange(boolean selfChange, Uri uri) {
            loadActionMap();
        }
    }

    @Override
    public boolean handleAction(String action) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public void onActionDispatched(NxAction actionEvent, String task) {
        if (actionEvent.isEnabled()) {
            mHost.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            mHost.playSoundEffect(SoundEffectConstants.CLICK);
            performTask(task);
        }
    }

    @Override
    public boolean onDoubleTapEnabled() {
        return isDoubleTapEnabled;
    }

    @Override
    public void onShortLeftSwipe() {
        fireAction(EVENT_FLING_SHORT_LEFT);
    }

    @Override
    public void onLongLeftSwipe() {
        fireAction(EVENT_FLING_LONG_LEFT);
    }

    @Override
    public void onShortRightSwipe() {
        fireAction(EVENT_FLING_SHORT_RIGHT);
    }

    @Override
    public void onLongRightSwipe() {
        fireAction(EVENT_FLING_LONG_RIGHT);
    }

    @Override
    public void onSingleLeftPress() {
        boolean isEnabled = ((NxAction) mActionMap.get(EVENT_SINGLE_LEFT_TAP))
                .isEnabled();
        fireAction(isEnabled ? EVENT_SINGLE_LEFT_TAP : EVENT_SINGLE_RIGHT_TAP);
    }

    @Override
    public void onSingleRightPress() {
        boolean isEnabled = ((NxAction) mActionMap.get(EVENT_SINGLE_RIGHT_TAP))
                .isEnabled();
        fireAction(isEnabled ? EVENT_SINGLE_RIGHT_TAP : EVENT_SINGLE_LEFT_TAP);
    }

    @Override
    public void onDoubleLeftTap() {
        boolean isEnabled = ((NxAction) mActionMap.get(EVENT_DOUBLE_LEFT_TAP))
                .isEnabled();
        fireAction(isEnabled ? EVENT_DOUBLE_LEFT_TAP : EVENT_DOUBLE_RIGHT_TAP);
    }

    @Override
    public void onDoubleRightTap() {
        boolean isEnabled = ((NxAction) mActionMap.get(EVENT_DOUBLE_RIGHT_TAP))
                .isEnabled();
        fireAction(isEnabled ? EVENT_DOUBLE_RIGHT_TAP : EVENT_DOUBLE_LEFT_TAP);
    }

    @Override
    public void onLongLeftPress() {
        boolean isEnabled = ((NxAction) mActionMap.get(EVENT_LONG_LEFT_PRESS))
                .isEnabled();
        if (isLockTaskOn()) {
            turnOffLockTask();
        } else {
            fireAction(isEnabled ? EVENT_LONG_LEFT_PRESS : EVENT_LONG_RIGHT_PRESS);
        }
    }

    @Override
    public void onLongRightPress() {
        boolean isEnabled = ((NxAction) mActionMap.get(EVENT_LONG_RIGHT_PRESS))
                .isEnabled();
        if (isLockTaskOn()) {
            turnOffLockTask();
        } else {
            fireAction(isEnabled ? EVENT_LONG_RIGHT_PRESS : EVENT_LONG_LEFT_PRESS);
        }
    }
}
