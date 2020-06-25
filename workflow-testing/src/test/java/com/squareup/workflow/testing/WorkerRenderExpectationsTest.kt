package com.squareup.workflow.testing

import com.squareup.workflow.Worker
import com.squareup.workflow.Workflow
import com.squareup.workflow.WorkflowOutput
import com.squareup.workflow.action
import com.squareup.workflow.runningWorker
import com.squareup.workflow.stateless
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// TODO add more failure tests
@OptIn(ExperimentalStdlibApi::class)
class WorkerRenderExpectationsTest {

  @Test fun `expectAnyWorkerOutputting() works`() {
    val stringWorker = object : Worker<String> {
      override fun run(): Flow<String> = emptyFlow()
    }
    val intWorker = object : Worker<Int> {
      override fun run(): Flow<Int> = emptyFlow()
    }
    val workflow = Workflow.stateless<Unit, String, Unit> {
      runningWorker(stringWorker) { action { setOutput(it) } }
      runningWorker(intWorker) { action { setOutput(it.toString()) } }
    }

    // Exact string match
    workflow.testRender(Unit)
        .expectAnyWorkerOutputting(typeOf<String>())
        .render()
    workflow.testRender(Unit)
        .expectAnyWorkerOutputting(typeOf<String>(), output = WorkflowOutput("foo"))
        .render()
        .verifyActionResult { _, output -> assertEquals("foo", output?.value) }

    // Supertype match
    workflow.testRender(Unit)
        .expectAnyWorkerOutputting(typeOf<CharSequence>())
        .render()
    workflow.testRender(Unit)
        .expectAnyWorkerOutputting(typeOf<String>(), output = WorkflowOutput("foo"))
        .render()
        .verifyActionResult { _, output -> assertEquals("foo", output?.value) }

    // Other type match
    workflow.testRender(Unit)
        .expectAnyWorkerOutputting(typeOf<Int>())
        .render()
    workflow.testRender(Unit)
        .expectAnyWorkerOutputting(typeOf<Int>(), output = WorkflowOutput(42))
        .render()
        .verifyActionResult { _, output -> assertEquals("42", output?.value) }
  }

  @Test fun `expectWorker(worker) works`() {
    val stringWorker = object : Worker<String> {
      override fun run(): Flow<String> = emptyFlow()
    }
    val intWorker = object : Worker<Int> {
      override fun run(): Flow<Int> = emptyFlow()
    }
    val workflow = Workflow.stateless<Unit, String, Unit> {
      runningWorker(stringWorker) { action { setOutput(it) } }
      runningWorker(intWorker) { action { setOutput(it.toString()) } }
    }

    // Exact string match
    workflow.testRender(Unit)
        .expectWorker(stringWorker)
        .render()
    workflow.testRender(Unit)
        .expectWorker(stringWorker, output = WorkflowOutput("foo"))
        .render()
        .verifyActionResult { _, output -> assertEquals("foo", output?.value) }

    // Other type match
    workflow.testRender(Unit)
        .expectWorker(intWorker)
        .render()
    workflow.testRender(Unit)
        .expectWorker(intWorker, output = WorkflowOutput(42))
        .render()
        .verifyActionResult { _, output -> assertEquals("42", output?.value) }
  }

  @Test fun `expectWorker(worker class, type args) works`() {
    abstract class EmptyWorkerCovariant<out T> : Worker<T> {
      final override fun run(): Flow<T> = emptyFlow()
    }

    abstract class EmptyWorker<T> : EmptyWorkerCovariant<T>()

    class EmptyStringWorker : EmptyWorker<String>()
    class EmptyIntWorker : EmptyWorker<Int>()

    val workflow = Workflow.stateless<Unit, String, Unit> {
      runningWorker(EmptyStringWorker()) { action { setOutput(it) } }
      runningWorker(EmptyIntWorker()) { action { setOutput(it.toString()) } }
    }

    // Exact string match
    workflow.testRender(Unit)
        .expectWorker(EmptyStringWorker::class)
        .render()
    workflow.testRender(Unit)
        .expectWorker(EmptyStringWorker::class, output = WorkflowOutput("foo"))
        .render()
        .verifyActionResult { _, output -> assertEquals("foo", output?.value) }

    // Supertype match without type args
    workflow.testRender(Unit)
        .expectWorker(EmptyWorker::class)
        .render()
    workflow.testRender(Unit)
        .expectWorker(EmptyWorker::class, output = WorkflowOutput("foo"))
        .render()
        .verifyActionResult { _, output -> assertEquals("foo", output?.value) }

    // Invariant supertype match with exact type args
    workflow.testRender(Unit)
        .expectWorker(EmptyWorker::class, listOf(typeOf<String>()))
        .render()
    workflow.testRender(Unit)
        .expectWorker(EmptyWorker::class, listOf(typeOf<String>()), output = WorkflowOutput("foo"))
        .render()
        .verifyActionResult { _, output -> assertEquals("foo", output?.value) }

    // Invariant supertype match with supertype args
    workflow.testRender(Unit)
        .expectWorker(EmptyWorker::class, listOf(typeOf<CharSequence>()))
        .expectWorker(EmptyIntWorker::class)
        .disallowUnexpectedChildren()
        .apply {
          val error = assertFailsWith<AssertionError> { render() }
          assertEquals(
              "Tried to render unexpected child worker ${typeOf<EmptyStringWorker>()}",
              error.message
          )
        }

    // Covariant supertype match with exact type args
    workflow.testRender(Unit)
        .expectWorker(EmptyWorkerCovariant::class, listOf(typeOf<String>()))
        .render()
    workflow.testRender(Unit)
        .expectWorker(
            EmptyWorkerCovariant::class, listOf(typeOf<String>()), output = WorkflowOutput("foo")
        )
        .render()
        .verifyActionResult { _, output -> assertEquals("foo", output?.value) }

    // Covariant supertype match with supertype args
    workflow.testRender(Unit)
        .expectWorker(EmptyWorkerCovariant::class, listOf(typeOf<CharSequence>()))
        .render()
    workflow.testRender(Unit)
        .expectWorker(
            EmptyWorkerCovariant::class, listOf(typeOf<CharSequence>()),
            output = WorkflowOutput("foo")
        )
        .render()
        .verifyActionResult { _, output -> assertEquals("foo", output?.value) }

    // Other type match
    workflow.testRender(Unit)
        .expectWorker(EmptyIntWorker::class)
        .render()
    workflow.testRender(Unit)
        .expectWorker(EmptyIntWorker::class, output = WorkflowOutput(42))
        .render()
        .verifyActionResult { _, output -> assertEquals("42", output?.value) }
  }

  @Test fun `expectWorker(worker KType) works`() {
    abstract class EmptyWorkerCovariant<out T> : Worker<T> {
      final override fun run(): Flow<T> = emptyFlow()
    }

    abstract class EmptyWorker<T> : EmptyWorkerCovariant<T>()

    class EmptyStringWorker : EmptyWorker<String>()
    class EmptyIntWorker : EmptyWorker<Int>()

    val workflow = Workflow.stateless<Unit, String, Unit> {
      runningWorker(EmptyStringWorker()) { action { setOutput(it) } }
      runningWorker(EmptyIntWorker()) { action { setOutput(it.toString()) } }
    }

    // Exact string match
    workflow.testRender(Unit)
        .expectWorker(typeOf<EmptyStringWorker>())
        .render()
    workflow.testRender(Unit)
        .expectWorker(typeOf<EmptyStringWorker>(), output = WorkflowOutput("foo"))
        .render()
        .verifyActionResult { _, output -> assertEquals("foo", output?.value) }

    // Supertype match without type args
    workflow.testRender(Unit)
        .expectWorker(typeOf<EmptyWorker<*>>())
        .render()
    workflow.testRender(Unit)
        .expectWorker(typeOf<EmptyWorker<*>>(), output = WorkflowOutput("foo"))
        .render()
        .verifyActionResult { _, output -> assertEquals("foo", output?.value) }

    // Invariant supertype match with exact type args
    workflow.testRender(Unit)
        .expectWorker(typeOf<EmptyWorker<String>>())
        .render()
    workflow.testRender(Unit)
        .expectWorker(typeOf<EmptyWorker<String>>(), output = WorkflowOutput("foo"))
        .render()
        .verifyActionResult { _, output -> assertEquals("foo", output?.value) }

    // Invariant supertype match with supertype args
    workflow.testRender(Unit)
        .expectWorker(typeOf<EmptyWorker<CharSequence>>())
        .expectWorker(EmptyIntWorker::class)
        .disallowUnexpectedChildren()
        .apply {
          val error = assertFailsWith<AssertionError> { render() }
          assertEquals(
              "Tried to render unexpected child worker ${typeOf<EmptyStringWorker>()}",
              error.message
          )
        }

    // Covariant supertype match with exact type args
    workflow.testRender(Unit)
        .expectWorker(typeOf<EmptyWorkerCovariant<String>>())
        .render()
    workflow.testRender(Unit)
        .expectWorker(typeOf<EmptyWorkerCovariant<String>>(), output = WorkflowOutput("foo"))
        .render()
        .verifyActionResult { _, output -> assertEquals("foo", output?.value) }

    // Covariant supertype match with supertype args
    workflow.testRender(Unit)
        .expectWorker(typeOf<EmptyWorkerCovariant<CharSequence>>())
        .render()
    workflow.testRender(Unit)
        .expectWorker(typeOf<EmptyWorkerCovariant<CharSequence>>(), output = WorkflowOutput("foo"))
        .render()
        .verifyActionResult { _, output -> assertEquals("foo", output?.value) }

    // Other type match
    workflow.testRender(Unit)
        .expectWorker(typeOf<EmptyIntWorker>())
        .render()
    workflow.testRender(Unit)
        .expectWorker(typeOf<EmptyIntWorker>(), output = WorkflowOutput(42))
        .render()
        .verifyActionResult { _, output -> assertEquals("42", output?.value) }
  }
}
