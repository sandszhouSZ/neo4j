/*
 * Copyright (c) 2002-2018 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.v3_5.planner.logical

import org.neo4j.cypher.internal.compiler.v3_5.planner.logical.steps.{alignGetValueFromIndexBehavior, countStorePlanner, verifyBestPlan}
import org.neo4j.cypher.internal.ir.v3_5.PlannerQuery
import org.neo4j.cypher.internal.planner.v3_5.spi.PlanningAttributes.{Cardinalities, Solveds}
import org.neo4j.cypher.internal.v3_5.logical.plans.LogicalPlan
import org.opencypher.v9_0.util.attribution.{Attributes, IdGen}

/*
This coordinates PlannerQuery planning and delegates work to the classes that do the actual planning of
QueryGraphs and EventHorizons
 */
case class PlanSingleQuery(planPart: (PlannerQuery, LogicalPlanningContext, Solveds, Cardinalities) => LogicalPlan = planPart,
                           planEventHorizon: (PlannerQuery, LogicalPlan, LogicalPlanningContext, Solveds, Cardinalities) => (LogicalPlan, LogicalPlanningContext) = PlanEventHorizon,
                           planWithTail: (LogicalPlan, Option[PlannerQuery], LogicalPlanningContext, Solveds, Cardinalities, Attributes) => (LogicalPlan, LogicalPlanningContext) = PlanWithTail(),
                           planUpdates: (PlannerQuery, LogicalPlan, Boolean, LogicalPlanningContext, Solveds, Cardinalities) => (LogicalPlan, LogicalPlanningContext) = PlanUpdates)
  extends ((PlannerQuery, LogicalPlanningContext, Solveds, Cardinalities, IdGen) => (LogicalPlan, LogicalPlanningContext)) {

  override def apply(in: PlannerQuery, context: LogicalPlanningContext,
                     solveds: Solveds, cardinalities: Cardinalities, idGen: IdGen): (LogicalPlan, LogicalPlanningContext) = {
    val (completePlan, ctx) =
      countStorePlanner(in, context, solveds, cardinalities) match {
        case Some((plan, afterCountStoreContext)) =>
          (plan, afterCountStoreContext.withUpdatedCardinalityInformation(plan, solveds, cardinalities))
        case None =>

          // context for this query, which aligns getValueFromIndexBehavior
          val queryContext = context.withLeafPlanUpdater(alignGetValueFromIndexBehavior(in, context.logicalPlanProducer, Attributes(context.logicalPlanProducer.idGen, solveds, cardinalities)))

          val partPlan = planPart(in, queryContext, solveds, cardinalities)
          val (planWithUpdates, contextAfterUpdates) = planUpdates(in, partPlan, true /*first QG*/ , queryContext, solveds, cardinalities)
          val (projectedPlan, contextAfterHorizon) = planEventHorizon(in, planWithUpdates, contextAfterUpdates, solveds, cardinalities)
          val projectedContext = contextAfterHorizon.withUpdatedCardinalityInformation(projectedPlan, solveds, cardinalities)
          (projectedPlan, projectedContext)
      }

    val (finalPlan, finalContext) = planWithTail(completePlan, in.tail, ctx, solveds, cardinalities, Attributes(idGen))
    (verifyBestPlan(finalPlan, in, finalContext, solveds, cardinalities), finalContext)
  }
}
