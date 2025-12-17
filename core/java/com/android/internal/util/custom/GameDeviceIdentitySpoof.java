/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.internal.util.custom;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

import java.lang.reflect.Field;

/** Applies a Settings-selected game device profile before app providers and code are initialized. */
public final class GameDeviceIdentitySpoof {
    private static final String TAG = "GameDeviceIdentity";
    private static final String SETTING_GAME_IDENTITIES_ENABLED = "game_device_identity_enabled";
    private static final String SETTING_GAME_IDENTITIES = "game_device_identity_profiles";
    private static final String[] BUILD_FIELDS = {
            "BRAND", "DEVICE", "MANUFACTURER", "MODEL", "FINGERPRINT", "PRODUCT"
    };

    private GameDeviceIdentitySpoof() {}

    public static void apply(Context context, String packageName, int userId) {
        if (context == null || packageName == null || packageName.isEmpty()) {
            return;
        }

        try {
            if (Settings.System.getIntForUser(context.getContentResolver(),
                    SETTING_GAME_IDENTITIES_ENABLED, 0, userId) != 1) {
                return;
            }
            final String identities = Settings.System.getStringForUser(
                    context.getContentResolver(), SETTING_GAME_IDENTITIES, userId);
            if (identities == null || identities.isEmpty()) return;

            final JSONObject profiles = new JSONObject(identities);
            final JSONObject customProfile = profiles.optJSONObject(packageName);
            if (customProfile != null) {
                applyCustomProfile(customProfile);
                return;
            }

            final String profile = profiles.optString(packageName, "");
            switch (profile) {
                case "pixel_6_pro":
                    applyProfile("google", "Google", "Pixel 6 Pro", "raven");
                    break;
                case "pixel_7_pro":
                    applyProfile("google", "Google", "Pixel 7 Pro", "cheetah");
                    break;
                case "pixel_8_pro":
                    applyProfile("google", "Google", "Pixel 8 Pro", "husky");
                    break;
                case "pixel_9_pro":
                    applyProfile("google", "Google", "Pixel 9 Pro", "caiman");
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to apply game identity for " + packageName, e);
        }
    }

    private static void applyCustomProfile(JSONObject profile) {
        for (String field : BUILD_FIELDS) {
            final String value = profile.optString(field, "");
            if (!value.isEmpty()) {
                setBuildField(field, value);
            }
        }
    }

    private static void applyProfile(String brand, String manufacturer, String model,
            String device) {
        setBuildField("BRAND", brand);
        setBuildField("MANUFACTURER", manufacturer);
        setBuildField("MODEL", model);
        setBuildField("DEVICE", device);
        setBuildField("PRODUCT", device);
    }

    private static void setBuildField(String name, String value) {
        Field field = null;
        try {
            field = Build.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(null, value);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "Unable to set Build." + name, e);
        } finally {
            if (field != null) {
                try {
                    field.setAccessible(false);
                } catch (RuntimeException ignored) {
                    // The per-process field update is best-effort.
                }
            }
        }
    }
}
