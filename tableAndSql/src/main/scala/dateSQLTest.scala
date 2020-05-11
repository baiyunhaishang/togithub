import java.util

import org.apache.flink.streaming.api.scala.StreamExecutionEnvironment
import org.apache.flink.table.api.{EnvironmentSettings, Table, TableUtils}
import org.apache.flink.table.api.scala.StreamTableEnvironment
import org.apache.flink.table.functions.ScalarFunction
import org.apache.flink.types.Row

object dateSQLTest {
  def main(args: Array[String]): Unit = {
    val env: StreamExecutionEnvironment = StreamExecutionEnvironment.getExecutionEnvironment
    env.setParallelism(1)
    val settings = EnvironmentSettings
      .newInstance()
      .useBlinkPlanner()
      .inStreamingMode()
      .build()
    val tableEnv: StreamTableEnvironment = StreamTableEnvironment.create( env, settings )
    tableEnv.registerFunction("a",new a)
    val table: Table = tableEnv.sqlQuery(//'2019-02-03'
      """
        |select a('dd') as c
        |""".stripMargin)

    table.printSchema()
    val rows: util.List[Row] = TableUtils.collectToList(table)
    println(rows.get(0))
//    println(TableUtils.collectToList(table))
//    tableEnv.execute("dd")
//    env.execute()


  }

}
class a extends ScalarFunction{
  def eval(a:String)={
    a+1
  }
}