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

import androidx.compose.ui.platform.ComposeView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.settings.brightness.BrightnessSliderView
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.AudioStreamSliderViewModel
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
class QSSlidersRowViewTest : SysuiTestCase() {

    @Test
    fun setSliderScaleY_updatesBrightnessAndVolumeChildren() {
        val brightnessView = spy(BrightnessSliderView(context, null))
        val volumeView = ComposeView(context)
        val rowView = QSSlidersRowView(context)

        rowView.setBrightnessView(brightnessView)
        rowView.setVolumeView(volumeView, mock(AudioStreamSliderViewModel.Factory::class.java))

        rowView.setSliderScaleY(0.5f)

        verify(brightnessView).setSliderScaleY(0.5f)
        assertThat(volumeView.scaleY).isEqualTo(0.5f)
    }
}
