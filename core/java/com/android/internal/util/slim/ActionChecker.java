/*
* Copyright (C) 2013-2015 SlimRoms Project
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

package com.android.internal.util.slim;

public class ActionChecker {

    private static boolean actionConfigContainsAction(ActionConfig config, String action) {
        return action.equals(config.getClickAction());
    }

    public static boolean containsAction(ActionConfig config, String action) {
        if (!actionConfigContainsAction(config, action)) {
            return true;
        } else {
            return false;
        }
    }
}
