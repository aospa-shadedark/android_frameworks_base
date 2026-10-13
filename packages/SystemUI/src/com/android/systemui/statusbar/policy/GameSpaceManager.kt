/*
 * Copyright (C) 2021 Chaldeaprjkt
 * Copyright (C) 2022-2025 crDroid Android Project
 * Copyright (C) 2025 AxionAOSP Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.policy

import android.app.ActivityTaskManager
import android.app.TaskStackListener
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.RemoteException
import android.os.UserHandle
import android.provider.Settings
import android.util.Log

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.settings.UserTracker

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@SysUISingleton
class GameSpaceManager @Inject constructor(
    private val context: Context,
    private val keyguardStateController: KeyguardStateController,
    private val userTracker: UserTracker,
) {
    private val taskManager by lazy { ActivityTaskManager.getService() }
    private val powerManager by lazy { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _activeGame = MutableStateFlow<String?>(null)
    val activeGame: StateFlow<String?> = _activeGame.asStateFlow()

    private val refreshTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var observationJob: Job? = null
    private var dispatchedUser = UserHandle.USER_NULL

    private val taskStackListener = object : TaskStackListener() {
        override fun onTaskStackChanged() = refresh()
        override fun onTaskRemoved(taskId: Int) = refresh()
        override fun onTaskMovedToFront(taskInfo: android.app.ActivityManager.RunningTaskInfo?) =
            refresh()
    }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF, Intent.ACTION_SCREEN_ON -> refresh()
            }
        }
    }

    private val keyguardCallback = object : KeyguardStateController.Callback {
        override fun onKeyguardShowingChanged() {
            refresh()
        }
    }

    private val gameListObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refresh()
    }
    private val userCallback = object : UserTracker.Callback {
        override fun onBeforeUserSwitching(newUser: Int) {
            setActiveGame(null, userTracker.userId)
        }

        override fun onUserChanged(newUser: Int, userContext: Context) {
            refresh()
        }
    }

    init {
        observe()
    }

    fun observe() {
        if (observationJob != null) return

        observationJob = coroutineScope.launch {
            refreshTrigger.collectLatest { updateGameState() }
        }

        registerReceivers()
        // Also send an initial stop after a SystemUI restart if no game is in front.
        dispatchedUser = UserHandle.USER_NULL
        refresh()
    }

    fun unobserve() {
        observationJob?.cancel()
        observationJob = null
        setActiveGame(null, userTracker.userId)
        unregisterReceivers()
    }

    fun shouldSuppressFullScreenIntent(): Boolean {
        return Settings.System.getIntForUser(
            context.contentResolver,
            Settings.System.GAMESPACE_SUPPRESS_FULLSCREEN_INTENT, 0,
            userTracker.userId
        ) == 1 && activeGame.value != null
    }

    private fun refresh() {
        refreshTrigger.tryEmit(Unit)
    }

    private fun registerReceivers() {
        try {
            taskManager.registerTaskStackListener(taskStackListener)
        } catch (e: RemoteException) {
            Log.w(TAG, "Could not observe foreground tasks", e)
        }
        context.registerReceiver(
            broadcastReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            Context.RECEIVER_NOT_EXPORTED,
        )
        keyguardStateController.addCallback(keyguardCallback)
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.GAMESPACE_GAME_LIST),
            false, gameListObserver, UserHandle.USER_ALL,
        )
        userTracker.addCallback(userCallback, context.mainExecutor)
    }

    private fun unregisterReceivers() {
        try {
            taskManager.unregisterTaskStackListener(taskStackListener)
        } catch (e: RemoteException) {
            Log.w(TAG, "Could not remove foreground task observer", e)
        }
        context.unregisterReceiver(broadcastReceiver)
        keyguardStateController.removeCallback(keyguardCallback)
        context.contentResolver.unregisterContentObserver(gameListObserver)
        userTracker.removeCallback(userCallback)
    }

    private suspend fun updateGameState() {
        val userId = userTracker.userId
        if (!powerManager.isInteractive || keyguardStateController.isShowing ||
            userTracker.isUserSwitching) {
            setActiveGame(null, userId)
            return
        }
        val currentPackage = withContext(Dispatchers.IO) {
            try {
                taskManager.focusedRootTaskInfo?.topActivity?.packageName
            } catch (e: RemoteException) {
                null
            }
        }
        // The user or lock state may have changed while querying ActivityTaskManager.
        if (userId != userTracker.userId) return
        if (!powerManager.isInteractive || keyguardStateController.isShowing ||
            userTracker.isUserSwitching) {
            setActiveGame(null, userId)
            return
        }
        // Transient system surfaces such as the shade should not tear down the game sidebar.
        if (currentPackage in TRANSIENT_PACKAGES &&
            activeGame.value?.let { isInGameList(it, userId) } == true) return
        setActiveGame(currentPackage?.takeIf { isInGameList(it, userId) }, userId)
    }

    private fun isInGameList(packageName: String, userId: Int): Boolean {
        val games = Settings.System.getStringForUser(
            context.contentResolver, Settings.System.GAMESPACE_GAME_LIST, userId,
        ) ?: return false
        return games.split(';').any {
            it.substringBefore('=').trim() == packageName &&
                    it.substringAfter('=', "").trim() in VALID_MODES
        }
    }

    private fun setActiveGame(packageName: String?, userId: Int) {
        if (_activeGame.value == packageName && dispatchedUser == userId) return
        _activeGame.value = packageName
        dispatchedUser = userId
        val intent = Intent(ACTION_GAME_STATE_CHANGED).setPackage("android")
            .putExtra(Intent.EXTRA_USER_HANDLE, userId)
        packageName?.let { intent.putExtra(EXTRA_GAME_PACKAGE, it) }
        // The receiving system_server bridge requires this permission on the sender too.
        context.sendBroadcastAsUser(
            intent, UserHandle.of(userId), android.Manifest.permission.MANAGE_GAME_MODE,
        )
    }

    companion object {
        private const val TAG = "GameSpaceManager"
        private const val ACTION_GAME_STATE_CHANGED = "com.android.server.action.PENTAS_GAME_STATE_CHANGED"
        private const val EXTRA_GAME_PACKAGE = "package_name"
        private val VALID_MODES = setOf("1", "2", "3")
        private val TRANSIENT_PACKAGES = setOf("android", "com.android.systemui")
    }
}
