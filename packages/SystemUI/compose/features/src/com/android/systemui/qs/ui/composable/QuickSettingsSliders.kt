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

package com.android.systemui.qs.ui.composable

import android.media.AudioManager
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.dimensionResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.PlatformSliderDefaults
import com.android.settingslib.volume.shared.model.AudioStream
import com.android.systemui.res.R
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.AudioStreamSliderViewModel
import com.android.systemui.volume.panel.component.volume.ui.composable.VolumeSlider

const val QS_MEDIA_VOLUME_SLIDER_TAG = "qs_media_volume_slider"

@Composable
fun QuickSettingsSliders(
    brightness: @Composable () -> Unit,
    volume: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    isVolumeSliderEnabled: Boolean = isQsMediaVolumeSliderEnabled(),
) {
    if (isVolumeSliderEnabled) {
        Row(
            horizontalArrangement = spacedBy(dimensionResource(id = R.dimen.qs_split_sliders_gap)),
            modifier = modifier.fillMaxWidth(),
        ) {
            Box(modifier = Modifier.weight(1f)) { brightness() }
            Box(modifier = Modifier.weight(1f)) { volume() }
        }
    } else {
        Box(modifier = modifier.fillMaxWidth()) { brightness() }
    }
}

@Composable
fun QSMediaVolumeSlider(
    audioStreamSliderViewModelFactory: AudioStreamSliderViewModel.Factory,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    val viewModel =
        remember(audioStreamSliderViewModelFactory, coroutineScope) {
            audioStreamSliderViewModelFactory.create(
                AudioStreamSliderViewModel.FactoryAudioStreamWrapper(
                    AudioStream(AudioManager.STREAM_MUSIC)
                ),
                coroutineScope,
            )
        }
    val sliderState by
        viewModel.slider.collectAsStateWithLifecycle(
            minActiveState = Lifecycle.State.CREATED
        )

    VolumeSlider(
        state = sliderState,
        onValueChange = { newValue -> viewModel.onValueChanged(sliderState, newValue) },
        onValueChangeFinished = { viewModel.onValueChangeFinished() },
        onIconTapped = { viewModel.toggleMuted(sliderState) },
        sliderColors = PlatformSliderDefaults.defaultPlatformSliderColors(),
        hapticsViewModelFactory = viewModel.getSliderHapticsViewModelFactory(),
        showLabel = false,
        modifier = modifier.fillMaxWidth().testTag(QS_MEDIA_VOLUME_SLIDER_TAG),
    )
}

@Composable
@ReadOnlyComposable
fun isQsMediaVolumeSliderEnabled(): Boolean {
    return LocalResources.current.getBoolean(R.bool.config_enableQsMediaVolumeSlider)
}
