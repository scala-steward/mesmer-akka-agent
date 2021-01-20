package io.scalac.extension.upstream.dummy
import io.scalac.extension.metric.{ MetricRecorder, PersistenceMetricMonitor, Synchronized, UpCounter }

trait NoopInstrument

trait NoopSynchronized extends Synchronized {
  override type Instrument[L] = NoopInstrument

  override def atomically[A, B](first: NoopInstrument, second: NoopInstrument): (A, B) => Unit = (_, _) => ()
}

class NoopMetricRecorder extends MetricRecorder[Long] with NoopInstrument {
  override def setValue(value: Long): Unit = ()
}

class NoopUpCounter extends UpCounter[Long] with NoopInstrument {
  override def incValue(value: Long): Unit = ()
}

class NoopPersistenceMetricsMonitor extends PersistenceMetricMonitor {
  override type Bound = BoundMonitor

  override def bind(labels: PersistenceMetricMonitor.Labels): Bound = new NoopBoundMonitor()

  class NoopBoundMonitor extends BoundMonitor with NoopSynchronized {
    override def recoveryTime: NoopInstrument with MetricRecorder[Long] = new NoopMetricRecorder()

    override def recoveryTotal: NoopInstrument with UpCounter[Long] = new NoopUpCounter()

    override def persistentEvent: NoopInstrument with MetricRecorder[Long] = new NoopMetricRecorder()

    override def persistentEventTotal: NoopInstrument with UpCounter[Long] = new NoopUpCounter()

    override def snapshot: NoopInstrument with UpCounter[Long] = new NoopUpCounter()
  }
}
