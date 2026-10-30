/*
 * Copyright (C) 2026 The Android Open Source Project
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
package com.android.systemui.media.controls.util;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;

import java.util.concurrent.Executor;

/** Refreshes visible media UI on codec and preference changes without polling. */
public final class AudioStreamInfoObserver {
    private final Context mContext;
    private final Executor mExecutor;
    private final Runnable mCallback;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private volatile boolean mListening;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            dispatchChange();
        }
    };

    private final ContentObserver mSettingsObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            AudioStreamInfoObserver.this.dispatchChange();
        }
    };

    public AudioStreamInfoObserver(Context context, Executor executor, Runnable callback) {
        mContext = context;
        mExecutor = executor;
        mCallback = callback;
    }

    public static boolean isIndicatorEnabled(Context context) {
        return Settings.System.getIntForUser(context.getContentResolver(),
                Settings.System.SHOW_AUDIO_CODEC_INDICATOR, 0, UserHandle.USER_CURRENT) == 1;
    }

    public void start() {
        if (mListening) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothA2dp.ACTION_CODEC_CONFIG_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        mContext.registerReceiver(mReceiver, filter, null, mHandler, Context.RECEIVER_EXPORTED);
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.SHOW_AUDIO_CODEC_INDICATOR),
                false, mSettingsObserver, UserHandle.USER_ALL);
        mListening = true;
    }

    public void stop() {
        if (!mListening) return;
        mListening = false;
        mContext.unregisterReceiver(mReceiver);
        mContext.getContentResolver().unregisterContentObserver(mSettingsObserver);
    }

    private void dispatchChange() {
        mExecutor.execute(() -> {
            if (mListening) mCallback.run();
        });
    }
}
