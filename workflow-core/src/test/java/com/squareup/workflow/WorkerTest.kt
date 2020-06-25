/*
 * Copyright 2020 Square Inc.
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
package com.squareup.workflow

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkerTest {

  @Test fun `createSideEffect workers are equivalent`() {
    val worker1 = Worker.createSideEffect {}
    val worker2 = Worker.createSideEffect {}
    assertTrue(worker1.doesSameWorkAs(worker2))
  }

  @Test fun `TypedWorkers are compared by higher types`() {
    val worker1 = Worker.create<List<Int>> { }
    val worker2 = Worker.create<List<String>> { }
    assertFalse(worker1.doesSameWorkAs(worker2))
  }

  @Test fun `TypedWorkers are equivalent with higher types`() {
    val worker1 = Worker.create<List<Int>> { }
    val worker2 = Worker.create<List<Int>> { }
    assertTrue(worker1.doesSameWorkAs(worker2))
  }
}
