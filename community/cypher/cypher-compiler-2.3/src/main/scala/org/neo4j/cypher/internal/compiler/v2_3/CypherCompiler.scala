/**
 * Copyright (c) 2002-2015 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
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
package org.neo4j.cypher.internal.compiler.v2_3

import org.neo4j.cypher.internal.{ExecutionMode, LRUCache}
import org.neo4j.cypher.internal.compiler.v2_3.ast.Statement
import org.neo4j.cypher.internal.compiler.v2_3.ast.rewriters.{normalizeReturnClauses, normalizeWithClauses}
import org.neo4j.cypher.internal.compiler.v2_3.executionplan._
import org.neo4j.cypher.internal.compiler.v2_3.parser.{CypherParser, ParserMonitor}
import org.neo4j.cypher.internal.compiler.v2_3.planner._
import org.neo4j.cypher.internal.compiler.v2_3.planner.logical.{CachedMetricsFactory, SimpleMetricsFactory}
import org.neo4j.cypher.internal.compiler.v2_3.spi.PlanContext
import org.neo4j.graphdb.GraphDatabaseService
import org.neo4j.helpers.Clock

trait SemanticCheckMonitor {
  def startSemanticCheck(query: String)
  def finishSemanticCheckSuccess(query: String)
  def finishSemanticCheckError(query: String, errors: Seq[SemanticError])
}

trait AstRewritingMonitor {
  def startRewriting(queryText: String, statement: Statement)
  def abortedRewriting(obj: AnyRef)
  def finishRewriting(queryText: String, statement: Statement)
}

trait CypherCacheFlushingMonitor[T] {
  def cacheFlushDetected(justBeforeKey: T){}
}

trait CypherCacheHitMonitor[T] {
  def cacheHit(key: T){}
  def cacheMiss(key: T){}
  def cacheDiscard(key: T){}
}

trait InfoLogger {
  def info(message: String)
}

trait CypherCacheMonitor[T, E] extends CypherCacheHitMonitor[T] with CypherCacheFlushingMonitor[E]

trait AstCacheMonitor extends CypherCacheMonitor[Statement, CacheAccessor[Statement, ExecutionPlan]]

object CypherCompilerFactory {
  val monitorTag = "cypher2.3"

  def costBasedCompiler(graph: GraphDatabaseService, queryCacheSize: Int, statsDivergenceThreshold: Double,
                        queryPlanTTL: Long, clock: Clock, monitors: Monitors,
                        logger: InfoLogger,
                        notificationLoggerBuilder: (ExecutionMode => InternalNotificationLogger),
                        plannerName: CostBasedPlannerName): CypherCompiler = {
    val parser = new CypherParser(monitors.newMonitor[ParserMonitor[Statement]](monitorTag))
    val checker = new SemanticChecker(monitors.newMonitor[SemanticCheckMonitor](monitorTag))
    val rewriter = new ASTRewriter(monitors.newMonitor[AstRewritingMonitor](monitorTag))
    val planBuilderMonitor = monitors.newMonitor[NewLogicalPlanSuccessRateMonitor](monitorTag)
    val planningMonitor = monitors.newMonitor[PlanningMonitor](monitorTag)
    val metricsFactory = CachedMetricsFactory(SimpleMetricsFactory)
    val planner = CostBasedPipeBuilderFactory(monitors, metricsFactory, planningMonitor, clock, plannerName = plannerName)
    val pipeBuilder = new LegacyVsNewPipeBuilder(new LegacyPipeBuilder(monitors), planner, planBuilderMonitor)
    val execPlanBuilder = new ExecutionPlanBuilder(graph, statsDivergenceThreshold, queryPlanTTL, clock, pipeBuilder)
    val planCacheFactory = () => new LRUCache[Statement, ExecutionPlan](queryCacheSize)
    monitors.addMonitorListener(logStalePlanRemovalMonitor(logger), monitorTag)
    val cacheMonitor = monitors.newMonitor[AstCacheMonitor](monitorTag)
    val cache = new MonitoringCacheAccessor[Statement, ExecutionPlan](cacheMonitor)

    new CypherCompiler(parser, checker, execPlanBuilder, rewriter, cache, planCacheFactory, cacheMonitor, monitors, notificationLoggerBuilder)
  }

  def ruleBasedCompiler(graph: GraphDatabaseService, queryCacheSize: Int, statsDivergenceThreshold: Double,
                        queryPlanTTL: Long, clock: Clock, monitors: Monitors, notificationLoggerBuilder: (ExecutionMode => InternalNotificationLogger)): CypherCompiler = {
    val parser = new CypherParser(monitors.newMonitor[ParserMonitor[ast.Statement]](monitorTag))
    val checker = new SemanticChecker(monitors.newMonitor[SemanticCheckMonitor](monitorTag))
    val rewriter = new ASTRewriter(monitors.newMonitor[AstRewritingMonitor](monitorTag))
    val pipeBuilder = new LegacyPipeBuilder(monitors)

    val execPlanBuilder = new ExecutionPlanBuilder(graph, statsDivergenceThreshold, queryPlanTTL, clock, pipeBuilder)
    val planCacheFactory = () => new LRUCache[Statement, ExecutionPlan](queryCacheSize)
    val cacheMonitor = monitors.newMonitor[AstCacheMonitor](monitorTag)
    val cache = new MonitoringCacheAccessor[Statement, ExecutionPlan](cacheMonitor)

    new CypherCompiler(parser, checker, execPlanBuilder, rewriter, cache, planCacheFactory, cacheMonitor, monitors, notificationLoggerBuilder)
  }

  private def logStalePlanRemovalMonitor(log: InfoLogger) = new AstCacheMonitor {
    override def cacheDiscard(key: Statement) {
      log.info(s"Discarded stale query from the query cache: $key")
    }
  }
}

case class CypherCompiler(parser: CypherParser,
                          semanticChecker: SemanticChecker,
                          executionPlanBuilder: ExecutionPlanBuilder,
                          astRewriter: ASTRewriter,
                          cacheAccessor: CacheAccessor[Statement, ExecutionPlan],
                          planCacheFactory: () => LRUCache[Statement, ExecutionPlan],
                          cacheMonitor: CypherCacheFlushingMonitor[CacheAccessor[Statement, ExecutionPlan]],
                          monitors: Monitors,
                          notificationLoggerBuilder: (ExecutionMode => InternalNotificationLogger)) {

  def planQuery(queryText: String, context: PlanContext, executionMode: ExecutionMode, offset: Option[InputPosition] = None): (ExecutionPlan, Map[String, Any]) =
    planPreparedQuery(prepareQuery(queryText, executionMode), context)

  def prepareQuery(queryText: String, executionMode: ExecutionMode, offset: Option[InputPosition] = None): PreparedQuery = {

    val notificationLogger = notificationLoggerBuilder(executionMode)
    val parsedStatement = parser.parse(queryText, offset)

    val cleanedStatement: Statement = parsedStatement.endoRewrite(inSequence(normalizeReturnClauses, normalizeWithClauses))
    val originalSemanticState = semanticChecker.check(queryText, cleanedStatement, notificationLogger, offset)

    val (rewrittenStatement, extractedParams, postConditions) = astRewriter.rewrite(queryText, cleanedStatement, originalSemanticState)
    val postRewriteSemanticState = semanticChecker.check(queryText, rewrittenStatement, devNullLogger, offset)

    val table = SemanticTable(types = postRewriteSemanticState.typeTable, recordedScopes = postRewriteSemanticState.recordedScopes)
    PreparedQuery(rewrittenStatement, queryText, extractedParams)(table, postConditions, postRewriteSemanticState.scopeTree, notificationLogger)
  }

  def planPreparedQuery(parsedQuery: PreparedQuery, context: PlanContext): (ExecutionPlan, Map[String, Any]) = {
    val cache = provideCache(cacheAccessor, cacheMonitor, context)
    var planned = false
    val plan = Iterator.continually {
      cacheAccessor.getOrElseUpdate(cache)(parsedQuery.statement, {
        planned = true
        executionPlanBuilder.build(context, parsedQuery)
      })
    }.flatMap { plan =>
      if ( !planned && plan.isStale(context.txIdProvider, context.statistics) ) {
        cacheAccessor.remove(cache)(parsedQuery.statement)
        None
      } else {
        Some(plan)
      }
    }.next()
    (plan, parsedQuery.extractedParams)
  }

  private def provideCache(cacheAccessor: CacheAccessor[Statement, ExecutionPlan],
                           monitor: CypherCacheFlushingMonitor[CacheAccessor[Statement, ExecutionPlan]],
                           context: PlanContext) =
    context.getOrCreateFromSchemaState(cacheAccessor, {
      monitor.cacheFlushDetected(cacheAccessor)
      planCacheFactory()
    })
}
