/* Ayasdi Inc. Copyright 2014 - all rights reserved. */
/**
 * @author mohit
 *  big dataframe on spark
 */
package com.ayasdi.bigdf

import scala.collection.mutable.HashMap
import scala.util.Try
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.rdd.RDD
import org.apache.commons.csv._
import scala.reflect.ClassTag
import scala.reflect.runtime.universe._
import scala.reflect.ClassTag
import com.ayasdi.bigdf.Preamble._
import scala.collection.JavaConversions
import scala.reflect.{ ClassTag, classTag }
import scala.reflect.runtime.{ universe => ru }

/**
 * types of joins
 */
object JoinType extends Enumeration {
    type JoinType = Value
    val Inner, Outer = Value
}

object ColumnType extends Enumeration {
    type ColumnType = Value
    val String, Double = Value
}

/*
 * Data Frame is a map of column key to an RDD containing that column
 * constructor is private, instances are created by factory calls(apply) in 
 * companion object
 * Number of rows cannot change. Columns can be added, removed, mutated
 */
case class DF private (val sc: SparkContext,
                       val cols: HashMap[String, Column[Any]] = new HashMap[String, Column[Any]],
                       val colIndexToName: HashMap[Int, String] = new HashMap[Int, String]) {
    def numCols = cols.size
    lazy val numRows = if (cols.head._2 == null) 0 else cols.head._2.rdd.count

    override def toString() = {
        "Silence is golden" //otherwise prints too much stuff
    }

    /*
     * columns are zip'd together to get rows
     */
    private def computeRows: RDD[Array[Any]] = {
        val first = cols(colIndexToName(0)).rdd
        val rest = (1 until colIndexToName.size).toList.map { i => cols(colIndexToName(i)).rdd }

        //if you get a compile error here, you have the wrong spark
        //get my forked version or patch yours from my pull request
        //https://github.com/apache/spark/pull/2429  
        first.zip(rest) //if you get a compile error here, you have the wrong spark
    }
    private var rowsRddCached: RDD[Array[Any]] = null
    def rowsRdd = {
        if (rowsRddCached != null) {
            rowsRddCached
        } else {
            rowsRddCached = computeRows
            rowsRddCached
        }
    }

    /*
     * add column keys, returns number of columns added
     */
    private def addHeader(header: Array[String]): Int = {
        addHeader(header.iterator)
    }

    /*
     * add column keys, returns number of columns added
     */
    private def addHeader(header: Iterator[String]) = {
        var i = 0
        header.foreach { colName =>
            cols.put(colName, null)
            colIndexToName.put(i, colName)
            i += 1
        }
        i
    }

    /**
     * get a column identified by name
     */
    def column(colName: String) = {
        val col = cols.getOrElse(colName, null)
        if (col == null) println(s"Hmm...didn't find that column ${colName}. Do you need a spellchecker?")
        col
    }
    /**
     * get a column identified by name
     */
    def apply(colName: String) = column(colName)
    
    /**
     * get multiple columns identified by name
     */
    def columnsByNames(colNames: Seq[String]) = {
        val selectedCols = for (colName <- colNames)
            yield (colName, cols.getOrElse(colName, null))
        if (selectedCols.exists(_._2 == null)) {
            val notFound = selectedCols.filter(_._2 == null)
            println("You sure? I don't know about these columns" + notFound.mkString(","))
            null
        } else {
            new ColumnSeq(selectedCols)
        }
    }

    /**
     * get columns with numeric index
     * FIXME: solo has to be "5 to 5" for now, should be just "5"
     */
    def columnsByRanges(indexRanges: Seq[Range]) = {
        val selectedCols = for (
            indexRange <- indexRanges;
            index <- indexRange;
            if (colIndexToName(index) != null)
        ) yield (colIndexToName(index), cols.getOrElse(colIndexToName(index), null))

        new ColumnSeq(selectedCols)
    }
    
    /**
     * get columns with numeric index
     * FIXME: solo has to be "5 to 5" for now, should be just "5"
     */
    def columnsByIndexes(indexes: Seq[Int]) = {
        val indexRanges = indexes.map { i => i to i }
        columnsByRanges(indexRanges)
    }
    
    def apply[T: ru.TypeTag](items: T*): ColumnSeq = {
        val tpe = ru.typeOf[T]
        if(tpe =:= ru.typeOf[Int]) columnsByIndexes(items.asInstanceOf[Seq[Int]])
        else if(tpe =:= ru.typeOf[String]) columnsByNames(items.asInstanceOf[Seq[String]])
        else if(tpe =:= ru.typeOf[Range] || tpe =:= ru.typeOf[Range.Inclusive]) columnsByRanges(items.asInstanceOf[Seq[Range]])
        else { println("got " + tpe); null }
    }
    
    private def filter(cond: Condition) = {
        rowsRdd.filter(row => cond.check(row))
    }
    /**
     * wrapper on filter to create a new DF from filtered RDD
     */
    def where(cond: Condition): DF = {
        val filteredRows = filter(cond)
        fromRows(filteredRows)
    }

    /**
     * allow omitting keyword "where"
     */
    def apply(cond: Condition) = where(cond)

    /**
     * update a column, add or replace
     */
    def update(colName: String, that: Column[Any]) = {
        val col = cols.getOrElse(colName, null)

        cols.put(colName, that)
        if (col != null) {
            println(s"Replaced Column: ${colName}")
        } else {
            println(s"New Column: ${colName} ")
            val colIndex = colIndexToName.size
            colIndexToName.put(colIndex, colName)
            that.index = colIndex
        }
        rowsRddCached = null //invalidate cached rows
        //FIXME: incremental computation of rows
    }

    /**
     * rename columns, modify same DF or make a new one
     */
    def rename(columns: Map[String, String], inPlace: Boolean = true) = {
        val df = if (inPlace == false) new DF(sc, cols.clone, colIndexToName.clone) else this

        columns.foreach {
            case (oldName, newName) =>
                val col = df.cols.remove(oldName)
                if (!col.isEmpty) {
                    val (i, n) = df.colIndexToName.find(x => x._2 == oldName).get
                    df.colIndexToName.put(i, newName)
                    df.cols.put(newName, col.get)
                    println(s"${oldName}[${i}] --> ${newName}")
                } else {
                    println(s"Wazz that? I can't find ${oldName}, skipping it")
                }
        }
        df
    }

    /**
     * number of rows that have NA(NaN or empty string)
     */
    def countRowsWithNA = {
        rowsRddCached = null //fillNA could have mutated columns, recalculate rows
        val x = rowsRdd.map { row => if (DFUtils.countNaN(row) > 0) 1 else 0 }
        x.reduce { _ + _ }
    }

    /**
     * number of columns that have NA(NaN or empty string)
     */
    def countColsWithNA = {
        cols.map { col => if (col._2.hasNA) 1 else 0 }.reduce { _ + _ }
    }

    /**
     * create a new DF after removing all rows that had NAs(NaNs or empty strings)
     */
    def dropNA = {
        val rows = rowsRdd.filter { row => DFUtils.countNaN(row) == 0 }
        fromRows(rows)
    }

    /*
     * augment with key
     */
    private def keyBy(colName: String) = {
        cols(colName).rdd
    }.asInstanceOf[RDD[Any]].zip(rowsRdd)

    /**
     * group by a column, uses a lot of memory. try to use aggregate(By) instead if possible
     */
    def groupBy(colName: String) = {
        keyBy(colName).groupByKey
    }

    /**
     * group by multiple columns, uses a lot of memory. try to use aggregate(By) instead if possible
     */
    def groupBy(colNames: String*) = {
        val columns = colNames.map { cols(_) }
        val k = columns.map { _.rdd }.asInstanceOf[RDD[Any]]
        val kv = k.zip(rowsRdd)

        kv.groupByKey
    }

    /**
     * aggregate one column after grouping by another
     */
    def aggregate[U: ClassTag](aggByCol: String, aggedCol: String, aggtor: Aggregator[U]) = {
        aggtor.colIndex = cols(aggedCol).index
        keyBy(aggByCol).combineByKey(aggtor.convert, aggtor.mergeValue, aggtor.mergeCombiners)
    }

    /**
     * pivot the df and return a new df
     * e.g. half-yearly sales in <salesperson, period H1 or H2, sales> format
     * Jack, H1, 20
     * Jack, H2, 21
     * Jill, H1, 30
     * becomes "pivoted" to <salesperson, H1 sales, H2 sales>
     * Jack, 20, 21
     * Jill, 30, NaN
     *
     * The resulting df will typically have much fewer rows and much more columns
     *
     * keyCol: column that has "primary key" for the pivoted df e.g. salesperson
     * pivotByCol: column that is being removed e.g. period
     * pivotedCols: columns that are being pivoted e.g. sales, by default all columns are pivoted
     */
    def pivot(keyCol: String, pivotByCol: String, pivotedCols: List[Int] = cols.values.map { _.index }.toList): DF = {
        val grped = groupBy(keyCol)
        val pivotValues = apply(pivotByCol).distinct.collect.asInstanceOf[Array[Double]]
        val pivotIndex = cols.getOrElse(pivotByCol, null).index
        val newDf = new DF(sc, new HashMap[String, Column[Any]], new HashMap[Int, String])
        pivotValues.foreach { pivotValue =>
            val grpSplit = new PivotHelper(grped, pivotIndex, pivotValue).get
            //            val grpSplit = grped.map { case (k,v) => 
            //                (k, v.filter { row => row(pivotIndex) == pivotValue } ) 
            //            }
            pivotedCols.foreach { pivotedColIndex =>
                val newColRdd = grpSplit.map {
                    case (k, v) =>
                        if (v.isEmpty) Double.NaN else v.head(pivotedColIndex)
                }.asInstanceOf[RDD[Double]]
                newDf.update(s"${colIndexToName(pivotedColIndex)}$pivotByCol==$pivotValue", Column(newColRdd))
            }
        }
        newDf
    }

    /**
     * print brief description of the DF
     */
    def describe() {
        cols.foreach {
            case (name, col) =>
                println(s"${name}:")
                println(col.toString)
        }
    }

    private def fromRows(filteredRows: RDD[Array[Any]]) = {
        val df = new DF(sc, cols.clone, colIndexToName.clone)

        val firstRowOption = Try { filteredRows.first }.toOption
        if (firstRowOption.nonEmpty) {
            val firstRow = firstRowOption.get
            for (i <- 0 until df.numCols) {
                val t = DF.getType(firstRow(i))
                val column = if (t == ColumnType.Double) {
                    val colRdd = filteredRows.map { row => row(i).asInstanceOf[Double] }
                    df.cols.put(df.colIndexToName(i), Column(colRdd, i))
                } else if (t == ColumnType.String) {
                    val colRdd = filteredRows.map { row => row(i).asInstanceOf[String] }
                    df.cols.put(df.colIndexToName(i), Column(colRdd, i))
                } else {
                    println(s"Could not determine type of column ${colIndexToName(i)}")
                    null
                }
                println(s"Column: ${df.colIndexToName(i)} \t\t\tType: ${t}")
            }
        } else {
            for (i <- 0 until df.numCols) {
                df.cols.put(df.colIndexToName(i), null)
            }
        }
        df
    }

}

object DF {
    /**
     * create DF from a text file with given separator
     * first line of file is a header
     */
    def apply(sc: SparkContext, inFile: String, separator: Char) = {
        val df = new DF(sc, new HashMap[String, Column[Any]], new HashMap[Int, String])
        val csvFormat = CSVFormat.DEFAULT.withDelimiter(separator)
        val file = sc.textFile(inFile)
        val firstLine = file.first
        val header = CSVParser.parse(firstLine, csvFormat).iterator.next
        println(s"Found ${header.size} columns")
        df.addHeader(JavaConversions.asScalaIterator(header.iterator))

        val dataLines = file.filter(_ != firstLine)

        def guessType(col: RDD[String]) = {
            val first = col.first
            if (Try { first.toDouble }.toOption == None)
                ColumnType.String
            else
                ColumnType.Double
        }

        val rows = dataLines.map { CSVParser.parse(_, csvFormat).iterator.next }
        val columns = for (i <- 0 until df.numCols) yield {
            rows.map { _.get(i) }
        }

        var i = 0
        columns.foreach { col =>
            val t = guessType(columns(i))
            println(s"Column: ${df.colIndexToName(i)} \t\t\tGuessed Type: ${t}")
            if (t == ColumnType.Double)
                df.cols.put(df.colIndexToName(i), Column.asDoubles(col, i))
            else
                df.cols.put(df.colIndexToName(i), Column(col, i))
            i += 1
            col.cache
        }
        df
    }

    /**
     * create a DF given column names and vectors of columns(not rows)
     */
    def apply(sc: SparkContext, header: Vector[String], vec: Vector[Vector[Any]]) = {
        val df = new DF(sc, new HashMap[String, Column[Any]], new HashMap[Int, String])
        df.addHeader(header.toArray)

        var i = 0
        vec.foreach { col =>
            col(0) match {
                case c: Double =>
                    println(s"Column: ${df.colIndexToName(i)} Type: Double")
                    df.cols.put(df.colIndexToName(i),
                        Column(sc.parallelize(col.asInstanceOf[Vector[Double]]), i))

                case c: String =>
                    println(s"Column: ${df.colIndexToName(i)} Type: String")
                    df.cols.put(df.colIndexToName(i),
                        Column(sc.parallelize(col.asInstanceOf[Vector[String]]), i))
            }
            i += 1
        }
        df
    }

    /**
     * create a DF from a ColumnSeq
     */
    def apply(sc: SparkContext, colSeq: ColumnSeq) = {
        val df = new DF(sc, new HashMap[String, Column[Any]], new HashMap[Int, String])
        val header = colSeq.cols.map { _._1 }
        val columns = colSeq.cols.map { _._2 }

        df.addHeader(header.toArray)
        var i = 0
        columns.foreach { col =>
            println(s"Column: ${df.colIndexToName(i)} Type: Double")
            df.cols.put(df.colIndexToName(i), col)
            i += 1
        }
        df
    }

    /**
     * create a DF from a Column
     */
    def apply(sc: SparkContext, name: String, col: Column[Double]) = {
        val df = new DF(sc, new HashMap[String, Column[Any]], new HashMap[Int, String])
        val i = df.addHeader(Array(name))
        df.cols.put(df.colIndexToName(i - 1), col)
        df
    }

    def joinRdd(sc: SparkContext, left: DF, right: DF, on: String, how: JoinType.JoinType = JoinType.Inner) = {
        val leftWithKey = left.cols(on).rdd.zip(left.rowsRdd)
        val rightWithKey = right.cols(on).rdd.zip(right.rowsRdd)
        leftWithKey.join(rightWithKey)
    }

    def getType(elem: Any) = {
        elem match {
            case x: Double => ColumnType.Double
            case x: String => ColumnType.String
            case _ => {
                println(s"PANIC: unsupported column type ${elem}")
                null
            }
        }
    }

    /**
     * relational-like join two DFs
     */
    def join(sc: SparkContext, left: DF, right: DF, on: String, how: JoinType.JoinType = JoinType.Inner) = {
        val df = new DF(sc, new HashMap[String, Column[Any]], new HashMap[Int, String])
        val joinedRows = joinRdd(sc, left, right, on, how)
        val firstRow = joinedRows.first

        def leftValue(row: (Any, (Array[Any], Array[Any]))) = row._2._1
        def rightValue(row: (Any, (Array[Any], Array[Any]))) = row._2._2

        /*
         * if the two DFs being joined have columns with same names, prefix them with
         * left_ and right_ in the joined DF
         */
        def joinedColumnName(name: String, start: Int) = {
            val collision = !left.cols.keys.toSet.intersect(right.cols.keys.toSet).isEmpty
            if (!collision) {
                name
            } else {
                if (start == 0) {
                    "left_" + name
                } else {
                    "right_" + name
                }
            }
        }

        /*
         * add left or right columns to the DF
         * start = 0 for left, start = left.numCols for right
         */
        def addCols(curDf: DF, start: Int, partGetter: ((Any, (Array[Any], Array[Any]))) => Array[Any]) = {
            for (joinedIndex <- start until start + curDf.numCols) {
                val origIndex = joinedIndex - start
                val newColName = joinedColumnName(curDf.colIndexToName(origIndex), start)
                val t = getType(partGetter(firstRow)(origIndex))
                if (t == ColumnType.Double) {
                    val colRdd = joinedRows.map { row => partGetter(row)(origIndex).asInstanceOf[Double] }
                    val column = Column(colRdd, joinedIndex)
                    df.cols.put(newColName, column)
                } else if (t == ColumnType.String) {
                    val colRdd = joinedRows.map { row => partGetter(row)(origIndex).asInstanceOf[String] }
                    val column = Column(colRdd, joinedIndex)
                    df.cols.put(newColName, column)
                } else {
                    println(s"Could not determine type of column ${left.colIndexToName(origIndex)}")
                    null
                }
                println(s"Column: ${curDf.colIndexToName(origIndex)} \t\t\tType: ${t}")

                df.colIndexToName(joinedIndex) = newColName
            }
        }

        addCols(left, 0, leftValue)
        addCols(right, left.numCols, rightValue)

        df
    }
}
