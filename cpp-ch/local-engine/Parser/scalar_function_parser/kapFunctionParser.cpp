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
#include <Parser/FunctionParser.h>
#include <Common/Exception.h>

namespace local_engine
{
#define REGISTER_KAP_FUNCTION_PARSER(cls_name, substrait_name, ch_name) \
   class KapFunctionParser##cls_name : public FunctionParser \
   { \
   public: \
       KapFunctionParser##cls_name(ParserContextPtr parser_context_) : FunctionParser(parser_context_) \
       { \
       } \
       ~KapFunctionParser##cls_name() override = default; \
       static constexpr auto name = #substrait_name; \
       String getName() const override \
       { \
           return #substrait_name; \
       } \
       String getCHFunctionName(const substrait::Expression_ScalarFunction & /*substrait_func*/) const override \
       { \
           return #ch_name; \
       } \
   }; \
   static const FunctionParserRegister<KapFunctionParser##cls_name> register_scalar_function_parser_##cls_name;

REGISTER_KAP_FUNCTION_PARSER(KeBitmapCardinality, ke_bitmap_cardinality, keBitmapCardinality);
REGISTER_KAP_FUNCTION_PARSER(KapMonthsBetween, kap_months_between, kapMonthsBetween);
REGISTER_KAP_FUNCTION_PARSER(KapYmdIntBetween, kap_ymd_int_between, kapYmdIntBetween);
REGISTER_KAP_FUNCTION_PARSER(Truncate, truncate, truncate);

}


