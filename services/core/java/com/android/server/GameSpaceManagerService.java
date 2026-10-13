/*
 * Copyright (C) 2025 the AxionAOSP Project
 *           (C) 2025 crDroid Android Project
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

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.UserHandle;
import android.provider.Settings;
import android.widget.Toast;

import com.android.internal.R;
import com.android.server.SystemService;
import com.android.server.UiThread;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class GameSpaceManagerService extends SystemService {

    private static final String TAG = "GameSpaceManagerService";
    private static final String GAME_LIST_SETTING = Settings.System.GAMESPACE_GAME_LIST;
    private static final String AUTO_DETECT_SETTING = "gamespace_auto_game_detect";
    private static final String DENIED_LIST_SETTING = "gamespace_denied_list";
    private static final String ACTION_GAME_STATE_CHANGED =
            "com.android.server.action.PENTAS_GAME_STATE_CHANGED";
    private static final String EXTRA_GAME_PACKAGE = "package_name";
    private static final Set<String> VALID_MODES = Set.of("1", "2", "3");

    private final Context mContext;
    private final PackageManager mPackageManager;
    private final Handler mBackgroundHandler;
    private final BroadcastReceiver mPackageChangeReceiver = new PackageChangeReceiver();
    private final ContentObserver mGameListObserver;
    private final PentasSessionBinder mPentasSessions;
    private int mCurrentUserId;

    public GameSpaceManagerService(Context context) {
        super(context);
        mContext = context;
        mPackageManager = context.getPackageManager();

        HandlerThread handlerThread = new HandlerThread("GameSpaceManagerThread");
        handlerThread.start();
        mBackgroundHandler = new Handler(handlerThread.getLooper());
        mPentasSessions = new PentasSessionBinder(context, mBackgroundHandler);

        mGameListObserver = new GameListObserver(mBackgroundHandler);
    }

    @Override
    public void onStart() {
        mCurrentUserId = ActivityManager.getCurrentUser();
        // SystemUI detects foreground games, but only system_server binds Pentas's private
        // session service. Require the sender's privileged permission on this bridge.
        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final int userId = intent.getIntExtra(
                        Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
                if (userId != mCurrentUserId || userId != ActivityManager.getCurrentUser()) return;
                final String packageName = intent.getStringExtra(EXTRA_GAME_PACKAGE);
                if (packageName != null && isInGameList(packageName, userId)) {
                    mPentasSessions.start(packageName, userId);
                } else {
                    mPentasSessions.stop();
                }
            }
        }, UserHandle.ALL, new IntentFilter(ACTION_GAME_STATE_CHANGED),
                android.Manifest.permission.MANAGE_GAME_MODE, mBackgroundHandler,
                Context.RECEIVER_EXPORTED);
    }

    @Override
    public void onUserSwitching(TargetUser from, TargetUser to) {
        mBackgroundHandler.post(() -> {
            mCurrentUserId = to.getUserIdentifier();
            mPentasSessions.stop();
            sanitizeGameList(mCurrentUserId);
        });
    }

    private boolean isInGameList(String packageName, int userId) {
        final String games = Settings.System.getStringForUser(
                mContext.getContentResolver(), GAME_LIST_SETTING, userId);
        if (games == null) return false;
        for (String entry : games.split(";")) {
            final int separator = entry.indexOf('=');
            if (separator > 0 && packageName.equals(entry.substring(0, separator).trim())
                    && isValidMode(entry.substring(separator + 1).trim())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_BOOT_COMPLETED) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_PACKAGE_ADDED);
            filter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
            filter.addDataScheme("package");
            mContext.registerReceiverAsUser(mPackageChangeReceiver, UserHandle.ALL, filter,
                    null, mBackgroundHandler);

            mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(GAME_LIST_SETTING), false,
                mGameListObserver, UserHandle.USER_ALL);

            mBackgroundHandler.post(() -> sanitizeGameList(ActivityManager.getCurrentUser()));
        }
    }

    private class PackageChangeReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String packageName = intent.getData() != null
                    ? intent.getData().getSchemeSpecificPart() : null;
            if (packageName == null) return;
            final int userId = intent.getIntExtra(
                    Intent.EXTRA_USER_HANDLE, UserHandle.USER_SYSTEM);

            mBackgroundHandler.post(() -> {
                if (Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())) {
                    handlePackageAdded(packageName, userId);
                } else if (Intent.ACTION_PACKAGE_FULLY_REMOVED.equals(intent.getAction())) {
                    removeFromGameSpace(packageName, userId);
                }
            });
        }
    }

    private void handlePackageAdded(String packageName, int userId) {
        final ContentResolver cr = mContext.getContentResolver();
        if (Settings.System.getIntForUser(cr, AUTO_DETECT_SETTING, 1, userId) == 0) return;
        final String denied = Settings.System.getStringForUser(cr, DENIED_LIST_SETTING, userId);
        if (denied != null) {
            for (String entry : denied.split(";")) {
                if (packageName.equals(entry.trim())) return;
            }
        }
        if (isGame(packageName, userId)) {
            addToGameSpace(packageName, userId);
        }
    }

    private boolean isGame(String packageName, int userId) {
        try {
            ApplicationInfo appInfo = mPackageManager.getApplicationInfoAsUser(
                    packageName, PackageManager.GET_META_DATA, userId);
            return appInfo != null && appInfo.category == ApplicationInfo.CATEGORY_GAME;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void addToGameSpace(String packageName, int userId) {
        ContentResolver cr = mContext.getContentResolver();
        String currentList = Settings.System.getStringForUser(cr, GAME_LIST_SETTING, userId);
        Map<String, String> gameMap = new HashMap<>();

        if (currentList != null && !currentList.isEmpty()) {
            String[] entries = currentList.split(";");
            for (String entry : entries) {
                String[] parts = entry.split("=");
                if (parts.length == 2 && isValidMode(parts[1])) {
                    gameMap.put(parts[0], parts[1]);
                }
            }
        }

        if (!gameMap.containsKey(packageName)) {
            gameMap.put(packageName, "2");
            String updatedList = serializeGameMap(gameMap);
            Settings.System.putStringForUser(cr, GAME_LIST_SETTING, updatedList, userId);
            if (userId == ActivityManager.getCurrentUser()) {
                sendGameAddedNotification(packageName, userId);
            }
        }
    }

    private void removeFromGameSpace(String packageName, int userId) {
        ContentResolver cr = mContext.getContentResolver();
        String currentList = Settings.System.getStringForUser(cr, GAME_LIST_SETTING, userId);
        if (currentList == null || currentList.isEmpty()) return;

        Map<String, String> gameMap = new HashMap<>();
        String[] entries = currentList.split(";");
        for (String entry : entries) {
            String[] parts = entry.split("=");
            if (parts.length == 2 && isValidMode(parts[1]) && !parts[0].equals(packageName)) {
                gameMap.put(parts[0], parts[1]);
            }
        }

        String updatedList = serializeGameMap(gameMap);
        Settings.System.putStringForUser(cr, GAME_LIST_SETTING, updatedList, userId);
    }

    private boolean isValidMode(String modeStr) {
        return VALID_MODES.contains(modeStr);
    }

    private void sanitizeGameList(int userId) {
        ContentResolver cr = mContext.getContentResolver();
        String currentList = Settings.System.getStringForUser(cr, GAME_LIST_SETTING, userId);
        if (currentList == null || currentList.isEmpty()) return;

        Map<String, String> gameMap = new HashMap<>();
        String[] entries = currentList.split(";");
        for (String entry : entries) {
            int firstEquals = entry.indexOf('=');
            if (firstEquals > 0 && firstEquals < entry.length() - 1) {
                String key = entry.substring(0, firstEquals).trim();
                String value = entry.substring(firstEquals + 1).split("[^0-9]", 2)[0].trim();
                if (isValidMode(value)) {
                    gameMap.put(key, value);
                }
            }
        }

        String sanitizedList = serializeGameMap(gameMap);
        if (!sanitizedList.equals(currentList)) {
            Settings.System.putStringForUser(cr, GAME_LIST_SETTING, sanitizedList, userId);
        }
    }

    private String serializeGameMap(Map<String, String> gameMap) {
        Set<String> result = new HashSet<>();
        for (Map.Entry<String, String> entry : gameMap.entrySet()) {
            result.add(entry.getKey() + "=" + entry.getValue());
        }
        return String.join(";", result);
    }

    private class GameListObserver extends ContentObserver {
        public GameListObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            sanitizeGameList(ActivityManager.getCurrentUser());
        }
    }

    private void sendGameAddedNotification(String packageName, int userId) {
        String appName;
        try {
            appName = mPackageManager.getApplicationLabel(
                mPackageManager.getApplicationInfoAsUser(packageName, 0, userId)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            appName = packageName;
        }
        final String finalAppName = appName;

        UiThread.getHandler().post(
            () -> Toast.makeText(
                mContext,
                mContext.getString(R.string.gamespace_new_game_added, finalAppName),
                Toast.LENGTH_LONG
            ).show()
        );
    }
}
