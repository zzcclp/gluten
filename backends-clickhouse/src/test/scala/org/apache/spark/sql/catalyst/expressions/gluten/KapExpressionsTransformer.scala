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
package org.apache.spark.sql.catalyst.expressions.gluten

import org.apache.gluten.exception.GlutenNotSupportException
import org.apache.gluten.expression._
import org.apache.gluten.extension.ExpressionExtensionTrait

import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.aggregate._
import org.apache.spark.sql.types.DataType

import scala.collection.mutable.ListBuffer

// scalastyle:off line.size.limit
case class KapExpressionsTransformer() extends ExpressionExtensionTrait {

  /** Generate the extension expressions list, format: Sig[XXXExpression]("XXXExpressionName") */
  def expressionSigList: Seq[Sig] = Seq(
    Sig[Sum0]("sum0"),
    Sig[KapSubtractMonths]("kap_month_between"),
    Sig[YMDintBetween]("kap_ymd_int_between")
  )

  override def replaceWithExtensionExpressionTransformer(
      substraitExprName: String,
      expr: Expression,
      attributeSeq: Seq[Attribute]): ExpressionTransformer = expr match {
    case kapSubtractMonths: KapSubtractMonths =>
      GenericExpressionTransformer(
        "kap_months_between",
        Seq(
          ExpressionConverter.replaceWithExpressionTransformer(
            kapSubtractMonths.right,
            attributeSeq),
          ExpressionConverter.replaceWithExpressionTransformer(kapSubtractMonths.left, attributeSeq)
        ),
        kapSubtractMonths
      )
    case kapYmdIntBetween: YMDintBetween =>
      GenericExpressionTransformer(
        "kap_ymd_int_between",
        Seq(
          ExpressionConverter.replaceWithExpressionTransformer(kapYmdIntBetween.left, attributeSeq),
          ExpressionConverter.replaceWithExpressionTransformer(kapYmdIntBetween.right, attributeSeq)
        ),
        kapYmdIntBetween
      )
    case _ =>
      throw new UnsupportedOperationException(
        s"${expr.getClass} or $expr is not currently supported.")
  }

  override def getAttrsIndexForExtensionAggregateExpr(
      aggregateFunc: AggregateFunction,
      mode: AggregateMode,
      exp: AggregateExpression,
      aggregateAttributeList: Seq[Attribute],
      aggregateAttr: ListBuffer[Attribute],
      resIndex: Int): Int = {
    var resIdx = resIndex
    exp.mode match {
      case Partial | PartialMerge =>
        val aggBufferAttr = aggregateFunc.inputAggBufferAttributes
        for (index <- aggBufferAttr.indices) {
          val attr = ConverterUtils.getAttrFromExpr(aggBufferAttr(index))
          aggregateAttr += attr
        }
        resIdx += aggBufferAttr.size
        resIdx
      case Final | Complete =>
        aggregateAttr += aggregateAttributeList(resIdx)
        resIdx += 1
        resIdx
      case other =>
        throw new GlutenNotSupportException(s"Unsupported aggregate mode: $other.")
    }
  }

  override def buildCustomAggregateFunction(
      aggregateFunc: AggregateFunction): (Option[String], Seq[DataType]) = {
    val substraitAggFuncName = aggregateFunc match {
      case _ =>
        extensionExpressionsMapping.get(aggregateFunc.getClass)
    }
    if (substraitAggFuncName.isEmpty) {
      throw new UnsupportedOperationException(
        s"Aggregate function ${aggregateFunc.getClass} is not supported.")
    }
    (substraitAggFuncName, aggregateFunc.children.map(child => child.dataType))
  }
}
// scalastyle:on line.size.limit
