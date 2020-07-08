/*
 * Copyright 2019 Square Inc.
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
package com.squareup.workflow.testing

import com.squareup.workflow.ExperimentalWorkflowApi
import com.squareup.workflow.RenderContext
import com.squareup.workflow.Sink
import com.squareup.workflow.StatefulWorkflow
import com.squareup.workflow.Worker
import com.squareup.workflow.Workflow
import com.squareup.workflow.WorkflowAction
import com.squareup.workflow.WorkflowAction.Companion.noAction
import com.squareup.workflow.WorkflowIdentifier
import com.squareup.workflow.WorkflowOutput
import com.squareup.workflow.applyTo
import com.squareup.workflow.identifier
import com.squareup.workflow.testing.RealRenderTester.Expectation.ExpectedSideEffect
import com.squareup.workflow.testing.RealRenderTester.Expectation.ExpectedWorker
import com.squareup.workflow.testing.RealRenderTester.Expectation.ExpectedWorkflow
import com.squareup.workflow.testing.RenderTester.ChildWorkflowMatch
import com.squareup.workflow.testing.RenderTester.ChildWorkflowMatch.Matched
import com.squareup.workflow.testing.RenderTester.RenderChildInvocation
import com.squareup.workflow.testing.RenderTester.SideEffectMatch
import com.squareup.workflow.testing.RenderTester.SideEffectMatch.NOT_MATCHED
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.allSupertypes
import kotlin.reflect.full.isSuperclassOf
import kotlin.reflect.full.isSupertypeOf

private const val WORKFLOW_INTERFACE_NAME = "com.squareup.workflow.Workflow"

@OptIn(ExperimentalWorkflowApi::class)
internal class RealRenderTester<PropsT, StateT, OutputT, RenderingT>(
  private val workflow: StatefulWorkflow<PropsT, StateT, OutputT, RenderingT>,
  private val props: PropsT,
  private val state: StateT,
  private val expectations: MutableList<Expectation<*>> = mutableListOf(),
  private val consumedExpectations: MutableList<Expectation<*>> = mutableListOf(),
  private var childWillEmitOutput: Boolean = false,
  private var processedAction: WorkflowAction<PropsT, StateT, OutputT>? = null
) : RenderTester<PropsT, StateT, OutputT, RenderingT>,
    RenderContext<PropsT, StateT, OutputT>,
    RenderTestResult<PropsT, StateT, OutputT>,
    Sink<WorkflowAction<PropsT, StateT, OutputT>> {

  internal sealed class Expectation<out OutputT> {
    abstract fun describe(): String

    open val output: WorkflowOutput<OutputT>? = null

    data class ExpectedWorkflow(
      val matcher: (RenderChildInvocation) -> ChildWorkflowMatch,
      val exactMatch: Boolean,
      val description: String
    ) : Expectation<Any?>() {
      override fun describe(): String = description
    }

    data class ExpectedWorker<out OutputT>(
      val matchesWhen: (otherWorker: Worker<*>) -> Boolean,
      val key: String,
      override val output: WorkflowOutput<OutputT>?,
      val description: String
    ) : Expectation<OutputT>() {
      override fun describe(): String = description.ifBlank { "worker key=$key, output=$output" }
    }

    data class ExpectedSideEffect(
      val matcher: (String) -> SideEffectMatch,
      val exactMatch: Boolean,
      val description: String
    ) : Expectation<Nothing>() {
      override fun describe(): String = description
    }
  }

  override val actionSink: Sink<WorkflowAction<PropsT, StateT, OutputT>> get() = this

  override fun expectWorkflow(
    description: String,
    exactMatch: Boolean,
    predicate: (RenderChildInvocation) -> ChildWorkflowMatch
  ): RenderTester<PropsT, StateT, OutputT, RenderingT> = apply {
    expectations += ExpectedWorkflow(predicate, exactMatch, description)
  }

//  override fun <ChildOutputT, ChildRenderingT> expectWorkflow(
//    identifier: WorkflowIdentifier,
//    rendering: ChildRenderingT,
//    key: String,
//    assertProps: (props: Any?) -> Unit,
//    output: WorkflowOutput<ChildOutputT>?,
//    description: String
//  ): RenderTester<PropsT, StateT, OutputT, RenderingT> {
//    val expectedWorkflow =
//      ExpectedWorkflow(identifier, key, assertProps, rendering, output, description)
//    if (output != null) {
//      checkNoOutputs(expectedWorkflow)
//      childWillEmitOutput = true
//    }
//    expectations += expectedWorkflow
//    return this
//  }

  override fun expectWorker(
    matchesWhen: (otherWorker: Worker<*>) -> Boolean,
    key: String,
    output: WorkflowOutput<Any?>?,
    description: String
  ): RenderTester<PropsT, StateT, OutputT, RenderingT> {
    val expectedWorker = ExpectedWorker(matchesWhen, key, output, description)
    if (output != null) {
      checkNoOutputs(expectedWorker)
      childWillEmitOutput = true
    }
    expectations += expectedWorker
    return this
  }

//  override fun expectSideEffect(key: String): RenderTester<PropsT, StateT, OutputT, RenderingT> {
//    if (expectations.any { it is ExpectedSideEffect && it.key == key }) {
//      throw AssertionError("Already expecting side effect with key \"$key\".")
//    }
//    expectations += ExpectedSideEffect(key)
//    return this
//  }

  override fun expectSideEffect(
    description: String,
    exactMatch: Boolean,
    matcher: (key: String) -> SideEffectMatch
  ): RenderTester<PropsT, StateT, OutputT, RenderingT> = apply {
    expectations += ExpectedSideEffect(matcher, exactMatch, description)
  }

  override fun render(block: (RenderingT) -> Unit): RenderTestResult<PropsT, StateT, OutputT> {
    // Clone the expectations to run a "dry" render pass.
    val noopContext = deepCloneForRender()
    workflow.render(props, state, noopContext)

    workflow.render(props, state, this)
        .also(block)

    // Ensure all exact matches were consumed.
    val unconsumedExactMatches = expectations.filter {
      when (it) {
        is ExpectedWorkflow -> it.exactMatch
        // Workers are always exact matches.
        is ExpectedWorker -> true
        is ExpectedSideEffect -> it.exactMatch
      }
    }
    if (unconsumedExactMatches.isNotEmpty()) {
      throw AssertionError(
          "Expected ${unconsumedExactMatches.size} more workflows, workers, or side effects to be ran:\n" +
              unconsumedExactMatches.joinToString(separator = "\n") { "  ${it.describe()}" }
      )
    }

    return this
  }

  override fun <ChildPropsT, ChildOutputT, ChildRenderingT> renderChild(
    child: Workflow<ChildPropsT, ChildOutputT, ChildRenderingT>,
    props: ChildPropsT,
    key: String,
    handler: (ChildOutputT) -> WorkflowAction<PropsT, StateT, OutputT>
  ): ChildRenderingT {
    val description = buildString {
      append("child ")
      append(child.identifier.describeRealIdentifier() ?: "workflow ${child.identifier}")
      if (key.isNotEmpty()) {
        append(" with key \"$key\"")
      }
    }
    val invocation = createRenderChildInvocation(child, props, key)
    val matches = expectations.filterIsInstance<ExpectedWorkflow>()
        .mapNotNull {
          val matchResult = it.matcher(invocation)
          if (matchResult is Matched) Pair(it, matchResult) else null
        }
    if (matches.isEmpty()) {
      throw AssertionError("Tried to render unexpected $description")
    }

    val exactMatches = matches.filter { it.first.exactMatch }
    val (_, match) = when {
      exactMatches.size == 1 -> {
        exactMatches.single()
            .also { (expected, _) ->
              expectations -= expected
              consumedExpectations += expected
            }
      }
      exactMatches.size > 1 -> {
        throw AssertionError(
            "Multiple workflows matched ${description}:\n" +
                exactMatches.joinToString(separator = "\n") { "  ${it.first.describe()}" }
        )
      }
      // Inexact matches are not consumable.
      else -> matches.first()
    }

    if (match.output != null) {
      check(processedAction == null) {
        "Expected only one output to be expected: $description expected to emit " +
            "${match.output.value} but $processedAction was already processed."
      }
      @Suppress("UNCHECKED_CAST")
      processedAction = handler(match.output.value as ChildOutputT)
    }

    @Suppress("UNCHECKED_CAST")
    return match.childRendering as ChildRenderingT
  }

  override fun <T> runningWorker(
    worker: Worker<T>,
    key: String,
    handler: (T) -> WorkflowAction<PropsT, StateT, OutputT>
  ) {
    val description = "worker $worker" +
        key.takeUnless { it.isEmpty() }
            ?.let { " with key \"$it\"" }
            .orEmpty()
    val expected = consumeExpectedWorker<ExpectedWorker<*>>(
        predicate = { it.matchesWhen(worker) && it.key == key },
        description = { description }
    )

    if (expected?.output != null) {
      check(processedAction == null) {
        "Expected only one output to be expected: $description expected to emit " +
            "${expected.output.value} but $processedAction was already processed."
      }
      @Suppress("UNCHECKED_CAST")
      processedAction = handler(expected.output.value as T)
    }
  }

  override fun runningSideEffect(
    key: String,
    sideEffect: suspend () -> Unit
  ) {
    val description = "sideEffect with key \"$key\""

    val matches = expectations.filterIsInstance<ExpectedSideEffect>()
        .mapNotNull {
          val matchResult = it.matcher(key)
          if (matchResult == NOT_MATCHED) null else Pair(it, matchResult)
        }
    if (matches.isEmpty()) {
      throw AssertionError("Tried to run unexpected $description")
    }
    val exactMatches = matches.filter { it.first.exactMatch }

    if (exactMatches.size > 1) {
      throw AssertionError(
          "Multiple side effects matched $description:\n" +
              matches.joinToString(separator = "\n") { "  ${it.first.describe()}" }
      )
    }

    // Inexact matches are not consumable.
    exactMatches.singleOrNull()
        ?.let { (expected, _) ->
          expectations -= expected
          consumedExpectations += expected
        }
  }

  override fun send(value: WorkflowAction<PropsT, StateT, OutputT>) {
    checkNoOutputs()
    check(processedAction == null) {
      "Tried to send action to sink after another action was already processed:\n" +
          "  processed action=$processedAction\n" +
          "  attempted action=$value"
    }
    processedAction = value
  }

  override fun verifyAction(block: (WorkflowAction<PropsT, StateT, OutputT>) -> Unit) {
    val action = processedAction ?: noAction()
    block(action)
  }

  override fun verifyActionResult(block: (newState: StateT, output: WorkflowOutput<OutputT>?) -> Unit) {
    verifyAction {
      val (state, output) = it.applyTo(props, state)
      block(state, output)
    }
  }

  private inline fun <reified T : ExpectedWorker<*>> consumeExpectedWorker(
    predicate: (T) -> Boolean,
    description: () -> String
  ): T? {
    val matchedExpectations = expectations.filterIsInstance<T>()
        .filter(predicate)
    return when (matchedExpectations.size) {
      0 -> null
      1 -> {
        val expected = matchedExpectations[0]
        // Move the worker to the consumed list.
        expectations -= expected
        consumedExpectations += expected
        expected
      }
      else -> throw AssertionError(
          "Multiple workers matched ${description()}:\n" +
              matchedExpectations.joinToString(separator = "\n") { "  ${it.describe()}" }
      )
    }
  }

  private fun deepCloneForRender(): RenderContext<PropsT, StateT, OutputT> = RealRenderTester(
      workflow, props, state,
      // Copy the list of expectations since it's mutable.
      expectations = ArrayList(expectations),
      // Don't care about consumed expectations.
      childWillEmitOutput = childWillEmitOutput,
      processedAction = processedAction
  )

  private fun checkNoOutputs(newExpectation: Expectation<*>? = null) {
    check(!childWillEmitOutput) {
      val expectationsWithOutputs = (expectations + listOfNotNull(newExpectation))
          .filter { it.output != null }
      "Expected only one child to emit an output:\n" +
          expectationsWithOutputs.joinToString(separator = "\n") { "  $it" }
    }
  }
}

//private fun KType.visitTypeArgs() {
//  arguments.forEach { arg ->
//    println("${arg.variance} ${arg.type}")
//  }
//  (classifier as? KClass<*>)?.supertypes?.forEach { it.visitTypeArgs() }
//}

//private fun KClass<*>.findWorkflowSubclass(): Any {
//  var currentClass = java
//  while (currentClass.genericInterfaces.none { "com.squareup.workflow.Workflow" in it.typeName }) {
//    currentClass =
//  }
//}

///**
// * Returns true if this workflow has the `RenderingT` type of [Unit].
// *
// * Doesn't support anonymous classes/objects created by inline functions due to
// * https://youtrack.jetbrains.com/issue/KT-17103.
// */
//@OptIn(ExperimentalStdlibApi::class)
//internal fun Workflow<*, *, *>.hasUnitRenderingType(): Boolean {
//  val workflowClass = this::class
//  val workflowInterfaceType = workflowClass.allSupertypes
//      .singleOrNull {
//        (it.classifier as? KClass<*>)?.let { superclass ->
//          // TODO factor into constant
//          superclass.qualifiedName == "com.squareup.workflow.Workflow"
//        } ?: false
//      } ?: return false
//  println("All supertypes: $workflowInterfaceType")
//  println("Arguments:")
//  workflowInterfaceType.arguments.forEach {
//    println(it)
//  }
//  val renderingArgument = workflowInterfaceType.arguments.last()
//  return renderingArgument.type == typeOf<Unit>()
//}

internal fun createRenderChildInvocation(
  workflow: Workflow<*, *, *>,
  props: Any?,
  renderKey: String
): RenderChildInvocation {
  val workflowClass = workflow::class

  // Get the KType of the Workflow interface with the type parameters specified by this workflow
  // instance.
  val workflowInterfaceType = workflowClass.allSupertypes
      .single { type ->
        (type.classifier as? KClass<*>)
            ?.let { it.qualifiedName == WORKFLOW_INTERFACE_NAME }
            ?: false
      }

//  println("All supertypes: $workflowInterfaceType")
//  println("Arguments:")
//  workflowInterfaceType.arguments.forEach {
//    println(it)
//  }

  check(workflowInterfaceType.arguments.size == 3)
  val (_, outputType, renderingType) = workflowInterfaceType.arguments
  return RenderChildInvocation(workflow, props, outputType, renderingType, renderKey)
}

/**
 * Returns true iff this identifier's [WorkflowIdentifier.getRealIdentifierType] is the same type as
 * or a subtype of [expected]'s.
 */
@OptIn(ExperimentalWorkflowApi::class)
internal fun WorkflowIdentifier.realTypeMatchesExpectation(
  expected: WorkflowIdentifier
): Boolean {
  val expectedType = expected.getRealIdentifierType()
  val actualType = getRealIdentifierType()
  return when {
    expectedType is KType && actualType is KType -> {
      expectedType.isSupertypeOf(actualType)
    }
    expectedType is KClass<*> && actualType is KClass<*> -> {
      expectedType.isSuperclassOf(actualType)
    }
    else -> {
      error(
          "Expected WorkflowIdentifier type to be KType or KClass: " +
              "actual: $actualType, expected: $expectedType"
      )
    }
  }
}
