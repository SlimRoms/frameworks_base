/*
 * Copyright (C) 2012 Slimroms
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
 */

package com.android.systemui.statusbar.policy;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;

import com.android.internal.util.slim.ButtonsConstants;
import com.android.internal.util.slim.SlimActions;
import com.android.systemui.R;
import com.android.systemui.statusbar.policy.KeyButtonView;

import java.util.List;

public class ExtensibleKeyButtonView extends KeyButtonView {

    private static final String TAG = "Key.Ext";

    private Context mContext;
    private Handler mHandler;

    private int mInjectKeycode;
    private String mClickAction, mLongpressAction;

    public ExtensibleKeyButtonView(Context context, AttributeSet attrs) {
        this(context, attrs, ButtonsConstants.ACTION_NULL, ButtonsConstants.ACTION_NULL);
    }

    public ExtensibleKeyButtonView(Context context, AttributeSet attrs, String clickAction, String longpressAction) {
        super(context, attrs);

        mContext = context;
        mHandler = new Handler();
        mClickAction = clickAction;
        mLongpressAction = longpressAction;

        setCode(0);
        setSupportsLongPress(false);

        if (clickAction != null){
            if (clickAction.equals(ButtonsConstants.ACTION_HOME)) {
                setCode(KeyEvent.KEYCODE_HOME);
                setId(R.id.home);
            } else if (clickAction.equals(ButtonsConstants.ACTION_BACK)) {
                setCode(KeyEvent.KEYCODE_BACK);
                setId(R.id.back);
            } else if (clickAction.equals(ButtonsConstants.ACTION_SEARCH)) {
                setCode(KeyEvent.KEYCODE_SEARCH);
            } else if (clickAction.equals(ButtonsConstants.ACTION_MENU)) {
                setCode(KeyEvent.KEYCODE_MENU);
            } else {
                // the remaining options need to be handled by OnClick
                setOnClickListener(mClickListener);
                if (clickAction.equals(ButtonsConstants.ACTION_RECENTS)) {
                    setId(R.id.recent_apps);
                }
            }
        }

        if (longpressAction != null)
            if ((!longpressAction.equals(ButtonsConstants.ACTION_NULL)) || (getCode() !=0)) {
                // I want to allow long presses for defined actions, or if
                // primary action is a 'key' and long press isn't defined otherwise
                setSupportsLongPress(true);
                setOnLongClickListener(mLongPressListener);
            }
    }

    public void injectKeyDelayed(int keycode){
        mInjectKeycode = keycode;
        mHandler.removeCallbacks(onInjectKey_Down);
        mHandler.removeCallbacks(onInjectKey_Up);
        mHandler.post(onInjectKey_Down);
        mHandler.postDelayed(onInjectKey_Up,10); // introduce small delay to handle key press
    }

    final Runnable onInjectKey_Down = new Runnable() {
        public void run() {
            final KeyEvent ev = new KeyEvent(mDownTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, mInjectKeycode, 0,
                    0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                    KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                    InputDevice.SOURCE_KEYBOARD);
            InputManager.getInstance().injectInputEvent(ev,
                    InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        }
    };

    final Runnable onInjectKey_Up = new Runnable() {
        public void run() {
            final KeyEvent ev = new KeyEvent(mDownTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, mInjectKeycode, 0,
                    0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                    KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                    InputDevice.SOURCE_KEYBOARD);
            InputManager.getInstance().injectInputEvent(ev,
                    InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        }
    };

    private OnClickListener mClickListener = new OnClickListener() {

        @Override
        public void onClick(View v) {
            // the other consts were handled by keycode.
            SlimActions.processAction(mContext, mClickAction);
            return;
        }
    };

    private OnLongClickListener mLongPressListener = new OnLongClickListener() {

        @Override
        public boolean onLongClick(View v) {
            if (mLongpressAction.equals(ButtonsConstants.ACTION_HOME)) {
                injectKeyDelayed(KeyEvent.KEYCODE_HOME);
                return true;
            } else if (mLongpressAction.equals(ButtonsConstants.ACTION_BACK)) {
                injectKeyDelayed(KeyEvent.KEYCODE_BACK);
                return true;
            } else if (mLongpressAction.equals(ButtonsConstants.ACTION_SEARCH)) {
                injectKeyDelayed(KeyEvent.KEYCODE_SEARCH);
                return true;
            } else if (mLongpressAction.equals(ButtonsConstants.ACTION_MENU)) {
                injectKeyDelayed(KeyEvent.KEYCODE_MENU);
                return true;
            } else {
                SlimActions.processAction(mContext, mLongpressAction);
                return true;
            }
        }
    };

}
