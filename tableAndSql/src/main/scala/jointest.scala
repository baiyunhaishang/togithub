import org.apache.flink.streaming.api.TimeCharacteristic
import org.apache.flink.streaming.api.scala._
import org.apache.flink.table.api.scala._
import org.apache.flink.table.api.{EnvironmentSettings, Table}

// 定义窗口聚合结果的样例类
case class ItemViewCount(itemId: Long, windowEnd: Long, count: Long)

object jointest {
  def main(args: Array[String]): Unit = {
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setParallelism(1)

//    val checkpointPath: String = "jetbrains://idea/navigate/reference?project=togithub&fqn="
//    val backend = new RocksDBStateBackend(checkpointPath)
//
//    env.setStateBackend(backend)
//    env.setStateBackend(new FsStateBackend("jetbrains://idea/navigate/reference?project=togithub&fqn="))
//    env.enableCheckpointing(1000)
    // 配置重启策略
//    env.setRestartStrategy(RestartStrategies.fixedDelayRestart(60, Time.of(10, TimeUnit.SECONDS)))

    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)

    val inputStream: DataStream[String] = env.readTextFile("I:\\SSDData\\data\\idea3\\testFlinkKafka\\hotIterm\\src\\main\\resources\\UserBehavior.csv")
    // 将数据转换成样例类类型，并且提取timestamp定义watermark
    val dataStream: DataStream[UserBehavior] = inputStream
      .map(data => {
        val dataArray = data.split(",")
        UserBehavior(dataArray(0).toLong, dataArray(1).toLong, dataArray(2).toInt, dataArray(3), dataArray(4).toLong)
      })
      .assignAscendingTimestamps(_.timestamp * 1000L)

    // 要调用Table API，先创建表执行环境
    val settings = EnvironmentSettings.newInstance()
      .useBlinkPlanner()
      .inStreamingMode()
      .build()
    val tableEnv = StreamTableEnvironment.create(env, settings)

    // 将DataStream注册成表，提取需要的字段，进行处理
    tableEnv.createTemporaryView("data_table1", dataStream, 'itemId, 'behavior, 'timestamp.rowtime as 'ts)
    tableEnv.createTemporaryView("data_table2", dataStream, 'itemId, 'behavior, 'timestamp.rowtime as 'ts)
    tableEnv.createTemporaryView("data_table3", dataStream, 'itemId, 'behavior, 'timestamp.rowtime as 'ts)
    val table: Table = tableEnv.sqlQuery(
      """
        |select a.itemId
        |       ,b.behavior
        |       ,count(a.itemId)
        |  from data_table1 a
        |  left join data_table2 b
        |    on a.itemId=b.itemId
        |   and a.behavior=b.behavior
        |  left join data_table3 c
        |    on a.itemId=c.itemId
        |   and a.behavior=c.behavior
        |   group by a.itemId,b.behavior
        """.stripMargin)
    table.toRetractStream[(Long, String ,Long)].print() //,hop_end(a.ts, interval '5' minute, interval '1' hour) as windowEnd
    table.printSchema()
    env.execute()
  }

}
/*val sinkDDL: String =
  """
    |create table jdbcOutputTable (
    |  id varchar(20) not null,
    |  cnt bigint not null
    |) with (
    |  'connector.type' = 'jdbc',
    |  'connector.url' = 'jdbc:mysql://localhost:3306/test',
    |  'connector.table' = 'sensor_count',
    |  'connector.driver' = 'com.mysql.jdbc.Driver',
    |  'connector.username' = 'root',
    |  'connector.password' = '123456'
    |)
  """.stripMargin

tableEnv.sqlUpdate(sinkDDL)
aggResultSqlTable.insertInto("jdbcOutputTable")
*/