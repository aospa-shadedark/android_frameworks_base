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

package com.android.systemui.media.controls.util

import android.bluetooth.BluetoothCodecConfig
import android.bluetooth.BluetoothCodecStatus
import android.bluetooth.BluetoothCodecType
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.res.Configuration
import android.media.MediaRoute2Info
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.bluetooth.A2dpProfile
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.settingslib.bluetooth.LocalBluetoothProfileManager
import com.android.settingslib.media.BluetoothMediaDevice
import com.android.settingslib.media.MediaDevice
import com.android.systemui.SysuiTestCase
import com.android.systemui.res.R
import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class AudioStreamInfoTest : SysuiTestCase() {
    private val bluetoothManager = mock<LocalBluetoothManager>()
    private val profileManager = mock<LocalBluetoothProfileManager>()
    private val a2dpProfile = mock<A2dpProfile>()
    private val cachedDevice = mock<CachedBluetoothDevice>()
    private val bluetoothDevice = mock<BluetoothDevice>()
    private val mediaDevice = mock<BluetoothMediaDevice>()
    private val codecStatus = mock<BluetoothCodecStatus>()
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = mContext.createConfigurationContext(
            Configuration(mContext.resources.configuration).apply { setLocale(Locale.US) }
        )
        whenever(bluetoothManager.profileManager).thenReturn(profileManager)
        whenever(profileManager.a2dpProfile).thenReturn(a2dpProfile)
        whenever(mediaDevice.routeInfo).thenReturn(route(MediaRoute2Info.TYPE_BLUETOOTH_A2DP))
        whenever(mediaDevice.cachedDevice).thenReturn(cachedDevice)
        whenever(cachedDevice.device).thenReturn(bluetoothDevice)
        whenever(a2dpProfile.activeDevice).thenReturn(bluetoothDevice)
        whenever(a2dpProfile.getConnectionStatus(bluetoothDevice))
            .thenReturn(BluetoothProfile.STATE_CONNECTED)
        whenever(a2dpProfile.getCodecStatus(bluetoothDevice)).thenReturn(codecStatus)
    }

    @Test
    fun negotiatedRates_doNotDefaultEveryLdacStreamTo96Khz() {
        for ((rate, expectedRate) in listOf(
            BluetoothCodecConfig.SAMPLE_RATE_44100 to "44.1 kHz",
            BluetoothCodecConfig.SAMPLE_RATE_48000 to "48 kHz",
            BluetoothCodecConfig.SAMPLE_RATE_96000 to "96 kHz",
        )) {
            val info = resolve(codec(rate = rate))!!

            assertThat(info.compactLabel).isEqualTo("LDAC")
            assertThat(info.summary).isEqualTo("LDAC · $expectedRate · 24-bit")
            assertThat(info.details).contains("Sample rate: $expectedRate")
            assertThat(info.codecLabel).isEqualTo("LDAC")
            assertThat(info.sampleRateLabel).isEqualTo(expectedRate)
            assertThat(info.bitDepthLabel).isEqualTo("24-bit")
            assertThat(info.connectionLabel).isEqualTo("Bluetooth A2DP")
            assertThat(info.note)
                .isEqualTo(context.getString(R.string.audio_stream_info_bluetooth_note))
        }
    }

    @Test
    fun bitDepth_usesReportedEnum() {
        val info = resolve(codec(depth = BluetoothCodecConfig.BITS_PER_SAMPLE_16))!!

        assertThat(info.summary).contains("16-bit")
        assertThat(info.details).doesNotContain("24-bit")
        assertThat(info.bitDepthLabel).isEqualTo("16-bit")
    }

    @Test
    fun capabilityMasksAndUnknownValues_areNotPresentedAsActiveFormats() {
        val info = resolve(codec(
            rate = BluetoothCodecConfig.SAMPLE_RATE_44100 or BluetoothCodecConfig.SAMPLE_RATE_96000,
            depth = BluetoothCodecConfig.BITS_PER_SAMPLE_16 or
                BluetoothCodecConfig.BITS_PER_SAMPLE_24,
        ))!!

        assertThat(info.summary).isEqualTo("LDAC")
        assertThat(info.details).contains("Sample rate: Not reported")
        assertThat(info.details).contains("Bit depth: Not reported")
        assertThat(info.details).doesNotContain("96 kHz")
        assertThat(info.sampleRateLabel).isEqualTo("Not reported")
        assertThat(info.bitDepthLabel).isEqualTo("Not reported")
    }

    @Test
    fun vendorCodecName_includesLhdcWithInvalidLegacyCodecIndex() {
        val config = BluetoothCodecConfig.Builder()
            .setExtendedCodecType(BluetoothCodecType(
                BluetoothCodecConfig.SOURCE_CODEC_TYPE_INVALID,
                BluetoothCodecType.CODEC_ID_LHDCV5,
                "LHDCV5",
            ))
            .setSampleRate(BluetoothCodecConfig.SAMPLE_RATE_48000)
            .setBitsPerSample(BluetoothCodecConfig.BITS_PER_SAMPLE_24)
            .build()

        val info = resolve(config)!!

        assertThat(info.compactLabel).isEqualTo("LHDCV5")
        assertThat(info.summary).isEqualTo("LHDCV5 · 48 kHz · 24-bit")
        assertThat(info.details).doesNotContain("192 kHz")
        assertThat(info.codecLabel).isEqualTo("LHDCV5")
        assertThat(info.sampleRateLabel).isEqualTo("48 kHz")
    }

    @Test
    fun blankCodecName_fallsBackToKnownCodecName() {
        val config = BluetoothCodecConfig.Builder()
            .setExtendedCodecType(BluetoothCodecType(
                BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC,
                BluetoothCodecType.CODEC_ID_AAC,
                "",
            ))
            .build()

        assertThat(resolve(config)!!.compactLabel).isEqualTo("AAC")
    }

    @Test
    fun unknownCodecName_usesLocalizedFallbackWithoutGuessing() {
        val config = BluetoothCodecConfig.Builder().build()

        val info = resolve(config)!!

        assertThat(info.compactLabel)
            .isEqualTo(context.getString(R.string.audio_stream_info_unknown_codec))
        assertThat(info.summary).doesNotContain("48 kHz")
        assertThat(info.details).contains("Not reported")
        assertThat(info.codecLabel)
            .isEqualTo(context.getString(R.string.audio_stream_info_unknown_codec))
        assertThat(info.sampleRateLabel).isEqualTo("Not reported")
        assertThat(info.bitDepthLabel).isEqualTo("Not reported")
    }

    @Test
    fun aptxCodecSpecific4_doesNotAssertLosslessOrLiveLatency() {
        for (cs4 in listOf(0x4000L, 0x5000L, 0xa059000L, 0xa05d000L)) {
            val info = resolve(codec(
                type = BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_ADAPTIVE,
                cs4 = cs4,
            ))!!

            assertThat(info.compactLabel).isEqualTo("aptX Adaptive")
            assertThat(info.details.lowercase(Locale.ROOT)).doesNotContain("lossless")
            assertThat(info.details.lowercase(Locale.ROOT)).doesNotContain("latency")
            assertThat(info.summary).doesNotContain("20ms")
        }
    }

    @Test
    fun inactiveDevice_doesNotUseItsCachedCodecStatus() {
        whenever(a2dpProfile.activeDevice).thenReturn(mock<BluetoothDevice>())

        assertThat(resolve(codec())).isNull()
        verify(a2dpProfile, never()).getCodecStatus(any())
    }

    @Test
    fun disconnectedDevice_doesNotUseItsCachedCodecStatus() {
        whenever(a2dpProfile.getConnectionStatus(bluetoothDevice))
            .thenReturn(BluetoothProfile.STATE_DISCONNECTED)

        assertThat(resolve(codec())).isNull()
        verify(a2dpProfile, never()).getCodecStatus(any())
    }

    @Test
    fun unavailableCodecStatus_hasNoInventedFallback() {
        whenever(a2dpProfile.getCodecStatus(bluetoothDevice)).thenReturn(null)

        assertThat(AudioStreamInfo.resolve(context, bluetoothManager, mediaDevice)).isNull()
    }

    @Test
    fun bluetoothServiceTransition_doesNotCrashTheMediaPlayer() {
        whenever(a2dpProfile.getCodecStatus(bluetoothDevice))
            .thenThrow(IllegalStateException("Bluetooth service is reconnecting"))

        assertThat(AudioStreamInfo.resolve(context, bluetoothManager, mediaDevice)).isNull()
    }

    @Test
    fun leAudioAndHearingAidRoutes_neverUseA2dpConfiguration() {
        for (type in listOf(MediaRoute2Info.TYPE_BLE_HEADSET, MediaRoute2Info.TYPE_HEARING_AID)) {
            whenever(mediaDevice.routeInfo).thenReturn(route(type))

            assertThat(AudioStreamInfo.resolve(context, bluetoothManager, mediaDevice)).isNull()
        }
        verify(a2dpProfile, never()).getCodecStatus(any())
    }

    @Test
    fun phoneAndCastRoutes_stayCleanEvenWhenBluetoothIsConnected() {
        val selectedDevice = mock<MediaDevice>()
        for (type in listOf(
            MediaRoute2Info.TYPE_BUILTIN_SPEAKER,
            MediaRoute2Info.TYPE_REMOTE_SPEAKER,
        )) {
            whenever(selectedDevice.routeInfo).thenReturn(route(type))

            assertThat(AudioStreamInfo.resolve(context, bluetoothManager, selectedDevice)).isNull()
        }
        verify(a2dpProfile, never()).getCodecStatus(any())
    }

    @Test
    fun usbRoute_reportsTransportWithoutInvented192KhzOrDirectPlayback() {
        val usb = mock<MediaDevice>()
        whenever(usb.routeInfo).thenReturn(route(MediaRoute2Info.TYPE_USB_HEADSET))

        val info = AudioStreamInfo.resolve(context, null, usb)!!

        assertThat(info.compactLabel).isEqualTo("USB audio")
        assertThat(info.summary).isEqualTo("USB audio")
        assertThat(info.details).contains("Active hardware sample rate: Not reported")
        assertThat(info.details).doesNotContain("192")
        assertThat(info.details).doesNotContain("Direct")
        assertThat(info.details).doesNotContain("bit-perfect")
        assertThat(info.codecLabel).isEqualTo("Not reported")
        assertThat(info.sampleRateLabel).isEqualTo("Not reported")
        assertThat(info.bitDepthLabel).isEqualTo("Not reported")
        assertThat(info.connectionLabel).isEqualTo("USB audio")
        assertThat(info.note)
            .isEqualTo(context.getString(R.string.audio_stream_info_wired_note))
        verifyNoInteractions(bluetoothManager)
    }

    @Test
    fun wiredRoute_reportsTransportWithoutInventedHiFiCapability() {
        val wired = mock<MediaDevice>()
        whenever(wired.routeInfo).thenReturn(route(MediaRoute2Info.TYPE_WIRED_HEADPHONES))

        val info = AudioStreamInfo.resolve(context, null, wired)!!

        assertThat(info.compactLabel).isEqualTo("Wired audio")
        assertThat(info.details).contains("Active hardware sample rate: Not reported")
        assertThat(info.details).doesNotContain("96")
        assertThat(info.details).doesNotContain("Hi-Fi")
        assertThat(info.codecLabel).isEqualTo("Not reported")
        assertThat(info.sampleRateLabel).isEqualTo("Not reported")
        assertThat(info.bitDepthLabel).isEqualTo("Not reported")
        assertThat(info.connectionLabel).isEqualTo("Wired audio")
        assertThat(info.note)
            .isEqualTo(context.getString(R.string.audio_stream_info_wired_note))
    }

    @Test
    fun equalConfigurations_areEqual_butChangedRateTriggersUpdate() {
        val first = resolve(codec(rate = BluetoothCodecConfig.SAMPLE_RATE_44100))!!
        val same = resolve(codec(rate = BluetoothCodecConfig.SAMPLE_RATE_44100))!!
        val changed = resolve(codec(rate = BluetoothCodecConfig.SAMPLE_RATE_48000))!!

        assertThat(first).isEqualTo(same)
        assertThat(first.hashCode()).isEqualTo(same.hashCode())
        assertThat(first).isNotEqualTo(changed)
    }

    @Test
    fun nullOrUnresolvedRoute_hasNoStreamInfo() {
        assertThat(AudioStreamInfo.resolve(context, bluetoothManager, null)).isNull()
        assertThat(AudioStreamInfo.resolve(context, bluetoothManager, mock<MediaDevice>())).isNull()
    }

    private fun resolve(config: BluetoothCodecConfig): AudioStreamInfo? {
        whenever(codecStatus.codecConfig).thenReturn(config)
        return AudioStreamInfo.resolve(context, bluetoothManager, mediaDevice)
    }

    private fun route(type: Int): MediaRoute2Info = mock<MediaRoute2Info>().also {
        whenever(it.type).thenReturn(type)
    }

    private fun codec(
        type: Int = BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC,
        rate: Int = BluetoothCodecConfig.SAMPLE_RATE_48000,
        depth: Int = BluetoothCodecConfig.BITS_PER_SAMPLE_24,
        cs4: Long = 0,
    ): BluetoothCodecConfig = BluetoothCodecConfig.Builder()
        .setCodecType(type)
        .setSampleRate(rate)
        .setBitsPerSample(depth)
        .setCodecSpecific4(cs4)
        .build()
}
