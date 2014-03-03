/*
 * Copyright (C) 2014 SlimRoms Project
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

package com.android.systemui.slimrecent;

import android.app.ActivityManager;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.AsyncTask;
import android.os.Process;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.android.cards.internal.CardExpand;
import com.android.systemui.R;

import java.lang.ref.WeakReference;

public class RecentExpandedCard extends CardExpand {

    private Drawable mDefaultThumbnailBackground;
    private int mThumbnailWidth;
    private int mThumbnailHeight;
    private int mPersistentTaskId = -1;
    private String mLabel;

    private BitmapDownloaderTask mTask;

    private boolean mReload;
    private boolean mDoNotNullBitmap;

    public RecentExpandedCard(Context context) {
        this(context, R.layout.recent_inner_card_expand);
    }

    // Main constructor. Set the important values we need.
    public RecentExpandedCard(Context context, int innerLayout) {
        super(context, innerLayout);

        final Resources res = context.getResources();

        // Render the default thumbnail background
        mThumbnailWidth =
                (int) res.getDimensionPixelSize(R.dimen.recent_thumbnail_width);
        mThumbnailHeight =
                (int) res.getDimensionPixelSize(R.dimen.recent_thumbnail_height);
        int color = res.getColor(R.drawable.status_bar_recents_app_thumbnail_background);

        mDefaultThumbnailBackground =
                new ColorDrawableWithDimensions(color, mThumbnailWidth, mThumbnailHeight);
    }

    public void updateExpandedContent(int persistentTaskId, String label) {
        if (label != null && label.equals(mLabel)) {
            mDoNotNullBitmap = true;
        }
        mLabel = label;
        mPersistentTaskId = persistentTaskId;
        mReload = true;
    }

    @Override
    public void setupInnerViewElements(ViewGroup parent, View view) {
        if (view == null || mPersistentTaskId == -1) {
            return;
        }

        ImageView thumbNail = (ImageView) view.findViewById(R.id.thumbnail);

        // Assign task bitmap to our view via async task loader. If it is just
        // a refresh of the view do not load it again and use the allready present one.
        if (thumbNail != null) {
            thumbNail.setBackground(mDefaultThumbnailBackground);
            if (mTask == null || mReload) {
                if (!mDoNotNullBitmap) {
                    thumbNail.setImageBitmap(null);
                }

                mReload = false;
                mDoNotNullBitmap = false;

                mTask = new BitmapDownloaderTask(thumbNail);
                mTask.executeOnExecutor(
                        AsyncTask.THREAD_POOL_EXECUTOR, mPersistentTaskId);
            } else {
                if (mTask.isLoaded()) {
                    thumbNail.setImageBitmap(mTask.getThumbnail());
                }
            }
        }
    }

    // Loads the actual task bitmap.
    private Bitmap loadThumbnail(int persistentTaskId) {
        final ActivityManager am = (ActivityManager)
                mContext.getSystemService(Context.ACTIVITY_SERVICE);
        return getResizedBitmap(am.getTaskTopThumbnail(persistentTaskId));
    }

    // Resize and crop the task bitmap to the overlay values.
    private Bitmap getResizedBitmap(Bitmap source) {
        if (source == null) {
            return null;
        }
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();

        // Compute the scaling factors to fit the new height and width, respectively.
        // To cover the final image, the final scaling will be the bigger
        // of these two.
        float xScale = (float) mThumbnailWidth / sourceWidth;
        float yScale = (float) mThumbnailHeight / sourceHeight;
        float scale = Math.max(xScale, yScale);

        // Now get the size of the source bitmap when scaled
        float scaledWidth = scale * sourceWidth;
        float scaledHeight = scale * sourceHeight;

        // Let's find out the left coordinates if the scaled bitmap
        // should be centered in the new size given by the parameters
        float left = (mThumbnailWidth - scaledWidth) / 2;

        // The target rectangle for the new, scaled version of the source bitmap
        RectF targetRect = new RectF(left, 0.0f, left + scaledWidth, scaledHeight);

        final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        paint.setAntiAlias(true);

        // Finally, we create a new bitmap of the specified size and draw our new,
        // scaled bitmap onto it.
        Bitmap dest = Bitmap.createBitmap(mThumbnailWidth, mThumbnailHeight, Config.ARGB_8888);
        Canvas canvas = new Canvas(dest);
        canvas.drawBitmap(source, null, targetRect, paint);

        return dest;
    }

    // AsyncTask loader for the task bitmap.
    private class BitmapDownloaderTask extends AsyncTask<Integer, Void, Bitmap> {

        private Bitmap mThumbnail;
        private boolean mLoaded;

        private final WeakReference<ImageView> mImageViewReference;
        private int mOrigPri;

        public BitmapDownloaderTask(ImageView imageView) {
            mImageViewReference = new WeakReference<ImageView>(imageView);
        }

        @Override
        protected Bitmap doInBackground(Integer... params) {
            // Save current thread priority and set it during the loading
            // to background priority.
            mOrigPri = Process.getThreadPriority(Process.myTid());
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            if (isCancelled()) {
                return null;
            }
            // Load and return bitmap
            return loadThumbnail(params[0]);
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            if (isCancelled()) {
                bitmap = null;
            }
            // Restore original thread priority.
            Process.setThreadPriority(mOrigPri);

            // Assign image to the view.
            if (mImageViewReference != null) {
                mLoaded = true;
                mThumbnail = bitmap;
                ImageView imageView = mImageViewReference.get();
                if (imageView != null) {
                    imageView.setImageBitmap(bitmap);
                }
            }
        }

        public boolean isLoaded() {
            return mLoaded;
        }

        public Bitmap getThumbnail() {
            return mThumbnail;
        }
    }
}

