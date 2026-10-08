/*
 * Copyright (C) 2023-2024 The Nameless-AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.wm;

import static android.provider.Settings.System.POP_UP_NOTIFICATION_BLACKLIST;
import static android.provider.Settings.System.POP_UP_SINGLE_TAP_ACTION;
import static android.provider.Settings.System.POP_UP_DOUBLE_TAP_ACTION;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.util.android.PopUpSettingsHelper;

import org.rising.view.PopUpViewManager;

class PopUpSettingsConfig {

    private static final String TAG = "PopUpSettingsConfig";

    // Default tap actions
    private static final int DEFAULT_SINGLE_TAP_ACTION = PopUpViewManager.TAP_ACTION_PIN_WINDOW;
    private static final int DEFAULT_DOUBLE_TAP_ACTION = PopUpViewManager.TAP_ACTION_EXIT;

    private static class InstanceHolder {
        private static final PopUpSettingsConfig INSTANCE = new PopUpSettingsConfig();
    }

    static PopUpSettingsConfig getInstance() {
        return InstanceHolder.INSTANCE;
    }

    private final ArraySet<String> mUserNotificationBlacklist = new ArraySet<>();

    private Context mContext;
    private Handler mHandler;
    private SettingsObserver mObserver;

    private int mSingleTapAction = DEFAULT_SINGLE_TAP_ACTION;
    private int mDoubleTapAction = DEFAULT_DOUBLE_TAP_ACTION;

    void init(Context context, Handler handler) {
        mContext = context;
        mHandler = handler;
        mObserver = new SettingsObserver(handler);
        mObserver.observe();
        updateAll();
    }

    private void updateNotificationBlacklist() {
        mUserNotificationBlacklist.clear();
        if (mContext == null) {
            Log.w(TAG, "Context is null, cannot update notification blacklist");
            return;
        }
        final String blacklist = PopUpSettingsHelper.getNotificationJumpBlacklist(mContext);
        if (TextUtils.isEmpty(blacklist)) {
            return;
        }
        final String[] apps = blacklist.split(";");
        for (String app : apps) {
            mUserNotificationBlacklist.add(app);
        }
    }

    boolean inNotificationBlacklist(String packageName) {
        return PopUpViewManager.inSystemNotificationBlacklist(packageName) ||
                mUserNotificationBlacklist.contains(packageName);
    }

    int getSingleTapAction() {
        return mSingleTapAction;
    }

    int getDoubleTapAction() {
        return mDoubleTapAction;
    }

    private void updateTapActions() {
        if (mContext == null) {
            Log.w(TAG, "Context is null, cannot update tap actions");
            return;
        }
        mSingleTapAction = PopUpSettingsHelper.getSingleTapAction(mContext);
        mDoubleTapAction = PopUpSettingsHelper.getDoubleTapAction(mContext);
    }

    void updateAll() {
        if (mHandler != null) {
            mHandler.post(() -> {
                updateNotificationBlacklist();
                updateTapActions();
            });
        } else {
            Log.w(TAG, "Handler is null, updating settings synchronously");
            updateNotificationBlacklist();
            updateTapActions();
        }
    }

    private final class SettingsObserver extends ContentObserver {

        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            if (mContext == null) {
                Log.e(TAG, "Context is null, cannot register content observer");
                return;
            }
            final ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(
                    Settings.System.getUriFor(POP_UP_NOTIFICATION_BLACKLIST),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(
                    Settings.System.getUriFor(POP_UP_SINGLE_TAP_ACTION),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(
                    Settings.System.getUriFor(POP_UP_DOUBLE_TAP_ACTION),
                    false, this, UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            switch (uri.getLastPathSegment()) {
                case POP_UP_NOTIFICATION_BLACKLIST:
                    updateNotificationBlacklist();
                    break;
                case POP_UP_SINGLE_TAP_ACTION:
                    updateTapActions();
                    break;
                case POP_UP_DOUBLE_TAP_ACTION:
                    updateTapActions();
                    break;
            }
        }
    }
}
