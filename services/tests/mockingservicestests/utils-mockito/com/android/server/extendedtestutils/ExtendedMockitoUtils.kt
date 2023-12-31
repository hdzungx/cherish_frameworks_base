/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.extendedtestutils

import com.android.dx.mockito.inline.extended.ExtendedMockito
import com.android.dx.mockito.inline.extended.MockedMethod
import com.android.dx.mockito.inline.extended.StaticCapableStubber
import org.mockito.stubbing.Answer

fun <T> StaticCapableStubber.wheneverStatic(methodCall: MockedMethod<T>) {
    this.`when`(methodCall)
}

fun <T> wheneverStatic(mockedMethod: MockedMethod<T>) = object : CustomStaticStubber<T> {
    override fun thenAnswer(answer: Answer<T>) {
        ExtendedMockito.doAnswer(answer).wheneverStatic(mockedMethod)
    }

    override fun thenReturn(value: T) {
        ExtendedMockito.doReturn(value).wheneverStatic(mockedMethod)
    }

    override fun thenDoNothing() {
        ExtendedMockito.doNothing().wheneverStatic(mockedMethod)
    }
}

interface CustomStaticStubber<T> {
    fun thenAnswer(answer: Answer<T>)
    fun thenReturn(value: T)
    fun thenDoNothing()
}
