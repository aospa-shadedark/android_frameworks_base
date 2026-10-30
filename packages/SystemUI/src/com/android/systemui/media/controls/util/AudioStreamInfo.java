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

import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothCodecType;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.media.MediaRoute2Info;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.settingslib.bluetooth.A2dpProfile;
import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.settingslib.bluetooth.LocalBluetoothProfileManager;
import com.android.settingslib.media.BluetoothMediaDevice;
import com.android.settingslib.media.MediaDevice;
import com.android.systemui.res.R;

import java.text.NumberFormat;
import java.util.Objects;

/** An immutable presentation of the selected route's reported audio configuration. */
public final class AudioStreamInfo {
    private final String mCompactLabel;
    private final String mSummary;
    private final String mDetails;
    private final String mCodecLabel;
    private final String mSampleRateLabel;
    private final String mBitDepthLabel;
    private final String mConnectionLabel;
    private final String mNote;

    private AudioStreamInfo(
            String compactLabel,
            String summary,
            String details,
            String codecLabel,
            String sampleRateLabel,
            String bitDepthLabel,
            String connectionLabel,
            String note) {
        mCompactLabel = compactLabel;
        mSummary = summary;
        mDetails = details;
        mCodecLabel = codecLabel;
        mSampleRateLabel = sampleRateLabel;
        mBitDepthLabel = bitDepthLabel;
        mConnectionLabel = connectionLabel;
        mNote = note;
    }

    /**
     * Resolves the selected output supplied by the caller. Connection/capability lists must not be
     * supplied as though they were the selected output. A2DP status is additionally checked against
     * the active Bluetooth device so cached configurations cannot label another route.
     */
    @Nullable
    public static AudioStreamInfo resolve(
            @NonNull Context context,
            @Nullable LocalBluetoothManager bluetoothManager,
            @Nullable MediaDevice device) {
        if (device == null || device.getRouteInfo() == null) {
            return null;
        }
        final int routeType = device.getRouteInfo().getType();
        switch (routeType) {
            case MediaRoute2Info.TYPE_USB_DEVICE:
            case MediaRoute2Info.TYPE_USB_ACCESSORY:
            case MediaRoute2Info.TYPE_USB_HEADSET:
                return wiredInfo(context, R.string.audio_stream_info_usb_audio);
            case MediaRoute2Info.TYPE_WIRED_HEADSET:
            case MediaRoute2Info.TYPE_WIRED_HEADPHONES:
                return wiredInfo(context, R.string.audio_stream_info_wired_audio);
            case MediaRoute2Info.TYPE_BLUETOOTH_A2DP:
                if (device instanceof BluetoothMediaDevice && bluetoothManager != null) {
                    return bluetoothInfo(context, bluetoothManager, (BluetoothMediaDevice) device);
                }
                return null;
            default:
                // A2DP codec status does not describe LE Audio, hearing aids, speakers, or casts.
                return null;
        }
    }

    @Nullable
    private static AudioStreamInfo bluetoothInfo(
            Context context, LocalBluetoothManager manager, BluetoothMediaDevice mediaDevice) {
        final CachedBluetoothDevice cachedDevice = mediaDevice.getCachedDevice();
        final LocalBluetoothProfileManager profileManager = manager.getProfileManager();
        if (cachedDevice == null || profileManager == null) {
            return null;
        }
        final BluetoothDevice device = cachedDevice.getDevice();
        final A2dpProfile profile = profileManager.getA2dpProfile();
        if (device == null || profile == null) {
            return null;
        }
        try {
            if (!device.equals(profile.getActiveDevice())
                    || profile.getConnectionStatus(device) != BluetoothProfile.STATE_CONNECTED) {
                return null;
            }
            final BluetoothCodecStatus status = profile.getCodecStatus(device);
            if (status == null || status.getCodecConfig() == null) {
                return null;
            }
            return codecInfo(context, status.getCodecConfig());
        } catch (IllegalStateException | SecurityException e) {
            // The Bluetooth service may be unavailable during a user/profile transition.
            return null;
        }
    }

    private static AudioStreamInfo codecInfo(Context context, BluetoothCodecConfig config) {
        final String codec = codecName(context, config);
        final String rate = sampleRate(context, config.getSampleRate());
        final String depth = bitDepth(context, config.getBitsPerSample());
        String summary = codec;
        if (rate != null) {
            summary = context.getString(R.string.audio_stream_info_join, summary, rate);
        }
        if (depth != null) {
            summary = context.getString(R.string.audio_stream_info_join, summary, depth);
        }
        final String details =
                context.getString(
                        R.string.audio_stream_info_bluetooth_details,
                        codec,
                        reportedOrUnknown(context, rate),
                        reportedOrUnknown(context, depth));
        return new AudioStreamInfo(
                codec,
                summary,
                details,
                codec,
                reportedOrUnknown(context, rate),
                reportedOrUnknown(context, depth),
                context.getString(R.string.audio_stream_info_bluetooth_connection),
                context.getString(R.string.audio_stream_info_bluetooth_note));
    }

    private static AudioStreamInfo wiredInfo(Context context, int labelResource) {
        final String label = context.getString(labelResource);
        final String unknown = context.getString(R.string.audio_stream_info_not_reported);
        return new AudioStreamInfo(
                label,
                label,
                context.getString(R.string.audio_stream_info_wired_details, label),
                unknown,
                unknown,
                unknown,
                label,
                context.getString(R.string.audio_stream_info_wired_note));
    }

    private static String codecName(Context context, BluetoothCodecConfig config) {
        final BluetoothCodecType extendedType = config.getExtendedCodecType();
        String name = extendedType == null ? null : extendedType.getCodecName();
        if (name == null || name.trim().isEmpty()) {
            final BluetoothCodecType fallbackType =
                    BluetoothCodecType.createFromType(config.getCodecType());
            name = fallbackType == null ? null : fallbackType.getCodecName();
        }
        if (name == null
                || name.trim().isEmpty()
                || name.startsWith("UNKNOWN CODEC")
                || "INVALID CODEC".equals(name)) {
            return context.getString(R.string.audio_stream_info_unknown_codec);
        }
        return name.trim();
    }

    /** A combined bit mask denotes capabilities, not a single negotiated sample rate. */
    @Nullable
    private static String sampleRate(Context context, int value) {
        final double rate;
        switch (value) {
            case BluetoothCodecConfig.SAMPLE_RATE_8000:
                rate = 8;
                break;
            case BluetoothCodecConfig.SAMPLE_RATE_16000:
                rate = 16;
                break;
            case BluetoothCodecConfig.SAMPLE_RATE_24000:
                rate = 24;
                break;
            case BluetoothCodecConfig.SAMPLE_RATE_32000:
                rate = 32;
                break;
            case BluetoothCodecConfig.SAMPLE_RATE_44100:
                rate = 44.1;
                break;
            case BluetoothCodecConfig.SAMPLE_RATE_48000:
                rate = 48;
                break;
            case BluetoothCodecConfig.SAMPLE_RATE_88200:
                rate = 88.2;
                break;
            case BluetoothCodecConfig.SAMPLE_RATE_96000:
                rate = 96;
                break;
            case BluetoothCodecConfig.SAMPLE_RATE_176400:
                rate = 176.4;
                break;
            case BluetoothCodecConfig.SAMPLE_RATE_192000:
                rate = 192;
                break;
            default:
                return null;
        }
        final NumberFormat format =
                NumberFormat.getNumberInstance(
                        context.getResources().getConfiguration().getLocales().get(0));
        format.setMaximumFractionDigits(1);
        return context.getString(R.string.audio_stream_info_sample_rate, format.format(rate));
    }

    @Nullable
    private static String bitDepth(Context context, int value) {
        final int bits;
        switch (value) {
            case BluetoothCodecConfig.BITS_PER_SAMPLE_16:
                bits = 16;
                break;
            case BluetoothCodecConfig.BITS_PER_SAMPLE_24:
                bits = 24;
                break;
            case BluetoothCodecConfig.BITS_PER_SAMPLE_32:
                bits = 32;
                break;
            default:
                return null;
        }
        return context.getString(R.string.audio_stream_info_bit_depth, bits);
    }

    private static String reportedOrUnknown(Context context, @Nullable String value) {
        return value == null ? context.getString(R.string.audio_stream_info_not_reported) : value;
    }

    @NonNull
    public String getCompactLabel() {
        return mCompactLabel;
    }

    @NonNull
    public String getSummary() {
        return mSummary;
    }

    @NonNull
    public String getDetails() {
        return mDetails;
    }

    @NonNull
    public String getCodecLabel() {
        return mCodecLabel;
    }

    @NonNull
    public String getSampleRateLabel() {
        return mSampleRateLabel;
    }

    @NonNull
    public String getBitDepthLabel() {
        return mBitDepthLabel;
    }

    @NonNull
    public String getConnectionLabel() {
        return mConnectionLabel;
    }

    @NonNull
    public String getNote() {
        return mNote;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof AudioStreamInfo)) return false;
        final AudioStreamInfo info = (AudioStreamInfo) other;
        return mCompactLabel.equals(info.mCompactLabel)
                && mSummary.equals(info.mSummary)
                && mDetails.equals(info.mDetails)
                && mCodecLabel.equals(info.mCodecLabel)
                && mSampleRateLabel.equals(info.mSampleRateLabel)
                && mBitDepthLabel.equals(info.mBitDepthLabel)
                && mConnectionLabel.equals(info.mConnectionLabel)
                && mNote.equals(info.mNote);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mCompactLabel,
                mSummary,
                mDetails,
                mCodecLabel,
                mSampleRateLabel,
                mBitDepthLabel,
                mConnectionLabel,
                mNote);
    }
}
