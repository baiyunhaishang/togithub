package com.bai.course

import org.apache.flink.streaming.api.scala._

object KeyedStateExample2 {
  def main(args: Array[String]): Unit = {
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setParallelism(1)
    val stream: DataStream[SensorReading] = env.addSource(new SensorSource)

    stream
      .keyBy(_.id)
      // 复习Option, Either, Some
      .flatMapWithState[(String, Double, Double), Double]{  //第一个是in，第二个是状态uid
        case (in: SensorReading, None) => (List.empty, Some(in.temperature)) // 初始化，Some操作将温度保存到了状态变量中
        case (r: SensorReading, lastTemp: Some[Double]) => {
          val tempDiff = (r.temperature - lastTemp.get).abs
          if (tempDiff > 1.7) {
            (List((r.id, r.temperature, lastTemp.get)), Some(r.temperature)) // Some操作将温度保存到了状态变量中
          } else {
            (List.empty, Some(r.temperature))
          }
        }
      }
      .print()

    env.execute()
  }
}












