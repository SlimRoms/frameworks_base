/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package android.content.res;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.animation.Animator;
import android.animation.StateListAnimator;
import android.annotation.AnyRes;
import android.annotation.AttrRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.PluralsRes;
import android.annotation.RawRes;
import android.annotation.StyleRes;
import android.annotation.StyleableRes;
import android.content.pm.ActivityInfo;
import android.content.pm.ActivityInfo.Config;
import android.content.res.Resources.NotFoundException;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.icu.text.PluralRules;
import android.os.Build;
import android.os.LocaleList;
import android.os.Trace;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.Slog;
import android.util.TypedValue;
import android.util.Xml;
import android.view.Display;
import android.view.DisplayAdjustments;

import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/**
 * The implementation of Resource access. This class contains the AssetManager and all caches
 * associated with it.
 *
 * {@link Resources} is just a thing wrapper around this class. When a configuration change
 * occurs, clients can retain the same {@link Resources} reference because the underlying
 * {@link ResourcesImpl} object will be updated or re-created.
 *
 * @hide
 */
public class ResourcesImpl {
    static final String TAG = "Resources";

    private static final boolean DEBUG_LOAD = false;
    private static final boolean DEBUG_CONFIG = false;
    private static final boolean TRACE_FOR_PRELOAD = false;
    private static final boolean TRACE_FOR_MISS_PRELOAD = false;

    private static final int LAYOUT_DIR_CONFIG = ActivityInfo.activityInfoConfigJavaToNative(
            ActivityInfo.CONFIG_LAYOUT_DIRECTION);

    private static final int ID_OTHER = 0x01000004;

    private static final Object sSync = new Object();

    private static boolean sPreloaded;
    private boolean mPreloading;

    // Information about preloaded resources.  Note that they are not
    // protected by a lock, because while preloading in zygote we are all
    // single-threaded, and after that these are immutable.
    private static final LongSparseArray<Drawable.ConstantState>[] sPreloadedDrawables;
    private static final LongSparseArray<Drawable.ConstantState> sPreloadedColorDrawables
            = new LongSparseArray<>();
    private static final LongSparseArray<android.content.res.ConstantState<ComplexColor>>
            sPreloadedComplexColors = new LongSparseArray<>();

    /** Lock object used to protect access to caches and configuration. */
    private final Object mAccessLock = new Object();

    // These are protected by mAccessLock.
    private final Configuration mTmpConfig = new Configuration();
    private final DrawableCache mDrawableCache = new DrawableCache();
    private final DrawableCache mColorDrawableCache = new DrawableCache();
    private final ConfigurationBoundResourceCache<ComplexColor> mComplexColorCache =
            new ConfigurationBoundResourceCache<>();
    private final ConfigurationBoundResourceCache<Animator> mAnimatorCache =
            new ConfigurationBoundResourceCache<>();
    private final ConfigurationBoundResourceCache<StateListAnimator> mStateListAnimatorCache =
            new ConfigurationBoundResourceCache<>();

    /** Size of the cyclical cache used to map XML files to blocks. */
    private static final int XML_BLOCK_CACHE_SIZE = 4;

    // Cyclical cache used for recently-accessed XML files.
    private int mLastCachedXmlBlockIndex = -1;
    private final int[] mCachedXmlBlockCookies = new int[XML_BLOCK_CACHE_SIZE];
    private final String[] mCachedXmlBlockFiles = new String[XML_BLOCK_CACHE_SIZE];
    private final XmlBlock[] mCachedXmlBlocks = new XmlBlock[XML_BLOCK_CACHE_SIZE];


    final AssetManager mAssets;
    private final DisplayMetrics mMetrics = new DisplayMetrics();
    private final DisplayAdjustments mDisplayAdjustments;

    private PluralRules mPluralRule;

    private final Configuration mConfiguration = new Configuration();

    static {
        sPreloadedDrawables = new LongSparseArray[2];
        sPreloadedDrawables[0] = new LongSparseArray<>();
        sPreloadedDrawables[1] = new LongSparseArray<>();
    }

    /**
     * Creates a new ResourcesImpl object with CompatibilityInfo.
     *
     * @param assets Previously created AssetManager.
     * @param metrics Current display metrics to consider when
     *                selecting/computing resource values.
     * @param config Desired device configuration to consider when
     *               selecting/computing resource values (optional).
     * @param displayAdjustments this resource's Display override and compatibility info.
     *                           Must not be null.
     */
    public ResourcesImpl(@NonNull AssetManager assets, @Nullable DisplayMetrics metrics,
            @Nullable Configuration config, @NonNull DisplayAdjustments displayAdjustments) {
        mAssets = assets;
        mMetrics.setToDefaults();
        mDisplayAdjustments = displayAdjustments;
        updateConfiguration(config, metrics, displayAdjustments.getCompatibilityInfo());
        mAssets.ensureStringBlocks();
    }

    public DisplayAdjustments getDisplayAdjustments() {
        return mDisplayAdjustments;
    }

    public AssetManager getAssets() {
        return mAssets;
    }

    DisplayMetrics getDisplayMetrics() {
        if (DEBUG_CONFIG) Slog.v(TAG, "Returning DisplayMetrics: " + mMetrics.widthPixels
                + "x" + mMetrics.heightPixels + " " + mMetrics.density);
        return mMetrics;
    }

    Configuration getConfiguration() {
        return mConfiguration;
    }

    Configuration[] getSizeConfigurations() {
        return mAssets.getSizeConfigurations();
    }

    CompatibilityInfo getCompatibilityInfo() {
        return mDisplayAdjustments.getCompatibilityInfo();
    }

    private PluralRules getPluralRule() {
        synchronized (sSync) {
            if (mPluralRule == null) {
                mPluralRule = PluralRules.forLocale(mConfiguration.getLocales().get(0));
            }
            return mPluralRule;
        }
    }

    void getValue(@AnyRes int id, TypedValue outValue, boolean resolveRefs)
            throws NotFoundException {
        boolean found = mAssets.getResourceValue(id, 0, outValue, resolveRefs);
        if (found) {
            return;
        }
        throw new NotFoundException("Resource ID #0x" + Integer.toHexString(id));
    }

    void getValueForDensity(@AnyRes int id, int density, TypedValue outValue,
            boolean resolveRefs) throws NotFoundException {
        boolean found = mAssets.getResourceValue(id, density, outValue, resolveRefs);
        if (found) {
            return;
        }
        throw new NotFoundException("Resource ID #0x" + Integer.toHexString(id));
    }

    void getValue(String name, TypedValue outValue, boolean resolveRefs)
            throws NotFoundException {
        int id = getIdentifier(name, "string", null);
        if (id != 0) {
            getValue(id, outValue, resolveRefs);
            return;
        }
        throw new NotFoundException("String resource name " + name);
    }

    int getIdentifier(String name, String defType, String defPackage) {
        if (name == null) {
            throw new NullPointerException("name is null");
        }
        try {
            return Integer.parseInt(name);
        } catch (Exception e) {
            // Ignore
        }
        return mAssets.getResourceIdentifier(name, defType, defPackage);
    }

    @NonNull
    String getResourceName(@AnyRes int resid) throws NotFoundException {
        String str = mAssets.getResourceName(resid);
        if (str != null) return str;
        throw new NotFoundException("Unable to find resource ID #0x"
                + Integer.toHexString(resid));
    }

    @NonNull
    String getResourcePackageName(@AnyRes int resid) throws NotFoundException {
        String str = mAssets.getResourcePackageName(resid);
        if (str != null) return str;
        throw new NotFoundException("Unable to find resource ID #0x"
                + Integer.toHexString(resid));
    }

    @NonNull
    String getResourceTypeName(@AnyRes int resid) throws NotFoundException {
        String str = mAssets.getResourceTypeName(resid);
        if (str != null) return str;
        throw new NotFoundException("Unable to find resource ID #0x"
                + Integer.toHexString(resid));
    }

    @NonNull
    String getResourceEntryName(@AnyRes int resid) throws NotFoundException {
        String str = mAssets.getResourceEntryName(resid);
        if (str != null) return str;
        throw new NotFoundException("Unable to find resource ID #0x"
                + Integer.toHexString(resid));
    }

    @NonNull
    CharSequence getQuantityText(@PluralsRes int id, int quantity) throws NotFoundException {
        PluralRules rule = getPluralRule();
        CharSequence res = mAssets.getResourceBagText(id,
                attrForQuantityCode(rule.select(quantity)));
        if (res != null) {
            return res;
        }
        res = mAssets.getResourceBagText(id, ID_OTHER);
        if (res != null) {
            return res;
        }
        throw new NotFoundException("Plural resource ID #0x" + Integer.toHexString(id)
                + " quantity=" + quantity
                + " item=" + rule.select(quantity));
    }

    private static int attrForQuantityCode(String quantityCode) {
        switch (quantityCode) {
            case PluralRules.KEYWORD_ZERO: return 0x01000005;
            case PluralRules.KEYWORD_ONE:  return 0x01000006;
            case PluralRules.KEYWORD_TWO:  return 0x01000007;
            case PluralRules.KEYWORD_FEW:  return 0x01000008;
            case PluralRules.KEYWORD_MANY: return 0x01000009;
            default:                       return ID_OTHER;
        }
    }

    @NonNull
    AssetFileDescriptor openRawResourceFd(@RawRes int id, TypedValue tempValue)
            throws NotFoundException {
        getValue(id, tempValue, true);
        try {
            return mAssets.openNonAssetFd(tempValue.assetCookie, tempValue.string.toString());
        } catch (Exception e) {
            throw new NotFoundException("File " + tempValue.string.toString() + " from drawable "
                    + "resource ID #0x" + Integer.toHexString(id), e);
        }
    }

    @NonNull
    InputStream openRawResource(@RawRes int id, TypedValue value) throws NotFoundException {
        getValue(id, value, true);
        try {
            return mAssets.openNonAsset(value.assetCookie, value.string.toString(),
                    AssetManager.ACCESS_STREAMING);
        } catch (Exception e) {
            // Note: value.string might be null
            NotFoundException rnf = new NotFoundException("File "
                    + (value.string == null ? "(null)" : value.string.toString())
                    + " from drawable resource ID #0x" + Integer.toHexString(id));
            rnf.initCause(e);
            throw rnf;
        }
    }

    ConfigurationBoundResourceCache<Animator> getAnimatorCache() {
        return mAnimatorCache;
    }

    ConfigurationBoundResourceCache<StateListAnimator> getStateListAnimatorCache() {
        return mStateListAnimatorCache;
    }

    public void updateConfiguration(Configuration config, DisplayMetrics metrics,
                                    CompatibilityInfo compat) {
        Trace.traceBegin(Trace.TRACE_TAG_RESOURCES, "ResourcesImpl#updateConfiguration");
        try {
            synchronized (mAccessLock) {
                if (false) {
                    Slog.i(TAG, "**** Updating config of " + this + ": old config is "
                            + mConfiguration + " old compat is "
                            + mDisplayAdjustments.getCompatibilityInfo());
                    Slog.i(TAG, "**** Updating config of " + this + ": new config is "
                            + config + " new compat is " + compat);
                }
                if (compat != null) {
                    mDisplayAdjustments.setCompatibilityInfo(compat);
                }
                if (metrics != null) {
                    mMetrics.setTo(metrics);
                }
                // NOTE: We should re-arrange this code to create a Display
                // with the CompatibilityInfo that is used everywhere we deal
                // with the display in relation to this app, rather than
                // doing the conversion here.  This impl should be okay because
                // we make sure to return a compatible display in the places
                // where there are public APIs to retrieve the display...  but
                // it would be cleaner and more maintainable to just be
                // consistently dealing with a compatible display everywhere in
                // the framework.
                mDisplayAdjustments.getCompatibilityInfo().applyToDisplayMetrics(mMetrics);

                final @Config int configChanges = calcConfigChanges(config);

                // If even after the update there are no Locales set, grab the default locales.
                LocaleList locales = mConfiguration.getLocales();
                if (locales.isEmpty()) {
                    locales = LocaleList.getDefault();
                    mConfiguration.setLocales(locales);
                }

                if ((configChanges & ActivityInfo.CONFIG_LOCALE) != 0) {
                    if (locales.size() > 1) {
                        // The LocaleList has changed. We must query the AssetManager's available
                        // Locales and figure out the best matching Locale in the new LocaleList.
                        String[] availableLocales = mAssets.getNonSystemLocales();
                        if (LocaleList.isPseudoLocalesOnly(availableLocales)) {
                            // No app defined locales, so grab the system locales.
                            availableLocales = mAssets.getLocales();
                            if (LocaleList.isPseudoLocalesOnly(availableLocales)) {
                                availableLocales = null;
                            }
                        }

                        if (availableLocales != null) {
                            final Locale bestLocale = locales.getFirstMatchWithEnglishSupported(
                                    availableLocales);
                            if (bestLocale != null && bestLocale != locales.get(0)) {
                                mConfiguration.setLocales(new LocaleList(bestLocale, locales));
                            }
                        }
                    }
                }

                if (mConfiguration.densityDpi != Configuration.DENSITY_DPI_UNDEFINED) {
                    mMetrics.densityDpi = mConfiguration.densityDpi;
                    mMetrics.density =
                            mConfiguration.densityDpi * DisplayMetrics.DENSITY_DEFAULT_SCALE;
                }
                mMetrics.scaledDensity = mMetrics.density * mConfiguration.fontScale;

                final int width, height;
                if (mMetrics.widthPixels >= mMetrics.heightPixels) {
                    width = mMetrics.widthPixels;
                    height = mMetrics.heightPixels;
                } else {
                    //noinspection SuspiciousNameCombination
                    width = mMetrics.heightPixels;
                    //noinspection SuspiciousNameCombination
                    height = mMetrics.widthPixels;
                }

                final int keyboardHidden;
                if (mConfiguration.keyboardHidden == Configuration.KEYBOARDHIDDEN_NO
                        && mConfiguration.hardKeyboardHidden
                        == Configuration.HARDKEYBOARDHIDDEN_YES) {
                    keyboardHidden = Configuration.KEYBOARDHIDDEN_SOFT;
                } else {
                    keyboardHidden = mConfiguration.keyboardHidden;
                }

                mAssets.setConfiguration(mConfiguration.mcc, mConfiguration.mnc,
                        adjustLanguageTag(mConfiguration.getLocales().get(0).toLanguageTag()),
                        mConfiguration.orientation,
                        mConfiguration.touchscreen,
                        mConfiguration.densityDpi, mConfiguration.keyboard,
                        keyboardHidden, mConfiguration.navigation, width, height,
                        mConfiguration.smallestScreenWidthDp,
                        mConfiguration.screenWidthDp, mConfiguration.screenHeightDp,
                        mConfiguration.screenLayout, mConfiguration.uiMode,
                        Build.VERSION.RESOURCES_SDK_INT);

                if (DEBUG_CONFIG) {
                    Slog.i(TAG, "**** Updating config of " + this + ": final config is "
                            + mConfiguration + " final compat is "
                            + mDisplayAdjustments.getCompatibilityInfo());
                }

                flushThemedResourceCache(configChanges);
                flushLayoutCache();

                synchronized (sSync) {
                    if (mPluralRule != null) {
                        mPluralRule = PluralRules.forLocale(mConfiguration.getLocales().get(0));
                    }
                }
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_RESOURCES);
        }
    }

    /**
     * @hide
     */
    void updateAssets(String[] assetPaths) {
        if (assetPaths.length == 0) {
            throw new IllegalArgumentException("at least the path to the target apk must be specified");
        }
        synchronized (mAccessLock) {
            String targetPath = assetPaths[0];
            boolean found = false;
            int cookie = mAssets.nextCookie(0);
            while (cookie > 0) {
                String path = mAssets.getCookieName(cookie);
                if (targetPath.equals(path)) {
                    found = true;
                    break;
                }
                cookie = mAssets.nextCookie(cookie);
            }
            if (!found) {
                return;
            }

            cookie = mAssets.nextOverlayCookie(targetPath, 0);
            List<Integer> cookiesToRemove = new LinkedList<Integer>();
            while (cookie > 0) {
                cookiesToRemove.add(cookie);
                cookie = mAssets.nextOverlayCookie(targetPath, cookie);
            }
            for (int c : cookiesToRemove) {
                mAssets.removeAsset(c);
            }
            for (int i = 1; i < assetPaths.length; i++) {
                mAssets.addOverlayPath(assetPaths[i]);
            }

            // We can't know how the resource table changed, so we need to clear _everything_.
            flushThemedResourceCache(0xffffffff);

            flushLayoutCache();

            mAssets.recreateStringBlocks();
        }
    }

    /**
     * Applies the new configuration, returning a bitmask of the changes
     * between the old and new configurations.
     *
     * @param config the new configuration
     * @return bitmask of config changes
     */
    public @Config int calcConfigChanges(@Nullable Configuration config) {
        if (config == null) {
            // If there is no configuration, assume all flags have changed.
            return 0xFFFFFFFF;
        }

        mTmpConfig.setTo(config);
        int density = config.densityDpi;
        if (density == Configuration.DENSITY_DPI_UNDEFINED) {
            density = mMetrics.noncompatDensityDpi;
        }

        mDisplayAdjustments.getCompatibilityInfo().applyToConfiguration(density, mTmpConfig);

        if (mTmpConfig.getLocales().isEmpty()) {
            mTmpConfig.setLocales(LocaleList.getDefault());
        }
        return mConfiguration.updateFrom(mTmpConfig);
    }

    /**
     * {@code Locale.toLanguageTag} will transform the obsolete (and deprecated)
     * language codes "in", "ji" and "iw" to "id", "yi" and "he" respectively.
     *
     * All released versions of android prior to "L" used the deprecated language
     * tags, so we will need to support them for backwards compatibility.
     *
     * Note that this conversion needs to take place *after* the call to
     * {@code toLanguageTag} because that will convert all the deprecated codes to
     * the new ones, even if they're set manually.
     */
    private static String adjustLanguageTag(String languageTag) {
        final int separator = languageTag.indexOf('-');
        final String language;
        final String remainder;

        if (separator == -1) {
            language = languageTag;
            remainder = "";
        } else {
            language = languageTag.substring(0, separator);
            remainder = languageTag.substring(separator);
        }

        return Locale.adjustLanguageCode(language) + remainder;
    }

    /**
     * Call this to remove all cached loaded layout resources from the
     * Resources object.  Only intended for use with performance testing
     * tools.
     */
    public void flushLayoutCache() {
        synchronized (mCachedXmlBlocks) {
            Arrays.fill(mCachedXmlBlockCookies, 0);
            Arrays.fill(mCachedXmlBlockFiles, null);

            final XmlBlock[] cachedXmlBlocks = mCachedXmlBlocks;
            for (int i = 0; i < XML_BLOCK_CACHE_SIZE; i++) {
                final XmlBlock oldBlock = cachedXmlBlocks[i];
                if (oldBlock != null) {
                    oldBlock.close();
                }
            }
            Arrays.fill(cachedXmlBlocks, null);
        }
    }

    private void flushThemedResourceCache(int configChanges) {
        mDrawableCache.onConfigurationChange(configChanges);
        mColorDrawableCache.onConfigurationChange(configChanges);
        mComplexColorCache.onConfigurationChange(configChanges);
        mAnimatorCache.onConfigurationChange(configChanges);
        mStateListAnimatorCache.onConfigurationChange(configChanges);
    }

    @Nullable
    Drawable loadDrawable(Resources wrapper, TypedValue value, int id, Resources.Theme theme,
            boolean useCache) throws NotFoundException {
        try {
            if (TRACE_FOR_PRELOAD) {
                // Log only framework resources
                if ((id >>> 24) == 0x1) {
                    final String name = getResourceName(id);
                    if (name != null) {
                        Log.d("PreloadDrawable", name);
                    }
                }
            }

            final boolean isColorDrawable;
            final DrawableCache caches;
            final long key;
            if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT
                    && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                isColorDrawable = true;
                caches = mColorDrawableCache;
                key = value.data;
            } else {
                isColorDrawable = false;
                caches = mDrawableCache;
                key = (((long) value.assetCookie) << 32) | value.data;
            }

            // First, check whether we have a cached version of this drawable
            // that was inflated against the specified theme. Skip the cache if
            // we're currently preloading or we're not using the cache.
            if (!mPreloading && useCache) {
                final Drawable cachedDrawable = caches.getInstance(key, wrapper, theme);
                if (cachedDrawable != null) {
                    return cachedDrawable;
                }
            }

            // Next, check preloaded drawables. Preloaded drawables may contain
            // unresolved theme attributes.
            final Drawable.ConstantState cs;
            if (isColorDrawable) {
                cs = sPreloadedColorDrawables.get(key);
            } else {
                cs = sPreloadedDrawables[mConfiguration.getLayoutDirection()].get(key);
            }

            Drawable dr;
            if (cs != null) {
                dr = cs.newDrawable(wrapper);
            } else if (isColorDrawable) {
                dr = new ColorDrawable(value.data);
            } else {
                dr = loadDrawableForCookie(wrapper, value, id, null);
            }

            // Determine if the drawable has unresolved theme attributes. If it
            // does, we'll need to apply a theme and store it in a theme-specific
            // cache.
            final boolean canApplyTheme = dr != null && dr.canApplyTheme();
            if (canApplyTheme && theme != null) {
                dr = dr.mutate();
                dr.applyTheme(theme);
                dr.clearMutated();
            }

            // If we were able to obtain a drawable, store it in the appropriate
            // cache: preload, not themed, null theme, or theme-specific. Don't
            // pollute the cache with drawables loaded from a foreign density.
            if (dr != null && useCache) {
                dr.setChangingConfigurations(value.changingConfigurations);
                cacheDrawable(value, isColorDrawable, caches, theme, canApplyTheme, key, dr);
            }

            return dr;
        } catch (Exception e) {
            String name;
            try {
                name = getResourceName(id);
            } catch (NotFoundException e2) {
                name = "(missing name)";
            }

            // The target drawable might fail to load for any number of
            // reasons, but we always want to include the resource name.
            // Since the client already expects this method to throw a
            // NotFoundException, just throw one of those.
            final NotFoundException nfe = new NotFoundException("Drawable " + name
                    + " with resource ID #0x" + Integer.toHexString(id), e);
            nfe.setStackTrace(new StackTraceElement[0]);
            throw nfe;
        }
    }

    private void cacheDrawable(TypedValue value, boolean isColorDrawable, DrawableCache caches,
            Resources.Theme theme, boolean usesTheme, long key, Drawable dr) {
        final Drawable.ConstantState cs = dr.getConstantState();
        if (cs == null) {
            return;
        }

        if (mPreloading) {
            final int changingConfigs = cs.getChangingConfigurations();
            if (isColorDrawable) {
                if (verifyPreloadConfig(changingConfigs, 0, value.resourceId, "drawable")) {
                    sPreloadedColorDrawables.put(key, cs);
                }
            } else {
                if (verifyPreloadConfig(
                        changingConfigs, LAYOUT_DIR_CONFIG, value.resourceId, "drawable")) {
                    if ((changingConfigs & LAYOUT_DIR_CONFIG) == 0) {
                        // If this resource does not vary based on layout direction,
                        // we can put it in all of the preload maps.
                        sPreloadedDrawables[0].put(key, cs);
                        sPreloadedDrawables[1].put(key, cs);
                    } else {
                        // Otherwise, only in the layout dir we loaded it for.
                        sPreloadedDrawables[mConfiguration.getLayoutDirection()].put(key, cs);
                    }
                }
            }
        } else {
            synchronized (mAccessLock) {
                caches.put(key, theme, cs, usesTheme);
            }
        }
    }

    private boolean verifyPreloadConfig(@Config int changingConfigurations,
            @Config int allowVarying, @AnyRes int resourceId, @Nullable String name) {
        // We allow preloading of resources even if they vary by font scale (which
        // doesn't impact resource selection) or density (which we handle specially by
        // simply turning off all preloading), as well as any other configs specified
        // by the caller.
        if (((changingConfigurations&~(ActivityInfo.CONFIG_FONT_SCALE |
                ActivityInfo.CONFIG_DENSITY)) & ~allowVarying) != 0) {
            String resName;
            try {
                resName = getResourceName(resourceId);
            } catch (NotFoundException e) {
                resName = "?";
            }
            // This should never happen in production, so we should log a
            // warning even if we're not debugging.
            Log.w(TAG, "Preloaded " + name + " resource #0x"
                    + Integer.toHexString(resourceId)
                    + " (" + resName + ") that varies with configuration!!");
            return false;
        }
        if (TRACE_FOR_PRELOAD) {
            String resName;
            try {
                resName = getResourceName(resourceId);
            } catch (NotFoundException e) {
                resName = "?";
            }
            Log.w(TAG, "Preloading " + name + " resource #0x"
                    + Integer.toHexString(resourceId)
                    + " (" + resName + ")");
        }
        return true;
    }

    /**
     * Loads a drawable from XML or resources stream.
     */
    private Drawable loadDrawableForCookie(Resources wrapper, TypedValue value, int id,
            Resources.Theme theme) {
        if (value.string == null) {
            throw new NotFoundException("Resource \"" + getResourceName(id) + "\" ("
                    + Integer.toHexString(id) + ") is not a Drawable (color or path): " + value);
        }

        final String file = value.string.toString();

        if (TRACE_FOR_MISS_PRELOAD) {
            // Log only framework resources
            if ((id >>> 24) == 0x1) {
                final String name = getResourceName(id);
                if (name != null) {
                    Log.d(TAG, "Loading framework drawable #" + Integer.toHexString(id)
                            + ": " + name + " at " + file);
                }
            }
        }

        if (DEBUG_LOAD) {
            Log.v(TAG, "Loading drawable for cookie " + value.assetCookie + ": " + file);
        }

        final Drawable dr;

        Trace.traceBegin(Trace.TRACE_TAG_RESOURCES, file);
        try {
            if (file.endsWith(".xml")) {
                final XmlResourceParser rp = loadXmlResourceParser(
                        file, id, value.assetCookie, "drawable");
                dr = Drawable.createFromXml(wrapper, rp, theme);
                rp.close();
            } else {
                final InputStream is = mAssets.openNonAsset(
                        value.assetCookie, file, AssetManager.ACCESS_STREAMING);
                dr = Drawable.createFromResourceStream(wrapper, value, is, file, null);
                is.close();
            }
        } catch (Exception e) {
            Trace.traceEnd(Trace.TRACE_TAG_RESOURCES);
            final NotFoundException rnf = new NotFoundException(
                    "File " + file + " from drawable resource ID #0x" + Integer.toHexString(id));
            rnf.initCause(e);
            throw rnf;
        }
        Trace.traceEnd(Trace.TRACE_TAG_RESOURCES);

        return dr;
    }

    /**
     * Given the value and id, we can get the XML filename as in value.data, based on that, we
     * first try to load CSL from the cache. If not found, try to get from the constant state.
     * Last, parse the XML and generate the CSL.
     */
    private ComplexColor loadComplexColorFromName(Resources wrapper, Resources.Theme theme,
            TypedValue value, int id) {
        final long key = (((long) value.assetCookie) << 32) | value.data;
        final ConfigurationBoundResourceCache<ComplexColor> cache = mComplexColorCache;
        ComplexColor complexColor = cache.getInstance(key, wrapper, theme);
        if (complexColor != null) {
            return complexColor;
        }

        final android.content.res.ConstantState<ComplexColor> factory =
                sPreloadedComplexColors.get(key);

        if (factory != null) {
            complexColor = factory.newInstance(wrapper, theme);
        }
        if (complexColor == null) {
            complexColor = loadComplexColorForCookie(wrapper, value, id, theme);
        }

        if (complexColor != null) {
            complexColor.setBaseChangingConfigurations(value.changingConfigurations);

            if (mPreloading) {
                if (verifyPreloadConfig(complexColor.getChangingConfigurations(),
                        0, value.resourceId, "color")) {
                    sPreloadedComplexColors.put(key, complexColor.getConstantState());
                }
            } else {
                cache.put(key, theme, complexColor.getConstantState());
            }
        }
        return complexColor;
    }

    @Nullable
    ComplexColor loadComplexColor(Resources wrapper, @NonNull TypedValue value, int id,
            Resources.Theme theme) {
        if (TRACE_FOR_PRELOAD) {
            // Log only framework resources
            if ((id >>> 24) == 0x1) {
                final String name = getResourceName(id);
                if (name != null) android.util.Log.d("loadComplexColor", name);
            }
        }

        final long key = (((long) value.assetCookie) << 32) | value.data;

        // Handle inline color definitions.
        if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT
                && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            return getColorStateListFromInt(value, key);
        }

        final String file = value.string.toString();

        ComplexColor complexColor;
        if (file.endsWith(".xml")) {
            try {
                complexColor = loadComplexColorFromName(wrapper, theme, value, id);
            } catch (Exception e) {
                final NotFoundException rnf = new NotFoundException(
                        "File " + file + " from complex color resource ID #0x"
                                + Integer.toHexString(id));
                rnf.initCause(e);
                throw rnf;
            }
        } else {
            throw new NotFoundException(
                    "File " + file + " from drawable resource ID #0x"
                            + Integer.toHexString(id) + ": .xml extension required");
        }

        return complexColor;
    }

    @Nullable
    ColorStateList loadColorStateList(Resources wrapper, TypedValue value, int id,
            Resources.Theme theme)
            throws NotFoundException {
        if (TRACE_FOR_PRELOAD) {
            // Log only framework resources
            if ((id >>> 24) == 0x1) {
                final String name = getResourceName(id);
                if (name != null) android.util.Log.d("PreloadColorStateList", name);
            }
        }

        final long key = (((long) value.assetCookie) << 32) | value.data;

        // Handle inline color definitions.
        if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT
                && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            return getColorStateListFromInt(value, key);
        }

        ComplexColor complexColor = loadComplexColorFromName(wrapper, theme, value, id);
        if (complexColor != null && complexColor instanceof ColorStateList) {
            return (ColorStateList) complexColor;
        }

        throw new NotFoundException(
                "Can't find ColorStateList from drawable resource ID #0x"
                        + Integer.toHexString(id));
    }

    @NonNull
    private ColorStateList getColorStateListFromInt(@NonNull TypedValue value, long key) {
        ColorStateList csl;
        final android.content.res.ConstantState<ComplexColor> factory =
                sPreloadedComplexColors.get(key);
        if (factory != null) {
            return (ColorStateList) factory.newInstance();
        }

        csl = ColorStateList.valueOf(value.data);

        if (mPreloading) {
            if (verifyPreloadConfig(value.changingConfigurations, 0, value.resourceId,
                    "color")) {
                sPreloadedComplexColors.put(key, csl.getConstantState());
            }
        }

        return csl;
    }

    /**
     * Load a ComplexColor based on the XML file content. The result can be a GradientColor or
     * ColorStateList. Note that pure color will be wrapped into a ColorStateList.
     *
     * We deferred the parser creation to this function b/c we need to differentiate b/t gradient
     * and selector tag.
     *
     * @return a ComplexColor (GradientColor or ColorStateList) based on the XML file content.
     */
    @Nullable
    private ComplexColor loadComplexColorForCookie(Resources wrapper, TypedValue value, int id,
            Resources.Theme theme) {
        if (value.string == null) {
            throw new UnsupportedOperationException(
                    "Can't convert to ComplexColor: type=0x" + value.type);
        }

        final String file = value.string.toString();

        if (TRACE_FOR_MISS_PRELOAD) {
            // Log only framework resources
            if ((id >>> 24) == 0x1) {
                final String name = getResourceName(id);
                if (name != null) {
                    Log.d(TAG, "Loading framework ComplexColor #" + Integer.toHexString(id)
                            + ": " + name + " at " + file);
                }
            }
        }

        if (DEBUG_LOAD) {
            Log.v(TAG, "Loading ComplexColor for cookie " + value.assetCookie + ": " + file);
        }

        ComplexColor complexColor = null;

        Trace.traceBegin(Trace.TRACE_TAG_RESOURCES, file);
        if (file.endsWith(".xml")) {
            try {
                final XmlResourceParser parser = loadXmlResourceParser(
                        file, id, value.assetCookie, "ComplexColor");

                final AttributeSet attrs = Xml.asAttributeSet(parser);
                int type;
                while ((type = parser.next()) != XmlPullParser.START_TAG
                        && type != XmlPullParser.END_DOCUMENT) {
                    // Seek parser to start tag.
                }
                if (type != XmlPullParser.START_TAG) {
                    throw new XmlPullParserException("No start tag found");
                }

                final String name = parser.getName();
                if (name.equals("gradient")) {
                    complexColor = GradientColor.createFromXmlInner(wrapper, parser, attrs, theme);
                } else if (name.equals("selector")) {
                    complexColor = ColorStateList.createFromXmlInner(wrapper, parser, attrs, theme);
                }
                parser.close();
            } catch (Exception e) {
                Trace.traceEnd(Trace.TRACE_TAG_RESOURCES);
                final NotFoundException rnf = new NotFoundException(
                        "File " + file + " from ComplexColor resource ID #0x"
                                + Integer.toHexString(id));
                rnf.initCause(e);
                throw rnf;
            }
        } else {
            Trace.traceEnd(Trace.TRACE_TAG_RESOURCES);
            throw new NotFoundException(
                    "File " + file + " from drawable resource ID #0x"
                            + Integer.toHexString(id) + ": .xml extension required");
        }
        Trace.traceEnd(Trace.TRACE_TAG_RESOURCES);

        return complexColor;
    }

    /**
     * Loads an XML parser for the specified file.
     *
     * @param file the path for the XML file to parse
     * @param id the resource identifier for the file
     * @param assetCookie the asset cookie for the file
     * @param type the type of resource (used for logging)
     * @return a parser for the specified XML file
     * @throws NotFoundException if the file could not be loaded
     */
    @NonNull
    XmlResourceParser loadXmlResourceParser(@NonNull String file, @AnyRes int id, int assetCookie,
            @NonNull String type)
            throws NotFoundException {
        if (id != 0) {
            try {
                synchronized (mCachedXmlBlocks) {
                    final int[] cachedXmlBlockCookies = mCachedXmlBlockCookies;
                    final String[] cachedXmlBlockFiles = mCachedXmlBlockFiles;
                    final XmlBlock[] cachedXmlBlocks = mCachedXmlBlocks;
                    // First see if this block is in our cache.
                    final int num = cachedXmlBlockFiles.length;
                    for (int i = 0; i < num; i++) {
                        if (cachedXmlBlockCookies[i] == assetCookie && cachedXmlBlockFiles[i] != null
                                && cachedXmlBlockFiles[i].equals(file)) {
                            return cachedXmlBlocks[i].newParser();
                        }
                    }

                    // Not in the cache, create a new block and put it at
                    // the next slot in the cache.
                    final XmlBlock block = mAssets.openXmlBlockAsset(assetCookie, file);
                    if (block != null) {
                        final int pos = (mLastCachedXmlBlockIndex + 1) % num;
                        mLastCachedXmlBlockIndex = pos;
                        final XmlBlock oldBlock = cachedXmlBlocks[pos];
                        if (oldBlock != null) {
                            oldBlock.close();
                        }
                        cachedXmlBlockCookies[pos] = assetCookie;
                        cachedXmlBlockFiles[pos] = file;
                        cachedXmlBlocks[pos] = block;
                        return block.newParser();
                    }
                }
            } catch (Exception e) {
                final NotFoundException rnf = new NotFoundException("File " + file
                        + " from xml type " + type + " resource ID #0x" + Integer.toHexString(id));
                rnf.initCause(e);
                throw rnf;
            }
        }

        throw new NotFoundException("File " + file + " from xml type " + type + " resource ID #0x"
                + Integer.toHexString(id));
    }

    /**
     * Start preloading of resource data using this Resources object.  Only
     * for use by the zygote process for loading common system resources.
     * {@hide}
     */
    public final void startPreloading() {
        synchronized (sSync) {
            if (sPreloaded) {
                throw new IllegalStateException("Resources already preloaded");
            }
            sPreloaded = true;
            mPreloading = true;
            mConfiguration.densityDpi = DisplayMetrics.DENSITY_DEVICE;
            updateConfiguration(null, null, null);
        }
    }

    /**
     * Called by zygote when it is done preloading resources, to change back
     * to normal Resources operation.
     */
    void finishPreloading() {
        if (mPreloading) {
            mPreloading = false;
            flushLayoutCache();
        }
    }

    LongSparseArray<Drawable.ConstantState> getPreloadedDrawables() {
        return sPreloadedDrawables[0];
    }

    ThemeImpl newThemeImpl() {
        return new ThemeImpl();
    }

    /**
     * Creates a new ThemeImpl which is already set to the given Resources.ThemeKey.
     */
    ThemeImpl newThemeImpl(Resources.ThemeKey key) {
        ThemeImpl impl = new ThemeImpl();
        impl.mKey.setTo(key);
        impl.rebase();
        return impl;
    }

    public class ThemeImpl {
        /**
         * Unique key for the series of styles applied to this theme.
         */
        private final Resources.ThemeKey mKey = new Resources.ThemeKey();

        @SuppressWarnings("hiding")
        private final AssetManager mAssets;
        private final long mTheme;

        /**
         * Resource identifier for the theme.
         */
        private int mThemeResId = 0;

        /*package*/ ThemeImpl() {
            mAssets = ResourcesImpl.this.mAssets;
            mTheme = mAssets.createTheme();
        }

        @Override
        protected void finalize() throws Throwable {
            super.finalize();
            mAssets.releaseTheme(mTheme);
        }

        /*package*/ Resources.ThemeKey getKey() {
            return mKey;
        }

        /*package*/ long getNativeTheme() {
            return mTheme;
        }

        /*package*/ int getAppliedStyleResId() {
            return mThemeResId;
        }

        void applyStyle(int resId, boolean force) {
            synchronized (mKey) {
                AssetManager.applyThemeStyle(mTheme, resId, force);

                mThemeResId = resId;
                mKey.append(resId, force);
            }
        }

        void setTo(ThemeImpl other) {
            synchronized (mKey) {
                synchronized (other.mKey) {
                    AssetManager.copyTheme(mTheme, other.mTheme);

                    mThemeResId = other.mThemeResId;
                    mKey.setTo(other.getKey());
                }
            }
        }

        @NonNull
        TypedArray obtainStyledAttributes(@NonNull Resources.Theme wrapper,
                AttributeSet set,
                @StyleableRes int[] attrs,
                @AttrRes int defStyleAttr,
                @StyleRes int defStyleRes) {
            synchronized (mKey) {
                final int len = attrs.length;
                final TypedArray array = TypedArray.obtain(wrapper.getResources(), len);

                // XXX note that for now we only work with compiled XML files.
                // To support generic XML files we will need to manually parse
                // out the attributes from the XML file (applying type information
                // contained in the resources and such).
                final XmlBlock.Parser parser = (XmlBlock.Parser) set;
                AssetManager.applyStyle(mTheme, defStyleAttr, defStyleRes,
                        parser != null ? parser.mParseState : 0,
                        attrs, array.mData, array.mIndices);
                array.mTheme = wrapper;
                array.mXml = parser;

                return array;
            }
        }

        @NonNull
        TypedArray resolveAttributes(@NonNull Resources.Theme wrapper,
                @NonNull int[] values,
                @NonNull int[] attrs) {
            synchronized (mKey) {
                final int len = attrs.length;
                if (values == null || len != values.length) {
                    throw new IllegalArgumentException(
                            "Base attribute values must the same length as attrs");
                }

                final TypedArray array = TypedArray.obtain(wrapper.getResources(), len);
                AssetManager.resolveAttrs(mTheme, 0, 0, values, attrs, array.mData, array.mIndices);
                array.mTheme = wrapper;
                array.mXml = null;
                return array;
            }
        }

        boolean resolveAttribute(int resid, TypedValue outValue, boolean resolveRefs) {
            synchronized (mKey) {
                return mAssets.getThemeValue(mTheme, resid, outValue, resolveRefs);
            }
        }

        int[] getAllAttributes() {
            return mAssets.getStyleAttributes(getAppliedStyleResId());
        }

        @Config int getChangingConfigurations() {
            synchronized (mKey) {
                final int nativeChangingConfig =
                        AssetManager.getThemeChangingConfigurations(mTheme);
                return ActivityInfo.activityInfoConfigNativeToJava(nativeChangingConfig);
            }
        }

        public void dump(int priority, String tag, String prefix) {
            synchronized (mKey) {
                AssetManager.dumpTheme(mTheme, priority, tag, prefix);
            }
        }

        String[] getTheme() {
            synchronized (mKey) {
                final int N = mKey.mCount;
                final String[] themes = new String[N * 2];
                for (int i = 0, j = N - 1; i < themes.length; i += 2, --j) {
                    final int resId = mKey.mResId[j];
                    final boolean forced = mKey.mForce[j];
                    try {
                        themes[i] = getResourceName(resId);
                    } catch (NotFoundException e) {
                        themes[i] = Integer.toHexString(i);
                    }
                    themes[i + 1] = forced ? "forced" : "not forced";
                }
                return themes;
            }
        }

        /**
         * Rebases the theme against the parent Resource object's current
         * configuration by re-applying the styles passed to
         * {@link #applyStyle(int, boolean)}.
         */
        void rebase() {
            synchronized (mKey) {
                AssetManager.clearTheme(mTheme);

                // Reapply the same styles in the same order.
                for (int i = 0; i < mKey.mCount; i++) {
                    final int resId = mKey.mResId[i];
                    final boolean force = mKey.mForce[i];
                    AssetManager.applyThemeStyle(mTheme, resId, force);
                }
            }
        }
    }
}
