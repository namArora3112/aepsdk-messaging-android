/*
 * Copyright 2026 Adobe. All rights reserved.
 * This file is licensed to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0
 */
package com.adobe.marketing.mobile.messagingsample

import android.app.Application

/** The normal sample has no dependency on the optional telemetry artifact. */
internal object TelemetryValidation {
    fun start(@Suppress("UNUSED_PARAMETER") application: Application) = Unit
    fun configure() = Unit
}
