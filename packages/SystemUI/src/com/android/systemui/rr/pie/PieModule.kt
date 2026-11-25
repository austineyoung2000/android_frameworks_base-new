/*
 * SPDX-FileCopyrightText: The RisingOS Android Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr.pie

import com.android.systemui.CoreStartable
import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap

@Module
abstract class PieModule {
    @Binds
    @IntoMap
    @ClassKey(PieController::class)
    abstract fun bindPieController(controller: PieController): CoreStartable
}
