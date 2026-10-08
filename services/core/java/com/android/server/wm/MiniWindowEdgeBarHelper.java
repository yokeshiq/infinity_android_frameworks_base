/*
 * Copyright (C) 2023-2024 The Nameless-AOSP Project
 * Copyright (C) 2026 The AviumUI Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.wm;

import static org.rising.DebugConstants.DEBUG_POP_UP;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Point;
import android.util.Slog;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewConfiguration;

import com.android.internal.R;

class MiniWindowEdgeBarHelper {

    private static final String TAG = "MiniWindowEdgeBarHelper";

    private static final int EDGE_BAR_COLOR = Color.parseColor("#FFF0F2F2");

    private static final float MINI_WINDOW_SCALE_EXIT_MAX = 0.86f;
    private static final float MINI_WINDOW_SCALE_EXIT_MIN = 0.6f;

    private static final int DRAG_SCALE_TYPE_NONE = 0;
    private static final int DRAG_SCALE_TYPE_TO_PINNED = 1;
    private static final int DRAG_SCALE_TYPE_TO_FULL = 2;

    private final RectF mBarBounds = new RectF();
    private final RectF mBarTouchBounds = new RectF();

    private Paint mPaint;
    private Task mTask;
    private TaskWindowSurfaceInfo mTaskWindowSurfaceInfo;
    private View mView;

    private int mBarHeight;
    private int mBarMargin;
    private int mBarRadius;
    private int mBarWidth;

    private float mDragSlop;

    private boolean mScrolling = false;
    private boolean mListening = false;

    private int mDragScaleType = DRAG_SCALE_TYPE_NONE;
    private float mStartScale = 1.0f;
    private Point mStartCenterPos = new Point();

    void onDragResizeChanged(float scale, Rect displayBound, boolean isLandscape) {
        if (mListening && mView != null) {
            mView.postOnAnimation(() -> {
                updateBoundsOnDrag(scale, displayBound, isLandscape);
                mView.invalidate();
            });
        }
    }

    void onResizeChanged() {
        if (mView != null) {
            mView.postOnAnimation(() -> {
                updateBounds();
                mView.invalidate();
            });
        }
    }

    void onVisibilityChanged(int visibility) {
        if (visibility == View.VISIBLE) {
            mListening = true;
            updateBounds();
        } else {
            mScrolling = false;
            mListening = false;
        }
    }

    void onOrientationChanged() {
        if (mListening && mView != null) {
            mView.postOnAnimation(() -> {
                updateBounds();
                mView.invalidate();
            });
        }
    }

    void onDraw(Canvas canvas) {
        if (!mBarBounds.isEmpty()) {
            canvas.drawRoundRect(mBarBounds, mBarRadius, mBarRadius, mPaint);
        }
    }

    private float mStartY = 0f;
    private float mCurrentScale = 1.0f;
    private static final float MIN_SCALE = 0.35f;
    private static final float MAX_SCALE = 1.3f;

    boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
        return true;
    }

    private void handleActionDown(MotionEvent event) {
        if (!mBarTouchBounds.contains(event.getRawX(), event.getRawY())) {
            return;
        }
        mStartY = event.getRawY();
        if (mTaskWindowSurfaceInfo != null) {
            mStartScale = mTaskWindowSurfaceInfo.getWindowSurfaceScale();
            mCurrentScale = mStartScale;
            mStartCenterPos = mTaskWindowSurfaceInfo.getWindowCenterPosition();
        }
    }

    private void handleActionMove(MotionEvent event) {
        if (mStartY == 0f || mTaskWindowSurfaceInfo == null || mTask == null) {
            return;
        }
        if (!mScrolling) {
            float deltaY = Math.abs(event.getRawY() - mStartY);
            if (deltaY > mDragSlop) {
                mScrolling = true;
            } else {
                return;
            }
        }

        float dragDistanceY = event.getRawY() - mStartY;
        float displayHeight = mView.getContext().getResources().getDisplayMetrics().heightPixels;

        float scaleDelta = (dragDistanceY / displayHeight) * 1.2f;
        float newScale = mStartScale + scaleDelta;
        newScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, newScale));
        mCurrentScale = newScale;

        int newDragScaleType = DRAG_SCALE_TYPE_NONE;
        if (newScale <= MINI_WINDOW_SCALE_EXIT_MIN) {
            newDragScaleType = DRAG_SCALE_TYPE_TO_PINNED;
        } else if (newScale >= MINI_WINDOW_SCALE_EXIT_MAX) {
            newDragScaleType = DRAG_SCALE_TYPE_TO_FULL;
        }

        if (newDragScaleType != mDragScaleType) {
            mDragScaleType = newDragScaleType;
            if (mDragScaleType != DRAG_SCALE_TYPE_NONE) {
                PopUpWindowController.getInstance().triggerVibrate();
            }
        }

        applyWindowScale(newScale);
    }

    private void applyWindowScale(float scale) {
        if (mTask == null || mTask.mSurfaceControl == null || mTaskWindowSurfaceInfo == null) {
            return;
        }

        mTaskWindowSurfaceInfo.cancelPopUpViewAnimation();

        Rect displayBound = new Rect();
        if (mTask.mDisplayContent != null) {
            mTask.mDisplayContent.getBounds(displayBound);
        }

        Point pos = new Point();
        Rect bounds = mTask.getBounds();
        float scaleFactor = WindowResizingAlgorithm.getPositionAndScaleFactorForTask(
                bounds, displayBound, mStartCenterPos, scale,
                false, pos);

        mTaskWindowSurfaceInfo.setWindowSurfaceScale(scale);
        mTaskWindowSurfaceInfo.setWindowSurfaceScaleFactor(scaleFactor);

        SurfaceControl.Transaction t = mTask.getSyncTransaction();
        SurfaceControl leash = mTask.mSurfaceControl;
        if (leash != null && leash.isValid()) {
            float realScale = mTaskWindowSurfaceInfo.getWindowSurfaceRealScale();
            t.setPosition(leash, pos.x, pos.y)
             .setScale(leash, realScale, realScale)
             .apply();
        }

        final int displayRotation = mTask.mDisplayContent != null ? mTask.mDisplayContent.getRotation() : Surface.ROTATION_0;
        final boolean isLandscape = displayRotation == Surface.ROTATION_90
                || displayRotation == Surface.ROTATION_270;
        DimmerWindow.getInstance().onDragResizeChanged(scale,
                mTaskWindowSurfaceInfo.getTaskWindowSurfaceBoundsOnDrag(displayBound), isLandscape);
    }

    boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                handleActionDown(event);
                return mBarTouchBounds.contains(event.getRawX(), event.getRawY());

            case MotionEvent.ACTION_MOVE:
                handleActionMove(event);
                return mScrolling;

            case MotionEvent.ACTION_UP:
                if (mScrolling) {
                    mScrolling = false;
                    mStartY = 0f;
                    handleDragEnd();
                    return true;
                }
                break;

            case MotionEvent.ACTION_CANCEL:
                if (mScrolling) {
                    mScrolling = false;
                    mStartY = 0f;
                    restoreOriginalState();
                    return true;
                }
                break;
        }
        return false;
    }

    private void handleDragEnd() {
        if (mTask == null) {
            return;
        }

        switch (mDragScaleType) {
            case DRAG_SCALE_TYPE_TO_PINNED:
                PopUpWindowController.getInstance().enterPinnedWindowingMode(mTask);
                break;

            case DRAG_SCALE_TYPE_TO_FULL:
                PopUpWindowController.getInstance().exitMiniWindowingMode();
                break;

            case DRAG_SCALE_TYPE_NONE:
            default:
                restoreOriginalState();
                break;
        }

        mDragScaleType = DRAG_SCALE_TYPE_NONE;
    }

    private void restoreOriginalState() {
        if (mTaskWindowSurfaceInfo == null || mTask == null) {
            return;
        }

        Rect displayBound = new Rect();
        if (mTask.mDisplayContent != null) {
            mTask.mDisplayContent.getBounds(displayBound);
        }

        final int displayRotation = mTask.mDisplayContent.getRotation();
        final boolean isLandscape = displayRotation == Surface.ROTATION_90
                || displayRotation == Surface.ROTATION_270;

        Point currentCenterPos = mTaskWindowSurfaceInfo.getWindowCenterPosition();
        Point startPos = new Point();
        Rect bounds = mTask.getBounds();
        float startScale = mTaskWindowSurfaceInfo.getWindowSurfaceScale();
        float startScaleFactor = WindowResizingAlgorithm.getPositionAndScaleFactorForTask(
                bounds, displayBound, currentCenterPos, startScale,
                false, startPos);

        final boolean isPortrait = displayRotation == Surface.ROTATION_0 || displayRotation == Surface.ROTATION_180;
        float defaultScale = WindowResizingAlgorithm.getDefaultMiniWindowScale(
                mTask.getConfiguration().orientation, isPortrait);
        Point endPos = new Point();
        float endScaleFactor = WindowResizingAlgorithm.getPositionAndScaleFactorForTask(
                bounds, displayBound, currentCenterPos, defaultScale,
                false, endPos);

        float startWinScale = startScale * startScaleFactor;
        float endWinScale = defaultScale * endScaleFactor;

        mTaskWindowSurfaceInfo.setWindowSurfaceScale(defaultScale);
        mTaskWindowSurfaceInfo.setWindowSurfaceScaleFactor(endScaleFactor);

        mTaskWindowSurfaceInfo.resizeWindowWithAnimation(startPos, endPos,
                bounds.width(), bounds.height(),
                startWinScale, endWinScale, displayBound, isLandscape);
    }

    void updateResources() {
        if (getContext() == null) {
            return;
        }
        mBarWidth = getContext().getResources().getDimensionPixelSize(R.dimen.mini_window_bar_width);
        mBarHeight = getContext().getResources().getDimensionPixelSize(R.dimen.mini_window_bar_height);
        mBarRadius = getContext().getResources().getDimensionPixelSize(R.dimen.mini_window_bar_radius);
        mBarMargin = getContext().getResources().getDimensionPixelSize(R.dimen.mini_window_bar_margin);
    }

    RectF getBarTouchBounds() {
        return new RectF(mBarTouchBounds);
    }

    void setUp(View view) {
        mView = view;
        updateResources();

        mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mPaint.setColor(EDGE_BAR_COLOR);
        mPaint.setStyle(Style.FILL);

        mDragSlop = (float) ViewConfiguration.get(getContext()).getScaledTouchSlop();

        updateBounds();
    }

    void setTask(Task task) {
        mTask = task;
        if (DEBUG_POP_UP) {
            Slog.d(TAG, "setTask: " + (task != null));
        }
        if (mTask != null) {
            mTaskWindowSurfaceInfo = mTask.mWindowContainerExt.getTaskWindowSurfaceInfo();
        } else {
            mTaskWindowSurfaceInfo = null;
        }
        mScrolling = false;
        mStartY = 0f;
        mDragScaleType = 0;
        onResizeChanged();
    }

    private void updateBoundsOnDrag(float scale, Rect bounds, boolean isLandscape) {
        final RectF barBounds = new RectF();
        final float height = mBarHeight * scale;
        final float margin = mBarMargin * scale;
        final float width = mBarWidth * scale;
        if (isLandscape) {
            barBounds.left = bounds.right + margin;
            barBounds.top = bounds.top + ((bounds.height() - width) / 2);
            barBounds.right = barBounds.left + height;
            barBounds.bottom = barBounds.top + width;

            mBarTouchBounds.left = bounds.right;
            mBarTouchBounds.top = barBounds.top - margin * 3;
            mBarTouchBounds.right = barBounds.right + margin * 4;
            mBarTouchBounds.bottom = barBounds.bottom + margin * 3;
        } else {
            barBounds.left = bounds.left + ((bounds.width() - width) / 2);
            barBounds.top = bounds.bottom + margin;
            barBounds.right = barBounds.left + width;
            barBounds.bottom = barBounds.top + height;

            mBarTouchBounds.left = bounds.left - margin * 3;
            mBarTouchBounds.top = bounds.bottom;
            mBarTouchBounds.right = bounds.right + margin * 3;
            mBarTouchBounds.bottom = barBounds.bottom + margin * 4;
        }
        mBarBounds.set(barBounds);
    }

    private void updateBounds() {
        final Task rootTask = mTask != null ? mTask.getRootTask() : null;
        if (rootTask == null || mTaskWindowSurfaceInfo == null || mView == null) {
            return;
        }
        if (!rootTask.getWindowConfiguration().isMiniExtWindowMode()) {
            return;
        }
        final RectF barBounds = new RectF();
        final Rect displayBound = new Rect();
        if (rootTask.mDisplayContent != null) {
            rootTask.mDisplayContent.getBounds(displayBound);
        }
        if (displayBound.isEmpty()) {
            return;
        }
        final Point pos = new Point();
        final Rect bound = rootTask.getBounds();
        final float scale = mTaskWindowSurfaceInfo.getWindowSurfaceScale();
        final float scaleFactor = WindowResizingAlgorithm.getPositionAndScaleFactorForTask(
                bound, displayBound, mTaskWindowSurfaceInfo.getWindowCenterPosition(), scale,
                false, pos);
        final float realScale = scale * scaleFactor;
        final Rect bounds = new Rect(0, 0, bound.width(), bound.height());
        bounds.scale(realScale);
        bounds.offsetTo(pos.x, pos.y);

        final int displayRotation = rootTask.getWindowConfiguration().getDisplayRotation();
        final boolean isLandscape = displayRotation == Surface.ROTATION_90
                || displayRotation == Surface.ROTATION_270;

        final float height = mBarHeight * scale;
        final float margin = mBarMargin * scale;
        final float width = mBarWidth * scale;
        if (isLandscape) {
            barBounds.left = bounds.right + margin;
            barBounds.top = bounds.top + ((bounds.height() - width) / 2);
            barBounds.right = barBounds.left + height;
            barBounds.bottom = barBounds.top + width;

            mBarTouchBounds.left = bounds.right;
            mBarTouchBounds.top = barBounds.top - margin * 3;
            mBarTouchBounds.right = barBounds.right + margin * 4;
            mBarTouchBounds.bottom = barBounds.bottom + margin * 3;
        } else {
            barBounds.left = bounds.left + ((bounds.width() - width) / 2);
            barBounds.top = bounds.bottom + margin;
            barBounds.right = barBounds.left + width;
            barBounds.bottom = barBounds.top + height;

            mBarTouchBounds.left = bounds.left - margin * 3;
            mBarTouchBounds.top = bounds.bottom;
            mBarTouchBounds.right = bounds.right + margin * 3;
            mBarTouchBounds.bottom = barBounds.bottom + margin * 4;
        }
        mBarBounds.set(barBounds);
    }

    private boolean passedSlop(float startX, float startY, float x, float y) {
        return Math.abs(x - startX) > mDragSlop ||
                Math.abs(y - startY) > mDragSlop;
    }

    private Context getContext() {
        if (mView == null) {
            return null;
        }
        return mView.getContext();
    }
}
