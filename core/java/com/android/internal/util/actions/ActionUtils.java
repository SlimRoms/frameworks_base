/*
 * Copyright (C) 2014 The TeamEos Project
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
 * Helper functions mostly for device configuration and some utilities
 * including a fun ViewGroup crawler and dpi conversion
 * 
 */

package com.android.internal.util.actions;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;

public final class ActionUtils {
    public static final String ANDROIDNS = "http://schemas.android.com/apk/res/android";

    // 10 inch tablets
    public static boolean isXLargeScreen() {
        int screenLayout = Resources.getSystem().getConfiguration().screenLayout &
                Configuration.SCREENLAYOUT_SIZE_MASK;
        return screenLayout == Configuration.SCREENLAYOUT_SIZE_XLARGE;
    }

    // 7 inch "phablets" i.e. grouper
    public static boolean isLargeScreen() {
        int screenLayout = Resources.getSystem().getConfiguration().screenLayout &
                Configuration.SCREENLAYOUT_SIZE_MASK;
        return screenLayout == Configuration.SCREENLAYOUT_SIZE_LARGE;
    }

    // normal phones
    public static boolean isNormalScreen() {
        int screenLayout = Resources.getSystem().getConfiguration().screenLayout &
                Configuration.SCREENLAYOUT_SIZE_MASK;
        return screenLayout == Configuration.SCREENLAYOUT_SIZE_NORMAL;
    }

    public static boolean isLandscape(Context context) {
        return Configuration.ORIENTATION_LANDSCAPE
                == context.getResources().getConfiguration().orientation;
    }

    public static boolean isCapKeyDevice(Context context) {
        return !context.getResources().getBoolean(
                com.android.internal.R.bool.config_showNavigationBar);
    }

    /**
     * This method converts dp unit to equivalent pixels, depending on device
     * density.
     * 
     * @param dp A value in dp (density independent pixels) unit. Which we need
     *            to convert into pixels
     * @param context Context to get resources and device specific display
     *            metrics
     * @return A float value to represent px equivalent to dp depending on
     *         device density
     */
    public static float convertDpToPixel(float dp, Context context) {
        Resources resources = context.getResources();
        DisplayMetrics metrics = resources.getDisplayMetrics();
        float px = dp * (metrics.densityDpi / 160f);
        return px;
    }

    public static int ConvertDpToPixelAsInt(float dp, Context context) {
        float px = convertDpToPixel(dp, context);
        if (px < 1)
            px = 1;
        return Math.round(px);
    }

    public static int ConvertDpToPixelAsInt(int dp, Context context) {
        float px = convertDpToPixel((float) dp, context);
        if (px < 1)
            px = 1;
        return Math.round(px);
    }

    /**
     * This method converts device specific pixels to density independent
     * pixels.
     * 
     * @param px A value in px (pixels) unit. Which we need to convert into db
     * @param context Context to get resources and device specific display
     *            metrics
     * @return A float value to represent dp equivalent to px value
     */
    public static float convertPixelsToDp(float px, Context context) {
        Resources resources = context.getResources();
        DisplayMetrics metrics = resources.getDisplayMetrics();
        float dp = px / (metrics.densityDpi / 160f);
        return dp;
    }

    /* utility to iterate a viewgroup and return a list of child views */
    public static ArrayList<View> getAllChildren(View v) {

        if (!(v instanceof ViewGroup)) {
            ArrayList<View> viewArrayList = new ArrayList<View>();
            viewArrayList.add(v);
            return viewArrayList;
        }

        ArrayList<View> result = new ArrayList<View>();

        ViewGroup vg = (ViewGroup) v;
        for (int i = 0; i < vg.getChildCount(); i++) {

            View child = vg.getChildAt(i);

            ArrayList<View> viewArrayList = new ArrayList<View>();
            viewArrayList.add(v);
            viewArrayList.addAll(getAllChildren(child));

            result.addAll(viewArrayList);
        }
        return result;
    }

    /* utility to iterate a viewgroup and return a list of child views of type */
    public static <T extends View> ArrayList<T> getAllChildren(View root, Class<T> returnType) {
        if (!(root instanceof ViewGroup)) {
            ArrayList<T> viewArrayList = new ArrayList<T>();
            try {
                viewArrayList.add(returnType.cast(root));
            } catch (Exception e) {
                // handle all exceptions the same and silently fail
            }
            return viewArrayList;
        }
        ArrayList<T> result = new ArrayList<T>();
        ViewGroup vg = (ViewGroup) root;
        for (int i = 0; i < vg.getChildCount(); i++) {
            View child = vg.getChildAt(i);
            ArrayList<T> viewArrayList = new ArrayList<T>();
            try {
                viewArrayList.add(returnType.cast(root));
            } catch (Exception e) {
                // handle all exceptions the same and silently fail
            }
            viewArrayList.addAll(getAllChildren(child, returnType));
            result.addAll(viewArrayList);
        }
        return result;
    }
}
