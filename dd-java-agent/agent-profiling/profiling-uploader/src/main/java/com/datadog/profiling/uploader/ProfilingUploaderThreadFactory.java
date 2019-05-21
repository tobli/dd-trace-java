/*
 * Copyright 2019 Datadog
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datadog.profiling.uploader;

import com.datadog.profiling.controller.ProfilingSystem;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Thread factory for the recording scheduler. */
final class ProfilingUploaderThreadFactory implements ThreadFactory {
  private static final AtomicInteger COUNTER = new AtomicInteger();

  @Override
  public Thread newThread(final Runnable r) {
    final Thread t =
        new Thread(
            ProfilingSystem.THREAD_GROUP,
            r,
            "dd-agent-profile-uploader-" + COUNTER.getAndIncrement());
    t.setDaemon(true);
    return t;
  }
}
