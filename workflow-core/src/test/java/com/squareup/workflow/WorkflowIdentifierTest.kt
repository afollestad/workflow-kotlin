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

import okio.Buffer
import okio.ByteString
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalWorkflowApi::class, ExperimentalStdlibApi::class)
class WorkflowIdentifierTest {

  @Test fun `flat identifier toString`() {
    val id = TestWorkflow1.identifier
    assertEquals(
        "WorkflowIdentifier(com.squareup.workflow.WorkflowIdentifierTest\$TestWorkflow1)",
        id.toString()
    )
  }

  @Test fun `impostor identifier toString`() {
    val id = TestImpostor1(TestWorkflow1).identifier
    assertEquals(
        "WorkflowIdentifier(com.squareup.workflow.WorkflowIdentifierTest\$TestImpostor1, " +
            "com.squareup.workflow.WorkflowIdentifierTest\$TestWorkflow1)",
        id.toString()
    )
  }

  @Test fun `impostor identifier description`() {
    val id = TestImpostor1(TestWorkflow1).identifier
    assertEquals("TestImpostor1(TestWorkflow1)", id.describeRealIdentifier())
  }

  @Test fun `restored identifier toString`() {
    val id = TestWorkflow1.identifier
    val serializedId = id.toByteStringOrNull()!!
    val restoredId = WorkflowIdentifier.parse(serializedId)
    assertEquals(id.toString(), restoredId.toString())
  }

  @Test fun `flat identifiers for same class are equal`() {
    val id1 = TestWorkflow1.identifier
    val id2 = TestWorkflow1.identifier
    assertEquals(id1, id2)
    assertEquals(id1.hashCode(), id2.hashCode())
  }

  @Test fun `flat identifiers for different classes are not equal`() {
    val id1 = TestWorkflow1.identifier
    val id2 = TestWorkflow2.identifier
    assertNotEquals(id1, id2)
  }

  @Test fun `impostor identifiers for same proxied class are equal`() {
    val impostorId1 = TestImpostor1(TestWorkflow1).identifier
    val impostorId2 = TestImpostor1(TestWorkflow1).identifier
    assertEquals(impostorId1, impostorId2)
    assertEquals(impostorId1.hashCode(), impostorId2.hashCode())
  }

  @Test fun `impostor identifiers for different proxied classes are not equal`() {
    val impostorId1 = TestImpostor1(TestWorkflow1).identifier
    val impostorId2 = TestImpostor1(TestWorkflow2).identifier
    assertNotEquals(impostorId1, impostorId2)
  }

  @Test fun `different impostor identifiers for same proxied class are not equal`() {
    val impostorId1 = TestImpostor1(TestWorkflow1).identifier
    val impostorId2 = TestImpostor2(TestWorkflow1).identifier
    assertNotEquals(impostorId1, impostorId2)
  }

  @Test fun `identifier restored from source is equal to itself`() {
    val id = TestWorkflow1.identifier
    val serializedId = id.toByteStringOrNull()!!
    val restoredId = WorkflowIdentifier.parse(serializedId)
    assertEquals(id, restoredId)
    assertEquals(id.hashCode(), restoredId.hashCode())
  }

  @Test fun `identifier restored from source is not equal to different identifier`() {
    val id1 = TestWorkflow1.identifier
    val id2 = TestWorkflow2.identifier
    val serializedId = id1.toByteStringOrNull()!!
    val restoredId = WorkflowIdentifier.parse(serializedId)
    assertNotEquals(id2, restoredId)
  }

  @Test fun `impostor identifier restored from source is equal to itself`() {
    val id = TestImpostor1(TestWorkflow1).identifier
    val serializedId = id.toByteStringOrNull()!!
    val restoredId = WorkflowIdentifier.parse(serializedId)
    assertEquals(id, restoredId)
    assertEquals(id.hashCode(), restoredId.hashCode())
  }

  @Test
  fun `impostor identifier restored from source is not equal to impostor with different proxied class`() {
    val id1 = TestImpostor1(TestWorkflow1).identifier
    val id2 = TestImpostor1(TestWorkflow2).identifier
    val serializedId = id1.toByteStringOrNull()!!
    val restoredId = WorkflowIdentifier.parse(serializedId)
    assertNotEquals(id2, restoredId)
  }

  @Test
  fun `impostor identifier restored from source is not equal to different impostor with same proxied class`() {
    val id1 = TestImpostor1(TestWorkflow1).identifier
    val id2 = TestImpostor2(TestWorkflow1).identifier
    val serializedId = id1.toByteStringOrNull()!!
    val restoredId = WorkflowIdentifier.parse(serializedId)
    assertNotEquals(id2, restoredId)
  }

  @Test fun `read from empty source throws`() {
    assertFailsWith<IllegalArgumentException> {
      WorkflowIdentifier.parse(ByteString.EMPTY)
    }
  }

  @Test fun `read from invalid source throws`() {
    val source = Buffer().apply { writeUtf8("invalid data") }
        .readByteString()
    assertFailsWith<IllegalArgumentException> {
      WorkflowIdentifier.parse(source)
    }
  }

  @Test fun `read from corrupted source throws`() {
    val source = TestWorkflow1.identifier.toByteStringOrNull()!!
        .toByteArray()
    source.indices.reversed()
        .take(10)
        .forEach { i ->
          source[i] = 0
        }
    val corruptedSource = Buffer().apply { write(source) }
        .readByteString()
    assertFailsWith<ClassNotFoundException> {
      WorkflowIdentifier.parse(corruptedSource)
    }
  }

  @Test fun `unsnapshottable identifier returns null ByteString`() {
    val id = unsnapshottableIdentifier(typeOf<TestWorkflow1>())
    assertNull(id.toByteStringOrNull())
  }

  @Test fun `unsnapshottable identifier toString()`() {
    val id = unsnapshottableIdentifier(typeOf<String>())
    assertEquals(
        "WorkflowIdentifier(${String::class.java.name} (Kotlin reflection is not available))",
        id.toString()
    )
  }

  @Test fun `unsnapshottable identifiers for same class are equal`() {
    val id1 = unsnapshottableIdentifier(typeOf<String>())
    val id2 = unsnapshottableIdentifier(typeOf<String>())
    assertEquals(id1, id2)
  }

  @Test fun `unsnapshottable identifiers for different class are not equal`() {
    val id1 = unsnapshottableIdentifier(typeOf<String>())
    val id2 = unsnapshottableIdentifier(typeOf<Int>())
    assertNotEquals(id1, id2)
  }

  @Test fun `unsnapshottable impostor identifier returns null ByteString`() {
    val id = TestUnsnapshottableImpostor(typeOf<String>()).identifier
    assertNull(id.toByteStringOrNull())
  }

  @Test fun `impostor of unsnapshottable impostor identifier returns null ByteString`() {
    val id = TestImpostor1(TestUnsnapshottableImpostor(typeOf<String>())).identifier
    assertNull(id.toByteStringOrNull())
  }

  @Test fun `unsnapshottable impostor identifier toString()`() {
    val id = TestUnsnapshottableImpostor(typeOf<String>()).identifier
    assertEquals(
        "WorkflowIdentifier(${TestUnsnapshottableImpostor::class.java.name}, " +
            "${String::class.java.name} (Kotlin reflection is not available))", id.toString()
    )
  }

  @Test fun `workflowIdentifier from Workflow class is equal to identifier from workflow`() {
    val instanceId = TestWorkflow1.identifier
    val classId = TestWorkflow1::class.workflowIdentifier
    assertEquals(instanceId, classId)
  }

  @Test
  fun `workflowIdentifier from Workflow class is not equal to identifier from different class`() {
    val id1 = TestWorkflow1::class.workflowIdentifier
    val id2 = TestWorkflow2::class.workflowIdentifier
    assertNotEquals(id1, id2)
  }

  @Test fun `workflowIdentifier from ImpostorWorkflow class throws`() {
    val error = assertFailsWith<IllegalArgumentException> {
      TestImpostor1::class.workflowIdentifier
    }
    assertEquals(
        "Cannot create WorkflowIdentifier from a KClass of ImpostorWorkflow: ${TestImpostor1::class.qualifiedName}",
        error.message
    )
  }

  @Test fun `impostorWorkflowIdentifier is equal to identifier from ImpostorWorkflow`() {
    val instanceId = TestImpostor1(TestWorkflow1).identifier
    val classId = TestImpostor1::class.impostorWorkflowIdentifier(TestWorkflow1.identifier)
    assertEquals(instanceId, classId)
  }

  @Test
  fun `impostorWorkflowIdentifier is not equal to identifier from ImpostorWorkflow with different proxied class`() {
    val instanceId = TestImpostor1(TestWorkflow1).identifier
    val classId = TestImpostor1::class.impostorWorkflowIdentifier(TestWorkflow2.identifier)
    assertNotEquals(instanceId, classId)
  }

  @Test
  fun `impostorWorkflowIdentifier is not equal to identifier from different ImpostorWorkflow with same proxied class`() {
    val instanceId = TestImpostor1(TestWorkflow1).identifier
    val classId = TestImpostor2::class.impostorWorkflowIdentifier(TestWorkflow1.identifier)
    assertNotEquals(instanceId, classId)
  }

  @Test fun `matchesActualIdentifierForTest() matches same workflow class`() {
    val id1 = TestWorkflow1.identifier
    val id2 = TestWorkflow1.identifier
    assertTrue(id1.matchesActualIdentifierForTest(id2))
    assertTrue(id2.matchesActualIdentifierForTest(id1))
  }

  @Test fun `matchesActualIdentifierForTest() doesn't match different workflow types`() {
    val id1 = TestWorkflow1.identifier
    val id2 = TestWorkflow2.identifier
    assertFalse(id1.matchesActualIdentifierForTest(id2))
  }

  @Test fun `matchesActualIdentifierForTest() matches subclass`() {
    val parentId = Parent::class.workflowIdentifier
    val childId = Child.identifier
    assertTrue(parentId.matchesActualIdentifierForTest(childId))
  }

  @Test fun `matchesActualIdentifierForTest() doesn't match superclass`() {
    val parentId = Parent::class.workflowIdentifier
    val childId = Child.identifier
    assertFalse(childId.matchesActualIdentifierForTest(parentId))
  }

  @Test
  fun `matchesActualIdentifierForTest() matches impostor identifiers with same proxied identifiers`() {
    val id1 = TestImpostor1(TestWorkflow1).identifier
    val id2 = TestImpostor1(TestWorkflow1).identifier
    assertTrue(id1.matchesActualIdentifierForTest(id2))
    assertTrue(id2.matchesActualIdentifierForTest(id1))
  }

  @Test
  fun `matchesActualIdentifierForTest() matches different impostor identifiers with same proxied identifier`() {
    val id1 = TestImpostor1(TestWorkflow1).identifier
    val id2 = TestImpostor2(TestWorkflow1).identifier
    assertTrue(id1.matchesActualIdentifierForTest(id2))
    assertTrue(id2.matchesActualIdentifierForTest(id1))
  }

  @Test
  fun `matchesActualIdentifierForTest() doesn't match impostor identifiers with different proxied identifiers`() {
    val id1 = TestImpostor1(TestWorkflow1).identifier
    val id2 = TestImpostor1(TestWorkflow2).identifier
    assertFalse(id1.matchesActualIdentifierForTest(id2))
    assertFalse(id2.matchesActualIdentifierForTest(id1))
  }

  private object TestWorkflow1 : Workflow<Nothing, Nothing, Nothing> {
    override fun asStatefulWorkflow(): StatefulWorkflow<Nothing, *, Nothing, Nothing> =
      throw NotImplementedError()
  }

  private object TestWorkflow2 : Workflow<Nothing, Nothing, Nothing> {
    override fun asStatefulWorkflow(): StatefulWorkflow<Nothing, *, Nothing, Nothing> =
      throw NotImplementedError()
  }

  private class TestImpostor1(
    private val proxied: Workflow<*, *, *>
  ) : Workflow<Nothing, Nothing, Nothing>, ImpostorWorkflow {
    override val realIdentifier: WorkflowIdentifier = proxied.identifier
    override fun describeRealIdentifier(): String? = "TestImpostor1(${proxied::class.simpleName})"
    override fun asStatefulWorkflow(): StatefulWorkflow<Nothing, *, Nothing, Nothing> =
      throw NotImplementedError()
  }

  private class TestImpostor2(
    proxied: Workflow<*, *, *>
  ) : Workflow<Nothing, Nothing, Nothing>, ImpostorWorkflow {
    override val realIdentifier: WorkflowIdentifier = proxied.identifier
    override fun asStatefulWorkflow(): StatefulWorkflow<Nothing, *, Nothing, Nothing> =
      throw NotImplementedError()
  }

  private class TestUnsnapshottableImpostor(
    type: KType
  ) : Workflow<Nothing, Nothing, Nothing>, ImpostorWorkflow {
    override val realIdentifier: WorkflowIdentifier = unsnapshottableIdentifier(type)
    override fun asStatefulWorkflow(): StatefulWorkflow<Nothing, *, Nothing, Nothing> =
      throw NotImplementedError()
  }

  private interface Parent : Workflow<Nothing, Nothing, Nothing>

  private object Child : Parent {
    override fun asStatefulWorkflow(): StatefulWorkflow<Nothing, *, Nothing, Nothing> =
      throw NotImplementedError()
  }
}
