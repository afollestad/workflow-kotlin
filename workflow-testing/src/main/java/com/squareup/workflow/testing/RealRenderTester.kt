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
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.isSuperclassOf
import kotlin.reflect.full.isSupertypeOf

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

    data class ExpectedWorkflow<OutputT, RenderingT>(
      val identifier: WorkflowIdentifier,
      val key: String,
      val assertProps: (props: Any?) -> Unit,
      val rendering: RenderingT,
      override val output: WorkflowOutput<OutputT>?,
      val description: String
    ) : Expectation<OutputT>() {
      override fun describe(): String = description.ifBlank {
        "workflow " +
            "identifier=$identifier, " +
            "key=$key, " +
            "rendering=$rendering, " +
            "output=$output"
      }
    }

    data class ExpectedWorker<out OutputT>(
      val matchesWhen: (otherWorker: Worker<*>) -> Boolean,
      val key: String,
      override val output: WorkflowOutput<OutputT>?,
      val description: String
    ) : Expectation<OutputT>() {
      override fun describe(): String = description.ifBlank { "worker key=$key, output=$output" }
    }

    data class ExpectedSideEffect(val key: String) : Expectation<Nothing>() {
      override fun describe(): String = "side effect with key \"$key\""
    }
  }

  private var allowUnexpectedChildren = true

  override val actionSink: Sink<WorkflowAction<PropsT, StateT, OutputT>> get() = this

  override fun <ChildOutputT, ChildRenderingT> expectWorkflow(
    identifier: WorkflowIdentifier,
    rendering: ChildRenderingT,
    key: String,
    assertProps: (props: Any?) -> Unit,
    output: WorkflowOutput<ChildOutputT>?,
    description: String
  ): RenderTester<PropsT, StateT, OutputT, RenderingT> {
    val expectedWorkflow =
      ExpectedWorkflow(identifier, key, assertProps, rendering, output, description)
    if (output != null) {
      checkNoOutputs(expectedWorkflow)
      childWillEmitOutput = true
    }
    expectations += expectedWorkflow
    return this
  }

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

  override fun expectSideEffect(key: String): RenderTester<PropsT, StateT, OutputT, RenderingT> {
    if (expectations.any { it is ExpectedSideEffect && it.key == key }) {
      throw AssertionError("Already expecting side effect with key \"$key\".")
    }
    expectations += ExpectedSideEffect(key)
    return this
  }

  // TODO unit tests
  override fun disallowUnexpectedChildren(): RenderTester<PropsT, StateT, OutputT, RenderingT> {
    allowUnexpectedChildren = false
    return this
  }

  override fun render(block: (RenderingT) -> Unit): RenderTestResult<PropsT, StateT, OutputT> {
    // Clone the expectations to run a "dry" render pass.
    val noopContext = deepCloneForRender()
    workflow.render(props, state, noopContext)

    workflow.render(props, state, this)
        .also(block)

    // Ensure all expected children ran.
    if (expectations.isNotEmpty()) {
      throw AssertionError(
          "Expected ${expectations.size} more workflows, workers, or side effects to be ran:\n" +
              expectations.joinToString(separator = "\n") { "  ${it.describe()}" }
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
    val expected = consumeExpectedChildWorkflow(
        hasUnitRenderingType = child.hasUnitRenderingType(),
        predicate = { expectation ->
          child.identifier.realTypeMatchesExpectation(expectation.identifier) &&
              expectation.key == key
        },
        description = {
          buildString {
            append("child ")
            append(child.identifier.describeRealIdentifier() ?: "workflow ${child.identifier}")
            if (key.isNotEmpty()) {
              append(" with key \"$key\"")
            }
          }
        }
    )

    expected.assertProps(props)

    if (expected.output != null) {
      check(processedAction == null)
      @Suppress("UNCHECKED_CAST")
      processedAction = handler(expected.output.value as ChildOutputT)
    }

    @Suppress("UNCHECKED_CAST")
    return expected.rendering as ChildRenderingT
  }

  override fun <T> runningWorker(
    worker: Worker<T>,
    key: String,
    handler: (T) -> WorkflowAction<PropsT, StateT, OutputT>
  ) {
    val expected = consumeExpectedWorker<ExpectedWorker<*>>(
        predicate = { it.matchesWhen(worker) && it.key == key },
        description = {
          "worker $worker" +
              key.takeUnless { it.isEmpty() }
                  ?.let { " with key \"$it\"" }
                  .orEmpty()
        }
    )

    if (expected?.output != null) {
      check(processedAction == null)
      @Suppress("UNCHECKED_CAST")
      processedAction = handler(expected.output.value as T)
    }
  }

  override fun runningSideEffect(
    key: String,
    sideEffect: suspend () -> Unit
  ) {
    val description = "sideEffect with key \"$key\""

    val matchedExpectations = expectations.filterIsInstance<ExpectedSideEffect>()
        .filter { it.key == key }
    when {
      matchedExpectations.isEmpty() && !allowUnexpectedChildren -> throw AssertionError(
          "Tried to run unexpected $description"
      )
      matchedExpectations.size == 1 -> {
        val expected = matchedExpectations[0]
        // Move the side effect to the consumed list.
        expectations -= expected
        consumedExpectations += expected
      }
      else -> throw AssertionError(
          "Multiple side effects matched $description:\n" +
              matchedExpectations.joinToString(separator = "\n") { "  ${it.describe()}" }
      )
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

  @OptIn(ExperimentalStdlibApi::class)
  private fun consumeExpectedChildWorkflow(
    hasUnitRenderingType: Boolean,
    predicate: (ExpectedWorkflow<*, *>) -> Boolean,
    description: () -> String
  ): ChildRenderResult {
    val matchedExpectations = expectations.filterIsInstance<ExpectedWorkflow<*, *>>()
        .filter(predicate)

    return when {
      matchedExpectations.isEmpty() && hasUnitRenderingType && allowUnexpectedChildren -> {
        // Unmatched workflows can never have outputs.
        ChildRenderResult(rendering = Unit, output = null, assertProps = {})
      }
      matchedExpectations.isEmpty() -> {
        throw AssertionError("Tried to render unexpected ${description()}")
      }
      matchedExpectations.size == 1 -> {
        matchedExpectations.single()
            .let {
              // Move the workflow to the consumed list.
              expectations -= it
              consumedExpectations += it

              ChildRenderResult(it.output, it.assertProps, it.rendering)
            }
      }
      else -> {
        throw AssertionError(
            "Multiple workflows matched ${description()}:\n" +
                matchedExpectations.joinToString(separator = "\n") { "  ${it.describe()}" }
        )
      }
    }
  }

  private inline fun <reified T : ExpectedWorker<*>> consumeExpectedWorker(
    predicate: (T) -> Boolean,
    description: () -> String
  ): T? {
    val matchedExpectations = expectations.filterIsInstance<T>()
        .filter(predicate)
    return when (matchedExpectations.size) {
      0 -> if (allowUnexpectedChildren) null else throw AssertionError(
          "Tried to run unexpected ${description()}"
      )
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

  /**
   * Represents the result of rendering a child workflow, either because an [ExpectedWorkflow]
   * matched or the workflow had a Unit rendering and [disallowUnexpectedChildren] was never called.
   */
  private data class ChildRenderResult(
    val output: WorkflowOutput<Any?>?,
    val assertProps: (props: Any?) -> Unit,
    val rendering: Any?
  )
}

private fun KType.visitTypeArgs() {
  arguments.forEach { arg ->
    println("${arg.variance} ${arg.type}")
  }
  (classifier as? KClass<*>)?.supertypes?.forEach { it.visitTypeArgs() }
}

private fun KClass<*>.findWorkflowSubclass(): Any {
  var currentClass = java
  while (currentClass.genericInterfaces.none { "com.squareup.workflow.Workflow" in it.typeName }) {
    currentClass =
  }
}

// TODO unit tests
internal fun Workflow<*, *, *>.hasUnitRenderingType(): Boolean {
  // Can't use Kotlin reflection because of what seems to be a bug when the class is anonymous.
//  val thisClass = asStatefulWorkflow()::class.java
  val workflowClass = this::class.java
//  val allSupertypes = workflowClass.allSupertypes().toList()
//  val allTypeArgs = allSupertypes.flatMap { it.arguments }.map { "${it.variance} ${it.type}" }
//  val typeParams = workflowClass.typeParameters
//  workflowClass.supertypes.forEach { it.visitTypeArgs() }
  // Multiple java methods may correspond to a single kotlin method.
//  val renderMethods = thisClass.methods.filter { it.name == "render" }
//  return renderMethods.any { it.returnType == Void.TYPE }
  return false
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
