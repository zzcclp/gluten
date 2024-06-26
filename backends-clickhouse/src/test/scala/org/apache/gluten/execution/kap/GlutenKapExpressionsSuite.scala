/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.gluten.execution.kap

import org.apache.gluten.execution.GlutenClickHouseWholeStageTransformerSuite
import org.apache.gluten.utils.UTSystemParameters

import org.apache.spark.SparkConf
import org.apache.spark.sql.catalyst.FunctionIdentifier
import org.apache.spark.sql.catalyst.analysis.FunctionRegistryBase
import org.apache.spark.sql.catalyst.expressions.{Expression, KapSubtractMonths, Sum0, YMDintBetween}
import org.apache.spark.sql.execution.adaptive.AdaptiveSparkPlanHelper
import org.apache.spark.sql.types._

import scala.reflect.ClassTag

class GlutenKapExpressionsSuite
  extends GlutenClickHouseWholeStageTransformerSuite
  with AdaptiveSparkPlanHelper {

  protected val csvDataPath: String = resPath + "csv-data"

  /** Run Gluten + ClickHouse Backend with SortShuffleManager */
  override protected def sparkConf: SparkConf = {
    super.sparkConf
      .set("spark.sql.shuffle.partitions", "10")
      .set("spark.sql.files.maxPartitionBytes", "32m")
      .set("spark.shuffle.manager", "org.apache.spark.shuffle.sort.ColumnarShuffleManager")
      .set("spark.io.compression.codec", "LZ4")
      .set("spark.sql.autoBroadcastJoinThreshold", "10MB")
      .set("spark.sql.adaptive.enabled", "false")
      .set("spark.sql.execution.useObjectHashAggregateExec", "true")
      .set(
        "spark.gluten.sql.columnar.extended.expressions.transformer",
        "org.apache.spark.sql.catalyst.expressions.gluten.KapExpressionsTransformer")
      .set("spark.gluten.sql.columnar.backend.ch.shuffle.hash.algorithm", "sparkMurmurHash3_32")
      .set("spark.gluten.sql.columnar.backend.ch.runtime_config.use_local_format", "true")
  }

  override def beforeAll(): Unit = {
    super.beforeAll()
    createKylinTables()
    registerSparkUdf[Sum0]("sum0")
    registerSparkUdf[KapSubtractMonths]("kap_month_between")
    registerSparkUdf[YMDintBetween]("_ymdint_between")
  }

  def createKylinTables(): Unit = {
    val schema = StructType.apply(
      Seq(
        StructField.apply("trans_id", LongType, nullable = true),
        StructField.apply("order_id", LongType, nullable = true),
        StructField.apply("cal_dt", DateType, nullable = true),
        StructField.apply("lstg_format_name", StringType, nullable = true),
        StructField.apply("leaf_categ_id", LongType, nullable = true),
        StructField.apply("lstg_site_id", IntegerType, nullable = true),
        StructField.apply("slr_segment_cd", ShortType, nullable = true),
        StructField.apply("seller_id", IntegerType, nullable = true),
        StructField.apply("price", DecimalType.apply(19, 4), nullable = true),
        StructField.apply("item_count", IntegerType, nullable = true),
        StructField.apply("test_count_distinct_bitmap", StringType, nullable = true),
        StructField.apply("is_effectual", BooleanType, nullable = true)
      ))

    val df = spark.read
      .option("delimiter", ",")
      .option("quote", "\"")
      .schema(schema)
      .csv(csvDataPath + "/DEFAULT.TEST_KYLIN_FACT.csv")
      .toDF()

    df.createTempView("test_kylin_fact")
  }

  private def registerSparkUdf[T <: Expression: ClassTag](
      name: String,
      since: Option[String] = None
  ): Unit = {
    val (expr, builder) = FunctionRegistryBase.build[T](name, since)
    spark.sessionState.functionRegistry.registerFunction(
      name = FunctionIdentifier.apply(name),
      info = expr,
      builder = builder
    )
  }

  test("test sum0") {
    val viewName = "sum0_table"
    withTempView(viewName) {
      val sum0DataDir = s"${UTSystemParameters.testDataPath}/$fileFormat/index/sum0"
      spark
        .sql(s"""
                | select
                | `84` as week_beg_dt,
                | `129` as meta_category_name,
                | `149` as lstg_format_name,
                | `180` as site_name,
                | `100000` as trans_cnt,
                | `100001` as gmv,
                | `100002` as price_cnt,
                | `100003` as seller_cnt,
                | `100004` as total_items
                | from parquet.`$sum0DataDir`
                |""".stripMargin)
        .createOrReplaceTempView(viewName)
      val sql1 =
        s"""
           |select * from sum0_table order by week_beg_dt limit 10
           |""".stripMargin
      compareResultsAgainstVanillaSpark(sql1, compareResult = true, df => { df.show(10) })

      val sql2 =
        s"""
           |select sum(gmv), sum0(trans_cnt), sum0(price_cnt), sum0(seller_cnt), sum(total_items)
           |from sum0_table
           |where lstg_format_name='FP-GTC'
           |and week_beg_dt between '2013-05-01' and DATE '2013-08-01'
           |""".stripMargin
      compareResultsAgainstVanillaSpark(sql2, compareResult = true, _ => {})

      val sql3 =
        s"""
           |select site_name, sum(gmv), sum0(trans_cnt), sum(total_items) from sum0_table
           |where lstg_format_name='FP-GTC'
           |and week_beg_dt = '2013-01-01'
           |group by site_name order by site_name
           |""".stripMargin
      compareResultsAgainstVanillaSpark(sql3, compareResult = true, _ => {})

      // aggregate empty data with group by
      val sql4 =
        s"""
           |select site_name, sum(gmv), sum0(trans_cnt), sum(total_items) from sum0_table
           |where extract(month from week_beg_dt) = 13
           |group by site_name order by site_name
           |""".stripMargin
      compareResultsAgainstVanillaSpark(sql4, compareResult = true, _ => {})

      // aggregate empty data without group by
      val sql5 =
        s"""
           |select sum(gmv), sum0(trans_cnt), sum(total_items) from sum0_table
           |where extract(month from week_beg_dt) = 13
           |""".stripMargin
      compareResultsAgainstVanillaSpark(sql5, compareResult = true, _ => {})

      val sql6 =
        s"""
           |select sum(gmv), sum0(trans_cnt), sum(total_items) from sum0_table
           |where extract(month from week_beg_dt) = 12
           |union all
           |select sum(gmv), sum0(trans_cnt), sum(total_items) from sum0_table
           |where extract(month from week_beg_dt) = 13
           |""".stripMargin
      compareResultsAgainstVanillaSpark(sql6, compareResult = true, _ => {})
    }
  }

  test("test kap_month_between") {
    val sql0 =
      s"""
         |select * from test_kylin_fact order by trans_id limit 10
         |""".stripMargin
    compareResultsAgainstVanillaSpark(
      sql0,
      compareResult = true,
      df => {
        df.show(10)
      })

    // test kap_month_between with date before and after
    val sql1 =
      s"""
         |select kap_month_between(date'2012-02-05', cal_dt) from test_kylin_fact
         |where cal_dt >= date'2012-01-01' and cal_dt <= date'2014-03-31'
         |group by cal_dt order by cal_dt
         |""".stripMargin
    compareResultsAgainstVanillaSpark(
      sql1,
      compareResult = true,
      df => {
        assert(df.head().getInt(0) == 1)
        assert(df.tail(1).apply(0).getInt(0) == -22)
      })

    // test kap_month_between with date null
    val sql2 =
      s"""
         |select kap_month_between(null, cal_dt) from test_kylin_fact
         |where cal_dt >= date'2012-01-01' and cal_dt <= date'2012-03-31'
         |""".stripMargin
    compareResultsAgainstVanillaSpark(sql2, compareResult = true, _ => {})

    // test kap_month_between with date before 1970
    val sql3 =
      s"""
         |select kap_month_between(date'1969-01-01', cal_dt) from test_kylin_fact
         |where cal_dt >= date'2012-01-01' and cal_dt <= date'2013-03-31'
         |group by cal_dt order by cal_dt
         |""".stripMargin
    compareResultsAgainstVanillaSpark(sql3, compareResult = true, _ => {})

    // test kap_month_between with timestamp
    val sql4 =
      s"""
         |select kap_month_between(timestamp'2012-02-05 01:00:00', cal_dt) from test_kylin_fact
         |where cal_dt >= date'2012-01-01' and cal_dt <= date'2014-04-01'
         |group by cal_dt order by cal_dt
         |""".stripMargin
    compareResultsAgainstVanillaSpark(
      sql4,
      compareResult = true,
      df => {
        assert(df.head().getInt(0) == 1)
        assert(df.tail(1).apply(0).getInt(0) == -22)
      })

    // test kap_month_between with reverse order
    val sql5 =
      s"""
         |select kap_month_between(cal_dt, timestamp'2012-02-05 01:00:00') from test_kylin_fact
         |where cal_dt >= date'2012-01-01' and cal_dt <= date'2014-04-01'
         |group by cal_dt order by cal_dt
         |""".stripMargin
    compareResultsAgainstVanillaSpark(
      sql5,
      compareResult = true,
      df => {
        assert(df.head().getInt(0) == -1)
        assert(df.tail(1).apply(0).getInt(0) == 22)
      })

  }

  test("test _ymdint_between") {

    // test _ymdint_between with date before and after
    val sql1 =
      s"""
         |select _ymdint_between(date'2012-02-05', cal_dt) from test_kylin_fact
         |where cal_dt >= date'2012-01-01' and cal_dt <= date'2012-03-31'
         |group by cal_dt order by cal_dt
         |""".stripMargin
    compareResultsAgainstVanillaSpark(
      sql1,
      compareResult = true,
      df => {
        assert(df.head().getString(0) == "00104")
        assert(df.tail(1).apply(0).getString(0) == "00126")
      })

    // test _ymdint_between with date null
    val sql2 =
      s"""
         |select _ymdint_between(null, cal_dt) from test_kylin_fact
         |where cal_dt >= date'2012-01-01' and cal_dt <= date'2012-03-31'
         |""".stripMargin
    compareResultsAgainstVanillaSpark(sql2, compareResult = true, _ => {})

    // test _ymdint_between with date before 1970
    val sql3 =
      s"""
         |select _ymdint_between(date'1969-01-01', cal_dt) from test_kylin_fact
         |where cal_dt >= date'2012-01-01' and cal_dt <= date'2012-03-31'
         |group by cal_dt order by cal_dt
         |""".stripMargin
    compareResultsAgainstVanillaSpark(
      sql3,
      compareResult = true,
      df => {
        assert(df.head().getString(0) == "430000")
        assert(df.tail(1).apply(0).getString(0) == "430230")
      })

    // test _ymdint_between with timestamp
    val sql4 =
      s"""
         |select cal_dt, _ymdint_between(cal_dt, timestamp'2012-02-05 01:00:00')
         |from test_kylin_fact
         |where cal_dt >= date'2012-01-01' and cal_dt <= date'2014-03-01'
         |group by cal_dt order by cal_dt
         |""".stripMargin
    compareResultsAgainstVanillaSpark(
      sql4,
      compareResult = true,
      df => {
        assert(df.tail(1).apply(0).getString(1) == "11025")
      })

    // test _ymdint_between with reverse order
    val sql5 =
      s"""
         |select cal_dt, _ymdint_between(timestamp'2012-02-05 01:00:00', cal_dt)
         |from test_kylin_fact
         |where cal_dt >= date'2012-01-01' and cal_dt <= date'2014-03-01'
         |group by cal_dt order by cal_dt
         |""".stripMargin
    compareResultsAgainstVanillaSpark(
      sql5,
      compareResult = true,
      df => {
        assert(df.tail(1).apply(0).getString(1) == "11027")
      })
  }

}
