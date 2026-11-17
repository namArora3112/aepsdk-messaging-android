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

package com.adobe.marketing.mobile.messaging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.adobe.marketing.mobile.Event;
import com.adobe.marketing.mobile.EventSource;
import com.adobe.marketing.mobile.EventType;
import com.adobe.marketing.mobile.MobileCore;
import com.adobe.marketing.mobile.util.TestHelper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Controlled real-Hub experiments; no Messaging/Edge extension or remote service is registered. */
@RunWith(AndroidJUnit4.class)
public class HubSignatureProjectionPocTests {
    @Rule public TestHelper.SetupCoreRule coreRule = new TestHelper.SetupCoreRule();

    private final List<Event> observed = new CopyOnWriteArrayList<>();

    @Before
    public void setup() throws InterruptedException {
        CountDownLatch registered = new CountDownLatch(1);
        MobileCore.registerExtensions(Collections.emptyList(), ignored -> registered.countDown());
        assertTrue(registered.await(5000, TimeUnit.MILLISECONDS));
        MobileCore.registerEventListener(
                EventType.WILDCARD,
                EventSource.WILDCARD,
                event -> {
                    if (EventType.MESSAGING.equalsIgnoreCase(event.getType())
                            || EventType.EDGE.equalsIgnoreCase(event.getType())) {
                        observed.add(event);
                    }
                });
    }

    @Test
    public void identicalSignatures_cannotTellWhichConcurrentRootHadTheError() throws Exception {
        Event first = root();
        Event second = root();
        Event errorForSecond = error(second);
        List<Event> worldOne =
                Arrays.asList(first, second, errorForSecond, complete(first), complete(second));
        dispatchAndAwait(worldOne);

        Event otherFirst = root();
        Event otherSecond = root();
        Event errorForFirst = error(otherFirst);
        List<Event> worldTwo =
                Arrays.asList(
                        otherFirst,
                        otherSecond,
                        errorForFirst,
                        complete(otherFirst),
                        complete(otherSecond));
        dispatchAndAwait(worldTwo);

        assertEquals(projection(worldOne).snapshot(), projection(worldTwo).snapshot());
        assertEquals(2, projection(worldOne).count("messaging.refresh.request"));
        assertEquals(1, projection(worldOne).count("edge.error.observed"));
        assertNotEquals(first.getUniqueIdentifier(), errorForSecond.getParentID());
        assertEquals(second.getUniqueIdentifier(), errorForSecond.getParentID());
        assertEquals(otherFirst.getUniqueIdentifier(), errorForFirst.getParentID());
        assertNotEquals(otherSecond.getUniqueIdentifier(), errorForFirst.getParentID());
    }

    @Test
    public void parentlessNotification_isVisibleButHasNoRoot() throws Exception {
        Event root = root();
        Event notification =
                new Event.Builder(
                                "Message propositions notification",
                                EventType.MESSAGING,
                                "com.adobe.eventSource.notification")
                        .build();
        List<Event> flow = Arrays.asList(root, complete(root), notification);
        dispatchAndAwait(flow);
        assertEquals(1, projection(flow).count("messaging.notification.observed"));
        assertNull(notification.getParentID());
        assertNull(notification.getResponseID());
    }

    @Test
    public void localVersionGetters_areInvisibleEvenWhenTheHubObserverIsAttached()
            throws Exception {
        MobileCore.extensionVersion();
        MobileCore.extensionVersion();
        MobileCore.extensionVersion();
        Event sentinel = root();
        dispatchAndAwait(Collections.singletonList(sentinel));
        assertEquals(1, projection(new ArrayList<>(observed)).count("messaging.refresh.request"));
        assertEquals(1, observed.size());
    }

    @Test
    public void replayOfDuplicateAndLostRoot_changesCountsWithoutRevealingMissingCalls()
            throws Exception {
        Event root = root();
        Event error = error(root);
        dispatchAndAwait(Arrays.asList(root, error));

        // Counterfactual replay of captured events, not duplicated SDK delivery.
        List<Event> duplicated = Arrays.asList(root, root, error);
        assertEquals(2, projection(duplicated).count("messaging.refresh.request"));
        Set<String> uniqueRoots = new HashSet<>();
        uniqueRoots.add(root.getUniqueIdentifier());
        uniqueRoots.add(root.getUniqueIdentifier());
        assertEquals(1, uniqueRoots.size());
        HubSignatureProjectionPoc rootLost = projection(Collections.singletonList(error));
        assertEquals(0, rootLost.count("messaging.refresh.request"));
        assertEquals(1, rootLost.count("edge.error.observed"));
    }

    private Event root() {
        return new Event.Builder(
                        "Refresh in-app messages", EventType.MESSAGING, EventSource.REQUEST_CONTENT)
                .setEventData(Collections.singletonMap("refreshmessages", true))
                .build();
    }

    private Event error(Event parent) {
        return new Event.Builder(
                        "AEP Error Response", EventType.EDGE, EventSource.ERROR_RESPONSE_CONTENT)
                .chainToParentEvent(parent)
                .build();
    }

    private Event complete(Event parent) {
        return new Event.Builder(
                        "Finalize propositions response",
                        EventType.MESSAGING,
                        EventSource.CONTENT_COMPLETE)
                .chainToParentEvent(parent)
                .build();
    }

    private HubSignatureProjectionPoc projection(List<Event> events) {
        HubSignatureProjectionPoc projection = new HubSignatureProjectionPoc();
        for (Event event : events) {
            Map<String, Object> data = event.getEventData();
            projection.observe(
                    event.getType(),
                    event.getSource(),
                    data != null && Boolean.TRUE.equals(data.get("refreshmessages")),
                    null);
        }
        return projection;
    }

    private void dispatchAndAwait(List<Event> events) throws InterruptedException {
        for (Event event : events) {
            MobileCore.dispatchEvent(event);
        }
        long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (observed.containsAll(events)) {
                return;
            }
            Thread.sleep(10);
        }
        assertTrue("Controlled events were not observed", observed.containsAll(events));
    }
}
