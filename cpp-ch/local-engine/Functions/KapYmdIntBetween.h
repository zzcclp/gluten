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
#pragma once

#include <Common/DateLUT.h>
#include <Common/DateLUTImpl.h>
#include <Columns/IColumn.h>
#include <Core/ColumnsWithTypeAndName.h>
#include <DataTypes/DataTypeDate.h>
#include <DataTypes/DataTypeString.h>
#include <DataTypes/DataTypesNumber.h>
#include <DataTypes/DataTypeNullable.h>
#include <DataTypes/IDataType.h>
#include <Functions/IFunction.h>
#include <Interpreters/Context.h>
#include <base/types.h>
#include <boost/format.hpp>

using namespace DB;
namespace DB
{
namespace ErrorCodes
{
extern const int NUMBER_OF_ARGUMENTS_DOESNT_MATCH;
extern const int ILLEGAL_TYPE_OF_ARGUMENT;
}
}

namespace local_engine
{
class KapYmdIntBetween : public DB::IFunction
{
public:
    static constexpr auto name = "kapYmdIntBetween";
    static DB::FunctionPtr create(DB::ContextPtr) { return std::make_shared<KapYmdIntBetween>();}
    KapYmdIntBetween() = default;
    ~KapYmdIntBetween() override = default;

    String getName() const override { return name; }
    size_t getNumberOfArguments() const override { return 2; }
    bool isSuitableForShortCircuitArgumentsExecution(const DB::DataTypesWithConstInfo & /*arguments*/) const override { return true; }

    DB::DataTypePtr getReturnTypeImpl(const DB::DataTypes & arguments) const override {
        if (arguments.size() != 2)
            throw DB::Exception(DB::ErrorCodes::NUMBER_OF_ARGUMENTS_DOESNT_MATCH,
                                "Number of arguments for function {} doesn't match: passed {}, should be 2.",
                                getName(), arguments.size());

        if (!isDate32(arguments[0]) || !isDate32(arguments[1]))
            throw DB::Exception(DB::ErrorCodes::ILLEGAL_TYPE_OF_ARGUMENT,
                                "Arguments for function {} must be Date", getName());
        return std::make_shared<DataTypeString>();
    }

    DB::ColumnPtr executeImpl(
        const DB::ColumnsWithTypeAndName & arguments, const DB::DataTypePtr & result_type, size_t rows_cnt) const override {
        const IColumn & col1 = *arguments[0].column;
        const IColumn & col2 = *arguments[1].column;
        auto res = result_type->createColumn();
        const DateLUTImpl & date_lut = DateLUT::instance();
        for (size_t i = 0; i < rows_cnt; ++i)
        {
            DB::Field f1;
            DB::Field f2;
            col1.get(i, f1);
            col2.get(i, f2);
            ExtendedDayNum d1 = ExtendedDayNum(f1.safeGet<Int32>());
            ExtendedDayNum d2 = ExtendedDayNum(f2.safeGet<Int32>());
            res->insert(kapYmdIntBetween(d1, d2, date_lut));
        }
        return res;
    }

private:
    String kapYmdIntBetween(ExtendedDayNum & d1, ExtendedDayNum & d2, const DateLUTImpl & date_lut) const
    {
        if (d1 == d2) {
            return "00000";
        }
        // from d1 to d2
        Int32 d1_year = date_lut.toYear(d1);
        Int32 d2_year = date_lut.toYear(d2);
        Int32 d1_month = date_lut.toMonth(d1);
        Int32 d2_month = date_lut.toMonth(d2);
        Int32 d1_day = date_lut.toDayOfMonth(d1);
        Int32 d2_day = date_lut.toDayOfMonth(d2);
        Int32 month_diff = (d2_year - d1_year) * 12 + d2_month - d1_month;
        Int32 day_diff = 0;

        if (d1 < d2) {
            // add months and add days
            if (d2_day < d1_day) {
                day_diff = d2_day + date_lut.daysInMonth(date_lut.addMonths(d2, -1)) - d1_day;
                month_diff--;
            } else {
                day_diff = d2_day - d1_day;
            }
        } else {
            // subtract months and subtract days
            if (d1_day < d2_day) {
                day_diff = d2_day - d1_day - date_lut.daysInMonth(d2);
                month_diff++;
            } else {
                day_diff = d2_day - d1_day;
            }
        }
        Int32 year_diff = month_diff / 12;
        month_diff = month_diff % 12;
        return (boost::format("%d%02d%02d") % std::abs(year_diff) % std::abs(month_diff) % std::abs(day_diff)).str();
    }
};
}