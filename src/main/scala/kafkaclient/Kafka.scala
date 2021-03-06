package kafkaclient

import scalaz.stream._
import scalaz.concurrent.Task
import java.util.Properties
import kafka.consumer._
import kafka.serializer._
import scodec.bits.ByteVector
import java.util.concurrent.Executors


case class KeyedValue(key: Option[ByteVector], value: ByteVector) {
  def keyAsString = key.flatMap(_.decodeUtf8.right.toOption).getOrElse("")
  def valueAsString = value.decodeUtf8.right.getOrElse("")

  override def toString = s"key: ${keyAsString} value: ${valueAsString}"
}


object Kafka {
  
  object ByteVectorDecoder extends Decoder[ByteVector] {
    def fromBytes(bytes: Array[Byte]): ByteVector = ByteVector(bytes)
  }

  def consumer(zookeeper: List[String], gid: String): Task[ConsumerConnector] = {
    val p = new Properties()
    p.setProperty("zookeeper.connect", zookeeper.mkString(","))
    p.setProperty("group.id", gid)
    p.setProperty("zookeeper.session.timeout.ms", "400")
    p.setProperty("zookeeper.sync.time.ms", "200")
    p.setProperty("auto.commit.interval.ms", "1000")
    p.setProperty("auto.offset.reset", "smallest")
    consumer(p)
  }

  def consumer(p: Properties): Task[ConsumerConnector] = {
    val c = new ConsumerConfig(p)
    Task{ Consumer.create(c) }
  }

  def subscribe(c: Task[ConsumerConnector], topic: String, nPartitions: Int = 1): Process[Task, KeyedValue] = {
    val streams = c map ( consumer => consumer.createMessageStreams(Map(topic -> nPartitions), ByteVectorDecoder, ByteVectorDecoder)(topic))

    val ec = Executors.newFixedThreadPool(nPartitions, new NamedThreadFactory("KafkaClient"))

    val queue = async.boundedQueue[KeyedValue](10)

    def task(s: KafkaStream[ByteVector, ByteVector]) : Task[Unit] = Task.fork(Task {
      val it = s.iterator
      while(it.hasNext) {
        val next = it.next()
        queue.enqueueOne(KeyedValue(Option(next.key()), next.message())).run
      }
    })(ec)

    val p = Process.await( streams ) { strm =>
      val processes = strm.map(s => Process.eval_(task(s)))
      val all = Process.emitAll(processes)
      val p = merge.mergeN(all)
      p merge queue.dequeue
    }

    p onComplete(Process eval_ c.map{ c =>
      c.shutdown()
      ec.shutdown()
    })
  }
}
