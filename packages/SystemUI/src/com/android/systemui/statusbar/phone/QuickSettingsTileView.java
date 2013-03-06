/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.FrameLayout;

/**
 *
 */
public class QuickSettingsTileView extends FrameLayout {

    private int mColSpan;
    private final int mRowSpan;

    private static final int DEFAULT_QUICK_TILES_BG_COLOR = 0xff161616;
    private static final int DEFAULT_QUICK_TILES_BG_PRESSED_COLOR = 0xff212121;

    public QuickSettingsTileView(Context context, AttributeSet attrs) {
        super(context, attrs);

        mColSpan = 1;
        mRowSpan = 1;

        int bgColor = Settings.System.getInt(context.getContentResolver(),
                Settings.System.QUICK_TILES_BG_COLOR, -2);
        int presColor = Settings.System.getInt(context.getContentResolver(),
                Settings.System.QUICK_TILES_BG_PRESSED_COLOR, -2);

        if (bgColor != -2 || presColor != -2) {
            if (bgColor == -2) {
                bgColor = DEFAULT_QUICK_TILES_BG_COLOR;
            }
            if (presColor == -2) {
                presColor = DEFAULT_QUICK_TILES_BG_COLOR;
            }
            ColorDrawable bgDrawable = new ColorDrawable(bgColor);
            ColorDrawable presDrawable = new ColorDrawable(presColor);
            StateListDrawable states = new StateListDrawable();
            states.addState(new int[] {android.R.attr.state_pressed}, presDrawable);
            states.addState(new int[] {}, bgDrawable);
            this.setBackground(states);
        }
    }

    void setColumnSpan(int span) {
        mColSpan = span;
    }

    int getColumnSpan() {
        return mColSpan;
    }

    public void setContent(int layoutId, LayoutInflater inflater) {
        inflater.inflate(layoutId, this);
    }
}
