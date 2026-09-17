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
package org.apache.gluten.execution

import org.apache.spark.SparkConf

class GlutenClickHouseDSV2Suite extends ParquetSuite {

  /** Run Gluten + ClickHouse Backend with ColumnarShuffleManager */
  override protected def sparkConf: SparkConf = {
    super.sparkConf
      .set("spark.shuffle.manager", "org.apache.spark.shuffle.sort.ColumnarShuffleManager")
      .set("spark.sql.shuffle.partitions", "1")
      .set("spark.sql.sources.useV1SourceList", "avro,csv,json,kafka,orc,text")
      .set("spark.sql.autoBroadcastJoinThreshold", "10MB")
      .set("spark.sql.adaptive.enabled", "false")
  }

  test("test BatchScanExecTransformer metrics") {
    val df = spark.read
      .format("parquet")
      .load(testParquetAbsolutePath + "/lineitem")
      .filter("l_orderkey = 119430")

    compareDfResultsAgainstVanillaSpark(
      () => {
        spark.read
          .format("parquet")
          .load(testParquetAbsolutePath + "/lineitem")
          .filter("l_orderkey = 119430")
      },
      true,
      df => {
        val plans = df.queryExecution.executedPlan.collect {
          case scanExec: BasicScanExecTransformer => scanExec
        }
        assert(plans.size == 1)
        assert(plans(0).isInstanceOf[BatchScanExecTransformer])

        assert(plans(0).metrics("numOutputRows").value === 20000)
        assert(plans(0).metrics("isParquetReaderV3").value === 1)
      }
    )
  }
}
