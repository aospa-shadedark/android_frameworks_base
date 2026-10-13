/*
 * Copyright (C) 2026 PenguinOS Project
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
package com.android.server;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.UserHandle;
import android.util.Slog;

/** Keeps Pentas bound for a game session. All calls and callbacks use the supplied handler. */
final class PentasSessionBinder {
    private static final String TAG = "PentasSessionBinder";
    private static final ComponentName SESSION_SERVICE = new ComponentName(
            "org.yasvara.pentas", "org.yasvara.pentas.session.SessionService");
    private static final int BIND_FLAGS = Context.BIND_AUTO_CREATE
            | Context.BIND_FOREGROUND_SERVICE | Context.BIND_IMPORTANT;

    private final Context mContext;
    private final Handler mHandler;
    private Connection mConnection;

    PentasSessionBinder(Context context, Handler handler) {
        mContext = context;
        mHandler = handler;
    }

    void start(String packageName, int userId) {
        final Connection previous = mConnection;
        if (previous != null && previous.mUserId == userId
                && packageName.equals(previous.mPackageName)) {
            return;
        }
        // SessionService uses distinct URIs to distinguish overlapping binds. Bind the new
        // session before releasing the old one so its onUnbind cannot stop the new session.
        mConnection = bind(packageName, userId, true /* rebindOnDeath */);
        unbind(previous);
    }

    void stop() {
        final Connection connection = mConnection;
        mConnection = null;
        unbind(connection);
    }

    private Connection bind(String packageName, int userId, boolean rebindOnDeath) {
        final Connection connection = new Connection(packageName, userId, rebindOnDeath);
        try {
            final Intent intent = new Intent().setComponent(SESSION_SERVICE)
                    .setData(new Uri.Builder().scheme("pentas").authority("session")
                            .appendPath(packageName).appendQueryParameter("mode", "1").build());
            if (mContext.bindServiceAsUser(intent, connection, BIND_FLAGS, mHandler,
                    UserHandle.of(userId))) {
                return connection;
            }
            Slog.w(TAG, "Pentas is not available for " + packageName + " in user " + userId);
        } catch (Exception e) {
            Slog.w(TAG, "Failed to bind Pentas for " + packageName, e);
        }
        // A failed bind can still have registered the connection with Context.
        unbind(connection);
        return null;
    }

    private void unbind(Connection connection) {
        if (connection == null) return;
        try {
            mContext.unbindService(connection);
        } catch (IllegalArgumentException e) {
            // A failed bind may never have registered this connection.
        } catch (Exception e) {
            Slog.w(TAG, "Failed to unbind Pentas for " + connection.mPackageName, e);
        }
    }

    private final class Connection implements ServiceConnection {
        final String mPackageName;
        final int mUserId;
        final boolean mRebindOnDeath;

        Connection(String packageName, int userId, boolean rebindOnDeath) {
            mPackageName = packageName;
            mUserId = userId;
            mRebindOnDeath = rebindOnDeath;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {}

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // ActivityManager reconnects this binding after the service process restarts.
        }

        @Override
        public void onBindingDied(ComponentName name) {
            if (mConnection != this) return;
            mConnection = null;
            unbind(this);
            if (mRebindOnDeath) {
                mConnection = bind(mPackageName, mUserId, false /* rebindOnDeath */);
            }
        }

        @Override
        public void onNullBinding(ComponentName name) {
            if (mConnection != this) return;
            mConnection = null;
            unbind(this);
        }
    }
}
