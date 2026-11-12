/*
  Copyright 2026 Adobe. All rights reserved.
  This file is licensed to you under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License. You may obtain a copy
  of the License at http://www.apache.org/licenses/LICENSE-2.0
  Unless required by applicable law or agreed to in writing, software distributed under
  the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
  OF ANY KIND, either express or implied. See the License for the specific language
  governing permissions and limitations under the License.
*/

package com.adobe.marketing.mobile.messaging

import com.adobe.marketing.mobile.AdobeCallback
import com.adobe.marketing.mobile.Event
import com.adobe.marketing.mobile.EventSource
import com.adobe.marketing.mobile.EventType
import com.adobe.marketing.mobile.Messaging
import com.adobe.marketing.mobile.MobileCore
import com.adobe.marketing.mobile.internal.PluginRegistry
import com.adobe.marketing.mobile.internal.telemetry.ApiCall
import com.adobe.marketing.mobile.internal.telemetry.ApiCallRecorder
import com.adobe.marketing.mobile.internal.telemetry.InMemoryTelemetryPlugin
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.MockedStatic
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnitRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(MockitoJUnitRunner.Silent::class)
class IamRefreshHandlerTests {

    @Mock
    private lateinit var mockCallback1: AdobeCallback<Boolean>

    @Mock
    private lateinit var mockCallback2: AdobeCallback<Boolean>

    private lateinit var mobileCoreMockedStatic: MockedStatic<MobileCore>
    private lateinit var messagingExtensionMockedStatic: MockedStatic<MessagingExtension>

    @Before
    fun setup() {
        PluginRegistry.clear()
        IamRefreshHandler.reset()
        mobileCoreMockedStatic = mockStatic(MobileCore::class.java)
        messagingExtensionMockedStatic = mockStatic(MessagingExtension::class.java)
    }

    @After
    fun tearDown() {
        PluginRegistry.clear()
        IamRefreshHandler.reset()
        mobileCoreMockedStatic.close()
        messagingExtensionMockedStatic.close()
    }

    @Test
    fun test_refreshInAppMessages_dispatchesEventAndRegistersCompletionHandler() {
        // Act
        IamRefreshHandler.refreshInAppMessages(mockCallback1)

        // Assert
        mobileCoreMockedStatic.verify({ MobileCore.dispatchEvent(any(Event::class.java)) }, times(1))
        messagingExtensionMockedStatic.verify({ MessagingExtension.addCompletionHandler(any(CompletionHandler::class.java)) }, times(1))

        // Verify event details
        val eventCaptor = ArgumentCaptor.forClass(Event::class.java)
        mobileCoreMockedStatic.verify { MobileCore.dispatchEvent(eventCaptor.capture()) }
        val dispatchedEvent = eventCaptor.value
        assertNotNull(dispatchedEvent)
        assertEquals(EventType.MESSAGING, dispatchedEvent.type)
        assertEquals(EventSource.REQUEST_CONTENT, dispatchedEvent.source)
        assertEquals("Refresh in-app messages", dispatchedEvent.name)
        assertEquals(true, dispatchedEvent.eventData["refreshmessages"])
    }

    @Test
    fun test_refreshInAppMessages_multipleCalls_onlyOneEventDispatched() {
        // Act
        IamRefreshHandler.refreshInAppMessages(mockCallback1)
        IamRefreshHandler.refreshInAppMessages(mockCallback2)

        // Assert
        mobileCoreMockedStatic.verify({ MobileCore.dispatchEvent(any(Event::class.java)) }, times(1))
        messagingExtensionMockedStatic.verify({ MessagingExtension.addCompletionHandler(any(CompletionHandler::class.java)) }, times(1))
    }

    @Test
    fun test_refreshInAppMessages_multipleCalls_allCallbacksTriggeredOnCompletion() {
        // Act
        IamRefreshHandler.refreshInAppMessages(mockCallback1)
        IamRefreshHandler.refreshInAppMessages(mockCallback2)

        // Capture completion handler
        val handlerCaptor = ArgumentCaptor.forClass(CompletionHandler::class.java)
        messagingExtensionMockedStatic.verify { MessagingExtension.addCompletionHandler(handlerCaptor.capture()) }
        val handler = handlerCaptor.value

        // Simulate completion
        handler.handle.call(true)

        // Assert all callbacks were called
        verify(mockCallback1, times(1)).call(true)
        verify(mockCallback2, times(1)).call(true)
    }

    @Test
    fun test_refreshInAppMessages_sequentialCalls_dispatchesNewEventAfterCompletion() {
        // --- First Refresh ---
        IamRefreshHandler.refreshInAppMessages(mockCallback1)

        // Capture first handler
        val handlerCaptor1 = ArgumentCaptor.forClass(CompletionHandler::class.java)
        messagingExtensionMockedStatic.verify { MessagingExtension.addCompletionHandler(handlerCaptor1.capture()) }
        val handler1 = handlerCaptor1.value

        // Complete first refresh
        handler1.handle.call(true)
        verify(mockCallback1, times(1)).call(true)

        // --- Second Refresh ---
        IamRefreshHandler.refreshInAppMessages(mockCallback2)

        // Verify a second event was dispatched
        mobileCoreMockedStatic.verify({ MobileCore.dispatchEvent(any(Event::class.java)) }, times(2))
        messagingExtensionMockedStatic.verify({ MessagingExtension.addCompletionHandler(any(CompletionHandler::class.java)) }, times(2))

        // Capture second handler
        val handlerCaptor2 = ArgumentCaptor.forClass(CompletionHandler::class.java)
        messagingExtensionMockedStatic.verify({ MessagingExtension.addCompletionHandler(handlerCaptor2.capture()) }, times(2))
        val handler2 = handlerCaptor2.allValues[1]

        // Complete second refresh
        handler2.handle.call(false)
        verify(mockCallback2, times(1)).call(false)
    }

    @Test
    fun test_refreshInAppMessages_nullCallback_doesNotCrash() {
        // Act
        IamRefreshHandler.refreshInAppMessages(null)

        // Assert
        mobileCoreMockedStatic.verify({ MobileCore.dispatchEvent(any(Event::class.java)) }, times(1))

        // Simulate completion
        val handlerCaptor = ArgumentCaptor.forClass(CompletionHandler::class.java)
        messagingExtensionMockedStatic.verify { MessagingExtension.addCompletionHandler(handlerCaptor.capture()) }
        handlerCaptor.value.handle.call(true) // Should not crash
    }

    @Test
    fun publicRefreshRecordsEachInvocationBeforeCoalescing() {
        val plugin = InMemoryTelemetryPlugin()
        plugin.updateCollectionPolicy(enabled = true, consentGranted = true)
        PluginRegistry.addPlugin(plugin)

        Messaging.refreshInAppMessages()
        Messaging.refreshInAppMessages()

        val calls = plugin.snapshot()
        assertEquals(2, calls.size)
        calls.forEach {
            assertEquals("com.adobe.messaging", it.extensionName)
            assertEquals(Messaging.extensionVersion(), it.extensionVersion)
            assertEquals("Messaging.refreshInAppMessages", it.apiName)
        }
        mobileCoreMockedStatic.verify({ MobileCore.dispatchEvent(any(Event::class.java)) }, times(1))
    }

    @Test
    fun internalRefreshDoesNotRecordAPublicApiCall() {
        val plugin = InMemoryTelemetryPlugin()
        plugin.updateCollectionPolicy(enabled = true, consentGranted = true)
        PluginRegistry.addPlugin(plugin)

        IamRefreshHandler.refreshInAppMessages()

        assertTrue(plugin.snapshot().isEmpty())
        mobileCoreMockedStatic.verify({ MobileCore.dispatchEvent(any(Event::class.java)) }, times(1))
    }

    @Test
    fun publicRefreshStillDispatchesWhenTelemetryFails() {
        var attempted = false
        PluginRegistry.addPlugin(
            object : ApiCallRecorder {
                override fun recordApiCall(call: ApiCall) {
                    attempted = true
                    throw IllegalStateException("Recorder unavailable")
                }
            }
        )

        Messaging.refreshInAppMessages()

        assertTrue(attempted)
        mobileCoreMockedStatic.verify({ MobileCore.dispatchEvent(any(Event::class.java)) }, times(1))
    }

    private fun <T> any(type: Class<T>): T = org.mockito.ArgumentMatchers.any(type)
}
