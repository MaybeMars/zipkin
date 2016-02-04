package com.twitter.zipkin.storage

import com.twitter.util.Await.result
import com.twitter.util.{Duration, Time}
import com.twitter.zipkin.Constants
import com.twitter.zipkin.adjuster.ApplyTimestampAndDuration
import com.twitter.zipkin.common.{Dependencies, DependencyLink, _}
import org.junit.{Before, Test}
import org.scalatest.Matchers
import org.scalatest.junit.JUnitSuite
import java.util.concurrent.TimeUnit._

/**
 * Base test for {@link DependencyStore} implementations. Subtypes should create a
 * connection to a real backend, even if that backend is in-process.
 *
 * <p/> This is JUnit-based to allow overriding tests and use of annotations
 * such as {@link org.junit.Ignore} and {@link org.junit.ClassRule}.
 */
abstract class DependencyStoreSpec extends JUnitSuite with Matchers {

  /** Notably, the cassandra implementation has day granularity */
  val day = MILLISECONDS.convert(1, DAYS)
  // Use real time, as most span-stores have TTL logic which looks back several days.
  val today = Time.now.floor(Duration.fromMilliseconds(day)).inMillis

  val zipkinWeb = Endpoint(172 << 24 | 17 << 16 | 3, 8080, "zipkin-web")
  val zipkinQuery = Endpoint(172 << 24 | 17 << 16 | 2, 9411, "zipkin-query")
  val zipkinJdbc = Endpoint(172 << 24 | 17 << 16 | 2, 0, "zipkin-jdbc")

  val trace = ApplyTimestampAndDuration(List(
    Span(1L, "get", 1L,
      annotations = List(
        Annotation(today * 1000, Constants.ServerRecv, Some(zipkinWeb)),
        Annotation((today + 350) * 1000, Constants.ServerSend, Some(zipkinWeb)))),
    Span(1L, "get", 2L, Some(1L),
      annotations = List(
        Annotation((today + 50) * 1000, Constants.ClientSend, Some(zipkinWeb)),
        Annotation((today + 100) * 1000, Constants.ServerRecv, Some(zipkinQuery.copy(port = 0))),
        Annotation((today + 250) * 1000, Constants.ServerSend, Some(zipkinQuery.copy(port = 0))),
        Annotation((today + 300) * 1000, Constants.ClientRecv, Some(zipkinWeb))),
      binaryAnnotations = List(
        BinaryAnnotation(Constants.ClientAddr, true, Some(zipkinWeb)),
        BinaryAnnotation(Constants.ServerAddr, true, Some(zipkinQuery)))),
    Span(1L, "query", 3L, Some(2L),
      annotations = List(
        Annotation((today + 150) * 1000, Constants.ClientSend, Some(zipkinQuery)),
        Annotation((today + 200) * 1000, Constants.ClientRecv, Some(zipkinQuery))),
      binaryAnnotations = List(
        BinaryAnnotation(Constants.ClientAddr, true, Some(zipkinQuery)),
        BinaryAnnotation(Constants.ServerAddr, true, Some(zipkinJdbc))))
  ))

  val dep = new Dependencies(today, today + 1000, List(
    new DependencyLink("zipkin-web", "zipkin-query", 1),
    new DependencyLink("zipkin-query", "zipkin-jdbc", 1)
  ))

  /**
   * Should maintain state between multiple calls within a test. Usually
   * implemented as a lazy.
   */
  def store: DependencyStore

  /** Clears the span store between tests. */
  def clear

  @Before def before() = clear

  def processDependencies(spans: List[Span])

  /**
   * Normally, the root-span is where trace id == span id and parent id == null.
   * The default is to look back one day from today.
   */
  @Test def getDependencies() = {
    processDependencies(trace)

    result(store.getDependencies(today + 1000)) should contain theSameElementsAs(dep.links)
  }

  /** Edge-case when there are no spans, or instrumentation isn't logging annotations properly. */
  @Test def getDependencies_empty() = {
    result(store.getDependencies(today + 1000)) should be(empty)
  }

  /**
   * Trace id is not required to be a span id. For example, some instrumentation may create separate
   * trace ids to help with collisions, or to encode information about the origin. This test makes
   * sure we don't rely on the trace id = root span id convention.
   */
  @Test def getDependencies_traceIdIsOpaque() = {
    processDependencies(trace.map(_.copy(traceId = Long.MaxValue)))

    result(store.getDependencies(today + 1000)) should contain theSameElementsAs(dep.links)
  }

  /**
   * When all servers are instrumented, they all log a "sr" annotation, indicating the service.
   */
  @Test def getDependenciesAllInstrumented() = {
    val one = Endpoint(127 << 24 | 1, 9410, "trace-producer-one")
    val two = Endpoint(127 << 24 | 2, 9410, "trace-producer-two")
    val three = Endpoint(127 << 24 | 3, 9410, "trace-producer-three")

    val trace = List(
      Span(10L, "get", 10L, None, Some(1445136539256150L), Some(1152579L), List(
        Annotation(1445136539256150L, Constants.ServerRecv, Some(one)),
        Annotation(1445136540408729L, Constants.ServerSend, Some(one)))),
      Span(10L, "get", 20L, Some(10L), Some(1445136539764798L), Some(639337L), List(
        Annotation(1445136539764798L, Constants.ClientSend, Some(one.copy(port = 3001))),
        Annotation(1445136539816432L, Constants.ServerRecv, Some(two)),
        Annotation(1445136540401414L, Constants.ServerSend, Some(two)),
        Annotation(1445136540404135L, Constants.ClientRecv, Some(one.copy(port = 3001))))),
      Span(10L, "query", 30L, Some(20L), Some(1445136540025751L), Some(371298L), List(
        Annotation(1445136540025751L, Constants.ClientSend, Some(two.copy(port = 3002))),
        Annotation(1445136540072846L, Constants.ServerRecv, Some(three)),
        Annotation(1445136540394644L, Constants.ServerSend, Some(three)),
        Annotation(1445136540397049L, Constants.ClientRecv, Some(two.copy(port = 3002)))))
    )
    processDependencies(trace)

    val traceDuration = Trace.duration(trace).get
    result(store.getDependencies(
      (trace(0).timestamp.get + traceDuration) / 1000,
      Some(traceDuration / 1000)
    )) should contain theSameElementsAs(
      List(
        new DependencyLink("trace-producer-one", "trace-producer-two", 1),
        new DependencyLink("trace-producer-two", "trace-producer-three", 1)
      )
    )
  }

  /**
   * The primary annotation used in the dependency graph is [[Constants.ServerRecv]]
   */
  @Test def getDependenciesMultiLevel() = {
    processDependencies(trace)

    result(store.getDependencies(today + 1000)) should contain theSameElementsAs(dep.links)
  }

  @Test def dependencies_loopback {
    val traceWithLoopback = List(
      trace(0),
      trace(1).copy(annotations = trace(1).annotations.map(a => a.copy(host = Some(zipkinWeb))),
                    binaryAnnotations = List.empty)
    )

    processDependencies(traceWithLoopback)

    result(store.getDependencies(today + 1000)) should contain theSameElementsAs(Dependencies.toLinks(traceWithLoopback))
  }

  /**
   * Some systems log a different trace id than the root span. This seems "headless", as we won't
   * see a span whose id is the same as the trace id.
   */
  @Test def dependencies_headlessTrace {
    processDependencies(List(trace(1), trace(2)))

    result(store.getDependencies(today + 1000)) should contain theSameElementsAs(dep.links)
  }


  @Test def getDependencies_looksBackIndefinitely() = {
    processDependencies(trace)

    result(store.getDependencies(today + 1000)) should contain theSameElementsAs(dep.links)
  }

  @Test def getDependencies_insideTheInterval() = {
    processDependencies(trace)

    result(store.getDependencies(dep.endTs, Some(dep.endTs - dep.startTs))) should contain theSameElementsAs(dep.links)
  }

  @Test def getDependencies_endTimeBeforeData() = {
    processDependencies(trace)

    result(store.getDependencies(today - day)) should be(empty)
  }

  @Test def getDependencies_lookbackAfterData() = {
    processDependencies(trace)

    result(store.getDependencies(today + 2 * day, Some(day))) should be(empty)
  }

  /**
   * This test confirms that the span store can process trace with intermediate
   * spans like the below properly.
   *
   *   span1: SR SS
   *     span2: intermediate call
   *       span3: CS SR SS CR: Dependency 1
   */
  @Test def getDependencies_intermediateSpans() = {
    val trace = List(
      Span(20L, "get", 20L,
        annotations = List(
          Annotation(today * 1000, Constants.ServerRecv, Some(zipkinWeb)),
          Annotation((today + 350) * 1000, Constants.ServerSend, Some(zipkinWeb)))),
      Span(20L, "call", 21L, Some(20L),
        binaryAnnotations = List(
          BinaryAnnotation(Constants.LocalComponent, "depth2", Some(zipkinWeb)))),
      Span(20L, "get", 22L, Some(21L),
        annotations = List(
          Annotation((today + 50) * 1000, Constants.ClientSend, Some(zipkinWeb)),
          Annotation((today + 100) * 1000, Constants.ServerRecv, Some(zipkinQuery)),
          Annotation((today + 250) * 1000, Constants.ServerSend, Some(zipkinQuery)),
          Annotation((today + 300) * 1000, Constants.ClientRecv, Some(zipkinWeb)))),
      Span(20L, "call", 23L, Some(22L),
        binaryAnnotations = List(
          BinaryAnnotation(Constants.LocalComponent, "depth4", Some(zipkinQuery)))),
      Span(20L, "call", 24L, Some(23L),
        binaryAnnotations = List(
          BinaryAnnotation(Constants.LocalComponent, "depth5", Some(zipkinQuery)))),
      Span(20L, "get", 25L, Some(24L),
        annotations = List(
          Annotation((today + 150) * 1000, Constants.ClientSend, Some(zipkinQuery)),
          Annotation((today + 200) * 1000, Constants.ClientRecv, Some(zipkinQuery))),
      binaryAnnotations = List(
        BinaryAnnotation(Constants.ClientAddr, true, Some(zipkinQuery)),
        BinaryAnnotation(Constants.ServerAddr, true, Some(zipkinJdbc))))
    )

    processDependencies(trace)
    result(store.getDependencies(today + 1000)) should contain theSameElementsAs(dep.links)
  }

  /**
   * This test confirms that the span store can detect dependency indicated by
   * SERVER_ADDR and CLIENT_ADDR.
   * In some cases an RPC call is made where one of the two services is not instrumented.
   * However, if the other service is able to emit "sa" or "ca" annotation with a service
   * name, the link can still be constructed.
   *
   *   span1: CA SR SS: Dependency 1 by a not-instrumented client
   *     span2: intermediate call
   *       span3: CS CR SA: Dependency 2 to a not-instrumented server
   */
  @Test def getDependencies_notInstrumentedClientAndServer() = {
    val someClient = Endpoint(172 << 24 | 17 << 16 | 4, 80, "some-client")

    val trace = List(
      Span(20L, "get", 20L,
        annotations = List(
          Annotation(today * 1000, Constants.ServerRecv, Some(zipkinWeb)),
          Annotation((today + 350) * 1000, Constants.ServerSend, Some(zipkinWeb))),
        binaryAnnotations = List(
          BinaryAnnotation(Constants.ClientAddr, true, Some(someClient)))),
      Span(20L, "get", 21L, Some(20L),
        annotations = List(
          Annotation((today + 50) * 1000, Constants.ClientSend, Some(zipkinWeb)),
          Annotation((today + 100) * 1000, Constants.ServerRecv, Some(zipkinQuery)),
          Annotation((today + 250) * 1000, Constants.ServerSend, Some(zipkinQuery)),
          Annotation((today + 300) * 1000, Constants.ClientRecv, Some(zipkinWeb)))),
      Span(20L, "get", 22L, Some(21L),
        annotations = List(
          Annotation((today + 150) * 1000, Constants.ClientSend, Some(zipkinQuery)),
          Annotation((today + 200) * 1000, Constants.ClientRecv, Some(zipkinQuery))),
        binaryAnnotations = List(
          BinaryAnnotation(Constants.ClientAddr, true, Some(zipkinQuery)),
          BinaryAnnotation(Constants.ServerAddr, true, Some(zipkinJdbc))))
    )

    processDependencies(trace)
    val dep = new Dependencies(today, today + 1000, List(
      new DependencyLink("some-client", "zipkin-web", 1),
      new DependencyLink("zipkin-web", "zipkin-query", 1),
      new DependencyLink("zipkin-query", "zipkin-jdbc", 1)
    ))
    result(store.getDependencies(today + 1000)) should contain theSameElementsAs(dep.links)
  }

  /**
   * This test confirms that the span store can detect dependency indicated by
   * SERVER_RECV or SERVER_ADDR only.
   * Some of implementations such as finagle don't send CLIENT_SEND and CLIENT_ADDR
   * annotations as desired.
   * However, if there is a SERVER_RECV or SERVER_ADDR annotation in the trace tree,
   * the link can still be constructed.
   *
   *   span1: SR SS: parent service
   *     span2: SA: Dependency 1
   *
   * Currently, the standard implentation can't detect a link with intermediate spans
   * that should be detected.
   *
   *   span1: SR SS: parent service
   *     span2: intermediate call
   *       span3: SR SS: Dependency 1 not detectable in the implementation
   */
  @Test def getDependencies_noClientSendAddrAnnotations() = {
    val trace = List(
      Span(20L, "get", 20L,
        annotations = List(
          Annotation(today * 1000, Constants.ServerRecv, Some(zipkinWeb)),
          Annotation((today + 350) * 1000, Constants.ServerSend, Some(zipkinWeb))),
        binaryAnnotations = List( // finagle also sends SA/CA itself
          BinaryAnnotation(Constants.ServerAddr, true, Some(zipkinWeb)),
          BinaryAnnotation(Constants.ClientAddr, true, Some(zipkinWeb)))),
      Span(20L, "get", 21L, Some(20L),
        annotations = List(
          Annotation((today + 150) * 1000, Constants.ClientSend, Some(zipkinQuery)),
          Annotation((today + 200) * 1000, Constants.ClientRecv, Some(zipkinQuery))),
        binaryAnnotations = List( // finagle also no SR on some condition and CA with itself
          BinaryAnnotation(Constants.ClientAddr, true, Some(zipkinQuery)),
          BinaryAnnotation(Constants.ServerAddr, true, Some(zipkinQuery))))
    )

    processDependencies(trace)
    val dep = new Dependencies(today, today + 1000, List(
      new DependencyLink("zipkin-web", "zipkin-query", 1)
    ))
    result(store.getDependencies(today + 1000)) should contain theSameElementsAs(dep.links)
  }

  /**
   * This test shows that dependency links can be filtered at daily granularity.
   * This allows the UI to look for dependency intervals besides today.
   */
  @Test def canSearchForIntervalsBesidesToday() = {
    // Let's pretend we have two days of data processed
    //  - Note: calling this twice allows test implementations to consider timestamps
    processDependencies(subtractDay(trace))
    processDependencies(trace)

    // A user looks at today's links.
    //  - Note: Using the smallest lookback avoids bumping into implementation around windowing.
    result(store.getDependencies(dep.endTs, Some(dep.endTs - dep.startTs))) should contain theSameElementsAs(dep.links)

    // A user compares the links from those a day ago.
    result(store.getDependencies(dep.endTs - day, Some(dep.endTs - dep.startTs))) should contain theSameElementsAs(dep.links)

    // A user looks at all links since data started
    result(store.getDependencies(dep.endTs)) should contain theSameElementsAs(
      List(
        new DependencyLink("zipkin-web", "zipkin-query", 2),
        new DependencyLink("zipkin-query", "zipkin-jdbc", 2)
      )
    )
  }

  /** rebases a trace backwards a day with different trace and span id. */
  private def subtractDay(trace: List[Span]) = trace.map(s =>
    s.copy(
      id = s.id + 100,
      traceId = s.traceId + 100,
      timestamp = s.timestamp.map(_ - (day * 1000)),
      annotations = s.annotations.map(a => a.copy(timestamp = a.timestamp - (day * 1000)))
    )
  )
}
