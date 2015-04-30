/**
 * Copyright 2011, Felix Palmer
 * Copyright (C) 2014 The TeamEos Project
 * 
 * Modification made for the NX Gesture Interface feature
 *
 * Licensed under the MIT license:
 * http://creativecommons.org/licenses/MIT/
 */

package com.android.systemui.nx.eyecandy;

import java.util.HashSet;
import java.util.Set;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff.Mode;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.media.audiofx.Visualizer;

import com.android.systemui.nx.eyecandy.RenderFactory.RenderType;
import com.android.systemui.nx.eyecandy.Renderer;

/**
 * A class that draws visualizations of data received from a
 * {@link Visualizer.OnDataCaptureListener#onWaveFormDataCapture } and
 * {@link Visualizer.OnDataCaptureListener#onFftDataCapture }
 */
public class NxPulse implements NxRenderer {
    private static final String TAG = "VisualizerView";

    private byte[] mBytes;
    private byte[] mFFTBytes;
    private boolean mFlash = false;
    private Rect mRect = new Rect();
    private Visualizer mVisualizer;
    private Set<Renderer> mRenderers;
    private Paint mFlashPaint = new Paint();
    private Paint mFadePaint = new Paint();
    private NxSurface mSurface;
    private Bitmap mCanvasBitmap;
    private Bitmap mRotatedBitmap;
    private Canvas mCanvas;
    private Context mContext;
    private boolean mVertical;
    private boolean mSizeChanged;
    private boolean mLeftInLandscape;

    public NxPulse(Context context) {
        mContext = context;
    }

    public void start() {
        mBytes = null;
        mFFTBytes = null;
        mFlashPaint.setColor(Color.argb(122, 255, 255, 255));
        mFadePaint.setColor(Color.argb(200, 255, 255, 255)); // Adjust alpha to
                                                             // change how
                                                             // quickly the
                                                             // image fades
        mFadePaint.setXfermode(new PorterDuffXfermode(Mode.MULTIPLY));
        if (mRenderers == null) {
            mRenderers = new HashSet<Renderer>();
        }
        addRenderer(RenderFactory.get(mContext, RenderType.Bar));

        if (mVisualizer == null) {
            mVisualizer = new Visualizer(0);
        }

        if (mVisualizer != null) {
            mVisualizer.setEnabled(false);
        }

        mVisualizer.setCaptureSize(Visualizer.getCaptureSizeRange()[1]);
        Visualizer.OnDataCaptureListener captureListener = new Visualizer.OnDataCaptureListener()
        {
            @Override
            public void onWaveFormDataCapture(Visualizer visualizer, byte[] bytes,
                    int samplingRate)
            {
                updateVisualizer(bytes);
            }

            @Override
            public void onFftDataCapture(Visualizer visualizer, byte[] bytes,
                    int samplingRate)
            {
                updateVisualizerFFT(bytes);
            }
        };

        mVisualizer.setDataCaptureListener(captureListener,
                Visualizer.getMaxCaptureRate() / 2, true, true);
        mVisualizer.setEnabled(true);
    }

    public void stop() {
        if (mVisualizer != null) {
            mVisualizer.setEnabled(false);
            mVisualizer.release();
            mVisualizer = null;
        }
        if (mRenderers != null) {
            mRenderers.clear();
            mRenderers = null;
        }
    }

    public void setSizeChanged() {
        mSizeChanged = true;
    }

    public void setLeftInLandscape(boolean leftInLandscape) {
        if (mLeftInLandscape != leftInLandscape) {
            mLeftInLandscape = leftInLandscape;
            mSizeChanged = true;
        }
    }

    public void addRenderer(Renderer renderer) {
        if (renderer != null) {
            mRenderers.add(renderer);
        }
    }

    /**
     * Pass data to the visualizer. Typically this will be obtained from the
     * Android Visualizer.OnDataCaptureListener call back. See
     * {@link Visualizer.OnDataCaptureListener#onWaveFormDataCapture }
     * 
     * @param bytes
     */
    public void updateVisualizer(byte[] bytes) {
        mBytes = bytes;
        mSurface.invalidate();
    }

    /**
     * Pass FFT data to the visualizer. Typically this will be obtained from the
     * Android Visualizer.OnDataCaptureListener call back. See
     * {@link Visualizer.OnDataCaptureListener#onFftDataCapture }
     * 
     * @param bytes
     */
    public void updateVisualizerFFT(byte[] bytes) {
        mFFTBytes = bytes;
        mSurface.invalidate();
    }

 
    /**
     * Call this to make the visualizer flash. Useful for flashing at the start
     * of a song/loop etc...
     */
    public void flash() {
        mFlash = true;
        mSurface.invalidate();
    }


    @Override
    public Canvas onDrawNx(Canvas canvas) {
        final Rect rect = mSurface.onGetSurfaceDimens();
        final int sWidth = rect.width();
        final int sHeight = rect.height();
        final int cWidth = canvas.getWidth();
        final int cHeight = canvas.getHeight();
        final boolean isVertical = sHeight > sWidth;

        // only null resources on orientation change
        // to allow proper fade effect
        if (isVertical != mVertical || mSizeChanged) {
            mVertical = isVertical;
            mSizeChanged = false;
            mCanvasBitmap = null;
            mCanvas = null;
        }

        // Create canvas once we're ready to draw
        // the renderers don't like painting vertically
        // if vertical, create a horizontal canvas based on flipped current dimensions
        // let renders paint, the rotate the bitmap to draw to NX surface
        // we keep both bitmaps as class members to minimize GC
        mRect.set(0, 0, isVertical ? sHeight : sWidth, isVertical ? sWidth: sHeight);

        if (mCanvasBitmap == null) {
            mCanvasBitmap = Bitmap.createBitmap(isVertical ? cHeight : cWidth, isVertical ? cWidth : cHeight,
                    Config.ARGB_8888);
        }
        if (mCanvas == null) {
            mCanvas = new Canvas(mCanvasBitmap);
        }

        if (mBytes != null) {
            // Render all audio renderers
            AudioData audioData = new AudioData(mBytes);
            for (Renderer r : mRenderers) {
                r.render(mCanvas, audioData, mRect);
            }
        }

        if (mFFTBytes != null) {
            // Render all FFT renderers
            FFTData fftData = new FFTData(mFFTBytes);
            for (Renderer r : mRenderers) {
                r.render(mCanvas, fftData, mRect);
            }
        }

        // Fade out old contents
        mCanvas.drawPaint(mFadePaint);

        if (mFlash) {
            mFlash = false;
            mCanvas.drawPaint(mFlashPaint);
        }

        // if vertical flip our horizontally rendered bitmap
        if (isVertical) {
            Matrix matrix = new Matrix();
            matrix.postRotate(mLeftInLandscape ? 90 : -90);
            mRotatedBitmap = Bitmap.createBitmap(mCanvasBitmap, 0, 0,
                    mCanvasBitmap.getWidth(), mCanvasBitmap.getHeight(),
                    matrix, true);
        }
        canvas.drawBitmap(isVertical ? mRotatedBitmap : mCanvasBitmap, new Matrix(), null);

        return canvas;
    }

    @Override
    public void onSetNxSurface(NxSurface surface) {
        mSurface = surface;
    }
}
