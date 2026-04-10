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

package com.android.systemui.qs

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import com.android.compose.theme.PlatformTheme
import com.android.systemui.qs.ui.composable.QSMediaVolumeSlider
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.AudioStreamSliderViewModel

object QSMediaSliderComposableProvider {
    @JvmStatic
    fun setContent(
        composeView: ComposeView,
        audioStreamSliderViewModelFactory: AudioStreamSliderViewModel.Factory,
    ) {
        composeView.setContent {
            PlatformTheme {
                QSMediaVolumeSlider(
                    audioStreamSliderViewModelFactory = audioStreamSliderViewModelFactory,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
