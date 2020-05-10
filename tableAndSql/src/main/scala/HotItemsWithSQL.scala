import java.sql.{Connection, DriverManager, PreparedStatement, Timestamp}

import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.TimeCharacteristic
import org.apache.flink.streaming.api.functions.sink.{RichSinkFunction, SinkFunction}
import org.apache.flink.streaming.api.scala._
import org.apache.flink.table.api.scala._
import org.apache.flink.table.api.{EnvironmentSettings, Table}

/**
  * Copyright (c) 2018-2028 尚硅谷 All Rights Reserved 
  *
  * Project: UserBehaviorAnalysis
  * Package: com.atguigu.hotitems_analysis
  * Version: 1.0
  *
  * Created by wushengran on 2020/4/29 17:27
  */
case class UserBehavior( userId: Long, itemId: Long, categoryId: Int, behavior: String, timestamp: Long )
object HotItemsWithSQL {
  def main(args: Array[String]): Unit = {
    // 创建一个流处理执行环境
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setParallelism(1)
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)

    val inputStream: DataStream[String] = env.readTextFile("I:\\SSDData\\data\\idea3\\UserBehaviorAnalysis\\HotItemsAnalysis\\src\\main\\resources\\UserBehavior.csv")
    // 将数据转换成样例类类型，并且提取timestamp定义watermark
    val dataStream: DataStream[UserBehavior] = inputStream
      .map( data => {
        val dataArray = data.split(",")
        UserBehavior( dataArray(0).toLong, dataArray(1).toLong, dataArray(2).toInt, dataArray(3), dataArray(4).toLong )
      } )
      .assignAscendingTimestamps(_.timestamp * 1000L)

    // 要调用Table API，先创建表执行环境
    val settings = EnvironmentSettings.newInstance()
      .useBlinkPlanner()
      .inStreamingMode()
      .build()
    val tableEnv = StreamTableEnvironment.create(env, settings)

    // 将DataStream注册成表，提取需要的字段，进行处理
    tableEnv.createTemporaryView("data_table", dataStream, 'itemId, 'behavior, 'timestamp.rowtime as 'ts)

    // 用SQL实现
    val resultTable: Table = tableEnv.sqlQuery(
      """
        |select *
        |from (
        |    select *,
        |      row_number() over (partition by windowEnd order by cnt desc) as row_num
        |    from (
        |      select itemId,
        |          count(itemId) as cnt,
        |          hop_end(ts, interval '5' minute, interval '1' hour) as windowEnd
        |      from data_table
        |      where behavior = 'pv'
        |      group by hop(ts, interval '5' minute, interval '1' hour), itemId
        |    )
        |)
        |where row_num <= 5
      """.stripMargin)

//    resultTable.toRetractStream[(Long, Long, Timestamp, Long)].print("result")
val outputDatastream: DataStream[(Boolean, (Long, Long, Timestamp, Long))] = resultTable.toRetractStream[(Long, Long, Timestamp, Long)]
    val value: DataStream[(Boolean, (Long, Long, Timestamp, Long))] = outputDatastream.filter(_._1)
//    val value1: DataStream[SensorReading] = value.asInstanceOf[DataStream[SensorReading]]
//    value1.print()
    value.addSink(new MyJdbcSink)
    value.print()
    /*(true,(2563440,12,2017-11-26 01:40:00.0,3))
    //(true,(2331370,9,2017-11-26 01:40:00.0,4))
    //(true,(138964,9,2017-11-26 01:40:00.0,5))*/
    env.execute("hot item with sql")
  }
}
class MyJdbcSink() extends RichSinkFunction[(Boolean, (Long, Long, Timestamp, Long))]{
  // 首先定义sql连接，以及预编译语句
  var conn: Connection = _
  var insertStmt: PreparedStatement = _
  var updateStmt: PreparedStatement = _

  // 在open生命周期方法中创建连接以及预编译语句
  override def open(parameters: Configuration): Unit = {
    conn = DriverManager.getConnection("jdbc:mysql://localhost:3306/test", "root", "123456")
    updateStmt = conn.prepareStatement("update temp set cnt = ? and row_num= ? where itemId = ? and windowEnd=?")
    insertStmt = conn.prepareStatement("insert into temp (itemId, cnt, windowEnd,row_num) values (?,?,?,?)")
  }

  override def invoke(value: (Boolean, (Long, Long, Timestamp, Long)), context: SinkFunction.Context[_]): Unit = {
    // 执行更新语句
    updateStmt.setLong(1, value._2._2)
    updateStmt.setLong(2,  value._2._4)
    updateStmt.setLong(3,  value._2._1)
    updateStmt.setString(4,  value._2._3.toString)
    updateStmt.execute()
    // 如果刚才没有更新数据，那么执行插入操作
    if( updateStmt.getUpdateCount == 0 ){
      insertStmt.setLong(1,value._2._1)
      insertStmt.setLong(2,value._2._2)
      insertStmt.setString(3, value._2._3.toString)
      insertStmt.setLong(4,  value._2._4)
      insertStmt.execute()
    }
  }
  // 关闭操作
  override def close(): Unit = {
    insertStmt.close()
    updateStmt.close()
    conn.close()
  }
}
// 输入数据的样例类
case class SensorReading( itemId: Long, cnt: Long, windowEnd: String,row_num:Long )