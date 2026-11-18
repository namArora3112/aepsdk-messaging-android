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

package com.adobe.marketing.mobile;

import com.adobe.marketing.mobile.plugin.TelemetryApiCapability;
import com.adobe.marketing.mobile.telemetrybridge.TelemetryBridge;
import java.util.Arrays;
import java.util.HashSet;

/** POC SDK adoption; the telemetry artifact remains optional. */
final class MessagingTelemetry {
    static final String PRODUCER = "com.adobe.messaging";
    static final String REFRESH = "Messaging.refreshInAppMessages";
    static final String GET = "Messaging.getPropositionsForSurfaces";
    private static final long OBSERVATION_TTL_MS = 60000;
    private static volatile boolean declared;

    private MessagingTelemetry() {}

    static String started(final String api) {
        try {
            if (!declared) {
                synchronized (MessagingTelemetry.class) {
                    if (!declared) {
                        declared =
                                TelemetryBridge.declareApiCapabilities(
                                        PRODUCER,
                                        Arrays.asList(
                                                new TelemetryApiCapability(
                                                        REFRESH, Messaging.extensionVersion()),
                                                new TelemetryApiCapability(
                                                        GET,
                                                        Messaging.extensionVersion(),
                                                        1,
                                                        new HashSet<>(
                                                                Arrays.asList(
                                                                        "success",
                                                                        "failure",
                                                                        "rejected",
                                                                        "timed_out")),
                                                        "cache_callback.v1",
                                                        OBSERVATION_TTL_MS)));
                    }
                }
            }
            return TelemetryBridge.apiStarted(PRODUCER, api);
        } catch (Exception | LinkageError ignored) {
            return null;
        }
    }

    static void completed(final String id, final String api, final String outcome) {
        try {
            TelemetryBridge.apiCompleted(id, PRODUCER, api, outcome);
        } catch (Exception | LinkageError ignored) {
            // An absent or older optional bridge cannot break the business call.
        }
    }
}
