package com.bai.course

import org.apache.flink.api.common.functions.AggregateFunction
import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.api.scala.function.ProcessWindowFunction
import org.apache.flink.streaming.api.windowing.time.Time
import org.apache.flink.streaming.api.windowing.windows.TimeWindow
import org.apache.flink.util.Collector

// 增量聚合函数和全窗口聚合函数的结合使用
object AggregateWithProcessWindowExample {

  case class MinMaxTemp(id: String, min: Double, max: Double, endTs: Long)

  def main(args: Array[String]): Unit = {
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setParallelism(1)
    val sensorData = env.addSource(new SensorSource)
    val minMaxTempPerWindow = sensorData
      .map(r => (r.id, r.temperature))
      .keyBy(_._1)
      .timeWindow(Time.seconds(5))
      .aggregate(new MyAgg, new AssignWindowEndProcessFunction)

    val a=sensorData
      .map(r => (r.id,r.temperature))
      .keyBy(_._1)
        .countWindow(30)
        .aggregate(new MyAgg, new AssignWindowEndProcessFunction)

//        .sum(_)


    minMaxTempPerWindow.print()

    env.execute()
  }

  class AssignWindowEndProcessFunction extends ProcessWindowFunction[(String, Double, Double), MinMaxTemp, String, TimeWindow] {
    override def process(key: String, context: Context, elements: Iterable[(String, Double, Double)], out: Collector[MinMaxTemp]): Unit = {
      val e = elements.iterator.next()
      out.collect(MinMaxTemp(key, e._2, e._3, context.window.getEnd))
    }
  }

  class MyAgg extends AggregateFunction[(String, Double), (String, Double, Double), (String, Double, Double)] {
    override def createAccumulator(): (String, Double, Double) = ("", Double.MaxValue, Double.MinValue)

    override def add(value: (String, Double), accumulator: (String, Double, Double)): (String, Double, Double) = {
      (value._1, accumulator._2.min(value._2), accumulator._3.max(value._2))
    }

    override def getResult(accumulator: (String, Double, Double)): (String, Double, Double) = accumulator

    override def merge(a: (String, Double, Double), b: (String, Double, Double)): (String, Double, Double) = {
      (a._1, a._2.min(b._2), a._3.max(b._3))
    }
  }

}

class countagg extends AggregateFunction[(SensorReading,String),(String,Long),(String,Long)] {
  override def createAccumulator() = ("",0)

  override def add(value: (SensorReading, String), accumulator: (String, Long)): (String, Long) = (value._1.id,accumulator._2+value._1.temperature.toLong)

  override def getResult(accumulator: (String, Long)): (String, Long) = (accumulator._1,accumulator._2)

  override def merge(a: (String, Long), b: (String, Long)): (String, Long) = (a._1,a._2+b._2)
}

class cnwin extends ProcessWindowFunction {
  override def process(key: Nothing, context: Context, elements: Iterable[Nothing], out: Collector[Nothing]): Unit = ???
}
