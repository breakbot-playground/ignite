/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.query.calcite.rule.logical;

import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelCollationTraitDef;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.RelFactories;
import org.apache.calcite.rel.logical.LogicalFilter;
import org.apache.calcite.rel.metadata.RelMdCollation;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.ignite.internal.processors.query.calcite.rel.IgniteRel;
import org.apache.ignite.internal.processors.query.calcite.rel.logical.IgniteLogicalFilter;

/**
 *
 */
public class IgniteFilterRule extends RelOptRule {
    public static final RelOptRule INSTANCE = new IgniteFilterRule();

    private IgniteFilterRule() {
        super(operand(LogicalFilter.class, Convention.NONE, any()), RelFactories.LOGICAL_BUILDER, "IgniteFilterRule");
    }

    @Override public void onMatch(RelOptRuleCall call) {
        LogicalFilter filter = call.rel(0);
        final RelNode input = filter.getInput();
        final RelOptCluster cluster = input.getCluster();
        final RelMetadataQuery mq = cluster.getMetadataQuery();
        final RelTraitSet traitSet = cluster.traitSet()
            .replace(IgniteRel.LOGICAL_CONVENTION)
            .replaceIfs(RelCollationTraitDef.INSTANCE,
                () -> RelMdCollation.filter(mq, input));
        RelNode convertedInput = convert(input, traitSet);
        call.transformTo(new IgniteLogicalFilter(cluster, traitSet, convertedInput, filter.getCondition(), filter.getVariablesSet()));
    }
}