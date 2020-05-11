import java.util
import java.util.concurrent.TimeUnit

import org.apache.flink.api.common.functions.{MapFunction, ReduceFunction, RichFlatMapFunction, RichMapFunction}
import org.apache.flink.api.common.restartstrategy.RestartStrategies
import org.apache.flink.api.common.state._
import org.apache.flink.api.common.time.Time
import org.apache.flink.configuration.Configuration
import org.apache.flink.contrib.streaming.state.RocksDBStateBackend
import org.apache.flink.runtime.state.filesystem.FsStateBackend
import org.apache.flink.runtime.state.memory.MemoryStateBackend
import org.apache.flink.streaming.api.CheckpointingMode
import org.apache.flink.streaming.api.checkpoint.ListCheckpointed
import org.apache.flink.streaming.api.functions.KeyedProcessFunction
import org.apache.flink.streaming.api.scala._
import org.apache.flink.util.Collector

/**
  
  */
object StateTest {
  def main(args: Array[String]): Unit = {
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setParallelism(1)

    // 配置状态后端
//    env.setStateBackend( new MemoryStateBackend() )
    env.setStateBackend( new FsStateBackend("") )
//    env.setStateBackend( new RocksDBStateBackend("", true) )

    // checkpoint相关配置
    // 启用检查点，指定触发检查点的间隔时间(毫秒)
    env.enableCheckpointing(10000L)
    // 其它配置
    env.getCheckpointConfig.setCheckpointingMode(CheckpointingMode.EXACTLY_ONCE)
    env.getCheckpointConfig.setCheckpointTimeout(30000L)
    env.getCheckpointConfig.setMaxConcurrentCheckpoints(2)
    env.getCheckpointConfig.setMinPauseBetweenCheckpoints(500L)
    env.getCheckpointConfig.setPreferCheckpointForRecovery(false)
    env.getCheckpointConfig.setTolerableCheckpointFailureNumber(3)

    // 重启策略的配置
    env.setRestartStrategy(RestartStrategies.fixedDelayRestart(3, 10000L))
    env.setRestartStrategy(RestartStrategies.failureRateRestart(5, Time.of(5, TimeUnit.MINUTES), Time.of(10, TimeUnit.SECONDS)))

    val inputStream = env.socketTextStream("localhost", 7777)
    val dataStream: DataStream[SensorReading] = inputStream
      .map( data => {
        val dataArray = data.split(",")
        SensorReading( dataArray(0), dataArray(1).toLong, dataArray(2).toDouble )
      } )

    val warningStream: DataStream[(String, Double, Double)] = dataStream
//        .map( new MyMapper() )
      .keyBy("id")
//      .flatMap( new TempChangeWarningWithFlatmap(10.0) )
      .flatMapWithState[(String, Double, Double), Double]({
      case (inputData: SensorReading, None) => (List.empty, Some(inputData.temperature))
      case (inputData: SensorReading, lastTemp: Some[Double]) => {
        val diff = (inputData.temperature - lastTemp.get).abs
        if( diff > 10.0 ){
          ( List( (inputData.id, lastTemp.get, inputData.temperature) ), Some(inputData.temperature) )
        } else {
          (List.empty, Some(inputData.temperature))
        }
      }
    })

    warningStream.print()

    env.execute("state test job")
  }
}

// 自定义 RichMapFunction
class TempChangeWarning(threshold: Double) extends RichMapFunction[SensorReading, (String, Double, Double)]{
  // 定义状态变量，上一次的温度值
  private var lastTempState: ValueState[Double] = _

  override def open(parameters: Configuration): Unit = {
    lastTempState = getRuntimeContext.getState(new ValueStateDescriptor[Double]("last-temp", classOf[Double]))
  }

  override def map(value: SensorReading): (String, Double, Double) = {
    // 从状态中取出上次的温度值
    val lastTemp = lastTempState.value()
    // 更新状态
    lastTempState.update(value.temperature)

    // 跟当前温度值计算差值，然后跟阈值比较，如果大于就报警
    val diff = (value.temperature - lastTemp).abs
    if( diff > threshold ){
      ( value.id, lastTemp, value.temperature )
    } else
      ( value.id, 0.0, 0.0 )
  }
}

// 自定义 RichFlatMapFunction，可以输出多个结果
class TempChangeWarningWithFlatmap(threshold: Double) extends RichFlatMapFunction[SensorReading, (String, Double, Double)]{
  lazy val lastTempState: ValueState[Double] = getRuntimeContext.getState(new ValueStateDescriptor[Double]("last-temp", classOf[Double]))
  override def flatMap(value: SensorReading, out: Collector[(String, Double, Double)]): Unit = {
    // 从状态中取出上次的温度值
    val lastTemp = lastTempState.value()
    // 更新状态
    lastTempState.update(value.temperature)

    // 跟当前温度值计算差值，然后跟阈值比较，如果大于就报警
    val diff = (value.temperature - lastTemp).abs
    if( diff > threshold ){
      out.collect( (value.id, lastTemp, value.temperature) )
    }
  }
}

// keyed state定义示例
class MyProcessor extends KeyedProcessFunction[String, SensorReading, Int]{
//  lazy val myState: ValueState[Int] = getRuntimeContext.getState( new ValueStateDescriptor[Int]("my-state", classOf[Int]) )
  lazy val myListState: ListState[String] = getRuntimeContext.getListState(new ListStateDescriptor[String]("my-liststate", classOf[String]))
  lazy val myMapState: MapState[String, Double] = getRuntimeContext.getMapState(new MapStateDescriptor[String, Double]("my-mapstate", classOf[String], classOf[Double]))
  lazy val myReducingState: ReducingState[SensorReading] = getRuntimeContext.getReducingState(new ReducingStateDescriptor[SensorReading](
    "my-reducingstate",
    new MyReduceFunction(),
    classOf[SensorReading])
  )
  var myState: ValueState[Int] = _
  override def open(parameters: Configuration): Unit = {
    myState = getRuntimeContext.getState( new ValueStateDescriptor[Int]("my-state", classOf[Int]) )
  }

  override def processElement(value: SensorReading, ctx: KeyedProcessFunction[String, SensorReading, Int]#Context, out: Collector[Int]): Unit = {
    myState.value()
    myState.update(1)
    myListState.add("hello")
    myListState.update(new util.ArrayList[String]())
    myMapState.put("sensor_1", 10.0)
    myMapState.get("sensor_1")
    myReducingState.add(value)
    myReducingState.clear()
  }
}

class MyReduceFunction() extends ReduceFunction[SensorReading] {
  override def reduce(value1: SensorReading, value2: SensorReading): SensorReading =
    SensorReading(value1.id, value1.timestamp.max(value2.timestamp), value1.temperature.min(value2.temperature))
}

// operator state示例
class MyMapper() extends RichMapFunction[SensorReading, Long] with ListCheckpointed[java.lang.Long]{
//  lazy val countState: ValueState[Long] = getRuntimeContext.getState( new ValueStateDescriptor[Long]("count", classOf[Long]) )
  var count: Long = 0L
  override def map(value: SensorReading): Long = {
    count += 1
    count
  }

  override def restoreState(state: util.List[java.lang.Long]): Unit = {
    val iter = state.iterator()
    while(iter.hasNext){
      count += iter.next()
    }
//    for( countState <- state ){
//      count += countState
//    }
  }

  override def snapshotState(checkpointId: Long, timestamp: Long): util.List[java.lang.Long] = {
    val stateList = new util.ArrayList[java.lang.Long]()
    stateList.add(count)
    stateList
  }
}