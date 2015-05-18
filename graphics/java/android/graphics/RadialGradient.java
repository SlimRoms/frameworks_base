/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.graphics;

import android.annotation.NonNull;
import android.annotation.Nullable;

public class RadialGradient extends Shader {

    private static final int TYPE_COLORS_AND_POSITIONS = 1;
    private static final int TYPE_COLOR_CENTER_AND_COLOR_EDGE = 2;

    /**
     * Type of the RadialGradient: can be either TYPE_COLORS_AND_POSITIONS or
     * TYPE_COLOR_CENTER_AND_COLOR_EDGE.
     */
    private int mType;

    private float mX;
    private float mY;
    private float mRadius;
    private int[] mColors;
    private float[] mPositions;
    private int mCenterColor;
    private int mEdgeColor;

    private TileMode mTileMode;

    /** Create a shader that draws a radial gradient given the center and radius.
        @param centerX  The x-coordinate of the center of the radius
        @param centerY  The y-coordinate of the center of the radius
        @param radius   Must be positive. The radius of the circle for this gradient.
        @param colors   The colors to be distributed between the center and edge of the circle
        @param stops    May be <code>null</code>. Valid values are between <code>0.0f</code> and
                        <code>1.0f</code>. The relative position of each corresponding color in
                        the colors array. If <code>null</code>, colors are distributed evenly
                        between the center and edge of the circle.
        @param tileMode The Shader tiling mode
    */
    public RadialGradient(float centerX, float centerY, float radius,
               @NonNull int colors[], @Nullable float stops[], @NonNull TileMode tileMode) {
        if (radius <= 0) {
            throw new IllegalArgumentException("radius must be > 0");
        }
        if (colors.length < 2) {
            throw new IllegalArgumentException("needs >= 2 number of colors");
        }
        if (stops != null && colors.length != stops.length) {
            throw new IllegalArgumentException("color and position arrays must be of equal length");
        }
        mType = TYPE_COLORS_AND_POSITIONS;
        mX = centerX;
        mY = centerY;
        mRadius = radius;
        mColors = colors;
        mPositions = stops;
        mTileMode = tileMode;
        init(nativeCreate1(centerX, centerY, radius, colors, stops, tileMode.nativeInt));
    }

    /** Create a shader that draws a radial gradient given the center and radius.
        @param centerX     The x-coordinate of the center of the radius
        @param centerY     The y-coordinate of the center of the radius
        @param radius      Must be positive. The radius of the circle for this gradient
        @param centerColor The color at the center of the circle.
        @param edgeColor   The color at the edge of the circle.
        @param tileMode    The Shader tiling mode
    */
    public RadialGradient(float centerX, float centerY, float radius,
            int centerColor, int edgeColor, @NonNull TileMode tileMode) {
        if (radius <= 0) {
            throw new IllegalArgumentException("radius must be > 0");
        }
        mType = TYPE_COLOR_CENTER_AND_COLOR_EDGE;
        mX = centerX;
        mY = centerY;
        mRadius = radius;
        mCenterColor = centerColor;
        mEdgeColor = edgeColor;
        mTileMode = tileMode;
        init(nativeCreate2(centerX, centerY, radius, centerColor, edgeColor, tileMode.nativeInt));
    }

    /**
     * @hide
     */
    @Override
    protected Shader copy() {
        final RadialGradient copy;
        switch (mType) {
            case TYPE_COLORS_AND_POSITIONS:
                copy = new RadialGradient(mX, mY, mRadius, mColors.clone(),
                        mPositions != null ? mPositions.clone() : null, mTileMode);
                break;
            case TYPE_COLOR_CENTER_AND_COLOR_EDGE:
                copy = new RadialGradient(mX, mY, mRadius, mCenterColor, mEdgeColor, mTileMode);
                break;
            default:
                throw new IllegalArgumentException("RadialGradient should be created with either "
                        "colors and positions or center color and edge color");
        }
        copyLocalMatrix(copy);
        return copy;
    }

    private static native long nativeCreate1(float x, float y, float radius,
            int colors[], float positions[], int tileMode);
    private static native long nativeCreate2(float x, float y, float radius,
            int color0, int color1, int tileMode);
}

