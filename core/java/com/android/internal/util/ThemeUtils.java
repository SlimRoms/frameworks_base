/*
 * Copyright (C) 2016 The CyanogenMod Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.util;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;

public class ThemeUtils {

    /**
     * Creates a themed context using the overlay applied to SystemUI
     * @param context Base context
     * @return Themed context
     */
    public static Context createUiContext(final Context context) {
        try {
            Context uiContext = context.createPackageContext("com.android.systemui",
                    Context.CONTEXT_RESTRICTED);
            return new ThemedUiContext(uiContext, context.getApplicationContext());
        } catch (PackageManager.NameNotFoundException e) {
        }

        return null;
    }

    private static class ThemedUiContext extends ContextWrapper {
        private Context mAppContext;

        public ThemedUiContext(Context context, Context appContext) {
            super(context);
            mAppContext = appContext;
        }

        @Override
        public Context getApplicationContext() {
            return mAppContext;
        }

        @Override
        public String getPackageName() {
            return mAppContext.getPackageName();
        }
    }
}
