package edu.berkeley.cs.boom.molly.derivations

import edu.berkeley.cs.boom.molly.ast._
import com.typesafe.scalalogging.slf4j.Logging
import edu.berkeley.cs.boom.molly.ast.StringLiteral
import edu.berkeley.cs.boom.molly.ast.Rule
import edu.berkeley.cs.boom.molly.{FailureSpec, UltimateModel}
import edu.berkeley.cs.boom.molly.ast.Identifier
import scala.Some
import edu.berkeley.cs.boom.molly.ast.Program
import edu.berkeley.cs.boom.molly.report.GraphvizPrettyPrinter
import java.util.concurrent.atomic.AtomicInteger
import scalaz._
import Scalaz._
import scala.collection.mutable


object RuleGoalGraphGraphvizGenerator extends GraphvizPrettyPrinter {
  def toDot(goal: GoalNode): String = {
    val dot = "digraph" <+> "dataflow" <+> braces(nest(
      linebreak <>
        dotStatements(goal).reduce(_ <@@> _)
    ) <> linebreak)
    super.pretty(dot)
  }

  private def dotStatements(goal: GoalNode): List[Doc] = {
    val id = "goal" + goal.id
    val nodes = List(node(id, "label" -> goal.tuple.toString))
    val edges = goal.rules.map(rule => diEdge(id, "rule" + rule.id))
    nodes ++ edges ++ goal.rules.flatMap(dotStatements)
  }

  private def dotStatements(rule: RuleNode): List[Doc] = {
    val id = "rule" + rule.id
    val nodes = List(node(id, "label" -> rule.rule.head.tableName, "shape" -> "square"))
    val edges = rule.subgoals.map(goal => diEdge(id, "goal" + goal.id))
    nodes ++ edges ++ rule.subgoals.flatMap(dotStatements)
  }
}

case class GoalTuple(table: String, cols: List[String]) {
  override def toString: String = table + "(" + cols.mkString(", ") + ")"
}

/**
 * Represents a goal (fact to be proved).  Appears at the root
 * of the rule-goal graph.
 *
 * @param tuple the fact being proved.
 * @param rules a list of rules that derived this fact.  If this contains
 *              more than one rule, then there are multiple derivations of
 *              this fact.
 */
case class GoalNode(id: Int, tuple: GoalTuple, rules: Set[RuleNode]) {
  def importantClocks: Set[(String, String, Int)] = {
    val childrenClocks = rules.flatMap(_.subgoals).flatMap(_.importantClocks)
    val newClock = tuple match {
      case GoalTuple("clock", List(from, to, time, _))
        if from != to && to != ProvenanceReader.WILDCARD => Set((from, to, time.toInt))
      case _ => Set.empty
    }
    childrenClocks ++ newClock
  }

  def enumerateDistinctDerivations: Set[GoalNode] = {
    if (rules.isEmpty) {
      Set(this)
    } else {
      rules.flatMap(_.enumerateDistinctDerivationsOfSubGoals).map(r => this.copy(rules = Set(r)))
    }
  }
}

/**
 * Represents a concrete application of a rule.
 *
 * @param rule the rule that was applied.
 * @param subgoals the facts that were used in this rule application.
 */
case class RuleNode(id: Int, rule: Rule, subgoals: Set[GoalNode]) {
  require (!subgoals.isEmpty, "RuleNode must have subgoals")
  def enumerateDistinctDerivationsOfSubGoals: List[RuleNode] = {
    val choices: List[List[GoalNode]] = subgoals.map(_.enumerateDistinctDerivations.toList).toList
    choices.sequence.map(subgoalDerivations => this.copy(subgoals = subgoalDerivations.toSet))
  }
}

case class Message(table: String, from: String, to: String, sendTime: Int, receiveTime: Int)

object ProvenanceReader {
  val WILDCARD = "__WILDCARD__"
}

/**
 * Constructs rule-goal graphs from provenance captured during execution.
 */
class ProvenanceReader(program: Program,
                       failureSpec: FailureSpec,
                       model: UltimateModel) extends Logging {
  import ProvenanceReader._
  private val nextRuleNodeId = new AtomicInteger(0)
  private val nextGoalNodeId = new AtomicInteger(0)
  private val derivationTreeCache = new mutable.HashMap[GoalTuple, GoalNode]()

  def getDerivationTree(goalTuple: GoalTuple): GoalNode = {
    derivationTreeCache.getOrElseUpdate(goalTuple, buildDerivationTree(goalTuple))
  }

  def getDerivationTreesForTable(goal: String): List[GoalNode] = {
    model.tableAtTime(goal, failureSpec.eot).map(GoalTuple(goal, _)).map(getDerivationTree)
  }

  def getMessages: List[Message] = {
    val asyncRules = program.rules.filter(_.head.time == Some(Async()))
    logger.debug(s"Async rules are ${asyncRules.map(_.head.tableName)}")
    val tableNamePattern = """^(.*)_prov\d+$""".r
    asyncRules.flatMap { rule =>
      model.tables(rule.head.tableName).flatMap { tuple =>
        val bindings = provRowToVariableBindings(rule, tuple)
        val from = bindings(rule.head.cols(0).asInstanceOf[Identifier].name)
        val to = bindings(rule.bodyPredicates(0).cols(0).asInstanceOf[Identifier].name)
        val sendTime = bindings("NRESERVED").toInt
        val receiveTime = bindings("MRESERVED").toInt
        val tableName = tableNamePattern.findFirstMatchIn(rule.head.tableName).get.group(1)
        if (sendTime != failureSpec.eot)
          Some(Message(tableName, from, to, sendTime, receiveTime))
        else
          None
      }
    }
  }

  private def buildDerivationTree(goalTuple: GoalTuple): GoalNode = {
    logger.debug(s"Reading provenance for tuple $goalTuple")
    // First, check whether the goal tuple is part of the EDB:
    if (isInEDB(goalTuple)) {
      logger.debug(s"Found $goalTuple in EDB")
      return GoalNode(nextGoalNodeId.getAndIncrement, goalTuple, Set.empty)
    }
    // Otherwise, a rule must have derived it:
    val ruleFirings = findRuleFirings(goalTuple)
    assert (!ruleFirings.isEmpty, s"Couldn't find rules to derive tuple $goalTuple")
    logger.debug(s"Rule firings: $ruleFirings")
    val ruleNodes = ruleFirings.map { case (provRuleName, provTableRow) =>
      val provRule = program.rules.find(_.head.tableName == provRuleName).get
      val bindings = provRowToVariableBindings(provRule, provTableRow)
      // Substitute the variable bindings to determine the set of goals used by this rule.
      def substituteBindings(pred: Predicate): GoalTuple = {
        val goalCols = pred.cols.map {
          case StringLiteral(s) => s
          case IntLiteral(i) => i.toString
          case Identifier(ident) => bindings(ident)
          case agg: Aggregate =>
            throw new NotImplementedError("Don't know how to substitute for aggregate")
          case expr: Expr =>
            throw new NotImplementedError("Didn't expect expression to appear in predicate")
        }
        GoalTuple(pred.tableName, goalCols)
      }
      val newGoalTuples = provRule.bodyPredicates.filterNot(_.notin).map(substituteBindings)
      logger.debug(s"New subgoal tuples are $newGoalTuples")
      // As a sanity check, make sure that negated tuples don't appear in their respective tables
      // or any provenance tables:
      val notinTuples = provRule.bodyPredicates.filter(_.notin).map(substituteBindings)
      for (tuple <- notinTuples) {
        val matchingTuples = model.tables(tuple.table).filter(matchesPattern(tuple.cols))
        val ruleFirings = findRuleFirings(tuple)
        val internallyConsistent = matchingTuples.isEmpty == ruleFirings.isEmpty
        assert(internallyConsistent, "sTuple $tuple found without derivation (or vice-versa)")
        assert(matchingTuples.isEmpty, s"Found tuple ${matchingTuples(0)} that appears in a notin")
        assert(ruleFirings.isEmpty,
          s"Found rule firings $ruleFirings for tuple $tuple that appears in a notin")
      }
      // Recursively compute the provenance of the new goals:
      RuleNode(nextRuleNodeId.getAndIncrement,
        provRule, newGoalTuples.map(getDerivationTree).toSet)
    }
    GoalNode(nextGoalNodeId.getAndIncrement, goalTuple, ruleNodes)
  }

  private def findRuleFirings(goalTuple: GoalTuple) = {
    // Find provenance tables that might explain how we derived `goalTuple`:
    val provTables = program.tables.map(_.name).filter(_.matches(s"^${goalTuple.table}_prov\\d+"))
    logger.debug(s"Table '${goalTuple.table}' has provenance tables $provTables")
    // Check which of them have matching facts:
    provTables.map(table => (table, searchProvTable(goalTuple.cols, model.tables(table))))
      .filter(_._2.isDefined).map(x => (x._1, x._2.get))
  }

  private def isInEDB(goalTuple: GoalTuple): Boolean = {
    val goalEDB = program.facts.filter(_.tableName == goalTuple.table)
    for (fact <- goalEDB) {
      val list = fact.cols.map {
        case IntLiteral(i) => i.toString
        case StringLiteral(s) => s
        case v => throw new IllegalStateException(
          s"Facts shouldn't contain aggregates, expressions, or variables, but found $v")
      }
      if (matchesPattern(goalTuple.cols)(list)) {
        return true
      }
    }
    false
  }

  /**
   * Check tuples for compatibility while accounting for wildcards.
   */
  private def matchesPattern(goal: List[String])(tuple: List[String]): Boolean = {
    require(goal.size == tuple.size, "Mismatched sizes")
    goal.zip(tuple).forall {
      case (a, WILDCARD) => true
      case (WILDCARD, b) => true
      case (x, y) => x == y
    }
  }

  private def provRowToVariableBindings(provRule: Rule, provTableRow: List[String]):
    Map[String, String] = {
    // Given a row from the provenance table, we need to reconstruct the variable bindings.
    // Most of the time there will be a 1-to-1 mapping between row values and variable
    // binding values, but in some cases rules may have arithmetic in the head, such as
    //    bcast_prov4(N, P, NRESERVED + 1) :- log(N, P, MRESERVED), clock(N, _, NRESERVED, _);
    // In these cases, we might need to invert that arithmetic to find the actual variable binding.
    // Fortunately, the current method of generating the provenance rules ensures that those
    // bindings will also be recorded in the head, so we can just skip over expressions:
    require(provRule.head.cols.size == provTableRow.size, "Incorrect number of columns")
    val bindings = provRule.head.cols.zip(provTableRow).flatMap { case (atom, provValue) =>
      atom match {
        case Identifier(ident) => Some((ident, provValue))
        case _ => None
      }
    } ++ List("_" -> WILDCARD)
    bindings.toMap
  }

  private def searchProvTable(target: List[String], provFacts: List[List[String]]):
    Option[List[String]] = {
    // The provenance tables record _all_ variable bindings used in the rule firing, not just those
    // that appear in the rule head, so the provenance table's schema won't necessarily match the
    // original table.  Because of how we perform the rewriting, the two tables agree on the first
    // N-1 columns, then the provenance table may have extra columns.  For both tables, the last
    // column will always record the time.
    def matchesTarget(fact: List[String]): Boolean = {
      matchesPattern(target)(fact.take(target.size - 1) ++ List(fact.last))
    }
    provFacts.find(matchesTarget)
  }
}
