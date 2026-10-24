/*
 * Copyright (C) 2026 The PenguinOS Project
 * Copyright (C) 2026 Paranoid Android
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.luminous

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.SystemProperties
import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.settings.UserTracker
import java.util.concurrent.Executor
import javax.inject.Inject

/** Mirrors the foreground user's Luminous preference to the property read by app processes. */
@SysUISingleton
class LuminousStartable
@Inject
constructor(
    @Application private val context: Context,
    private val userTracker: UserTracker,
    @Main private val handler: Handler,
    @Main private val executor: Executor,
) : CoreStartable {
    private val observer =
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = sync()
        }

    // UserTracker holds callbacks weakly, so retain this for the lifetime of SystemUI.
    private val userCallback =
        object : UserTracker.Callback {
            override fun onUserChanged(newUser: Int, userContext: Context) = sync()
        }

    override fun start() {
        // Only the system-user process owns the device-wide property.
        if (UserHandle.myUserId() != UserHandle.USER_SYSTEM) return
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(SETTING),
            false,
            observer,
            UserHandle.USER_ALL,
        )
        userTracker.addCallback(userCallback, executor)
        sync()
    }

    private fun sync() {
        val allApps =
            Settings.Secure.getIntForUser(
                context.contentResolver,
                SETTING,
                0,
                userTracker.userId,
            ) == 1
        SystemProperties.set(PROPERTY, if (allApps) "2" else "1")
    }

    private companion object {
        const val SETTING = "penguin_luminous_all_apps"
        const val PROPERTY = "persist.sys.penguin.luminous"
    }
}
