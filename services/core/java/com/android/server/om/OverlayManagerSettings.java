/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.om;

import static android.content.om.OverlayInfo.STATE_NOT_APPROVED_UNKNOWN;
import static com.android.server.om.OverlayManagerService.DEBUG;
import static com.android.server.om.OverlayManagerService.TAG;

import android.annotation.NonNull;
import android.content.om.OverlayInfo;
import android.util.AndroidRuntimeException;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

class OverlayManagerSettings {
    private final List<ChangeListener> mListeners = new ArrayList<>();

    private final ArrayList<SettingsItem> mItems = new ArrayList<>();


    void init(@NonNull String packageName, int userId, @NonNull String targetPackageName,
            @NonNull String baseCodePath) {
        remove(packageName, userId);
        SettingsItem item = new SettingsItem(packageName, userId, targetPackageName, baseCodePath);
        mItems.add(item);
    }

    void remove(@NonNull String packageName, int userId) {
        SettingsItem item = select(packageName, userId);
        if (item == null) {
            return;
        }
        OverlayInfo oi = item.getOverlayInfo();
        mItems.remove(item);
        if (oi != null) {
            notifyOverlayRemoved(oi, false);
        }
    }

    boolean contains(@NonNull String packageName, int userId) {
        return select(packageName, userId) != null;
    }

    OverlayInfo getOverlayInfo(@NonNull String packageName, int userId) throws BadKeyException {
        SettingsItem item = select(packageName, userId);
        if (item == null) {
            throw new BadKeyException(packageName, userId);
        }
        return item.getOverlayInfo();
    }

    String getTargetPackageName(@NonNull String packageName, int userId) throws BadKeyException {
        SettingsItem item = select(packageName, userId);
        if (item == null) {
            throw new BadKeyException(packageName, userId);
        }
        return item.getTargetPackageName();
    }

    void setBaseCodePath(@NonNull String packageName, int userId, String path) throws BadKeyException {
        SettingsItem item = select(packageName, userId);
        if (item == null) {
            throw new BadKeyException(packageName, userId);
        }
        item.setBaseCodePath(path);
        notifySettingsChanged();
    }

    boolean getUpgrading(@NonNull String packageName, int userId) throws BadKeyException {
        SettingsItem item = select(packageName, userId);
        if (item == null) {
            throw new BadKeyException(packageName, userId);
        }
        return item.isUpgrading();
    }

    void setUpgrading(@NonNull String packageName, int userId, boolean newValue) throws BadKeyException {
        SettingsItem item = select(packageName, userId);
        if (item == null) {
            throw new BadKeyException(packageName, userId);
        }
        if (newValue == item.isUpgrading()) {
            return; // nothing to do
        }

        if (newValue) {
            OverlayInfo oi = item.getOverlayInfo();
            item.setUpgrading(true);
            item.setState(STATE_NOT_APPROVED_UNKNOWN);
            notifyOverlayRemoved(oi, false);
            notifySettingsChanged();
        } else {
            item.setUpgrading(false);
            notifySettingsChanged();
        }
    }

    boolean getEnabled(@NonNull String packageName, int userId) throws BadKeyException {
        SettingsItem item = select(packageName, userId);
        if (item == null) {
            throw new BadKeyException(packageName, userId);
        }
        return item.isEnabled();
    }

    void setEnabled(@NonNull String packageName, int userId, boolean enable) throws BadKeyException {
        SettingsItem item = select(packageName, userId);
        if (item == null) {
            throw new BadKeyException(packageName, userId);
        }
        if (enable == item.isEnabled()) {
            return; // nothing to do
        }

        item.setEnabled(enable);
        notifySettingsChanged();
    }

    int getState(@NonNull String packageName, int userId) throws BadKeyException {
        SettingsItem item = select(packageName, userId);
        if (item == null) {
            throw new BadKeyException(packageName, userId);
        }
        return item.getState();
    }

    void setState(@NonNull String packageName, int userId, int state, boolean shouldWait)
            throws BadKeyException {
        SettingsItem item = select(packageName, userId);
        if (item == null) {
            throw new BadKeyException(packageName, userId);
        }
        OverlayInfo previous = item.getOverlayInfo();
        item.setState(state);
        OverlayInfo current = item.getOverlayInfo();
        if (previous.state == STATE_NOT_APPROVED_UNKNOWN) {
            notifyOverlayAdded(current, shouldWait);
            notifySettingsChanged();
        } else if (current.state != previous.state) {
            notifyOverlayChanged(current, previous, shouldWait);
            notifySettingsChanged();
        }
    }

    List<OverlayInfo> getOverlaysForTarget(@NonNull String targetPackageName, int userId) {
        List<SettingsItem> items = selectWhereTarget(targetPackageName, userId);
        if (items.isEmpty()) {
            return Collections.emptyList();
        }
        List<OverlayInfo> out = new ArrayList<>(items.size());
        for (SettingsItem item : items) {
            if (item.isUpgrading()) {
                continue;
            }
            out.add(item.getOverlayInfo());
        }
        return out;
    }

    Map<String, List<OverlayInfo>> getOverlaysForUser(int userId) {
        List<SettingsItem> items = selectWhereUser(userId);
        if (items.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, List<OverlayInfo>> out = new ArrayMap<>(items.size());
        for (SettingsItem item : items) {
            if (item.isUpgrading()) {
                continue;
            }
            String targetPackageName = item.getTargetPackageName();
            if (!out.containsKey(targetPackageName)) {
                out.put(targetPackageName, new ArrayList<OverlayInfo>());
            }
            List<OverlayInfo> overlays = out.get(targetPackageName);
            overlays.add(item.getOverlayInfo());
        }
        return out;
    }

    List<String> getTargetPackageNamesForUser(int userId) {
        List<SettingsItem> items = selectWhereUser(userId);
        if (items.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        for (SettingsItem item : items) {
            if (item.isUpgrading()) {
                continue;
            }
            String targetPackageName = item.getTargetPackageName();
            if (!out.contains(targetPackageName)) {
                out.add(targetPackageName);
            }
        }
        return out;
    }

    List<Integer> getUsers() {
        ArrayList<Integer> users = new ArrayList<>();
        for (SettingsItem item : mItems) {
            if (!users.contains(item.userId)) {
                users.add(item.userId);
            }
        }
        return users;
    }

    void removeUser(int userId) {
        Iterator<SettingsItem> iter = mItems.iterator();
        while (iter.hasNext()) {
            SettingsItem item = iter.next();
            if (item.userId == userId) {
                iter.remove();
            }
        }
    }

    boolean setPriority(@NonNull String packageName, @NonNull String newParentPackageName,
            int userId) {
        if (packageName.equals(newParentPackageName)) {
            return false;
        }
        SettingsItem rowToMove = select(packageName, userId);
        if (rowToMove == null || rowToMove.isUpgrading()) {
            return false;
        }
        SettingsItem newParentRow = select(newParentPackageName, userId);
        if (newParentRow == null || newParentRow.isUpgrading()) {
            return false;
        }
        if (!rowToMove.getTargetPackageName().equals(newParentRow.getTargetPackageName())) {
            return false;
        }

        mItems.remove(rowToMove);
        ListIterator<SettingsItem> iter = mItems.listIterator();
        while (iter.hasNext()) {
            SettingsItem item = iter.next();
            if (item.userId == userId && item.packageName.equals(newParentPackageName)) {
                iter.add(rowToMove);
                notifyOverlayPriorityChanged(rowToMove.getOverlayInfo());
                notifySettingsChanged();
                return true;
            }
        }

        Slog.wtf(TAG, "failed to find the parent item a second time");
        return false;
    }

    boolean setLowestPriority(@NonNull String packageName, int userId) {
        SettingsItem item = select(packageName, userId);
        if (item == null || item.isUpgrading()) {
            return false;
        }
        mItems.remove(item);
        mItems.add(0, item);
        notifyOverlayPriorityChanged(item.getOverlayInfo());
        notifySettingsChanged();
        return true;
    }

    boolean setHighestPriority(@NonNull String packageName, int userId) {
        SettingsItem item = select(packageName, userId);
        if (item == null || item.isUpgrading()) {
            return false;
        }
        mItems.remove(item);
        mItems.add(item);
        notifyOverlayPriorityChanged(item.getOverlayInfo());
        notifySettingsChanged();
        return true;
    }

    private static final String TAB1 = "    ";
    private static final String TAB2 = TAB1 + TAB1;
    private static final String TAB3 = TAB2 + TAB1;

    void dump(@NonNull PrintWriter pw) {
        pw.println("Settings");
        dumpItems(pw);
        dumpListeners(pw);
    }

    private void dumpItems(@NonNull PrintWriter pw) {
        pw.println(TAB1 + "Items");

        if (mItems.isEmpty()) {
            pw.println(TAB2 + "<none>");
            return;
        }

        for (SettingsItem item : mItems) {
            StringBuilder sb = new StringBuilder();
            sb.append(TAB2 + item.packageName + ":" + item.userId + " {\n");
            sb.append(TAB3 + "packageName.......: " + item.packageName + "\n");
            sb.append(TAB3 + "userId............: " + item.userId + "\n");
            sb.append(TAB3 + "targetPackageName.: " + item.getTargetPackageName() + "\n");
            sb.append(TAB3 + "baseCodePath......: " + item.getBaseCodePath() + "\n");
            sb.append(TAB3 + "state.............: " + OverlayInfo.stateToString(item.getState()) + "\n");
            sb.append(TAB3 + "isEnabled.........: " + item.isEnabled() + "\n");
            sb.append(TAB3 + "isUpgrading.......: " + item.isUpgrading() + "\n");
            sb.append(TAB2 + "}");
            pw.println(sb.toString());
        }
    }

    private void dumpListeners(@NonNull PrintWriter pw) {
        pw.println(TAB1 + "Change listeners");

        if (mListeners.isEmpty()) {
            pw.println(TAB2 + "<none>");
            return;
        }

        for (ChangeListener ch : mListeners) {
            pw.println(TAB2 + ch);
        }

    }

    void restore(InputStream is) throws IOException, XmlPullParserException {
        Serializer.restore(mItems, is);
    }

    void persist(OutputStream os) throws IOException, XmlPullParserException {
        Serializer.persist(mItems, os);
    }

    private static class Serializer {
        private static final String TAG_OVERLAYS = "overlays";
        private static final String TAG_ITEM = "item";

        private static final String ATTR_BASE_CODE_PATH = "baseCodePath";
        private static final String ATTR_IS_ENABLED = "isEnabled";
        private static final String ATTR_IS_UPGRADING = "isUpgrading";
        private static final String ATTR_PACKAGE_NAME = "packageName";
        private static final String ATTR_STATE = "state";
        private static final String ATTR_TARGET_PACKAGE_NAME = "targetPackageName";
        private static final String ATTR_USER_ID = "userId";
        private static final String ATTR_VERSION = "version";

        private static final int CURRENT_VERSION = 1;

        public static void restore(ArrayList<SettingsItem> table, InputStream is)
            throws IOException, XmlPullParserException {

            table.clear();
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(new InputStreamReader(is));
            XmlUtils.beginDocument(parser, TAG_OVERLAYS);
            int version = XmlUtils.readIntAttribute(parser, ATTR_VERSION);
            if (version != CURRENT_VERSION) {
                throw new XmlPullParserException("unrecognized version " + version);
            }
            int depth = parser.getDepth();

            while (XmlUtils.nextElementWithin(parser, depth)) {
                switch (parser.getName()) {
                    case TAG_ITEM:
                        SettingsItem item = restoreRow(parser, depth + 1);
                        table.add(item);
                        break;
                }
            }
        }

        private static SettingsItem restoreRow(XmlPullParser parser, int depth)
            throws IOException {

            String packageName = XmlUtils.readStringAttribute(parser, ATTR_PACKAGE_NAME);
            int userId = XmlUtils.readIntAttribute(parser, ATTR_USER_ID);
            String targetPackageName = XmlUtils.readStringAttribute(parser, ATTR_TARGET_PACKAGE_NAME);
            String baseCodePath = XmlUtils.readStringAttribute(parser, ATTR_BASE_CODE_PATH);
            int state = XmlUtils.readIntAttribute(parser, ATTR_STATE);
            boolean isEnabled = XmlUtils.readBooleanAttribute(parser, ATTR_IS_ENABLED);
            boolean isUpgrading = XmlUtils.readBooleanAttribute(parser, ATTR_IS_UPGRADING);

            return new SettingsItem(packageName, userId, targetPackageName, baseCodePath, state,
                    isEnabled, isUpgrading);
        }

        public static void persist(ArrayList<SettingsItem> table, OutputStream os)
            throws IOException, XmlPullParserException {
            FastXmlSerializer xml = new FastXmlSerializer();
            xml.setOutput(os, "utf-8");
            xml.startDocument(null, true);
            xml.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            xml.startTag(null, TAG_OVERLAYS);
            XmlUtils.writeIntAttribute(xml, ATTR_VERSION, CURRENT_VERSION);

            for (SettingsItem item : table) {
                persistRow(xml, item);
            }
            xml.endTag(null, TAG_OVERLAYS);
            xml.endDocument();
        }

        private static void persistRow(FastXmlSerializer xml, SettingsItem item)
            throws IOException {

            xml.startTag(null, TAG_ITEM);
            XmlUtils.writeStringAttribute(xml, ATTR_PACKAGE_NAME, item.packageName);
            XmlUtils.writeIntAttribute(xml, ATTR_USER_ID, item.userId);
            XmlUtils.writeStringAttribute(xml, ATTR_TARGET_PACKAGE_NAME, item.targetPackageName);
            XmlUtils.writeStringAttribute(xml, ATTR_BASE_CODE_PATH, item.baseCodePath);
            XmlUtils.writeIntAttribute(xml, ATTR_STATE, item.state);
            XmlUtils.writeBooleanAttribute(xml, ATTR_IS_ENABLED, item.isEnabled);
            XmlUtils.writeBooleanAttribute(xml, ATTR_IS_UPGRADING, item.isUpgrading);
            xml.endTag(null, TAG_ITEM);
        }
    }

    private static class SettingsItem {
        private final int userId;
        private final String packageName;
        private final String targetPackageName;
        private String baseCodePath;
        private int state;
        private boolean isEnabled;
        private boolean isUpgrading;
        private OverlayInfo cache;

        SettingsItem(@NonNull String packageName, int userId, @NonNull String targetPackageName,
                @NonNull String baseCodePath, int state, boolean isEnabled, boolean isUpgrading) {
            this.packageName = packageName;
            this.userId = userId;
            this.targetPackageName = targetPackageName;
            this.baseCodePath = baseCodePath;
            this.state = state;
            this.isEnabled = isEnabled;
            this.isUpgrading = isUpgrading;
            cache = null;
        }

        SettingsItem(@NonNull String packageName, int userId, @NonNull String targetPackageName,
                @NonNull String baseCodePath) {
            this(packageName, userId, targetPackageName, baseCodePath, STATE_NOT_APPROVED_UNKNOWN,
                    false, false);
        }

        private String getTargetPackageName() {
            return targetPackageName;
        }

        private String getBaseCodePath() {
            return baseCodePath;
        }

        private void setBaseCodePath(@NonNull String path) {
            if (!baseCodePath.equals(path)) {
                baseCodePath = path;
                invalidateCache();
            }
        }

        private int getState() {
            return state;
        }

        private void setState(int state) {
            if (this.state != state) {
                this.state = state;
                invalidateCache();
            }
        }

        private boolean isEnabled() {
            return isEnabled;
        }

        private void setEnabled(boolean enable) {
            if (isEnabled != enable) {
                isEnabled = enable;
                invalidateCache();
            }
        }

        private boolean isUpgrading() {
            return isUpgrading;
        }

        private void setUpgrading(boolean upgrading) {
            if (isUpgrading != upgrading) {
                isUpgrading = upgrading;
                invalidateCache();
            }
        }

        private OverlayInfo getOverlayInfo() {
            if (isUpgrading) {
                return null;
            }
            if (cache == null) {
                cache = new OverlayInfo(packageName, targetPackageName, baseCodePath,
                        state, userId);
            }
            return cache;
        }

        private void invalidateCache() {
            cache = null;
        }
    }

    private SettingsItem select(@NonNull String packageName, int userId) {
        for (SettingsItem item : mItems) {
            if (item.userId == userId && item.packageName.equals(packageName)) {
                return item;
            }
        }
        return null;
    }

    private List<SettingsItem> selectWhereUser(int userId) {
        ArrayList<SettingsItem> items = new ArrayList<>();
        for (SettingsItem item : mItems) {
            if (item.userId == userId) {
                items.add(item);
            }
        }
        return items;
    }

    private List<SettingsItem> selectWhereTarget(@NonNull String targetPackageName, int userId) {
        ArrayList<SettingsItem> items = new ArrayList<>();
        for (SettingsItem item : mItems) {
            if (item.userId == userId && item.getTargetPackageName().equals(targetPackageName)) {
                items.add(item);
            }
        }
        return items;
    }

    private void assertNotNull(Object o) {
        if (o == null) {
            throw new AndroidRuntimeException("object must not be null");
        }
    }

    void addChangeListener(final ChangeListener listener) {
        mListeners.add(listener);
    }

    void removeChangeListener(final ChangeListener listener) {
        mListeners.remove(listener);
    }

    private void notifySettingsChanged() {
        for (ChangeListener listener : mListeners) {
            listener.onSettingsChanged();
        }
    }

    private void notifyOverlayAdded(final OverlayInfo oi, boolean shouldWait) {
        if (DEBUG) {
            assertNotNull(oi);
        }
        for (ChangeListener listener : mListeners) {
            listener.onOverlayAdded(oi, shouldWait);
        }
    }

    private void notifyOverlayRemoved(final OverlayInfo oi, boolean shouldWait) {
        if (DEBUG) {
            assertNotNull(oi);
        }
        for (ChangeListener listener : mListeners) {
            listener.onOverlayRemoved(oi, shouldWait);
        }
    }

    private void notifyOverlayChanged(final OverlayInfo oi, final OverlayInfo oldOi,
            boolean shouldWait) {
        if (DEBUG) {
            assertNotNull(oi);
            assertNotNull(oldOi);
        }
        for (ChangeListener listener : mListeners) {
            listener.onOverlayChanged(oi, oldOi, shouldWait);
        }
    }

    private void notifyOverlayPriorityChanged(final OverlayInfo oi) {
        if (DEBUG) {
            assertNotNull(oi);
        }
        for (ChangeListener listener : mListeners) {
            listener.onOverlayPriorityChanged(oi);
        }
    }

    interface ChangeListener {
        void onSettingsChanged();
        void onOverlayAdded(@NonNull OverlayInfo oi, boolean shouldWait);
        void onOverlayRemoved(@NonNull OverlayInfo oi, boolean shouldWait);
        void onOverlayChanged(@NonNull OverlayInfo oi, @NonNull OverlayInfo oldOi,
            boolean shouldWait);
        void onOverlayPriorityChanged(@NonNull OverlayInfo oi);
    }

    class BadKeyException extends RuntimeException {
        public BadKeyException(String packageName, int userId) {
            super("Bad key packageName=" + packageName + " userId=" + userId);
        }
    }
}
