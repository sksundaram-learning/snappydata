/*
 * Copyright (c) 2017 SnappyData, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */
package org.apache.spark.sql.execution.row

import java.lang.reflect.Field
import java.sql.{Connection, ResultSet, Statement}
import java.util.GregorianCalendar

import scala.collection.mutable.ArrayBuffer
import scala.util.control.NonFatal

import com.esotericsoftware.kryo.io.{Input, Output}
import com.esotericsoftware.kryo.{Kryo, KryoSerializable}
import com.gemstone.gemfire.cache.IsolationLevel
import com.gemstone.gemfire.internal.cache._
import com.gemstone.gemfire.internal.shared.ClientSharedData
import com.pivotal.gemfirexd.internal.engine.Misc
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils
import com.pivotal.gemfirexd.internal.engine.store.{AbstractCompactExecRow, GemFireContainer, RegionEntryUtils}
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedResultSet
import com.zaxxer.hikari.pool.ProxyResultSet

import org.apache.spark.serializer.ConnectionPropertiesSerializer
import org.apache.spark.sql.SnappySession
import org.apache.spark.sql.catalyst.expressions.DynamicReplacableConstant
import org.apache.spark.sql.collection.MultiBucketExecutorPartition
import org.apache.spark.sql.execution.RDDKryo
import org.apache.spark.sql.execution.columnar.{ExternalStoreUtils, ResultSetIterator}
import org.apache.spark.sql.sources._
import org.apache.spark.{Partition, TaskContext}

/**
 * A scanner RDD which is very specific to Snappy store row tables.
 * This scans row tables in parallel unlike Spark's inbuilt JDBCRDD.
 */
class RowFormatScanRDD(@transient val session: SnappySession,
    protected var tableName: String,
    protected var isPartitioned: Boolean,
    @transient private val columns: Array[String],
    var pushProjections: Boolean,
    var useResultSet: Boolean,
    protected var connProperties: ConnectionProperties,
    @transient private val filters: Array[Filter] = Array.empty[Filter],
    @transient protected val partitionEvaluator: () => Array[Partition] = () =>
      Array.empty[Partition], var commitTx: Boolean)
    extends RDDKryo[Any](session.sparkContext, Nil) with KryoSerializable {

  protected var filterWhereArgs: ArrayBuffer[Any] = _
  /**
   * `filters`, but as a WHERE clause suitable for injection into a SQL query.
   */
  protected var filterWhereClause: String = {
    val numFilters = filters.length
    if (numFilters > 0) {
      val sb = new StringBuilder().append(" WHERE ")
      val args = new ArrayBuffer[Any](numFilters)
      val initLen = sb.length
      filters.foreach { s =>
        compileFilter(s, sb, args, sb.length > initLen)
      }
      if (args.nonEmpty) {
        filterWhereArgs = args
        sb.toString()
      } else ""
    } else ""
  }

  protected lazy val resultSetField: Field = {
    val field = classOf[ProxyResultSet].getDeclaredField("delegate")
    field.setAccessible(true)
    field
  }

  // below should exactly match ExternalStoreUtils.handledFilter
  private def compileFilter(f: Filter, sb: StringBuilder,
      args: ArrayBuffer[Any], addAnd: Boolean): Unit = f match {
    case EqualTo(col, value) =>
      if (addAnd) {
        sb.append(" AND ")
      }
      sb.append(col).append(" = ?")
      args += value
    case LessThan(col, value) =>
      if (addAnd) {
        sb.append(" AND ")
      }
      sb.append(col).append(" < ?")
      args += value
    case GreaterThan(col, value) =>
      if (addAnd) {
        sb.append(" AND ")
      }
      sb.append(col).append(" > ?")
      args += value
    case LessThanOrEqual(col, value) =>
      if (addAnd) {
        sb.append(" AND ")
      }
      sb.append(col).append(" <= ?")
      args += value
    case GreaterThanOrEqual(col, value) =>
      if (addAnd) {
        sb.append(" AND ")
      }
      sb.append(col).append(" >= ?")
      args += value
    case StringStartsWith(col, value) =>
      if (addAnd) {
        sb.append(" AND ")
      }
      sb.append(col).append(" LIKE ?")
      args += (value + '%')
    case In(col, values) =>
      if (addAnd) {
        sb.append(" AND ")
      }
      sb.append(col).append(" IN (")
      (1 until values.length).foreach(_ => sb.append("?,"))
      sb.append("?)")
      args ++= values
    case And(left, right) =>
      if (addAnd) {
        sb.append(" AND ")
      }
      sb.append('(')
      compileFilter(left, sb, args, addAnd = false)
      sb.append(") AND (")
      compileFilter(right, sb, args, addAnd = false)
      sb.append(')')
    case Or(left, right) =>
      if (addAnd) {
        sb.append(" AND ")
      }
      sb.append('(')
      compileFilter(left, sb, args, addAnd = false)
      sb.append(") OR (")
      compileFilter(right, sb, args, addAnd = false)
      sb.append(')')
    case _ => // no filter pushdown
  }

  /**
   * `columns`, but as a String suitable for injection into a SQL query.
   */
  protected var columnList: String = {
    if (!pushProjections) "*"
    else if (columns.length > 0) {
      val sb = new StringBuilder()
      columns.foreach { s =>
        if (sb.nonEmpty) sb.append(',')
        sb.append('"').append(s).append('"')
      }
      sb.toString()
    } else "1"
  }

  def computeResultSet(
      thePart: Partition, context: TaskContext): (Connection, Statement, ResultSet) = {
    val conn = ExternalStoreUtils.getConnection(tableName,
      connProperties, forExecutor = true)

    if (context ne null) {
      val partitionId = context.partitionId()
      context.addTaskCompletionListener { _ =>
        logDebug(s"closed connection for task from listener $partitionId")
        try {
          conn.commit()
          conn.close()
          logDebug("closed connection for task " + context.partitionId())
        } catch {
          case NonFatal(e) => logWarning("Exception closing connection", e)
        }
      }
    }

    if (isPartitioned) {
      val ps = conn.prepareStatement(
        "call sys.SET_BUCKETS_FOR_LOCAL_EXECUTION(?, ?, ?)")
      try {
        ps.setString(1, tableName)
        val bucketString = thePart match {
          case p: MultiBucketExecutorPartition => p.bucketsString
          case _ => thePart.index.toString
        }
        ps.setString(2, bucketString)
        ps.setInt(3, -1)
        ps.executeUpdate()
      } finally {
        ps.close()
      }
    }
    val sqlText = s"SELECT $columnList FROM $tableName$filterWhereClause"
    val args = filterWhereArgs
    val stmt = conn.prepareStatement(sqlText)
    if (args ne null) {
      ExternalStoreUtils.setStatementParameters(stmt, args.map {
        case pl: DynamicReplacableConstant => pl.convertedLiteral
        case v => v
      })
    }
    val fetchSize = connProperties.executorConnProps.getProperty("fetchSize")
    if (fetchSize ne null) {
      stmt.setFetchSize(fetchSize.toInt)
    }
    val rs = stmt.executeQuery()
    /* (hangs for some reason)
    // setup context stack for lightWeightNext calls
    val rs = stmt.executeQuery().asInstanceOf[EmbedResultSet]
    val embedConn = stmt.getConnection.asInstanceOf[EmbedConnection]
    val lcc = embedConn.getLanguageConnectionContext
    embedConn.getTR.setupContextStack()
    rs.pushStatementContext(lcc, true)
    */
    (conn, stmt, rs)
  }


  def commitTxBeforeTaskCompletion(conn: Option[Connection], context: TaskContext): Unit = {
    Option(TaskContext.get()).foreach(_.addTaskCompletionListener(_ => {
      val tx = TXManagerImpl.getCurrentSnapshotTXState
      if (tx != null /* && !(tx.asInstanceOf[TXStateProxy]).isClosed() */ ) {
        val txMgr = tx.getTxMgr
        txMgr.masqueradeAs(tx)
        txMgr.commit()
      }
    }))
  }

  /**
   * Runs the SQL query against the JDBC driver.
   */
  override def compute(thePart: Partition,
      context: TaskContext): Iterator[Any] = {

    if (pushProjections || useResultSet) {
      if (!pushProjections) {
        val txManagerImpl = GemFireCacheImpl.getExisting.getCacheTransactionManager
        if (txManagerImpl.getTXState eq null) {
          txManagerImpl.begin(IsolationLevel.SNAPSHOT, null)
          // if (commitTx)
          commitTxBeforeTaskCompletion(None, context)
        }
      }
      // we always iterate here for column table
      val (conn, stmt, rs) = computeResultSet(thePart, context)
      val itr = new ResultSetTraversal(conn, stmt, rs, context)
      if (commitTx && pushProjections) {
        commitTxBeforeTaskCompletion(Option(conn), context)
      }
      itr
    } else {
      val txManagerImpl = GemFireCacheImpl.getExisting.getCacheTransactionManager
      var tx = txManagerImpl.getTXState
      val startTX = tx eq null
      if (startTX) {
        tx = txManagerImpl.beginTX(TXManagerImpl.getOrCreateTXContext,
          IsolationLevel.SNAPSHOT, null, null)
      }
      // use iterator over CompactExecRows directly when no projection;
      // higher layer PartitionedPhysicalRDD will take care of conversion
      // or direct code generation as appropriate
      val itr = if (isPartitioned && filterWhereClause.isEmpty) {
        val container = GemFireXDUtils.getGemFireContainer(tableName, true)
        val bucketIds = thePart match {
          case p: MultiBucketExecutorPartition => p.buckets
          case _ => java.util.Collections.singleton(Int.box(thePart.index))
        }

        val txId = if (tx ne null) tx.getTransactionId else null
        new CompactExecRowIteratorOnScan(container, bucketIds, txId)
      } else {
        val (conn, stmt, rs) = computeResultSet(thePart, context)
        val ers = rs match {
          case e: EmbedResultSet => e
          case p: ProxyResultSet =>
            resultSetField.get(p).asInstanceOf[EmbedResultSet]
        }
        new CompactExecRowIteratorOnRS(conn, stmt, ers, context)
      }
      // add the listener after the close listener added by iterator
      // so its invoked just before it
      if (startTX) {
        // if (commitTx) {
        commitTxBeforeTaskCompletion(None, context)
        // }
      }
      itr
    }
  }

  override def getPreferredLocations(split: Partition): Seq[String] = {
    split.asInstanceOf[MultiBucketExecutorPartition].hostExecutorIds
  }

  override def getPartitions: Array[Partition] = {
    // use incoming partitions if provided (e.g. for collocated tables)
    val parts = partitionEvaluator()
    if (parts != null && parts.length > 0) {
      return parts
    }

    Misc.getRegionForTable(tableName, true).asInstanceOf[CacheDistributionAdvisee] match {
      case pr: PartitionedRegion => session.sessionState.getTablePartitions(pr)
      case dr => session.sessionState.getTablePartitions(dr)
    }
  }

  override def write(kryo: Kryo, output: Output): Unit = {
    super.write(kryo, output)
    output.writeBoolean(commitTx)
    output.writeString(tableName)
    output.writeBoolean(isPartitioned)
    output.writeBoolean(pushProjections)
    output.writeBoolean(useResultSet)

    output.writeString(columnList)
    val filterArgs = filterWhereArgs
    val len = if (filterArgs eq null) 0 else filterArgs.size
    if (len == 0) {
      output.writeVarInt(0, true)
    } else {
      var i = 0
      output.writeVarInt(len, true)
      output.writeString(filterWhereClause)
      while (i < len) {
        kryo.writeClassAndObject(output, filterArgs(i))
        i += 1
      }
    }
    // need connection properties only if computing ResultSet
    if (pushProjections || useResultSet || !isPartitioned || len > 0) {
      ConnectionPropertiesSerializer.write(kryo, output, connProperties)
    }
  }

  override def read(kryo: Kryo, input: Input): Unit = {
    super.read(kryo, input)
    commitTx = input.readBoolean()
    tableName = input.readString()
    isPartitioned = input.readBoolean()
    pushProjections = input.readBoolean()
    useResultSet = input.readBoolean()

    columnList = input.readString()
    val numFilters = input.readVarInt(true)
    if (numFilters == 0) {
      filterWhereClause = ""
      filterWhereArgs = null
    } else {
      filterWhereClause = input.readString()
      filterWhereArgs = new ArrayBuffer[Any](numFilters)
      var i = 0
      while (i < numFilters) {
        filterWhereArgs += kryo.readClassAndObject(input)
        i += 1
      }
    }
    // read connection properties only if computing ResultSet
    if (pushProjections || useResultSet || !isPartitioned || numFilters > 0) {
      connProperties = ConnectionPropertiesSerializer.read(kryo, input)
    }
  }
}

/**
 * This does not return any valid results from result set rather caller is
 * expected to explicitly invoke ResultSet.next()/get*.
 * This is primarily intended to be used for cleanup.
 */
final class ResultSetTraversal(conn: Connection,
    stmt: Statement, val rs: ResultSet, context: TaskContext)
    extends ResultSetIterator[Void](conn, stmt, rs, context) {

  lazy val defaultCal: GregorianCalendar =
    ClientSharedData.getDefaultCleanCalendar

  override protected def getCurrentValue: Void = null
}

final class CompactExecRowIteratorOnRS(conn: Connection,
    stmt: Statement, ers: EmbedResultSet, context: TaskContext)
    extends ResultSetIterator[AbstractCompactExecRow](conn, stmt,
      ers, context) {

  override protected def getCurrentValue: AbstractCompactExecRow = {
    ers.currentRow.asInstanceOf[AbstractCompactExecRow]
  }
}

abstract class PRValuesIterator[T](container: GemFireContainer,
    region: LocalRegion, bucketIds: java.util.Set[Integer]) extends Iterator[T] {

  protected final var hasNextValue = true
  protected final var doMove = true
  // transaction started by row buffer scan should be used here
  private val tx = TXManagerImpl.getCurrentSnapshotTXState
  private[execution] final val itr = if (container ne null) {
    container.getEntrySetIteratorForBucketSet(
      bucketIds.asInstanceOf[java.util.Set[Integer]], null, tx, 0,
      false, true).asInstanceOf[PartitionedRegion#PRLocalScanIterator]
  } else if (region ne null) {
    region.getDataView(tx).getLocalEntriesIterator(
      bucketIds.asInstanceOf[java.util.Set[Integer]], false, false, true,
      region, true).asInstanceOf[PartitionedRegion#PRLocalScanIterator]
  } else null

  protected def currentVal: T

  protected def moveNext(): Unit

  override final def hasNext: Boolean = {
    if (doMove) {
      moveNext()
      doMove = false
    }
    hasNextValue
  }

  override final def next: T = {
    if (doMove) {
      moveNext()
    }
    doMove = true
    currentVal
  }
}

final class CompactExecRowIteratorOnScan(container: GemFireContainer,
    bucketIds: java.util.Set[Integer], txId: TXId)
    extends PRValuesIterator[AbstractCompactExecRow](container,
      region = null, bucketIds) {

  override protected val currentVal: AbstractCompactExecRow = container
      .newTemplateRow().asInstanceOf[AbstractCompactExecRow]

  override protected def moveNext(): Unit = {
    val itr = this.itr
    while (itr.hasNext) {
      val rl = itr.next()
      val owner = itr.getHostedBucketRegion
      if (((owner ne null) || rl.isInstanceOf[NonLocalRegionEntry]) &&
          RegionEntryUtils.fillRowWithoutFaultInOptimized(container, owner,
            rl.asInstanceOf[RowLocation], currentVal)) {
        return
      }
    }
    hasNextValue = false
  }
}
