/*
 * Copyright (C) 2023-2024 The Nameless-AOSP Project
 * Copyright (C) 2026 The AviumUI Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.wm;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.android.internal.R;

class PinnedWindowOverlayView extends FrameLayout {

    private static final String TAG = "PinnedWindowOverlayView";

    private PinnedWindowOverlayController mController;
    private Task mTask;

    private ImageView mMuteButton;
    private ImageView mScaleButton;
    private View mMenuContainer;

    private boolean mIsMenuVisible = false;

    public PinnedWindowOverlayView(Context context) {
        super(context);
        init();
    }

    public PinnedWindowOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mMuteButton = findViewById(R.id.pinned_window_menu_mute_button);
        mScaleButton = findViewById(R.id.pinned_window_menu_scale_button);
        mMenuContainer = findViewById(R.id.pinned_window_menu_container);

        if (mMuteButton != null) {
            mMuteButton.setOnClickListener(v -> toggleMute());
        }

        if (mScaleButton != null) {
            mScaleButton.setOnClickListener(v -> toggleScale());
        }
        
        hideMenu();
    }

    void setController(PinnedWindowOverlayController controller) {
        mController = controller;
    }

    void setTask(Task task) {
        mTask = task;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        PinnedWindowOverlayController.getInstance().onTouchEvent(event);
        return true;
    }
    
    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (isTouchOnButton(event)) {
            return false;
        }

        return true;
    }

    private boolean isTouchOnButton(MotionEvent event) {
        if (mMenuContainer != null && mMenuContainer.getVisibility() == View.VISIBLE
                && isTouchOnView(event, mMenuContainer)) {
            return true;
        }
        return false;
    }

    private boolean isTouchOnView(MotionEvent event, View view) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        float x = event.getRawX();
        float y = event.getRawY();
        return x >= location[0] && x <= location[0] + view.getWidth()
                && y >= location[1] && y <= location[1] + view.getHeight();
    }

    void showMenu() {
        if (mMenuContainer != null) {
            mMenuContainer.setVisibility(View.VISIBLE);
            mIsMenuVisible = true;
        }
    }

    void hideMenu() {
        if (mMenuContainer != null) {
            mMenuContainer.setVisibility(View.GONE);
            mIsMenuVisible = false;
        }
    }

    private void toggleMute() {
        if (mTask == null) {
            return;
        }
        PopUpWindowController.getInstance().triggerPinnedWindowMute(mTask);
    }
    
    private void toggleScale() {
        if (mTask == null) {
            return;
        }
        PopUpWindowController.getInstance().triggerPinnedWindowResize(mTask);
    }
}
