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
import org.apache.spark.sql.catalyst.expressions.Sum0
import org.apache.spark.sql.execution.adaptive.AdaptiveSparkPlanHelper

class GlutenKapExpressionsSuite
  extends GlutenClickHouseWholeStageTransformerSuite
  with AdaptiveSparkPlanHelper {

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
    // register the extension expressions
    val (sum0Expr, sum0Builder) = FunctionRegistryBase.build[Sum0]("sum0", None)
    spark.sessionState.functionRegistry.registerFunction(
      FunctionIdentifier.apply("sum0"),
      sum0Expr,
      sum0Builder
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
}
