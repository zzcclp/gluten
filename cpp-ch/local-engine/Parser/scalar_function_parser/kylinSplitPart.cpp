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

#include <DataTypes/DataTypeNullable.h>
#include <DataTypes/DataTypesNumber.h>
#include <Parser/FunctionParser.h>

namespace DB
{
namespace ErrorCodes
{
extern const int NUMBER_OF_ARGUMENTS_DOESNT_MATCH;
}
}

namespace local_engine
{
using namespace DB;

class FunctionParserKylinSplitPart : public FunctionParser
{
public:
   explicit FunctionParserKylinSplitPart(ParserContextPtr parser_context_) : FunctionParser(parser_context_) {}
   ~FunctionParserKylinSplitPart() override = default;

   static constexpr auto name = "kylin_split_part";

   String getName() const override { return name; }

   const ActionsDAG::Node * parse(
       const substrait::Expression_ScalarFunction & substrait_func,
       ActionsDAG & actions_dag) const override
   {
       /*
            parse kylin_split_part(str, rex, idx) as
            parts = splitByRegexp(rex, str)
            if (abs(idx) > 0 && abs(idx) <= parts.length)
                arrayElement(parts, idx)
            else
                null
        */
       auto parsed_args = parseFunctionArguments(substrait_func, "", actions_dag);
       if (parsed_args.size() != 3)
           throw Exception(ErrorCodes::NUMBER_OF_ARGUMENTS_DOESNT_MATCH, "Function {} requires exactly three arguments", getName());

       const auto * str_node = parsed_args[0];
       const auto * rex_node = parsed_args[1];
       const auto * idx_node = parsed_args[2];

       const auto * parts_node = toFunctionNode(actions_dag, "splitByRegexp", {rex_node, str_node});
       const auto * length_node = toFunctionNode(actions_dag, "length", {parts_node});

       // abs(idx) > 0 && abs(idx) <= parts.length
       const auto * zero_const_node = addColumnToActionsDAG(actions_dag, std::make_shared<DB::DataTypeUInt8>(), 0);
       const auto * abs_idx_node = toFunctionNode(actions_dag, "abs", {idx_node});
       const auto * abs_idx_gt_zero_node = toFunctionNode(actions_dag, "greater", {abs_idx_node, zero_const_node});
       const auto * abs_idx_le_length_node = toFunctionNode(actions_dag, "lessOrEquals", {abs_idx_node, length_node});
       const auto * condition_node = toFunctionNode(actions_dag, "and", {abs_idx_gt_zero_node, abs_idx_le_length_node});
       const auto * then_node = toFunctionNode(actions_dag, "arrayElement", {parts_node, idx_node});

       // NULL
       auto result_type = std::make_shared<DataTypeString>();
       const auto * null_const_node = addColumnToActionsDAG(actions_dag, makeNullable(result_type), Field());

       // if
       const auto * result_node = toFunctionNode(actions_dag, "if", {condition_node, then_node, null_const_node});

       return convertNodeTypeIfNeeded(substrait_func, result_node, actions_dag);
   }
};

static FunctionParserRegister<FunctionParserKylinSplitPart> register_kylin_split_part;
}
