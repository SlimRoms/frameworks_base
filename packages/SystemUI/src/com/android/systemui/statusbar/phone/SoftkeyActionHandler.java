/*
 * Copyright (C) 2014 The TeamEos Project
 *
 * Author: Randall Rushing aka Bigrushdog (randall.rushing@gmail.com)
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
 * Manage KeyButtonView action states and action dispatch
 *
 */

package com.android.systemui.statusbar.phone;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.android.internal.util.actions.ActionHandler;
import com.android.internal.util.actions.ActionUtils;

import com.android.systemui.R;
import com.android.systemui.statusbar.policy.KeyButtonView;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.ViewConfiguration;

public class SoftkeyActionHandler extends ActionHandler {
    private static final int DT_TIMEOUT = ViewConfiguration.getDoubleTapTimeout();
    private static final int LP_TIMEOUT = ViewConfiguration.getLongPressTimeout();
    final int LP_TIMEOUT_MAX = LP_TIMEOUT;
    // no less than 25ms longer than single tap timeout
    final int LP_TIMEOUT_MIN = 25;

    public static class ButtonInfo {
        public String singleAction, doubleTapAction, longPressAction, lpUri, dtUri;

        public ButtonInfo(String singleTap, String LpUri, String DtUri) {
            this.singleAction = singleTap;
            this.lpUri = LpUri;
            this.dtUri = DtUri;
        }

        public void init(ContentResolver resolver) {
            loadLongPress(resolver);
            loadDoubleTap(resolver);
        }

        public boolean resetAction(ContentResolver resolver, Uri uri) {
            if (Settings.System.getUriFor(lpUri).equals(uri)) {
                loadLongPress(resolver);
                return true;
            } else if (Settings.System.getUriFor(dtUri).equals(uri)) {
                loadDoubleTap(resolver);
                return true;
            }
            return false;
        }

        public void loadDoubleTap(ContentResolver resolver) {
            doubleTapAction = getActionFromUri(resolver, dtUri);
        }

        public void loadLongPress(ContentResolver resolver) {
            longPressAction = getActionFromUri(resolver, lpUri);
        }

        private String getActionFromUri(ContentResolver resolver, String uri) {
            String action = Settings.System
                    .getStringForUser(resolver, uri, UserHandle.USER_CURRENT);
            if (action == null)
                action = ActionHandler.SYSTEMUI_TASK_NO_ACTION;
            return action;
        }
    }

    private Map<Integer, ButtonInfo> mSoftkeyMap = new HashMap<Integer, ButtonInfo>();
    private NavigationBarView mNavigationBarView;
    private ContentResolver mResolver;
    private SoftkeyActionObserver mObserver;
    private boolean mRecreating;
    private boolean mKeyguardShowing;

    public SoftkeyActionHandler(NavigationBarView v) {
        super(v.getContext());
        mNavigationBarView = v;
        mResolver = v.getContext().getContentResolver();
        loadButtonMap(); // MUST load button map before observing
        mObserver = new SoftkeyActionObserver(new Handler());
        mObserver.observe();
    }

    public void setKeyguardShowing(boolean showing) {
        if (mKeyguardShowing != showing) {
            mKeyguardShowing = showing;
        }
    }

    public boolean isSecureToFire(String action) {
        return action == null
                || !mKeyguardShowing
                || (mKeyguardShowing && SYSTEMUI_TASK_BACK.equals(action));
    }

    public void setIsRecreating(boolean recreating) {
        mRecreating = recreating;
    }

    private void loadButtonMap() {
        mSoftkeyMap.clear();
        mSoftkeyMap.put(Integer.valueOf(R.id.back),
                new ButtonInfo(ActionHandler.SYSTEMUI_TASK_BACK,
                        Settings.System.SOFTKEY_BACK_LONGPRESS,
                        Settings.System.SOFTKEY_BACK_DOUBLETAP));
        mSoftkeyMap.put(Integer.valueOf(R.id.home),
                new ButtonInfo(ActionHandler.SYSTEMUI_TASK_HOME,
                        Settings.System.SOFTKEY_HOME_LONGPRESS,
                        Settings.System.SOFTKEY_HOME_DOUBLETAP));
        mSoftkeyMap.put(Integer.valueOf(R.id.recent_apps),
                new ButtonInfo(ActionHandler.SYSTEMUI_TASK_RECENTS,
                        Settings.System.SOFTKEY_RECENT_LONGPRESS,
                        Settings.System.SOFTKEY_RECENT_DOUBLETAP));
        mSoftkeyMap.put(Integer.valueOf(R.id.menu),
                new ButtonInfo(ActionHandler.SYSTEMUI_TASK_MENU,
                        Settings.System.SOFTKEY_MENU_LONGPRESS,
                        Settings.System.SOFTKEY_MENU_DOUBLETAP));
        for (Map.Entry<Integer, ButtonInfo> entry : mSoftkeyMap.entrySet()) {
            entry.getValue().init(mResolver);
        }
    }

    public void assignButtonInfo() {
        int lpTimeout = getLongPressTimeout();
        for (KeyButtonView v : ActionUtils.getAllChildren(mNavigationBarView, KeyButtonView.class)) {
            KeyButtonView button = ((KeyButtonView) v);
            button.setLongPressTimeout(lpTimeout);
            button.setDoubleTapTimeout(DT_TIMEOUT);
            ButtonInfo info = mSoftkeyMap.get(button.getId());
            if (info == null) {
                continue;
            }
            button.setActionHandler(this);
            button.setButtonInfo(mSoftkeyMap.get(button.getId()));
        }
    }

    private void updateButtonAction(Uri uri) {
        for (Map.Entry<Integer, ButtonInfo> entry : mSoftkeyMap.entrySet()) {
            ButtonInfo info = entry.getValue();
            boolean changed = info.resetAction(mResolver, uri);
            if (!changed) {
                continue;
            }
            Integer infoId = entry.getKey();            
            for (KeyButtonView v : ActionUtils.getAllChildren(mNavigationBarView,
                    KeyButtonView.class)) {
                if (Integer.valueOf(v.getId()).equals(infoId)) {
                    v.setButtonInfo(info);
                }
            }
        }
    }

    private int getLongPressTimeout() {
        int lpTimeout = Settings.System
                .getIntForUser(mResolver, Settings.System.SOFTKEY_LONGPRESS_TIMEOUT, LP_TIMEOUT,
                        UserHandle.USER_CURRENT);
        if (lpTimeout > LP_TIMEOUT_MAX) {
            lpTimeout = LP_TIMEOUT_MAX;
        } else if (lpTimeout < LP_TIMEOUT_MIN) {
            lpTimeout = LP_TIMEOUT_MIN;
        }
        return lpTimeout;
    }

    private void setLongPressTimeout() {
        int lpTimeout = getLongPressTimeout();
        for (KeyButtonView v : ActionUtils.getAllChildren(mNavigationBarView, KeyButtonView.class)) {
            KeyButtonView button = ((KeyButtonView) v);
            button.setLongPressTimeout(lpTimeout);
        }
    }

    @Override
    public boolean handleAction(String action) {
        // TODO Auto-generated method stub
        return false;
    }

    public void onDispose() {
        if (mObserver != null) {
            mObserver.unobserve();
        }
        mSoftkeyMap.clear();
    }

    private class SoftkeyActionObserver extends ContentObserver {
        SoftkeyActionObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (mNavigationBarView == null)
                return;

            Uri longPressUri = Settings.System.getUriFor(Settings.System.SOFTKEY_LONGPRESS_TIMEOUT);
            if (longPressUri != null && uri.equals(longPressUri)) {
                setLongPressTimeout();
            } else {
                updateButtonAction(uri);
            }
        }

        void observe() {
            for (ButtonInfo info : mSoftkeyMap.values()) {
                mResolver.registerContentObserver(Settings.System.getUriFor(info.lpUri), false,
                        SoftkeyActionObserver.this, UserHandle.USER_ALL);
                mResolver.registerContentObserver(Settings.System.getUriFor(info.dtUri), false,
                        SoftkeyActionObserver.this, UserHandle.USER_ALL);
            }
            mResolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SOFTKEY_LONGPRESS_TIMEOUT), false,
                    SoftkeyActionObserver.this, UserHandle.USER_ALL);
        }

        void unobserve() {
            mResolver.unregisterContentObserver(SoftkeyActionObserver.this);
        }
    }
}
