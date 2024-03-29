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

#include <DataTypes/DataTypeDateTime64.h>
#include <DataTypes/DataTypeNullable.h>
#include <DataTypes/DataTypesNumber.h>
#include <Parser/FunctionParser.h>
#include <Common/DateLUTImpl.h>

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
using namespace DB;

static const std::map<std::string, std::string> KE_UNIT_TO_CH_FUNCTION
    = {{"FRAC_SECOND", "addMicroseconds"},
       {"SQL_TSI_FRAC_SECOND", "addMicroseconds"},
       {"MICROSECOND", "addMicroseconds"},
       {"MILLISECOND", "addMilliseconds"},
       {"SECOND", "addSeconds"},
       {"SQL_TSI_SECOND", "addSeconds"},
       {"MINUTE", "addMinutes"},
       {"SQL_TSI_MINUTE", "addMinutes"},
       {"HOUR", "addHours"},
       {"SQL_TSI_HOUR", "addHours"},
       {"DAY", "addDays"},
       {"DAYOFYEAR", "addDays"},
       {"SQL_TSI_DAY", "addDays"},
       {"WEEK", "addWeeks"},
       {"SQL_TSI_WEEK", "addWeeks"},
       {"MONTH", "addMonths"},
       {"SQL_TSI_MONTH", "addMonths"},
       {"QUARTER", "addQuarters"},
       {"SQL_TSI_QUARTER", "addQuarters"},
       {"YEAR", "addYears"},
       {"SQL_TSI_YEAR", "addYears"}};


class FunctionParserTimestampAdd : public FunctionParser
{
public:
    explicit FunctionParserTimestampAdd(ParserContextPtr parser_context_) : FunctionParser(parser_context_) { }
    ~FunctionParserTimestampAdd() override = default;

    static constexpr auto name = "timestampadd";

    String getName() const override { return name; }
    String getCHFunctionName(const substrait::Expression_ScalarFunction &) const override { return "timestamp_add"; }

    const ActionsDAG::Node * parse(const substrait::Expression_ScalarFunction & substrait_func, ActionsDAG & actions_dag) const override
    {
        auto parsed_args = parseFunctionArguments(substrait_func, actions_dag);
        if (parsed_args.size() < 3)
            throw Exception(ErrorCodes::NUMBER_OF_ARGUMENTS_DOESNT_MATCH, "Function {} requires at least three arguments", getName());

        String timezone;
        if (parsed_args.size() == 4)
        {
            const auto & timezone_field = substrait_func.arguments().at(3);
            if (!timezone_field.value().has_literal() || !timezone_field.value().literal().has_string())
            throw Exception(
                ErrorCodes::BAD_ARGUMENTS,
                "Unsupported timezone_field argument, should be a string literal, but: {}",
                timezone_field.DebugString());
            timezone = timezone_field.value().literal().string();
        }

        const auto & unit_field = substrait_func.arguments().at(0);

        return unit_field.value().has_literal() ? parseLiteralFunction(substrait_func, parsed_args, actions_dag, timezone)
                                                : parseOtherFunction(substrait_func, parsed_args, actions_dag, timezone);
    }

    const ActionsDAG::Node * parseLiteralFunction(
        const substrait::Expression_ScalarFunction & substrait_func,
        const ActionsDAG::NodeRawConstPtrs & parsed_args,
        ActionsDAG & actions_dag,
        const String & timezone) const
    {
        const auto & unit_field = substrait_func.arguments().at(0);
        const auto & unit = Poco::toUpper(unit_field.value().literal().string());

        if (!KE_UNIT_TO_CH_FUNCTION.contains(unit))
            throw Exception(ErrorCodes::BAD_ARGUMENTS, "Unsupported unit argument: {}", unit);

        const std::string & ch_function_name = KE_UNIT_TO_CH_FUNCTION.at(unit);
        ActionsDAG::NodeRawConstPtrs args = {parsed_args[2], parsed_args[1]};
        if (timezone.empty())
        {
            const ActionsDAG::Node * result_node = toFunctionNode(actions_dag, ch_function_name, args);
            return convertNodeTypeIfNeeded(substrait_func, result_node, actions_dag);
        }

        const auto * time_zone_node = addColumnToActionsDAG(actions_dag, std::make_shared<DataTypeString>(), timezone);
        if (isDateTimeOrDateTime64(parsed_args[2]->result_type))
        {
            args.emplace_back(time_zone_node);
            const ActionsDAG::Node * result_node = toFunctionNode(actions_dag, ch_function_name, args);
            return convertNodeTypeIfNeeded(substrait_func, result_node, actions_dag);
        }

        const ActionsDAG::Node * result_node = toFunctionNode(actions_dag, ch_function_name, args);
        const auto * scale_node = addColumnToActionsDAG(actions_dag, std::make_shared<DataTypeUInt32>(), 6);
        return convertNodeTypeIfNeeded(
            substrait_func, toFunctionNode(actions_dag, "toDateTime64", {result_node, scale_node, time_zone_node}), actions_dag);
    }


    const ActionsDAG::Node * parseOtherFunction(
        const substrait::Expression_ScalarFunction & substrait_func,
        const ActionsDAG::NodeRawConstPtrs & parsed_args,
        ActionsDAG & actions_dag,
        const String & timezone) const
    {
        const DB::ActionsDAG::Node * timezone_node;
        if (!timezone.empty())
            timezone_node = addColumnToActionsDAG(actions_dag, std::make_shared<DataTypeString>(), timezone);

        const auto * scale_node = addColumnToActionsDAG(actions_dag, std::make_shared<DataTypeUInt32>(), 6);
        const auto * from_node = parsed_args[2];
        if (!isDateTimeOrDateTime64(from_node->result_type))
        {
            ActionsDAG::NodeRawConstPtrs from_node_convert_args = {from_node, scale_node};
            if (!timezone.empty())
                from_node_convert_args.emplace_back(timezone_node);

            from_node = toFunctionNode(actions_dag, "toDateTime64", from_node_convert_args);
        }

        ActionsDAG::NodeRawConstPtrs multi_if_args;
        ActionsDAG::NodeRawConstPtrs timestamp_add_args = {from_node, parsed_args[1]};
        if (!timezone.empty())
            timestamp_add_args.emplace_back(timezone_node);

        for (const auto & ke_unit_to_ch_function : KE_UNIT_TO_CH_FUNCTION)
        {
            const auto * unit_node = addColumnToActionsDAG(actions_dag, std::make_shared<DataTypeString>(), ke_unit_to_ch_function.first);
            ActionsDAG::NodeRawConstPtrs mutiif_args = {parsed_args[0], unit_node};
            multi_if_args.emplace_back(toFunctionNode(actions_dag, "equals", mutiif_args));
            multi_if_args.emplace_back(toFunctionNode(actions_dag, ke_unit_to_ch_function.second, timestamp_add_args));
        }

        multi_if_args.emplace_back(
            addColumnToActionsDAG(actions_dag, std::make_shared<DataTypeNullable>(std::make_shared<DataTypeDateTime64>(6)), Field{}));
        const ActionsDAG::Node * result_node = toFunctionNode(actions_dag, "multiIf", multi_if_args);
        return convertNodeTypeIfNeeded(substrait_func, result_node, actions_dag);
    }
};
static FunctionParserRegister<FunctionParserTimestampAdd> register_timestamp_add;
}
