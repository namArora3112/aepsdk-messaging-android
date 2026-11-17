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

import com.adobe.marketing.mobile.EventSource;
import com.adobe.marketing.mobile.EventType;
import java.util.LinkedHashMap;
import java.util.Map;

/** Test-only fixed catalog. Its input contract cannot carry event or operation identifiers. */
final class HubSignatureProjectionPoc {
    private final Map<String, Integer> counts = new LinkedHashMap<>();

    void observe(String type, String source, boolean refreshMessages, String xdmEventType) {
        String label = null;
        if (EventType.MESSAGING.equalsIgnoreCase(type)) {
            if (EventSource.REQUEST_CONTENT.equalsIgnoreCase(source) && refreshMessages) {
                label = "messaging.refresh.request";
            } else if (EventSource.CONTENT_COMPLETE.equalsIgnoreCase(source)) {
                label = "messaging.finalize.observed";
            } else if ("com.adobe.eventSource.notification".equalsIgnoreCase(source)) {
                label = "messaging.notification.observed";
            }
        } else if (EventType.EDGE.equalsIgnoreCase(type)) {
            if (EventSource.REQUEST_CONTENT.equalsIgnoreCase(source)
                    && "decisioning.propositionFetch".equals(xdmEventType)) {
                label = "edge.propositionFetch.request";
            } else if ("personalization:decisions".equalsIgnoreCase(source)) {
                label = "edge.decisionHandle.observed";
            } else if (EventSource.ERROR_RESPONSE_CONTENT.equalsIgnoreCase(source)) {
                label = "edge.error.observed";
            } else if (EventSource.CONTENT_COMPLETE.equalsIgnoreCase(source)) {
                label = "edge.streamComplete.observed";
            }
        } else if (EventType.RULES_ENGINE.equalsIgnoreCase(type)
                && EventSource.REQUEST_RESET.equalsIgnoreCase(source)) {
            label = "rules.reset.observed";
        }
        if (label != null) {
            counts.put(label, count(label) + 1);
        }
    }

    int count(String label) {
        Integer value = counts.get(label);
        return value == null ? 0 : value;
    }

    Map<String, Integer> snapshot() {
        return new LinkedHashMap<>(counts);
    }
}
