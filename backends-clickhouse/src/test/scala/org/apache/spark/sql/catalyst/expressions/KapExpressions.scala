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
package org.apache.spark.sql.catalyst.expressions

import org.apache.spark.sql.catalyst.analysis.TypeCheckResult
import org.apache.spark.sql.catalyst.expressions.aggregate.DeclarativeAggregate
import org.apache.spark.sql.catalyst.expressions.codegen.{CodegenContext, ExprCode}
import org.apache.spark.sql.catalyst.util.{KapDateTimeUtils, TimeUtil, TypeUtils}
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.UTF8String

// scalastyle:off line.size.limit
@ExpressionDescription(
  usage = "_FUNC_(expr) - Returns the sum calculated from values of a group. " +
    "It differs in that when no non null values are applied zero is returned instead of null")
case class Sum0(child: Expression) extends DeclarativeAggregate with ImplicitCastInputTypes {

  override def children: Seq[Expression] = child :: Nil

  override def nullable: Boolean = true

  // Return data type.
  override def dataType: DataType = resultType

  override def inputTypes: Seq[AbstractDataType] = Seq(NumericType)

  override def checkInputDataTypes(): TypeCheckResult =
    TypeUtils.checkForNumericExpr(child.dataType, "function sum")

  private lazy val resultType = child.dataType match {
    case DecimalType.Fixed(precision, scale) =>
      DecimalType.bounded(precision + 10, scale)
    case _: IntegralType => LongType
    case _ => DoubleType
  }

  private lazy val sumDataType = resultType

  private lazy val sum = AttributeReference("sum", sumDataType)()

  private lazy val zero = Cast(Literal(0), sumDataType)

  override lazy val aggBufferAttributes = sum :: Nil

  override lazy val initialValues: Seq[Expression] = Seq(
    //    /* sum = */ Literal.create(0, sumDataType)
    //    /* sum = */ Literal.create(null, sumDataType)
    Cast(Literal(0), sumDataType)
  )

  override lazy val updateExpressions: Seq[Expression] = {
    if (child.nullable) {
      Seq(
        /* sum = */
        Coalesce(Seq(Add(Coalesce(Seq(sum, zero)), Cast(child, sumDataType)), sum))
      )
    } else {
      Seq(
        /* sum = */
        Add(Coalesce(Seq(sum, zero)), Cast(child, sumDataType))
      )
    }
  }

  override lazy val mergeExpressions: Seq[Expression] = {
    Seq(
      /* sum = */
      Coalesce(Seq(Add(Coalesce(Seq(sum.left, zero)), sum.right), sum.left))
    )
  }

  override lazy val evaluateExpression: Expression = sum

  override protected def withNewChildrenInternal(newChildren: IndexedSeq[Expression]): Expression =
    super.legacyWithNewChildren(newChildren)
}

// Returns the date that is num_months after start_date.
@ExpressionDescription(
  usage = "_FUNC_(date0, date1) - Returns the num of months between `date0` after `date1`.",
  extended = """
    Examples:
      > SELECT _FUNC_('2016-08-31', '2017-08-31');
       12
  """
)
case class KapSubtractMonths(a: Expression, b: Expression)
  extends BinaryExpression
  with ImplicitCastInputTypes {

  override def left: Expression = a

  override def right: Expression = b

  override def inputTypes: Seq[AbstractDataType] = Seq(DateType, DateType)

  override def dataType: DataType = IntegerType

  override def nullSafeEval(date0: Any, date1: Any): Any = {
    KapDateTimeUtils.dateSubtractMonths(date0.asInstanceOf[Int], date1.asInstanceOf[Int])
  }

  override def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    val dtu = KapDateTimeUtils.getClass.getName.stripSuffix("$")
    defineCodeGen(
      ctx,
      ev,
      (d0, d1) => {
        s"""$dtu.dateSubtractMonths($d0, $d1)"""
      })
  }

  override def prettyName: String = "kap_months_between"

  override protected def withNewChildrenInternal(
      newLeft: Expression,
      newRight: Expression): Expression = {
    val newChildren = Seq(newLeft, newRight)
    super.legacyWithNewChildren(newChildren)
  }

}

case class YMDintBetween(first: Expression, second: Expression)
  extends BinaryExpression
  with ImplicitCastInputTypes {

  override def left: Expression = first

  override def right: Expression = second

  override def dataType: DataType = StringType

  override def inputTypes: Seq[AbstractDataType] = Seq(DateType, DateType)

  override protected def nullSafeEval(input1: Any, input2: Any): Any = {
    (first.dataType, second.dataType) match {
      case (DateType, DateType) =>
        UTF8String.fromString(
          TimeUtil.ymdintBetween(
            KapDateTimeUtils.daysToMillis(input1.asInstanceOf[Int]),
            KapDateTimeUtils.daysToMillis(input2.asInstanceOf[Int])))
    }
  }

  override protected def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    val td = classOf[TimeUtil].getName
    val dtu = KapDateTimeUtils.getClass.getName.stripSuffix("$")
    defineCodeGen(
      ctx,
      ev,
      (arg1, arg2) => {
        s"""org.apache.spark.unsafe.types.UTF8String.fromString($td.ymdintBetween($dtu.daysToMillis($arg1),
           |$dtu.daysToMillis($arg2)))""".stripMargin
      }
    )
  }

  override protected def withNewChildrenInternal(
      newFirst: Expression,
      newSecond: Expression): Expression = {
    val newChildren = Seq(newFirst, newSecond)
    super.legacyWithNewChildren(newChildren)
  }
}

case class KylinSplitPart(left: Expression, mid: Expression, right: Expression)
  extends TernaryExpression
  with ExpectsInputTypes {

  override def dataType: DataType = left.dataType

  override def nullable: Boolean = true

  override def inputTypes: Seq[AbstractDataType] = Seq(StringType, StringType, IntegerType)

  override def first: Expression = left

  override def second: Expression = mid

  override def third: Expression = right

  override protected def nullSafeEval(input1: Any, input2: Any, input3: Any): Any = {
    SplitPartImpl.evaluate(input1.toString, input2.toString, input3.asInstanceOf[Int])
  }

  override protected def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    val ta = SplitPartImpl.getClass.getName.stripSuffix("$")
    nullSafeCodeGen(
      ctx,
      ev,
      (arg1, arg2, arg3) => {
        s"""
          org.apache.spark.unsafe.types.UTF8String result = $ta.evaluate($arg1.toString(), $arg2.toString(), $arg3);
          if (result == null) {
            ${ev.isNull} = true;
          } else {
            ${ev.value} = result;
          }
        """
      }
    )
  }

  override protected def withNewChildrenInternal(
      newFirst: Expression,
      newSecond: Expression,
      newThird: Expression): Expression = {
    val newChildren = Seq(newFirst, newSecond, newThird)
    super.legacyWithNewChildren(newChildren)
  }
}

object SplitPartImpl {

  def evaluate(str: String, rex: String, index: Int): UTF8String = {
    val parts = str.split(rex)
    if (index - 1 < parts.length && index > 0) {
      UTF8String.fromString(parts(index - 1))
    } else if (index < 0 && Math.abs(index) <= parts.length) {
      UTF8String.fromString(parts(parts.length + index))
    } else {
      null
    }
  }
}

// scalastyle:on line.size.limit
