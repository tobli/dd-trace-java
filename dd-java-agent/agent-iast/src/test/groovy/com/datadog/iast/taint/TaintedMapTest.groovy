package com.datadog.iast.taint

import com.datadog.iast.model.Range
import datadog.trace.api.Platform
import datadog.trace.test.util.CircularBuffer
import datadog.trace.test.util.DDSpecification
import spock.lang.IgnoreIf

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import static datadog.trace.test.util.GCUtils.awaitGC
import static java.util.concurrent.TimeUnit.SECONDS

class TaintedMapTest extends DDSpecification {

  def 'simple workflow'() {
    given:
    def map = new DefaultTaintedMap()
    final o = new Object()
    final to = new TaintedObject(o, [] as Range[], map.getReferenceQueue())

    expect:
    map.size() == 0
    map.toList().size() == 0

    when:
    map.put(to)

    then:
    map.toList().size() == 1

    and:
    map.get(o) != null
    map.get(o).get() == o

    when:
    map.clear()

    then:
    map.toList().size() == 0
  }

  def 'last put always exists'() {
    given:
    int nTotalObjects = DefaultTaintedMap.DEFAULT_CAPACITY
    def map = new DefaultTaintedMap()

    expect:
    (1..nTotalObjects).each { i ->
      final o = new Object()
      final to = new TaintedObject(o, [] as Range[], map.getReferenceQueue())
      map.put(to)
      assert map.get(o) == to
    }
  }

  def 'garbage-collected entries are purged'() {
    given:
    int iters = 16
    int nObjectsPerIter = 1024
    int nRetainedObjects = 8
    def map = new DefaultTaintedMap()
    def objectBuffer = new CircularBuffer<Object>(nRetainedObjects)

    when:
    (1..nRetainedObjects).each {
      final o = new Object()
      final to = new TaintedObject(o, [] as Range[], map.getReferenceQueue())
      map.put(to)
      objectBuffer.add(o)
      assert map.get(o) == to
    }
    (1..iters).each {
      final refs = (1..nObjectsPerIter).collect {
        final o = new Object()
        final to = new TaintedObject(o, [] as Range[], map.getReferenceQueue())
        map.put(to)
        return to
      }
      awaitCollected(refs)
    }

    then:
    !map.isFlat()
    final entries = map.toList()
    entries.size() <= nRetainedObjects + nObjectsPerIter
    entries.findAll { it.get() != null }.size() == nRetainedObjects
    objectBuffer.each { o ->
      final to = map.get(o)
      assert to != null
      assert to.get() == o
    }
  }

  def 'single-threaded put-intensive workflow without garbage collection interaction and under max size'() {
    given:
    int nTotalObjects = 1024
    int nRetainedObjects = 1024
    def map = new DefaultTaintedMap()
    def objectBuffer = new CircularBuffer<Tuple2<Object, TaintedObject>>(nRetainedObjects)

    when: 'perform puts'
    (1..nTotalObjects).each { i ->
      final o = new Object()
      final to = new TaintedObject(o, [] as Range[], map.getReferenceQueue())
      objectBuffer.add(new Tuple2(o, to))
      map.put(to)
    }

    then: 'map contains exact amount of objects'
    map.toList().size() == nTotalObjects

    and: 'all objects are as expected'
    objectBuffer.each {
      assert map.get(it.get(0)) == it.get(1)
    }
  }

  def 'single-threaded put-intensive workflow with garbage collection interaction and under max size'() {
    given:
    int nTotalObjects = 1024 * 2
    int nRetainedObjects = 1024
    def map = new DefaultTaintedMap()
    def objectBuffer = new CircularBuffer<Tuple2<Object, TaintedObject>>(nRetainedObjects)

    when: 'perform puts'
    (1..nTotalObjects).each { i ->
      final o = new Object()
      final to = new TaintedObject(o, [] as Range[], map.getReferenceQueue())
      objectBuffer.add(new Tuple2(o, to))
      map.put(to)
    }

    then: 'map is not in flat mode'
    !map.isFlat()

    and: 'map contains exact amount of objects'
    map.toList().size() == nTotalObjects

    and: 'all objects are as expected'
    objectBuffer.each {
      assert map.get(it.get(0)) == it.get(1)
    }
  }

  def 'single-threaded put-intensive workflow without garbage collection interaction and over max size'() {
    given:
    int nTotalObjects = DefaultTaintedMap.DEFAULT_FLAT_MODE_THRESHOLD * 4
    int nRetainedObjects = nTotalObjects
    def map = new DefaultTaintedMap()
    def objectBuffer = new CircularBuffer<Tuple2<Object, TaintedObject>>(nRetainedObjects)

    when: 'perform puts'
    (1..nTotalObjects).each { i ->
      final o = new Object()
      final to = new TaintedObject(o, [] as Range[], map.getReferenceQueue())
      objectBuffer.add(new Tuple2(o, to))
      map.put(to)
    }

    then: 'map is in flat mode'
    map.isFlat()

    and: 'map contains enough objects'
    map.toList().size() >= DefaultTaintedMap.DEFAULT_FLAT_MODE_THRESHOLD * 0.9

    and: 'all objects are as expected'
    int presentObjects = objectBuffer.count {
      map.get(it.get(0)) == it.get(1)
    }
    presentObjects >= DefaultTaintedMap.DEFAULT_FLAT_MODE_THRESHOLD * 0.9
  }

  @IgnoreIf({ Platform.isJ9() })
  def 'single-threaded put-intensive workflow with garbage collection interaction and over max size'() {
    given:
    int nTotalObjects = DefaultTaintedMap.DEFAULT_FLAT_MODE_THRESHOLD * 4
    int nRetainedObjects = 1024
    def map = new DefaultTaintedMap()
    def objectBuffer = new CircularBuffer<Tuple2<Object, Object>>(nRetainedObjects)

    when: 'perform puts'
    (1..nTotalObjects).each { i ->
      final o = new Object()
      final to = new TaintedObject(o, [] as Range[], map.getReferenceQueue())
      objectBuffer.add(new Tuple2(o, to))
      map.put(to)
    }

    then: 'map is in flat mode'
    map.isFlat()

    and: 'map contains enough objects'
    map.toList().size() >= DefaultTaintedMap.DEFAULT_FLAT_MODE_THRESHOLD * 0.6

    and: 'all objects are as expected'
    // FIXME: This might need improvement, we remove too many new objects.
    int presentObjects = objectBuffer.count {
      map.get(it.get(0)) == it.get(1)
    }
    presentObjects >= nRetainedObjects * 0.9
  }

  def 'multi-threaded put-intensive workflow without garbage collection interaction and under max size'() {
    given:
    float maxAcceptableLoss = 0.999
    int nThreads = 32
    int nObjectsPerThread = (int) Math.floor((DefaultTaintedMap.DEFAULT_FLAT_MODE_THRESHOLD - 4096) / nThreads)
    int nTotalObjects = nThreads * nObjectsPerThread
    def executorService = Executors.newFixedThreadPool(nThreads)
    def startLatch = new CountDownLatch(nThreads)
    def map = new DefaultTaintedMap()

    // Holder to avoid objects being garbage collected
    def objectHolder = new ConcurrentHashMap<Object, TaintedObject>()

    when: 'perform a high amount of concurrent puts'
    def futures = (1..nThreads).collect { thread ->
      executorService.submit({
        ->
        final tuples = new ArrayList<Tuple2<Object, TaintedObject>>(nObjectsPerThread)
        for (int i = 1; i <= nObjectsPerThread; i++) {
          final o = new Object()
          final to = new TaintedObject(o, [] as Range[], map.getReferenceQueue())
          tuples.add(new Tuple2<Object, TaintedObject>(o, to))
          objectHolder.put(o, to)
        }
        startLatch.countDown()
        startLatch.await()
        tuples.each { map.put(it.get(1)) }
      } as Runnable)
    }
    futures.collect({
      it.get()
    })

    then: 'map is not in flat mode'
    !map.isFlat()

    then: 'map does not contain extra objects'
    map.toList().size() <= nTotalObjects

    and: 'map did not lose too many objects'
    map.toList().size() >= nTotalObjects * maxAcceptableLoss

    and: 'sanity check'
    objectHolder.size() == nTotalObjects

    and: 'all objects are as expected'
    objectHolder.count {
      map.get(it.getKey()) == it.getValue()
    } >= nTotalObjects * maxAcceptableLoss

    cleanup:
    executorService?.shutdown()
  }

  private static void awaitCollected(final List<TaintedObject> objects, final long duration = 1, final TimeUnit unit = SECONDS) {
    def attempts = 3
    while (!objects.empty && attempts > 0) {
      objects.removeAll { awaitCollected(it, duration, unit) }
      attempts--
    }
    if (!objects.empty) {
      // ok so once we got here ... the GC didn't want to collect our instances and not for the lack of trying,
      // let's simulate the collection and keep on with our lives
      objects.each {it.enqueue() }
      objects.clear()
    }
  }

  private static boolean awaitCollected(final TaintedObject to, final long duration, final TimeUnit unit) {
    try {
      if (to.get() != null) {
        awaitGC(to, duration, unit)
      }
      return true
    } catch (final Exception e) {
      return false
    }
  }
}
