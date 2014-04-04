package edu.berkeley.cs.boom.molly

import edu.berkeley.cs.boom.molly.ast._
import edu.berkeley.cs.boom.molly.ast.StringLiteral
import edu.berkeley.cs.boom.molly.ast.Expr
import edu.berkeley.cs.boom.molly.ast.Aggregate
import edu.berkeley.cs.boom.molly.ast.Program
import org.jgrapht.alg.util.UnionFind
import scala.collection.JavaConverters._

object DedalusTyper {

  private type Type = String
  private type ColRef = (String, Int)  // (tableName, columnNumber) pairs
  private val INT: Type = "int"
  private val STRING: Type = "string"
  private val UNKNOWN: Type = "unknown"

  private def inferTypeOfAtom(atom: Atom): Type = {
    atom match {
      case Identifier("MRESERVED") => INT
      case Identifier("NRESERVED") => INT
      case StringLiteral(_) => STRING
      case IntLiteral(_) => INT
      case a: Aggregate => INT
      case e: Expr => INT
      case _ => UNKNOWN
    }
  }

  /**
   * Infers the types of predicate columns.
   *
   * The possible column types are 'string' and 'int'.
   *
   * @param program the program to type
   * @return a copy of the program with its `tables` field filled in.
   */
  def inferTypes(program: Program): Program = {
    require (program.tables.isEmpty, "Program is already typed!")
    val allPredicates = program.facts ++ program.rules.map(_.head) ++
      program.rules.flatMap(_.bodyPredicates)
    val allColRefs = for (
      pred <- allPredicates;
      (col, colNum) <- pred.cols.zipWithIndex
    ) yield (pred.tableName, colNum)

    // Determine (maximal) sets of columns that must have the same type:
    val colRefToMinColRef = new UnionFind[ColRef](allColRefs.toSet.asJava)

    // Find multiple occurrences of variable in rules; these columns must have the same type:
    for (
      rule <- program.rules;
      sameTypedCols <- rule.variablesWithIndexes.groupBy(_._1).values.map(x => x.map(_._2));
      firstColRef = sameTypedCols.head;
      colRef <- sameTypedCols
    ) {
      colRefToMinColRef.union(colRef, firstColRef)
    }

    // Accumulate all type evidence:
    val typeEvidence: Map[ColRef, Set[Type]] = {
      val inferredFromPredicates = for (
        pred <- allPredicates;
        (col, colNum) <- pred.cols.zipWithIndex;
        inferredType = inferTypeOfAtom(col)
        if inferredType != UNKNOWN
      ) yield (colRefToMinColRef.find((pred.tableName, colNum)), inferredType)
      val inferredFromQuals = for (
        rule <- program.rules;
        expressionVars = rule.bodyQuals.flatMap(_.variables);
        (col, colNum) <- rule.head.cols.zipWithIndex
        if col.isInstanceOf[Identifier]
        if expressionVars.contains(col.asInstanceOf[Identifier])
      ) yield (colRefToMinColRef.find((rule.head.tableName, colNum)), INT)
      val evidence = inferredFromPredicates ++ inferredFromQuals
      evidence.groupBy(_._1).mapValues(_.map(_._2).toSet)
    }

    // Check that all occurrences of a given predicate have the same number of columns:
    val numColsInTable = allPredicates.groupBy(_.tableName).mapValues { predicates =>
      val colCounts = predicates.map(_.cols.size).toSet
      assert(colCounts.size == 1,
        s"Predicate ${predicates.head.tableName} used with inconsistent number of columns")
      colCounts.head
    }

    // Assign types to each group of columns:
    val tableNames = allPredicates.map(_.tableName).toSet
    val tables = tableNames.map { tableName =>
      val numCols = numColsInTable(tableName)
      val colTypes = (0 to numCols - 1).map { colNum =>
        val representative = colRefToMinColRef.find((tableName, colNum))
        val types = typeEvidence.getOrElse(representative,
          throw new Exception(
            s"No evidence for type of column ${representative._2} of ${representative._1}"))
        assert(types.size == 1,
          s"Conflicting evidence for type of column $colNum of $tableName: $types")
        types.head
      }
      Table(tableName, colTypes.toList)
    }
    program.copy(tables = tables)
  }
}
