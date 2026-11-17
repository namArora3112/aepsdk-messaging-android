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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.adobe.marketing.mobile.AdobeCallbackWithError;
import com.adobe.marketing.mobile.AdobeError;
import com.adobe.marketing.mobile.Edge;
import com.adobe.marketing.mobile.Event;
import com.adobe.marketing.mobile.EventSource;
import com.adobe.marketing.mobile.EventType;
import com.adobe.marketing.mobile.Messaging;
import com.adobe.marketing.mobile.MobileCore;
import com.adobe.marketing.mobile.edge.identity.Identity;
import com.adobe.marketing.mobile.services.HttpConnecting;
import com.adobe.marketing.mobile.services.HttpMethod;
import com.adobe.marketing.mobile.util.TestHelper;
import com.adobe.marketing.mobile.util.TestableNetworkRequest;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Full payloads are synthetic fixtures, saved only in the instrumented test app's private files.
 */
@RunWith(AndroidJUnit4.class)
public class RefreshEventCorrelationPocTests {
    private static final String EDGE_URL = "https://edge.adobedc.net/ee/v1/interact";
    private static final String BASE_SURFACE =
            "mobileapp://com.adobe.marketing.mobile.messaging.test";
    private static final String DECISIONS = "personalization:decisions";
    private static final long WAIT_MS = 15000;

    static {
        BuildConfig.IS_E2E_TEST.set(false);
        BuildConfig.IS_FUNCTIONAL_TEST.set(true);
    }

    @Rule public TestHelper.SetupCoreRule coreRule = new TestHelper.SetupCoreRule();

    private final List<Event> events = new CopyOnWriteArrayList<>();
    private final CountDownLatch networkRelease = new CountDownLatch(1);
    private final CountDownLatch refreshCompleted = new CountDownLatch(1);
    private final AtomicReference<Boolean> localCompletion = new AtomicReference<>();
    private String responseJson;

    @Before
    public void setup() throws Exception {
        IamRefreshHandler.INSTANCE.reset();
        EdgePersonalizationResponseHandler.internetAvailableOverrideForTesting = true;
        MessagingTestUtils.setEdgeIdentityPersistence(
                MessagingTestUtils.createIdentityMap("ECID", "poc-ecid"),
                TestHelper.getDefaultApplication());
        MobileCore.registerEventListener(EventType.WILDCARD, EventSource.WILDCARD, events::add);
        Map<String, Object> config = new HashMap<>();
        config.put("edge.configId", "poc-config");
        config.put("messaging.eventDataset", "poc-dataset");
        MobileCore.updateConfiguration(config);
        CountDownLatch registered = new CountDownLatch(1);
        MobileCore.registerExtensions(
                Arrays.asList(Messaging.EXTENSION, Identity.EXTENSION, Edge.EXTENSION),
                ignored -> registered.countDown());
        assertTrue(registered.await(WAIT_MS, TimeUnit.MILLISECONDS));
        await(event -> is(event, EventType.MESSAGING, EventSource.CONTENT_COMPLETE));
        drainMessagingQueue();
        events.clear();
        TestHelper.resetTestExpectations();
    }

    @After
    public void tearDown() {
        networkRelease.countDown();
        EdgePersonalizationResponseHandler.internetAvailableOverrideForTesting = null;
        IamRefreshHandler.INSTANCE.reset();
    }

    @Test
    public void refresh_streamedInAppDecisions_useExistingIds() throws Exception {
        installResponse(200, decisionsResponse(true));
        Messaging.refreshInAppMessages();
        Event request = awaitRefreshRequest();
        awaitEdgeRequest(request);
        observeLocalCompletion();
        networkRelease.countDown();
        assertRefreshCompleted();
        drainMessagingQueue();

        assertEquals(2, matching(event -> is(event, EventType.EDGE, DECISIONS)).size());
        assertChain(request);
        assertTrue(localCompletion.get());
        saveTrace("streamed-inapp-decisions", 1);
    }

    @Test
    public void refresh_emptyResponse_completesWithoutDecisionEvents() throws Exception {
        installResponse(200, new JSONObject().put("handle", new JSONArray()));
        Messaging.refreshInAppMessages();
        Event request = awaitRefreshRequest();
        awaitEdgeRequest(request);
        observeLocalCompletion();
        networkRelease.countDown();
        assertRefreshCompleted();
        drainMessagingQueue();

        assertChain(request);
        assertTrue(matching(event -> is(event, EventType.EDGE, DECISIONS)).isEmpty());
        assertTrue(localCompletion.get());
        saveTrace("empty-response", 1);
    }

    @Test
    public void refresh_errorResponse_stillHasStreamCompletion() throws Exception {
        JSONObject error =
                new JSONObject()
                        .put("eventIndex", 0)
                        .put("status", 400)
                        .put("type", "https://ns.adobe.com/aep/errors/POC")
                        .put("title", "Synthetic rejected request");
        installResponse(400, new JSONObject().put("errors", new JSONArray().put(error)));
        Messaging.refreshInAppMessages();
        Event request = awaitRefreshRequest();
        awaitEdgeRequest(request);
        observeLocalCompletion();
        networkRelease.countDown();
        assertRefreshCompleted();
        drainMessagingQueue();

        assertChain(request);
        List<Event> errors =
                matching(event -> is(event, EventType.EDGE, EventSource.ERROR_RESPONSE_CONTENT));
        assertFalse("The controlled Edge error must be visible", errors.isEmpty());
        for (Event errorEvent : errors) {
            assertEquals(request.getUniqueIdentifier(), resolveRoot(errorEvent));
        }
        assertTrue(
                "Messaging completion reports processing, not remote success",
                localCompletion.get());
        saveTrace("http-400-with-completion", 1);
    }

    @Test
    public void refresh_overlappingPublicCalls_shareOneRequest() throws Exception {
        installResponse(200, new JSONObject().put("handle", new JSONArray()));
        Messaging.refreshInAppMessages();
        Event request = awaitRefreshRequest();
        awaitEdgeRequest(request);
        Messaging.refreshInAppMessages();
        observeLocalCompletion();
        networkRelease.countDown();
        assertRefreshCompleted();
        drainMessagingQueue();

        assertEquals(1, matching(this::isRefreshRequest).size());
        assertEquals(
                1,
                matching(event -> is(event, EventType.EDGE, EventSource.REQUEST_CONTENT)).size());
        assertChain(request);
        saveTrace("two-overlapping-api-calls-one-request", 2);
    }

    @Test
    public void customDispatch_sameRefreshSignature_hasSameCorrelatableLifecycle()
            throws Exception {
        installResponse(200, new JSONObject().put("handle", new JSONArray()));
        Map<String, Object> data = new HashMap<>();
        data.put("refreshmessages", true);
        Event custom =
                new Event.Builder(
                                "Refresh in-app messages",
                                EventType.MESSAGING,
                                EventSource.REQUEST_CONTENT)
                        .setEventData(data)
                        .build();
        MobileCore.dispatchEvent(custom);
        awaitEdgeRequest(custom);
        networkRelease.countDown();
        await(
                event ->
                        is(event, EventType.MESSAGING, EventSource.CONTENT_COMPLETE)
                                && custom.getUniqueIdentifier().equals(resolveRoot(event)));
        drainMessagingQueue();

        assertChain(custom);
        saveTrace("custom-dispatch-zero-refresh-api-calls", 0);
    }

    @Test
    public void refresh_callbackDeadline_hasNoTerminalHubEventBeforeLateResponse()
            throws Exception {
        installResponse(200, new JSONObject().put("handle", new JSONArray()));
        Messaging.refreshInAppMessages();
        Event request = awaitRefreshRequest();
        awaitEdgeRequest(request);
        observeLocalCompletion();
        assertRefreshCompleted();

        assertFalse(localCompletion.get());
        assertTrue(
                matching(
                                event ->
                                        is(event, EventType.EDGE, EventSource.CONTENT_COMPLETE)
                                                || is(
                                                        event,
                                                        EventType.MESSAGING,
                                                        EventSource.CONTENT_COMPLETE))
                        .isEmpty());
        saveTrace("callback-timeout-response-still-held", 1);
    }

    @Test
    public void refresh_codeBasedNotification_doesNotCarryTheRequestLink() throws Exception {
        installResponse(200, decisionsResponse(false));
        Messaging.refreshInAppMessages();
        Event request = awaitRefreshRequest();
        awaitEdgeRequest(request);
        observeLocalCompletion();
        networkRelease.countDown();
        assertRefreshCompleted();
        Event notification =
                await(
                        event ->
                                is(
                                        event,
                                        EventType.MESSAGING,
                                        MessagingConstants.EventSource.NOTIFICATION));
        drainMessagingQueue();

        assertChain(request);
        assertNotNull(notification.getEventData().get("propositions"));
        assertNull(notification.getParentID());
        assertNull(notification.getResponseID());
        assertNull(resolveRoot(notification));
        saveTrace("code-based-notification-missing-request-link", 1);
    }

    private void observeLocalCompletion() {
        // A coalesced test-only waiter synchronizes processing; it is not an EventHub response
        // listener.
        IamRefreshHandler.INSTANCE.refreshInAppMessages(
                success -> {
                    localCompletion.set(success);
                    refreshCompleted.countDown();
                });
    }

    private void assertRefreshCompleted() throws InterruptedException {
        assertTrue(
                "Missing local completion", refreshCompleted.await(WAIT_MS, TimeUnit.MILLISECONDS));
        assertNotNull(localCompletion.get());
    }

    private Event awaitRefreshRequest() throws InterruptedException {
        return await(this::isRefreshRequest);
    }

    private boolean isRefreshRequest(Event event) {
        return is(event, EventType.MESSAGING, EventSource.REQUEST_CONTENT)
                && event.getEventData() != null
                && Boolean.TRUE.equals(event.getEventData().get("refreshmessages"));
    }

    private Event awaitEdgeRequest(Event request) throws InterruptedException {
        return await(
                event ->
                        is(event, EventType.EDGE, EventSource.REQUEST_CONTENT)
                                && request.getUniqueIdentifier().equals(event.getParentID()));
    }

    private void assertChain(Event root) throws InterruptedException {
        Event edgeRequest = awaitEdgeRequest(root);
        Event edgeComplete =
                await(
                        event ->
                                is(event, EventType.EDGE, EventSource.CONTENT_COMPLETE)
                                        && edgeRequest
                                                .getUniqueIdentifier()
                                                .equals(event.getResponseID()));
        Event finalize =
                await(
                        event ->
                                is(event, EventType.MESSAGING, EventSource.CONTENT_COMPLETE)
                                        && edgeRequest
                                                .getUniqueIdentifier()
                                                .equals(event.getEventData().get("endingEventId")));
        assertEquals(edgeRequest.getUniqueIdentifier(), edgeComplete.getParentID());
        assertEquals(edgeComplete.getUniqueIdentifier(), finalize.getParentID());
        assertNotEquals(
                edgeRequest.getUniqueIdentifier(), edgeComplete.getEventData().get("requestId"));
        assertEquals(root.getUniqueIdentifier(), resolveRoot(finalize));
        for (Event decision : matching(event -> is(event, EventType.EDGE, DECISIONS))) {
            assertEquals(root.getUniqueIdentifier(), resolveRoot(decision));
            assertEquals(edgeRequest.getUniqueIdentifier(), decision.getParentID());
            assertEquals(
                    edgeRequest.getUniqueIdentifier(),
                    decision.getEventData().get("requestEventId"));
            assertEquals(
                    edgeComplete.getEventData().get("requestId"),
                    decision.getEventData().get("requestId"));
        }
    }

    private Event await(Predicate<Event> predicate) throws InterruptedException {
        long deadline = android.os.SystemClock.elapsedRealtime() + WAIT_MS;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            for (Event event : events) {
                if (predicate.test(event)) {
                    return event;
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Expected EventHub event not observed: " + events);
    }

    private List<Event> matching(Predicate<Event> predicate) {
        List<Event> result = new ArrayList<>();
        for (Event event : events) {
            if (predicate.test(event)) {
                result.add(event);
            }
        }
        return result;
    }

    private static boolean is(Event event, String type, String source) {
        return type.equalsIgnoreCase(event.getType()) && source.equalsIgnoreCase(event.getSource());
    }

    private String resolveRoot(Event event) {
        Map<String, Event> byId = new HashMap<>();
        for (Event observed : events) {
            byId.put(observed.getUniqueIdentifier(), observed);
        }
        return resolveRoot(event, byId, new HashSet<>());
    }

    private String resolveRoot(Event event, Map<String, Event> byId, Set<String> visited) {
        if (event == null || !visited.add(event.getUniqueIdentifier())) {
            return null;
        }
        if (isRefreshRequest(event)) {
            return event.getUniqueIdentifier();
        }
        List<Object> links =
                new ArrayList<>(Arrays.asList(event.getParentID(), event.getResponseID()));
        if (event.getEventData() != null) {
            links.add(event.getEventData().get("requestEventId"));
            links.add(event.getEventData().get("endingEventId"));
        }
        for (Object link : links) {
            if (link instanceof String) {
                String root = resolveRoot(byId.get(link), byId, visited);
                if (root != null) {
                    return root;
                }
            }
        }
        return null;
    }

    private void drainMessagingQueue() throws InterruptedException {
        // This cache read is a processing barrier, not part of the refresh being measured.
        Set<String> alreadyObserved = new HashSet<>();
        for (Event event : events) {
            alreadyObserved.add(event.getUniqueIdentifier());
        }
        CountDownLatch drained = new CountDownLatch(1);
        AtomicReference<AdobeError> barrierError = new AtomicReference<>();
        Messaging.getPropositionsForSurfaces(
                Collections.singletonList(new Surface()),
                new AdobeCallbackWithError<Map<Surface, List<Proposition>>>() {
                    @Override
                    public void call(Map<Surface, List<Proposition>> ignored) {
                        drained.countDown();
                    }

                    @Override
                    public void fail(AdobeError error) {
                        barrierError.set(error);
                        drained.countDown();
                    }
                });
        assertTrue("Messaging queue did not drain", drained.await(WAIT_MS, TimeUnit.MILLISECONDS));
        assertNotEquals(AdobeError.CALLBACK_TIMEOUT, barrierError.get());
        Event barrierRequest =
                await(
                        event ->
                                is(event, EventType.MESSAGING, EventSource.REQUEST_CONTENT)
                                        && event.getEventData() != null
                                        && Boolean.TRUE.equals(
                                                event.getEventData().get("getpropositions"))
                                        && !alreadyObserved.contains(event.getUniqueIdentifier()));
        await(event -> barrierRequest.getUniqueIdentifier().equals(event.getResponseID()));
    }

    private void installResponse(int status, JSONObject body) {
        TestHelper.setNetworkResponseFor(
                EDGE_URL,
                HttpMethod.POST,
                new HttpConnecting() {
                    @Override
                    public InputStream getInputStream() {
                        return responseStream();
                    }

                    @Override
                    public InputStream getErrorStream() {
                        return status >= 400 ? responseStream() : null;
                    }

                    private InputStream responseStream() {
                        try {
                            assertTrue(networkRelease.await(30, TimeUnit.SECONDS));
                            List<TestableNetworkRequest> requests =
                                    TestHelper.getNetworkRequestsWith(EDGE_URL, HttpMethod.POST);
                            body.put(
                                    "requestId",
                                    requests.get(requests.size() - 1).queryParam("requestId"));
                            responseJson = body.toString();
                            return new ByteArrayInputStream(
                                    ("\u0000" + responseJson).getBytes(StandardCharsets.UTF_8));
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    }

                    @Override
                    public int getResponseCode() {
                        return status;
                    }

                    @Override
                    public String getResponseMessage() {
                        return "Synthetic POC response";
                    }

                    @Override
                    public String getResponsePropertyValue(String key) {
                        return null;
                    }

                    @Override
                    public void close() {}
                });
    }

    private JSONObject decisionsResponse(boolean inApp) throws Exception {
        JSONArray handles = new JSONArray();
        for (int index = 0; index < (inApp ? 2 : 1); index++) {
            JSONObject item =
                    new JSONObject()
                            .put("id", "poc-item-" + index)
                            .put(
                                    "schema",
                                    inApp
                                            ? MessagingConstants.SchemaValues.SCHEMA_RULESET_ITEM
                                            : MessagingConstants.SchemaValues.SCHEMA_JSON_CONTENT)
                            .put(
                                    "data",
                                    inApp
                                            ? inAppRules(index)
                                            : new JSONObject().put("content", "synthetic"));
            JSONObject proposition =
                    new JSONObject()
                            .put("id", "poc-proposition-" + index)
                            .put("scope", BASE_SURFACE)
                            .put(
                                    "scopeDetails",
                                    new JSONObject()
                                            .put("decisionProvider", "AJO")
                                            .put(
                                                    "activity",
                                                    new JSONObject()
                                                            .put("id", "poc-activity-" + index)))
                            .put("items", new JSONArray().put(item));
            handles.put(
                    new JSONObject()
                            .put("type", DECISIONS)
                            .put("eventIndex", 0)
                            .put("payload", new JSONArray().put(proposition)));
        }
        return new JSONObject().put("handle", handles);
    }

    private JSONObject inAppRules(int index) throws Exception {
        JSONObject message =
                new JSONObject()
                        .put("id", "poc-message-" + index)
                        .put("schema", MessagingConstants.SchemaValues.SCHEMA_IAM)
                        .put(
                                "data",
                                new JSONObject()
                                        .put("contentType", "text/html")
                                        .put("content", "<html><body>Synthetic POC</body></html>")
                                        .put("mobileParameters", new JSONObject()));
        JSONObject condition =
                new JSONObject()
                        .put("type", "matcher")
                        .put(
                                "definition",
                                new JSONObject()
                                        .put("key", "action")
                                        .put("matcher", "eq")
                                        .put("values", new JSONArray().put("poc-never-trigger")));
        JSONObject rule =
                new JSONObject()
                        .put("condition", condition)
                        .put(
                                "consequences",
                                new JSONArray()
                                        .put(
                                                new JSONObject()
                                                        .put("id", "poc-consequence-" + index)
                                                        .put("type", "schema")
                                                        .put("detail", message)));
        return new JSONObject().put("version", 1).put("rules", new JSONArray().put(rule));
    }

    private void saveTrace(String scenario, int publicApiCalls) throws Exception {
        JSONArray trace = new JSONArray();
        HubSignatureProjectionPoc projection = new HubSignatureProjectionPoc();
        int linkedEvents = 0;
        int linkedErrors = 0;
        for (Event event : events) {
            Map<String, Object> data = event.getEventData();
            Object xdm = data == null ? null : data.get("xdm");
            Object xdmType = xdm instanceof Map ? ((Map<?, ?>) xdm).get("eventType") : null;
            projection.observe(
                    event.getType(),
                    event.getSource(),
                    data != null && Boolean.TRUE.equals(data.get("refreshmessages")),
                    xdmType instanceof String ? (String) xdmType : null);
            if (resolveRoot(event) != null) {
                linkedEvents++;
                if (is(event, EventType.EDGE, EventSource.ERROR_RESPONSE_CONTENT)) {
                    linkedErrors++;
                }
            }
            JSONObject row =
                    new JSONObject()
                            .put("id", event.getUniqueIdentifier())
                            .put("name", event.getName())
                            .put("type", event.getType())
                            .put("source", event.getSource())
                            .put("parentId", nullable(event.getParentID()))
                            .put("responseId", nullable(event.getResponseID()))
                            .put("resolvedRefreshRootId", nullable(resolveRoot(event)))
                            .put(
                                    "eventData",
                                    event.getEventData() == null
                                            ? JSONObject.NULL
                                            : new JSONObject(event.getEventData()));
            trace.put(row);
        }
        assertEquals(1, projection.count("messaging.refresh.request"));
        assertEquals(1, projection.count("edge.propositionFetch.request"));
        if ("http-400-with-completion".equals(scenario)) {
            assertTrue(projection.count("edge.error.observed") >= 1);
            assertTrue(linkedErrors >= 1);
            assertTrue(projection.count("messaging.finalize.observed") >= 1);
        }
        if ("callback-timeout-response-still-held".equals(scenario)) {
            assertEquals(0, projection.count("messaging.finalize.observed"));
            assertEquals(0, projection.count("edge.error.observed"));
        }
        if ("code-based-notification-missing-request-link".equals(scenario)) {
            assertEquals(1, projection.count("messaging.notification.observed"));
        }
        JSONObject result =
                new JSONObject()
                        .put("scenario", scenario)
                        .put("publicRefreshApiCalls", publicApiCalls)
                        .put("localCompletion", nullable(localCompletion.get()))
                        .put("idFreeProjection", new JSONObject(projection.snapshot()))
                        .put(
                                "idAwareCorrelation",
                                new JSONObject()
                                        .put("linkedEvents", linkedEvents)
                                        .put("linkedErrorEvents", linkedErrors))
                        .put("syntheticOnly", true)
                        .put(
                                "synchronization",
                                "Coalesced internal callback; cache-read barrier after completion,"
                                        + " except timeout.")
                        .put(
                                "networkResponse",
                                responseJson == null
                                        ? JSONObject.NULL
                                        : new JSONObject(responseJson))
                        .put("events", trace);
        File directory =
                new File(
                        InstrumentationRegistry.getInstrumentation()
                                .getTargetContext()
                                .getFilesDir(),
                        "refresh-correlation-poc");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream stream =
                new FileOutputStream(new File(directory, scenario + ".json"))) {
            stream.write(result.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static Object nullable(Object value) {
        return value == null ? JSONObject.NULL : value;
    }
}
