/*
 * Copyright (C) 2023-2024 The Nameless-AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.wm;

import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;

import static com.android.server.wm.WindowResizingAlgorithm.BOUNDARY_GAP;

import static org.rising.DebugConstants.DEBUG_POP_UP;

import android.app.WindowConfiguration;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.util.Slog;
import android.view.DisplayInfo;
import android.view.IWindow;
import android.view.InsetsState;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.WindowInsets;
import android.view.MotionEvent;

import com.android.internal.R;

import com.android.server.wm.Transition.ChangeInfo.PopUpViewInfo;

import java.util.Arrays;
import java.util.List;

class TaskWindowSurfaceInfo {

    private static final String TAG = "TaskWindowSurfaceInfo";

    private static final int DENSITY_DEFAULT = 420;

    private static final float SLIDE_TO_EDGE_THRESHOLD_RATIO = 0.65f;
    private static final float DOCKED_WIDTH_RATIO = 0.1f;
    private static final float DOCKED_ALPHA = 0.6f;
    private static final float NORMAL_ALPHA = 1.0f;

    private final List<String> mForceUpdateDpiList;

    private final PopUpAnimationController mPopUpAnimationController;

    private final Configuration mConfiguration = new Configuration();
    private final TransitionInfoExt mTransitionInfoExt = new TransitionInfoExt();
    private final Point mWindowCenterPosition;
    private final PointF mPinnedWindowVerticalPosRatio;
    private final Rect mWindowBoundaryGap;

    private float mCornerRadius;
    private float mPinnedWindowCornerRadius;
    private float mMiniWindowCornerRadius;
    private boolean mIsPinnedWindowSmall = true;

    private boolean mMute = false;

    private float mWindowSurfaceScale;
    private float mWindowSurfaceScaleFactor;

    final WindowManagerService mService;
    final Task mTask;

    int mFreezedWindowingMode = WINDOWING_MODE_UNDEFINED;

    private boolean mIsDockedToEdge = false;
    private boolean mIsDockedLeft = true;
    private Rect mOriginalBoundsBeforeDock = new Rect();
    private float mOriginalScaleBeforeDock = 1.0f;
    private float mOriginalAlphaBeforeDock = 1.0f;
    private Point mOriginalCenterPositionBeforeDock = new Point();
    private float mOriginalCornerRadiusBeforeDock = 0f;

    private int mLastSurfaceX = Integer.MIN_VALUE;
    private int mLastSurfaceY = Integer.MIN_VALUE;
    private float mLastSurfaceScale = -1.0f;

    TaskWindowSurfaceInfo(Task task) {
        mTask = task;
        mService = task.mWmService;

        mConfiguration.setTo(mTask.getConfiguration());

        mForceUpdateDpiList = Arrays.asList(mService.mContext.getResources()
                .getStringArray(R.array.config_popUpView_forceUpdateDpiPackages));

        mWindowCenterPosition = new Point();
        mPinnedWindowVerticalPosRatio = new PointF(0.2f, 0.2f);
        setWindowSurfaceScale(1.0f);
        mWindowSurfaceScaleFactor = 1.0f;
        mWindowBoundaryGap = new Rect(0, BOUNDARY_GAP, BOUNDARY_GAP, 0);

        mMiniWindowCornerRadius = mService.mContext.getResources()
                .getDimensionPixelSize(R.dimen.mini_window_corner_radius);
        mPinnedWindowCornerRadius = mService.mContext.getResources()
                .getDimensionPixelSize(R.dimen.pinned_window_corner_radius);
        mCornerRadius = mMiniWindowCornerRadius;

        mPopUpAnimationController = new PopUpAnimationController(mService);
        mPopUpAnimationController.setTask(task);
    }

    TaskWindowSurfaceInfo(TaskWindowSurfaceInfo other, int preFreezedWindowingMode) {
        mTask = other.mTask;
        mService = other.mService;

        mFreezedWindowingMode = preFreezedWindowingMode;
        mConfiguration.setTo(mTask.getConfiguration());
        mMute = other.getMute();

        mForceUpdateDpiList = Arrays.asList(mService.mContext.getResources()
                .getStringArray(R.array.config_popUpView_forceUpdateDpiPackages));

        mWindowCenterPosition = other.getWindowCenterPosition();
        mPinnedWindowVerticalPosRatio = other.getPinnedWindowVerticalPosRatioPointF();
        mWindowSurfaceScale = other.getWindowSurfaceScale();
        mWindowSurfaceScaleFactor = other.getWindowSurfaceScaleFactor();
        mWindowBoundaryGap = other.getWindowBoundaryGap();

        mCornerRadius = other.getCornerRadius();
        mIsPinnedWindowSmall = other.isPinnedWindowSmall();

        mPopUpAnimationController = new PopUpAnimationController(mService);
        mPopUpAnimationController.setTask(mTask);
    }

    void toggleMute() {
        mMute = !mMute;
    }

    boolean getMute() {
        return mMute;
    }

    void setWindowCenterPosition(Point pos) {
        mWindowCenterPosition.set(pos.x, pos.y);
    }

    Point getWindowCenterPosition() {
        return new Point(mWindowCenterPosition.x, mWindowCenterPosition.y);
    }

    PopUpViewInfo getPopUpViewInfo() {
        return mTransitionInfoExt.getPopUpViewInfo();
    }

    void setWindowSurfaceScaleDrag(float scale, Rect displayBound, boolean isLandscape) {
        if (mWindowSurfaceScale != scale) {
            mWindowSurfaceScale = scale;
            if (mTask.getWindowConfiguration().isPinnedExtWindowMode()) {
                final Rect result = new Rect();
                final Point pos = new Point();
                final Rect bound = mTask.getBounds();
                WindowResizingAlgorithm.getPositionAndScaleFactorForTask(
                        bound, displayBound, mWindowCenterPosition, mWindowSurfaceScale,
                        false, pos);
                result.set(0, 0, bound.width(), bound.height());
                result.scale(getWindowSurfaceRealScale());
                result.offsetTo(pos.x, pos.y);
                DimmerWindow.getInstance().onDragResizeChanged(scale, result, isLandscape);
            } else {
                DimmerWindow.getInstance().onDragResizeChanged(scale,
                        getTaskWindowSurfaceBoundsOnDrag(displayBound), isLandscape);
            }
        }
    }

    void setWindowSurfaceScale(float scale) {
        if (scale == WindowResizingAlgorithm.PINNED_WINDOW_SCALE_SMALL_PORT
                || scale == WindowResizingAlgorithm.PINNED_WINDOW_SCALE_SMALL_LAND) {
            mIsPinnedWindowSmall = true;
        } else if (scale == WindowResizingAlgorithm.PINNED_WINDOW_SCALE_LARGE_PORT
                || scale == WindowResizingAlgorithm.PINNED_WINDOW_SCALE_LARGE_LAND) {
            mIsPinnedWindowSmall = false;
        }
        if (mWindowSurfaceScale != scale) {
            mWindowSurfaceScale = scale;
            DimmerWindow.getInstance().onResizeChanged();
        }
    }

    float getWindowSurfaceScale() {
        return mWindowSurfaceScale;
    }

    float getWindowSurfaceRealScale() {
        return mWindowSurfaceScale * mWindowSurfaceScaleFactor;
    }

    float getWindowSurfaceRealScale(float scale) {
        return scale * mWindowSurfaceScaleFactor;
    }

    void setWindowSurfaceScaleFactor(float factor) {
        mWindowSurfaceScaleFactor = factor;
    }

    float getWindowSurfaceScaleFactor() {
        return mWindowSurfaceScaleFactor;
    }

    void resetWindowBoundaryGap() {
        mWindowBoundaryGap.setEmpty();
    }

    float getCornerRadius() {
        return mCornerRadius;
    }

    void setCornerRadius(float cornerRadius) {
        mCornerRadius = cornerRadius;
    }

    float getMiniWindowCornerRadius() {
        return mMiniWindowCornerRadius;
    }

    void setPinnedWindowVerticalPosRatio(Point pos, Rect displayBound, boolean isDrag) {
        if (displayBound != null && displayBound.height() > 0) {
            final float ratio = (float) pos.y / displayBound.height();
            if (isDrag) {
                mPinnedWindowVerticalPosRatio.set(ratio, ratio);
            } else if (displayBound.height() > displayBound.width()) {
                mPinnedWindowVerticalPosRatio.x = ratio;
            } else {
                mPinnedWindowVerticalPosRatio.y = ratio;
            }
        }
    }

    float getPinnedWindowVerticalPosRatio(Rect displayBound) {
        return displayBound.height() > displayBound.width() ?
                mPinnedWindowVerticalPosRatio.x : mPinnedWindowVerticalPosRatio.y;
    }

    PointF getPinnedWindowVerticalPosRatioPointF() {
        return new PointF(mPinnedWindowVerticalPosRatio.x, mPinnedWindowVerticalPosRatio.y);
    }

    void resetWindowBoundaryGapToOrigin() {
        mWindowBoundaryGap.set(0, BOUNDARY_GAP * 2, BOUNDARY_GAP, 0);
    }

    void setWindowBoundaryGap(int left, int top, int right, int bottom) {
        mWindowBoundaryGap.set(left, top, right, bottom);
    }

    Rect getWindowBoundaryGap() {
        return new Rect(mWindowBoundaryGap.left, mWindowBoundaryGap.top,
                mWindowBoundaryGap.right, mWindowBoundaryGap.bottom);
    }

    Rect getTaskWindowSurfaceBounds() {
        int windowingMode = mFreezedWindowingMode;
        if (windowingMode == WINDOWING_MODE_UNDEFINED) {
            windowingMode = mTask.getConfiguration().windowConfiguration.getWindowingMode();
        }
        final Rect result = new Rect();
        if (WindowConfiguration.isPopUpWindowMode(windowingMode)) {
            final Rect displayBound = new Rect();
            if (mTask.mDisplayContent != null) {
                mTask.mDisplayContent.getBounds(displayBound);
            }
            final Point pos = new Point();
            final Rect bound = mTask.getBounds();
            final boolean isPinned = WindowConfiguration.isPinnedExtWindowMode(windowingMode);
            if (isPinned) {
                if (mIsDragging) {
                    final Point center = getWindowCenterPosition();
                    result.set(0, 0, bound.width(), bound.height());
                    result.scale(mWindowSurfaceScale);
                    result.offsetTo(center.x - result.width() / 2,
                            center.y - result.height() / 2);
                } else {
                    final float verticalPosRatio = getPinnedWindowVerticalPosRatio(displayBound);
                    WindowResizingAlgorithm.getCenterByBoundaryGap(bound, displayBound,
                            getWindowBoundaryGap(), verticalPosRatio, getWindowCenterPosition(),
                            getWindowSurfaceScale(), pos);
                    result.set(0, 0, bound.width(), bound.height());
                    result.scale(mWindowSurfaceScale);
                    result.offsetTo(pos.x - result.width() / 2, pos.y - result.height() / 2);
                }
            } else {
                mWindowSurfaceScaleFactor = WindowResizingAlgorithm.getPositionAndScaleFactorForTask(
                        bound, displayBound, mWindowCenterPosition, mWindowSurfaceScale,
                        false, pos);
                result.set(0, 0, bound.width(), bound.height());
                result.scale(getWindowSurfaceRealScale());
                result.offsetTo(pos.x, pos.y);
            }
        }
        return result;
    }

    Rect getTaskWindowSurfaceBoundsOnDrag(Rect displayBound) {
        final Rect result = new Rect();
        final Point pos = new Point();
        final Rect bound = mTask.getBounds();
        final boolean isPinned = mTask.getWindowConfiguration().isPinnedExtWindowMode();
        if (!isPinned) {
            mWindowSurfaceScaleFactor = WindowResizingAlgorithm.getPositionAndScaleFactorForTask(
                    bound, displayBound, mWindowCenterPosition, mWindowSurfaceScale,
                    false, pos);
        } else {
            WindowResizingAlgorithm.getPositionAndScaleFactorForTask(
                    bound, displayBound, mWindowCenterPosition, mWindowSurfaceScale,
                    false, pos);
        }
        result.set(0, 0, bound.width(), bound.height());
        result.scale(getWindowSurfaceRealScale());
        result.offsetTo(pos.x, pos.y);
        return result;
    }

    void onWindowingModeChanged(int preWindowMode) {
        final WindowConfiguration winConfig = mTask.getConfiguration().windowConfiguration;
        final boolean isMiniWindow = winConfig.isMiniExtWindowMode();
        final boolean isPopUpWindow = winConfig.isPopUpWindowMode();
        final boolean isPrevMiniWindow = WindowConfiguration.isMiniExtWindowMode(preWindowMode);
        final boolean isPrevPopUpWindow = WindowConfiguration.isPopUpWindowMode(preWindowMode);
        if (DEBUG_POP_UP) {
            Slog.d(TAG, "onWindowingModeChanged " + preWindowMode + "->"
                    + winConfig.getWindowingMode() + " mTask=" + mTask);
        }
        if (!isPopUpWindow && isPrevPopUpWindow) {
            resetDraggingState();
            if (mIsDockedToEdge) {
                mIsDockedToEdge = false;
            }
            final IWindow window = getIWindow();
            if (window != null) {
                finishTaskPositioning(window);
            }
            if (cancelPopUpViewAnimation()) {
                if (DEBUG_POP_UP) {
                    Slog.d(TAG, "cancel PopUpViewAnimation when exit PopupView. mTask=" + mTask);
                }
            }
        }
        if (isMiniWindow) {
            resetDraggingState();
            if (isPrevPopUpWindow && !isPrevMiniWindow) {
            } else {
                final Rect displayBound = new Rect();
                if (mTask.mDisplayContent != null) {
                    mTask.mDisplayContent.getBounds(displayBound);
                    final int displayRotation = mTask.mDisplayContent.getRotation();
                    final boolean isLandscape = displayRotation == Surface.ROTATION_90
                            || displayRotation == Surface.ROTATION_270;
                    final int leftMargin = (int) (displayBound.width() * 0.15f);
                    final Point pos;
                    if (isLandscape) {
                        pos = new Point(leftMargin, displayBound.height() / 2);
                    } else {
                        pos = new Point(displayBound.width() / 2, displayBound.height() / 2);
                    }
                    setWindowSurfaceScale(WindowResizingAlgorithm.getDefaultMiniWindowScale(
                            mTask.getConfiguration().orientation, mTask.mDisplayContent.getRotation()));
                    setWindowCenterPosition(pos);
                }
            }
            mCornerRadius = mMiniWindowCornerRadius;
        } else if (mTask.getWindowConfiguration().isPinnedExtWindowMode()) {
            mCornerRadius = mPinnedWindowCornerRadius;
            if (!isPrevPopUpWindow) {
                resetWindowBoundaryGapToOrigin();
                final Rect displayBound = new Rect();
                if (mTask.mDisplayContent != null) {
                    mTask.mDisplayContent.getBounds(displayBound);
                }
                if (!displayBound.isEmpty()) {
                    setWindowSurfaceScale(WindowResizingAlgorithm.getDefaultPinnedWindowScale(
                            mTask.getConfiguration().orientation, mIsPinnedWindowSmall));
                    final Point pos = new Point();
                    final Rect bounds = mTask.getBounds();
                    WindowResizingAlgorithm.getCenterByBoundaryGap(
                            bounds, displayBound, getWindowBoundaryGap(),
                            getPinnedWindowVerticalPosRatio(displayBound),
                            getWindowCenterPosition(),
                            getWindowSurfaceScale(), pos);
                    setWindowCenterPosition(pos);
                    setWindowSurfaceScaleFactor(1.0f);
                }
            }
        }
        if (isPopUpWindow || isPrevPopUpWindow) {
            final SurfaceControl surfaceControl = mTask.getSurfaceControl();
            if (surfaceControl != null && surfaceControl.isValid()) {
                mService.mTransactionFactory.get().setTrustedOverlay(surfaceControl, isPopUpWindow).apply();
            }
            updateDensityIfNeed(isPrevPopUpWindow && !isPopUpWindow);
        }
    }

    private void updateDensityIfNeed(boolean isExitPopUpView) {
        if (mTask.getWindowConfiguration().isPopUpWindowMode()) {
            final int initDensity = mTask.mDisplayContent == null ?
                    DENSITY_DEFAULT : mTask.mDisplayContent.mInitialDisplayDensity;
            if (mTask.getConfiguration().densityDpi < initDensity) {
                final ActivityRecord topActivity = mTask.topRunningActivityLocked();
                if (topActivity == null || !mForceUpdateDpiList.contains(topActivity.packageName)) {
                    return;
                }
                if (DEBUG_POP_UP) {
                    Slog.d(TAG, "force update density for " + mTask +
                            " densityDpi=" + mTask.getConfiguration().densityDpi);
                }
                mTask.getRequestedOverrideConfiguration().densityDpi = initDensity;
                mTask.onRequestedOverrideConfigurationChanged(mTask.getRequestedOverrideConfiguration());
            }
        } else if (isExitPopUpView && mTask.getRequestedOverrideConfiguration().densityDpi != 0) {
            if (DEBUG_POP_UP) {
                Slog.d(TAG, "reset density for " + mTask +
                        " densityDpi=" + mTask.getRequestedOverrideConfiguration().densityDpi);
            }
            mTask.getRequestedOverrideConfiguration().densityDpi = 0;
            mTask.onRequestedOverrideConfigurationChanged(mTask.getRequestedOverrideConfiguration());
        }
    }

    void onRotationChanged() {
        if (mTask.mDisplayContent == null) {
            return;
        }
        final Rect displayBound = new Rect();
        mTask.mDisplayContent.getBounds(displayBound);
        if (displayBound.isEmpty()) {
            return;
        }
        
        if (mTask.getConfiguration().windowConfiguration.isMiniExtWindowMode()) {
            final Point pos = new Point(displayBound.width() / 2, displayBound.height() / 2);
            setWindowSurfaceScale(WindowResizingAlgorithm.getDefaultMiniWindowScale(
                    mTask.getConfiguration().orientation, mTask.mDisplayContent.getRotation()));
            setWindowCenterPosition(pos);
        } else if (mTask.getConfiguration().windowConfiguration.isPinnedExtWindowMode()) {
            if (mIsDockedToEdge) {
                updateDockedPositionAfterRotation(displayBound);
            } else {
                setWindowSurfaceScale(WindowResizingAlgorithm.getDefaultPinnedWindowScale(
                        mTask.getConfiguration().orientation, mIsPinnedWindowSmall));
                final Point pos = new Point();
                WindowResizingAlgorithm.getCenterByBoundaryGap(
                        mTask.getBounds(), displayBound, getWindowBoundaryGap(),
                        getPinnedWindowVerticalPosRatio(displayBound),
                        getWindowCenterPosition(),
                        getWindowSurfaceScale(), pos);
                setWindowCenterPosition(pos);
                setWindowSurfaceScaleFactor(1.0f);
                final Rect surfaceBounds = getTaskWindowSurfaceBounds();
                PinnedWindowOverlayController.getInstance().updateOverlayPosition(
                        surfaceBounds.left, surfaceBounds.top,
                        mTask.getBounds().width(), mTask.getBounds().height(), mWindowSurfaceScale);
                mLastSurfaceX = Integer.MIN_VALUE;
                mLastSurfaceY = Integer.MIN_VALUE;
                mLastSurfaceScale = -1.0f;
            }
        }
    }

    private void updateDockedPositionAfterRotation(Rect displayBound) {
        final float realScale = mOriginalScaleBeforeDock;
        final int windowWidth = (int) (mOriginalBoundsBeforeDock.width() * realScale);
        final int windowHeight = (int) (mOriginalBoundsBeforeDock.height() * realScale);
        final int visibleWidth = (int) (displayBound.width() * DOCKED_WIDTH_RATIO);
        final int visibleHeight = (int) (windowHeight * 0.5f);

        final int dockedLeft = mIsDockedLeft ? 0 : displayBound.width() - windowWidth;
        final int dockedTop = Math.max(0, Math.min(
                (int) (mWindowCenterPosition.y - visibleHeight / 2),
                displayBound.height() - visibleHeight));

        mWindowCenterPosition.set(dockedLeft + windowWidth / 2, dockedTop + visibleHeight / 2);

        final int overlayX = mIsDockedLeft ? 0 : displayBound.width() - visibleWidth;
        final int overlayY = dockedTop;
        
        PinnedWindowOverlayController.getInstance().updateOverlayPosition(
                overlayX, overlayY, visibleWidth, visibleHeight, 1.0f);

        if (mTask.mSurfaceControl != null && mTask.mSurfaceControl.isValid()) {
            final int cropWidth = (int) (visibleWidth / realScale);
            final int cropHeight = (int) (mOriginalBoundsBeforeDock.height() * 0.5f);
            
            mService.mTransactionFactory.get()
                    .setPosition(mTask.mSurfaceControl, overlayX, overlayY)
                    .setWindowCrop(mTask.mSurfaceControl, cropWidth, cropHeight)
                    .setAlpha(mTask.mSurfaceControl, DOCKED_ALPHA)
                    .apply();
        }
    }

    void onConfigurationChanged() {
        final Configuration newConfig = mTask.getConfiguration();
        final long diff = mConfiguration.windowConfiguration.diff(newConfig.windowConfiguration, false);
        if ((WindowConfiguration.WINDOW_CONFIG_BOUNDS & diff) != 0
                || (WindowConfiguration.WINDOW_CONFIG_ROTATION & diff) != 0) {
            if (mTask.getWindowConfiguration().isMiniExtWindowMode()
                    && mTask.mDisplayContent != null) {
                setWindowSurfaceScale(WindowResizingAlgorithm.getDefaultMiniWindowScale(
                        mTask.getConfiguration().orientation, mTask.mDisplayContent.getRotation()));
            }
            DimmerWindow.getInstance().onResizeChanged();
        }
        if ((mConfiguration.diff(newConfig) & ActivityInfo.CONFIG_DENSITY) != 0) {
            updateDensityIfNeed(false);
        }
        mConfiguration.setTo(newConfig);
    }

    void onPrepareSurfaces(SurfaceControl.Transaction t) {
        final WindowConfiguration winConfig = mTask.getConfiguration().windowConfiguration;
        final boolean isPinned = winConfig.isPinnedExtWindowMode();
        final boolean animating = mPopUpAnimationController.isAnimating();
        if (isPinned && !mIsDragging) {
            final int[] xy = new int[2];
            final float[] scaleArr = new float[1];
            if (mPopUpAnimationController.getCurrentAnimPosition(xy, scaleArr)) {
                PinnedWindowOverlayController.getInstance().updateOverlayPosition(
                        xy[0], xy[1], mTask.getBounds().width(),
                        mTask.getBounds().height(), scaleArr[0]);
                if (!animating) {
                    mPopUpAnimationController.clearAnimPosition();
                }
            }
        }

        final boolean hasAnimationLeash = hasTaskSurfaceAnimationLeash() ||
                animating ||
                mTask.mTransitionController.isPlaying();
        if (winConfig.isPopUpWindowMode() && !hasAnimationLeash &&
                !isWindowPositioningLocked() && !mIsDragging && !isPinned) {
            final Rect displayBound = new Rect();
            if (mTask.mDisplayContent != null) {
                mTask.mDisplayContent.getBounds(displayBound);
            }
            final Point pos = new Point();
            final Rect bound = mTask.getBounds();
            mWindowSurfaceScaleFactor = WindowResizingAlgorithm.getPositionAndScaleFactorForTask(
                    bound, displayBound, mWindowCenterPosition, mWindowSurfaceScale,
                    false, pos);
            t.setPosition(mTask.mSurfaceControl, pos.x, pos.y)
                    .setWindowCrop(mTask.mSurfaceControl, bound.width(), bound.height())
                    .setCornerRadius(mTask.mSurfaceControl, mCornerRadius)
                    .setScale(mTask.mSurfaceControl, getWindowSurfaceRealScale(), getWindowSurfaceRealScale());
            if (mIsDockedToEdge) {
                t.setAlpha(mTask.mSurfaceControl, DOCKED_ALPHA);
            } else {
                t.setAlpha(mTask.mSurfaceControl, NORMAL_ALPHA);
            }
            t.show(mTask.mSurfaceControl);
        }
        if (isPinned && !hasAnimationLeash && !isWindowPositioningLocked() && !mIsDragging
                && mTask.mSurfaceControl != null && mTask.mSurfaceControl.isValid()) {
            final Rect surfaceBounds = getTaskWindowSurfaceBounds();
            if (surfaceBounds.left != mLastSurfaceX || surfaceBounds.top != mLastSurfaceY
                    || mLastSurfaceScale != mWindowSurfaceScale) {
                t.setPosition(mTask.mSurfaceControl, surfaceBounds.left, surfaceBounds.top)
                        .setWindowCrop(mTask.mSurfaceControl, mTask.getBounds().width(), mTask.getBounds().height())
                        .setCornerRadius(mTask.mSurfaceControl, mCornerRadius)
                        .setScale(mTask.mSurfaceControl, mWindowSurfaceScale, mWindowSurfaceScale)
                        .setAlpha(mTask.mSurfaceControl, mIsDockedToEdge ? DOCKED_ALPHA : NORMAL_ALPHA)
                        .show(mTask.mSurfaceControl);
                PinnedWindowOverlayController.getInstance().updateOverlayPosition(
                        surfaceBounds.left, surfaceBounds.top,
                        mTask.getBounds().width(), mTask.getBounds().height(), mWindowSurfaceScale);
                mLastSurfaceX = surfaceBounds.left;
                mLastSurfaceY = surfaceBounds.top;
                mLastSurfaceScale = mWindowSurfaceScale;
            }
        }
    }

    void scheduleTransition(TaskWindowSurfaceInfo freezeTaskWindowSurfaceInfo, DisplayInfo displayInfo) {
        mTransitionInfoExt.setupPopUpViewInfo(freezeTaskWindowSurfaceInfo, this, displayInfo);
        if (!mTask.mTransitionController.isShellTransitionsEnabled()) {
            final PopUpViewInfo info = mTransitionInfoExt.getPopUpViewInfo();
            final Rect displayBound = new Rect(0, 0, displayInfo.appWidth, displayInfo.appHeight);
            final int boundWidth = info.mWindowCrop.width();
            final int boundHeight = info.mWindowCrop.height();
            final boolean isLandscape = displayInfo.rotation == Surface.ROTATION_90
                    || displayInfo.rotation == Surface.ROTATION_270;
            mPopUpAnimationController.playResizeAnimation(
                    info.mStartPos, info.mEndPos, boundWidth, boundHeight,
                    info.mStartScale, info.mEndScale, displayBound, isLandscape, this);
        }
    }

    boolean isCrossOverAnimating() {
        return mPopUpAnimationController.isCrossOverAnimating();
    }

    void playExitAnimation(boolean isFromLeaveButton, float startScale,
            PopUpAnimationController.OnAnimationEndCallback callback) {
        mPopUpAnimationController.playExitAnimation(
                isFromLeaveButton, startScale, callback);
    }

    void resizeWindowWithAnimation(Point startPos, Point endPos, int boundWidth,
            int boundHeight, float startWinScale, float endWinScale,
            Rect displayBound, boolean isLandscape) {
        mPopUpAnimationController.playResizeAnimation(startPos, endPos,
                boundWidth, boundHeight, startWinScale, endWinScale,
                displayBound, isLandscape, this);
    }

    void playToggleResizeWindowAnimation(Point startPos, Point endPos, float startWinScale,
            float endWinScale, PopUpAnimationController.OnAnimationEndCallback callback) {
        mPopUpAnimationController.playToggleResizeWindowAnimation(
                startPos, endPos, startWinScale, endWinScale, callback);
    }

    void flingWindowToEdge(Point startPos, Point endPos, int boundWidth, int boundHeight,
            float winScale, float velX, float velY) {
        mPopUpAnimationController.playSpringAnimation(
                startPos, endPos, boundWidth, boundHeight, winScale, velX, velY);
    }

    boolean cancelPopUpViewAnimation() {
        return mPopUpAnimationController.cancelAnimation();
    }

    private void finishTaskPositioning(IWindow window) {
        try {
            if (mService != null && window != null) {
                synchronized (mService.mGlobalLock) {
                }
            }
        } catch (Exception e) {
            if (DEBUG_POP_UP) {
                Slog.w(TAG, "Failed to finish task positioning", e);
            }
        }
    }

    private boolean isWindowPositioningLocked() {
        try {
            synchronized (mService.mGlobalLock) {
                return false;
            }
        } catch (Exception e) {
            if (DEBUG_POP_UP) {
                Slog.w(TAG, "Failed to check window positioning lock state", e);
            }
            return false;
        }
    }

    private boolean mIsDragging = false;
    private float mDragStartX, mDragStartY;
    private int mDragStartLeft, mDragStartTop;
    private Point mDragStartCenterPosition;
    private float mDragLastX, mDragLastY;
    private long mDragStartTime;
    private android.view.VelocityTracker mVelocityTracker;

    void startMoving(float x, float y) {
        final Rect bounds = getTaskWindowSurfaceBounds();
        final int[] animXY = new int[2];
        final float[] animScale = new float[1];
        if (mPopUpAnimationController.getCurrentAnimPosition(animXY, animScale)) {
            bounds.offsetTo(animXY[0], animXY[1]);
            mPopUpAnimationController.clearAnimPosition();
        }
        cancelPopUpViewAnimation();
        mIsDragging = true;
        mDragStartX = x;
        mDragStartY = y;
        mDragLastX = x;
        mDragLastY = y;
        mDragStartTime = android.os.SystemClock.uptimeMillis();
        mDragStartCenterPosition = getWindowCenterPosition();
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
        }
        mVelocityTracker = android.view.VelocityTracker.obtain();
        final long now = android.os.SystemClock.uptimeMillis();
        final MotionEvent syntheticEvent = MotionEvent.obtain(
                now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        mVelocityTracker.addMovement(syntheticEvent);
        syntheticEvent.recycle();
        mDragStartLeft = bounds.left;
        mDragStartTop = bounds.top;
        if (DEBUG_POP_UP) {
            Slog.d(TAG, "startMoving: startX=" + x + " startY=" + y + " bounds=" + bounds
                    + " mDragStartLeft=" + mDragStartLeft + " mDragStartTop=" + mDragStartTop);
        }
    }

    void moveTo(float x, float y) {
        if (!mIsDragging) {
            return;
        }
        final float dx = x - mDragStartX;
        final float dy = y - mDragStartY;
        final int newLeft = (int) (mDragStartLeft + dx);
        final int newTop = (int) (mDragStartTop + dy);
        final boolean isPinned = mTask.getWindowConfiguration().isPinnedExtWindowMode();
        final float scale = isPinned ? mWindowSurfaceScale : getWindowSurfaceRealScale();

        final SurfaceControl.Transaction t = mService.mTransactionFactory.get();
        if (mTask.mSurfaceControl != null && mTask.mSurfaceControl.isValid()) {
            t.setPosition(mTask.mSurfaceControl, newLeft, newTop)
             .setScale(mTask.mSurfaceControl, scale, scale);
            t.apply();
        }
        final int scaledWidth = (int) (mTask.getBounds().width() * scale);
        final int scaledHeight = (int) (mTask.getBounds().height() * scale);
        mWindowCenterPosition.set(newLeft + scaledWidth / 2, newTop + scaledHeight / 2);
        if (isPinned) {
            PinnedWindowOverlayController.getInstance().updateOverlayPosition(newLeft, newTop,
                    mTask.getBounds().width(), mTask.getBounds().height(), scale);
        }

        if (DEBUG_POP_UP) {
            Slog.d(TAG, "moveTo: newLeft=" + newLeft + " newTop=" + newTop + " scale=" + getWindowSurfaceRealScale());
        }
    }

    void finishMoving() {
        if (!mIsDragging) {
            return;
        }
        final Rect surfaceBounds = getTaskWindowSurfaceBounds();

        mIsDragging = false;

        if (mVelocityTracker != null) {
            mVelocityTracker.computeCurrentVelocity(1000,
                    android.view.ViewConfiguration.get(mService.mContext).getScaledMaximumFlingVelocity());
        }
        final float xVelocity = mVelocityTracker != null ? mVelocityTracker.getXVelocity() : 0.0f;
        final float yVelocity = mVelocityTracker != null ? mVelocityTracker.getYVelocity() : 0.0f;
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }

        final Rect displayBound = new Rect();
        if (mTask.mDisplayContent != null) {
            mTask.mDisplayContent.getBounds(displayBound);
        }
        final int displayWidth = displayBound.width();

        final Point startPos = new Point(surfaceBounds.left, surfaceBounds.top);
        final Point currentCenterPos = new Point(
                surfaceBounds.left + surfaceBounds.width() / 2,
                surfaceBounds.top + surfaceBounds.height() / 2);

        final float winScale = getWindowSurfaceScale();
        final Rect bounds = mTask.getBounds();

        if (mTask.getWindowConfiguration().isPinnedExtWindowMode()) {
            if (shouldSlideToEdge(surfaceBounds, displayBound, xVelocity, yVelocity)) {
                dockToEdge(surfaceBounds, displayBound, xVelocity);
                return;
            }
        }

        final Rect boundaryGap = WindowResizingAlgorithm.getBoundaryGapAfterMoving(
                displayWidth, displayBound, surfaceBounds,
                currentCenterPos.x, currentCenterPos.y, xVelocity, yVelocity);

        setWindowBoundaryGap(boundaryGap.left, boundaryGap.top, boundaryGap.right, boundaryGap.bottom);

        final Point endPos = new Point();
        WindowResizingAlgorithm.getCenterByBoundaryGap(bounds, displayBound, boundaryGap,
                getPinnedWindowVerticalPosRatio(displayBound), currentCenterPos, winScale, endPos);

        setWindowCenterPosition(endPos);
        final boolean isPinned = mTask.getWindowConfiguration().isPinnedExtWindowMode();
        if (isPinned) {
            setPinnedWindowVerticalPosRatio(endPos, displayBound, true);
        }

        final float realScale = isPinned ? mWindowSurfaceScale : getWindowSurfaceRealScale();
        endPos.x -= bounds.width() * realScale / 2;
        endPos.y -= bounds.height() * realScale / 2;

        flingWindowToEdge(startPos, endPos, bounds.width(), bounds.height(), realScale, xVelocity, yVelocity);

        if (DEBUG_POP_UP) {
            Slog.d(TAG, "finishMoving: surfaceBounds=" + surfaceBounds + " startPos=" + startPos
                    + " endPos=" + endPos + " boundaryGap=" + boundaryGap
                    + " velX=" + xVelocity + " velY=" + yVelocity
                    + " realScale=" + realScale);
        }
    }

    private boolean shouldSlideToEdge(Rect surfaceBounds, Rect displayBound, float xVelocity, float yVelocity) {
        if (mIsDockedToEdge) {
            return false;
        }
        final int threshold = (int) (displayBound.width() * SLIDE_TO_EDGE_THRESHOLD_RATIO);
        final boolean nearLeftEdge = surfaceBounds.left < threshold;
        final boolean nearRightEdge = surfaceBounds.right > displayBound.width() - threshold;
        final boolean fastLeftSwipe = xVelocity < -3000;
        final boolean fastRightSwipe = xVelocity > 3000;
        final boolean isHorizontalSwipe = Math.abs(xVelocity) > Math.abs(yVelocity) * 2;
        final boolean isOutwardSwipe = (nearLeftEdge && fastLeftSwipe) || (nearRightEdge && fastRightSwipe);
        return isHorizontalSwipe && isOutwardSwipe && (surfaceBounds.left < 0 || surfaceBounds.right > displayBound.width());
    }

    private void dockToEdge(Rect surfaceBounds, Rect displayBound, float xVelocity) {
        cancelPopUpViewAnimation();

        final boolean toLeft = surfaceBounds.centerX() < displayBound.width() / 2;
        mIsDockedLeft = toLeft;
        mIsDockedToEdge = true;

        mOriginalBoundsBeforeDock.set(mTask.getBounds());
        mOriginalScaleBeforeDock = getWindowSurfaceScale();
        mOriginalAlphaBeforeDock = NORMAL_ALPHA;
        mOriginalCenterPositionBeforeDock.set(getWindowCenterPosition());
        mOriginalCornerRadiusBeforeDock = getCornerRadius();

        final float realScale = mWindowSurfaceScale;
        final int windowWidth = (int) (mTask.getBounds().width() * realScale);
        final int windowHeight = (int) (mTask.getBounds().height() * realScale);
        final int visibleWidth = (int) (displayBound.width() * DOCKED_WIDTH_RATIO);
        final int visibleHeight = (int) (windowHeight * 0.5f);

        final int dockedLeft = toLeft ? 0 : displayBound.width() - windowWidth;
        final int dockedTop = Math.max(0, Math.min(surfaceBounds.top + (windowHeight - visibleHeight) / 2,
                displayBound.height() - visibleHeight));

        final int startX = surfaceBounds.left;
        final int startY = surfaceBounds.top;
        final int endX = toLeft ? 0 : displayBound.width() - visibleWidth;
        final int endY = dockedTop;
        final int startCropWidth = mTask.getBounds().width();
        final int startCropHeight = mTask.getBounds().height();
        final int endCropWidth = (int) (visibleWidth / realScale);
        final int endCropHeight = (int) (mTask.getBounds().height() * 0.5f);
        final float startAlpha = NORMAL_ALPHA;
        final float endAlpha = DOCKED_ALPHA;

        mPopUpAnimationController.playDockAnimation(
                startX, startY, endX, endY,
                startCropWidth, startCropHeight, endCropWidth, endCropHeight,
                startAlpha, endAlpha, realScale, mTask, this);

        mWindowCenterPosition.set(dockedLeft + windowWidth / 2, dockedTop + visibleHeight / 2);

        PinnedWindowOverlayController.getInstance().updateOverlayPosition(
                toLeft ? 0 : displayBound.width() - visibleWidth, dockedTop,
                visibleWidth, visibleHeight, 1.0f);
        PinnedWindowOverlayController.getInstance().setDockedState(true);
    }

    void restoreFromDocked() {
        if (!mIsDockedToEdge) {
            return;
        }

        mIsDockedToEdge = false;
        PinnedWindowOverlayController.getInstance().setDockedState(false);

        final Rect displayBound = new Rect();
        if (mTask.mDisplayContent != null) {
            mTask.mDisplayContent.getBounds(displayBound);
        }

        final float realScale = mOriginalScaleBeforeDock;
        final Rect bounds = mOriginalBoundsBeforeDock;
        final Point centerPos = mOriginalCenterPositionBeforeDock;

        final Point endPos = new Point();
        WindowResizingAlgorithm.getCenterByBoundaryGap(bounds, displayBound, getWindowBoundaryGap(),
                getPinnedWindowVerticalPosRatio(displayBound), centerPos, realScale, endPos);

        setWindowCenterPosition(endPos);
        setPinnedWindowVerticalPosRatio(endPos, displayBound, true);

        endPos.x -= bounds.width() * realScale / 2;
        endPos.y -= bounds.height() * realScale / 2;

        final int currentVisibleWidth = (int) (displayBound.width() * DOCKED_WIDTH_RATIO);
        final int currentVisibleHeight = (int) (bounds.height() * realScale * 0.5f);
        final int startX = mIsDockedLeft ? 0 : displayBound.width() - currentVisibleWidth;
        final int startY = (int) (mWindowCenterPosition.y - currentVisibleHeight / 2);
        final int startCropWidth = (int) (currentVisibleWidth / realScale);
        final int startCropHeight = (int) (bounds.height() * 0.5f);
        final int endCropWidth = bounds.width();
        final int endCropHeight = bounds.height();
        final float startAlpha = DOCKED_ALPHA;
        final float endAlpha = NORMAL_ALPHA;

        mPopUpAnimationController.playRestoreAnimation(
                startX, startY, endPos.x, endPos.y,
                startCropWidth, startCropHeight, endCropWidth, endCropHeight,
                startAlpha, endAlpha, realScale, mOriginalCornerRadiusBeforeDock, mTask, this);

        PinnedWindowOverlayController.getInstance().updateOverlayPosition(endPos.x, endPos.y,
                bounds.width(), bounds.height(), realScale);

        if (DEBUG_POP_UP) {
            Slog.d(TAG, "restoreFromDocked: endPos=" + endPos + " realScale=" + realScale);
        }
    }

    boolean isDockedToEdge() {
        return mIsDockedToEdge;
    }

    void updateMoving(float x, float y) {
        mDragLastX = x;
        mDragLastY = y;
        if (mVelocityTracker != null) {
            final long now = android.os.SystemClock.uptimeMillis();
            final MotionEvent syntheticEvent = MotionEvent.obtain(
                    now, now, MotionEvent.ACTION_MOVE, x, y, 0);
            mVelocityTracker.addMovement(syntheticEvent);
            syntheticEvent.recycle();
        }
        moveTo(x, y);
    }

    void cancelMoving() {
        if (!mIsDragging) {
            return;
        }
        mIsDragging = false;
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
        if (mDragStartCenterPosition != null) {
            mWindowCenterPosition.set(mDragStartCenterPosition.x, mDragStartCenterPosition.y);
        }
        
        final SurfaceControl.Transaction t = mService.mTransactionFactory.get();
        if (mTask.mSurfaceControl != null && mTask.mSurfaceControl.isValid()) {
            t.setPosition(mTask.mSurfaceControl, mDragStartLeft, mDragStartTop);
            t.apply();
        }
        if (DEBUG_POP_UP) {
            Slog.d(TAG, "cancelMoving: restored to start position");
        }
    }

    void toggleResize() {
        final boolean isCurrentlySmall = isPinnedWindowSmall();
        final float newScale = isCurrentlySmall ?
                WindowResizingAlgorithm.getDefaultPinnedWindowScale(
                        mTask.getConfiguration().orientation, false) :
                WindowResizingAlgorithm.getDefaultPinnedWindowScale(
                        mTask.getConfiguration().orientation, true);

        setWindowSurfaceScale(newScale);
        if (mTask.getWindowConfiguration().isPinnedExtWindowMode()) {
            final Rect bounds = new Rect();
            mTask.getBounds(bounds);
            final Rect displayBound = new Rect();
            if (mTask.mDisplayContent != null) {
                mTask.mDisplayContent.getBounds(displayBound);
            }
            final Point pos = new Point();
            WindowResizingAlgorithm.getCenterByBoundaryGap(
                    bounds, displayBound, getWindowBoundaryGap(),
                    getPinnedWindowVerticalPosRatio(displayBound),
                    getWindowCenterPosition(), newScale, pos);
            setWindowCenterPosition(pos);
        }

        if (mTask.mSurfaceControl != null && mTask.mSurfaceControl.isValid()) {
            final Rect surfaceBounds = getTaskWindowSurfaceBounds();
            final SurfaceControl.Transaction t = mService.mTransactionFactory.get();
            t.setPosition(mTask.mSurfaceControl, surfaceBounds.left, surfaceBounds.top)
             .setScale(mTask.mSurfaceControl, newScale, newScale);
            t.apply();

            if (mTask.getWindowConfiguration().isPinnedExtWindowMode()) {
                PinnedWindowOverlayController.getInstance().updateOverlayPosition(
                        surfaceBounds.left, surfaceBounds.top,
                        mTask.getBounds().width(), mTask.getBounds().height(), newScale);
            }
        }

        if (DEBUG_POP_UP) {
            Slog.d(TAG, "toggleResize: " + (isCurrentlySmall ? "small->large" : "large->small") +
                    " newScale=" + newScale);
        }
    }

    boolean isPinnedWindowSmall() {
        return mIsPinnedWindowSmall;
    }

    void resetDraggingState() {
        if (mIsDragging) {
            mIsDragging = false;
            if (mVelocityTracker != null) {
                mVelocityTracker.recycle();
                mVelocityTracker = null;
            }
        }
    }

    private boolean hasTaskSurfaceAnimationLeash() {
        try {
            if (mTask.mSurfaceAnimator != null) {
                return mTask.mSurfaceAnimator.hasLeash();
            }
            return false;
        } catch (Exception e) {
            if (DEBUG_POP_UP) {
                Slog.w(TAG, "Failed to check animation leash state", e);
            }
            return false;
        }
    }

    private IWindow getIWindow() {
        synchronized (mService.mAtmService.mGlobalLock) {
            if (mTask != null && mTask.getTopVisibleAppMainWindow() != null) {
                final IWindow iWindow = mTask.getTopVisibleAppMainWindow().getIWindow();
                return iWindow;
            }
            return null;
        }
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder(1024);
        sb.append("Task=");
        sb.append(mTask);
        sb.append(" {mTask.windowingMode=");
        sb.append(mTask.getConfiguration().windowConfiguration.getWindowingMode());
        sb.append("}");
        sb.append(" {mFreezedWindowingMode=");
        sb.append(mFreezedWindowingMode);
        sb.append(" mMute=");
        sb.append(mMute);
        sb.append(" mWindowCenterPosition=");
        sb.append(mWindowCenterPosition);
        sb.append(" mWindowSurfaceScale=");
        sb.append(mWindowSurfaceScale);
        sb.append(" mWindowSurfaceScaleFactor=");
        sb.append(mWindowSurfaceScaleFactor);
        sb.append(" mWindowBoundaryGap=");
        sb.append(mWindowBoundaryGap);
        sb.append(" mCornerRadius=");
        sb.append(mCornerRadius);
        sb.append("}");
        return sb.toString();
    }
}
