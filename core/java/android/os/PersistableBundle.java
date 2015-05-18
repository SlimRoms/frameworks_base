/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.os;

import android.util.ArrayMap;
import com.android.internal.util.XmlUtils;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * A mapping from String values to various types that can be saved to persistent and later
 * restored.
 *
 */
public final class PersistableBundle extends BaseBundle implements Cloneable, Parcelable,
        XmlUtils.WriteMapCallback {
    private static final String TAG_PERSISTABLEMAP = "pbundle_as_map";
    public static final PersistableBundle EMPTY;
    static final Parcel EMPTY_PARCEL;

    static {
        EMPTY = new PersistableBundle();
        EMPTY.mMap = ArrayMap.EMPTY;
        EMPTY_PARCEL = BaseBundle.EMPTY_PARCEL;
    }

    /**
     * Constructs a new, empty PersistableBundle.
     */
    public PersistableBundle() {
        super();
    }

    /**
     * Constructs a new, empty PersistableBundle sized to hold the given number of
     * elements. The PersistableBundle will grow as needed.
     *
     * @param capacity the initial capacity of the PersistableBundle
     */
    public PersistableBundle(int capacity) {
        super(capacity);
    }

    /**
     * Constructs a PersistableBundle containing a copy of the mappings from the given
     * PersistableBundle.
     *
     * @param b a PersistableBundle to be copied.
     */
    public PersistableBundle(PersistableBundle b) {
        super(b);
    }

    /**
     * Constructs a PersistableBundle containing the mappings passed in.
     *
     * @param map a Map containing only those items that can be persisted.
     * @throws IllegalArgumentException if any element of #map cannot be persisted.
     */
    private PersistableBundle(Map<String, Object> map) {
        super();

        // First stuff everything in.
        putAll(map);

        // Now verify each item throwing an exception if there is a violation.
        Set<String> keys = map.keySet();
        Iterator<String> iterator = keys.iterator();
        while (iterator.hasNext()) {
            String key = iterator.next();
            Object value = map.get(key);
            if (value instanceof Map) {
                // Fix up any Maps by replacing them with PersistableBundles.
                putPersistableBundle(key, new PersistableBundle((Map<String, Object>) value));
            } else if (!(value instanceof Integer) && !(value instanceof Long) &&
                    !(value instanceof Double) && !(value instanceof String) &&
                    !(value instanceof int[]) && !(value instanceof long[]) &&
                    !(value instanceof double[]) && !(value instanceof String[]) &&
                    !(value instanceof PersistableBundle) && (value != null) &&
                    !(value instanceof Boolean) && !(value instanceof boolean[])) {
                throw new IllegalArgumentException("Bad value in PersistableBundle key=" + key
                        " value=" + value);
            }
        }
    }

    /* package */ PersistableBundle(Parcel parcelledData, int length) {
        super(parcelledData, length);
    }

    /**
     * Make a PersistableBundle for a single key/value pair.
     *
     * @hide
     */
    public static PersistableBundle forPair(String key, String value) {
        PersistableBundle b = new PersistableBundle(1);
        b.putString(key, value);
        return b;
    }

    /**
     * Clones the current PersistableBundle. The internal map is cloned, but the keys and
     * values to which it refers are copied by reference.
     */
    @Override
    public Object clone() {
        return new PersistableBundle(this);
    }

    /**
     * Inserts a PersistableBundle value into the mapping of this Bundle, replacing
     * any existing value for the given key.  Either key or value may be null.
     *
     * @param key a String, or null
     * @param value a Bundle object, or null
     */
    public void putPersistableBundle(String key, PersistableBundle value) {
        unparcel();
        mMap.put(key, value);
    }

    /**
     * Returns the value associated with the given key, or null if
     * no mapping of the desired type exists for the given key or a null
     * value is explicitly associated with the key.
     *
     * @param key a String, or null
     * @return a Bundle value, or null
     */
    public PersistableBundle getPersistableBundle(String key) {
        unparcel();
        Object o = mMap.get(key);
        if (o == null) {
            return null;
        }
        try {
            return (PersistableBundle) o;
        } catch (ClassCastException e) {
            typeWarning(key, o, "Bundle", e);
            return null;
        }
    }

    public static final Parcelable.Creator<PersistableBundle> CREATOR =
            new Parcelable.Creator<PersistableBundle>() {
                @Override
                public PersistableBundle createFromParcel(Parcel in) {
                    return in.readPersistableBundle();
                }

                @Override
                public PersistableBundle[] newArray(int size) {
                    return new PersistableBundle[size];
                }
            };

    /** @hide */
    @Override
    public void writeUnknownObject(Object v, String name, XmlSerializer out)
            throws XmlPullParserException, IOException {
        if (v instanceof PersistableBundle) {
            out.startTag(null, TAG_PERSISTABLEMAP);
            out.attribute(null, "name", name);
            ((PersistableBundle) v).saveToXml(out);
            out.endTag(null, TAG_PERSISTABLEMAP);
        } else {
            throw new XmlPullParserException("Unknown Object o=" + v);
        }
    }

    /** @hide */
    public void saveToXml(XmlSerializer out) throws IOException, XmlPullParserException {
        unparcel();
        XmlUtils.writeMapXml(mMap, out, this);
    }

    /** @hide */
    static class MyReadMapCallback implements  XmlUtils.ReadMapCallback {
        @Override
        public Object readThisUnknownObjectXml(XmlPullParser in, String tag)
                throws XmlPullParserException, IOException {
            if (TAG_PERSISTABLEMAP.equals(tag)) {
                return restoreFromXml(in);
            }
            throw new XmlPullParserException("Unknown tag=" + tag);
        }
    }

    /**
     * Report the nature of this Parcelable's contents
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Writes the PersistableBundle contents to a Parcel, typically in order for
     * it to be passed through an IBinder connection.
     * @param parcel The parcel to copy this bundle to.
     */
    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        final boolean oldAllowFds = parcel.pushAllowFds(false);
        try {
            writeToParcelInner(parcel, flags);
        } finally {
            parcel.restoreAllowFds(oldAllowFds);
        }
    }

    /** @hide */
    public static PersistableBundle restoreFromXml(XmlPullParser in) throws IOException,
            XmlPullParserException {
        final int outerDepth = in.getDepth();
        final String startTag = in.getName();
        final String[] tagName = new String[1];
        int event;
        while (((event = in.next()) != XmlPullParser.END_DOCUMENT) &&
                (event != XmlPullParser.END_TAG || in.getDepth() < outerDepth)) {
            if (event == XmlPullParser.START_TAG) {
                return new PersistableBundle((Map<String, Object>)
                        XmlUtils.readThisMapXml(in, startTag, tagName, new MyReadMapCallback()));
            }
        }
        return EMPTY;
    }

    @Override
    synchronized public String toString() {
        if (mParcelledData != null) {
            if (mParcelledData == EMPTY_PARCEL) {
                return "PersistableBundle[EMPTY_PARCEL]";
            } else {
                return "PersistableBundle[mParcelledData.dataSize="
                        mParcelledData.dataSize() + "]";
            }
        }
        return "PersistableBundle[" + mMap.toString() + "]";
    }

}
