/*
 * Copyright (C) 2012 ParanoidAndroid Project
 * Copyright (C) 2013 Slimroms
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
package com.android.internal.policy.impl.keyguard;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.R;

public class KeyguardShortcuts extends LinearLayout {

    private static final int INNER_PADDING = 20;

    private KeyguardSecurityCallback mCallback;
    private PackageManager mPackageManager;
    private Context mContext;

    public KeyguardShortcuts(Context context) {
        this(context, null);
    }

    public KeyguardShortcuts(Context context, AttributeSet attrs) {
        super(context, attrs);

        mContext = context;
        mPackageManager = mContext.getPackageManager();

        createShortcuts();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
    }

    public void setKeyguardCallback(KeyguardSecurityCallback callback) {
        mCallback = callback;
    }

    private void createShortcuts() {
        String apps = Settings.System.getStringForUser(mContext.getContentResolver(),
                Settings.System.LOCKSCREEN_SHORTCUTS, UserHandle.USER_CURRENT);
        boolean longpress = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.LOCKSCREEN_SHORTCUTS_LONGPRESS, 0, UserHandle.USER_CURRENT) == 1;

        if(apps == null || apps.isEmpty() || isScreenLarge() || isEightTargets()) return;
        final String[] shortcuts = apps.split("\\|");
        Resources res = mContext.getResources();
        for(int j = 0; j < shortcuts.length; j++) {
            String target = shortcuts[j];
            String packageName = null;
            String resourceString = null;
            String[] data = target.split(":");
            packageName = data[0];
            if(data.length > 1) {
                resourceString = data[1];
            }
            ImageView i = new ImageView(mContext);
            int dimens = Math.round(res.getDimensionPixelSize(
                    R.dimen.app_icon_size));
            LinearLayout.LayoutParams vp =
                    new LinearLayout.LayoutParams(dimens, dimens);
            i.setLayoutParams(vp);
            Drawable img = null;
            try {
                final Intent launchIntent = mPackageManager
                        .getLaunchIntentForPackage(packageName);
                if(launchIntent == null) { // No intent found
                    throw new NameNotFoundException();
                }
                if(resourceString == null) {
                    img = mPackageManager.getApplicationIcon(packageName);
                } else { // Custom icon
                    img = getDrawable(res, resourceString);
                }
                i.setImageDrawable(img);

                if (longpress) {
                    i.setOnLongClickListener(new View.OnLongClickListener() {
                        @Override
                        public boolean onLongClick(View v) {
                            mContext.startActivity(launchIntent);
                            if(mCallback != null) mCallback.dismiss(false);
                            return true;
                        }
                    });
                } else {
                    i.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            mContext.startActivity(launchIntent);
                            if(mCallback != null) mCallback.dismiss(false);
                        }
                    });
                }
                addView(i);
                if(j+1 < shortcuts.length) addSeparator();
            } catch(NameNotFoundException e) {
                // No custom icon is set and PackageManager fails to found
                // default application icon. Or maybe it was uninstalled
            } catch(NullPointerException e) {
                // Something is null?, we better avoid adding the target
            }
        }
    }

    private void addSeparator() {
        View v = new View(mContext);
        LinearLayout.LayoutParams vp =
                new LinearLayout.LayoutParams(INNER_PADDING, 0);
        v.setLayoutParams(vp);
        addView(v);
    }

    private Drawable getDrawable(Resources res, String drawableName){
        int resourceId = res.getIdentifier(drawableName, "drawable", "android");
        if(resourceId == 0) {
            Drawable d = Drawable.createFromPath(drawableName);
            return d;
        } else {
            return res.getDrawable(resourceId);
        }
    }

    private boolean isEightTargets() {
        final int storedVal = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.LOCKSCREEN_EIGHT_TARGETS, 0, UserHandle.USER_CURRENT);
        if (storedVal == 0) return false;
        return true;
    }

    public boolean isScreenLarge() {
        final int screenSize = Resources.getSystem().getConfiguration().screenLayout &
                Configuration.SCREENLAYOUT_SIZE_MASK;
        boolean isScreenLarge = screenSize == Configuration.SCREENLAYOUT_SIZE_LARGE ||
                screenSize == Configuration.SCREENLAYOUT_SIZE_XLARGE;
        return isScreenLarge;
    }

}
