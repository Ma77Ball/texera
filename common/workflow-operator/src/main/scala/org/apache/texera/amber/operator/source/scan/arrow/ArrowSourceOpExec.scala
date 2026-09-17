/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.texera.amber.operator.source.scan.arrow

import org.apache.texera.amber.core.executor.SourceOperatorExecutor
import org.apache.texera.amber.core.storage.DocumentFactory
import org.apache.texera.amber.core.tuple.TupleLike
import org.apache.texera.amber.util.ArrowUtils
import org.apache.texera.amber.util.JSONUtils.objectMapper
import org.apache.arrow.memory.{BufferAllocator, RootAllocator}
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.ArrowFileReader

import java.net.URI
import java.nio.file.{Files, StandardOpenOption}

class ArrowSourceOpExec(
    descString: String
) extends SourceOperatorExecutor {
  private val desc: ArrowSourceOpDesc =
    objectMapper.readValue(descString, classOf[ArrowSourceOpDesc])
  private var reader: Option[ArrowFileReader] = None
  private var root: Option[VectorSchemaRoot] = None
  private var allocator: Option[RootAllocator] = None
  // Separate allocator for the engine-owned batches yielded by produceColumnarBatch; freed in close().
  private var columnarAllocator: Option[RootAllocator] = None

  override def open(): Unit = {
    try {
      val file = DocumentFactory.openReadonlyDocument(new URI(desc.fileName.get)).asFile()
      val alloc = new RootAllocator()
      allocator = Some(alloc)
      val channel = Files.newByteChannel(file.toPath, StandardOpenOption.READ)
      val arrowReader = new ArrowFileReader(channel, alloc)
      val vectorRoot = arrowReader.getVectorSchemaRoot
      reader = Some(arrowReader)
      root = Some(vectorRoot)
    } catch {
      case e: Exception =>
        close() // Ensure resources are closed in case of an error
        throw new RuntimeException("Failed to open Arrow source", e)
    }
  }

  override def produceTuple(): Iterator[TupleLike] = {
    val rowIterator = new Iterator[TupleLike] {
      private var currentIndex = 0
      private var currentBatchIndex = 0

      override def hasNext: Boolean = {
        if (root.exists(_.getRowCount > currentIndex)) {
          true
        } else {
          reader.exists(arrowReader => {
            val hasMoreBatches = arrowReader.loadNextBatch()
            if (hasMoreBatches) {
              currentIndex = 0
              currentBatchIndex += 1
              true
            } else {
              false
            }
          })
        }
      }

      override def next(): TupleLike = {
        root.map { vectorSchemaRoot =>
          val tuple = ArrowUtils.getTexeraTuple(currentIndex, vectorSchemaRoot)
          currentIndex += 1
          tuple
        }.get
      }
    }

    var tupleIterator = rowIterator.drop(desc.offset.getOrElse(0))
    if (desc.limit.isDefined) tupleIterator = tupleIterator.take(desc.limit.get)
    tupleIterator
  }

  /**
    * Columnar passthrough: hand each Arrow record batch read from the file straight to the wire,
    * with no per-row Tuple decode and no re-encode. The batch data is already Arrow, so we only
    * copy it (column by column) into a fresh, engine-owned root - the engine closes each emitted
    * root, while ArrowFileReader keeps reusing its own internal root across loadNextBatch(), so we
    * cannot hand the reader's root out directly. offset/limit are not applied here (they would need
    * cross-batch row slicing); when either is set we return None and the row path handles them.
    */
  override def produceColumnarBatch(): Option[Iterator[VectorSchemaRoot]] = {
    if (desc.offset.isDefined || desc.limit.isDefined) return None
    val arrowReader = reader.getOrElse(return None)
    val srcRoot = root.getOrElse(return None)
    val alloc = new RootAllocator()
    columnarAllocator = Some(alloc)

    Some(new Iterator[VectorSchemaRoot] {
      private var hasBatch = arrowReader.loadNextBatch()

      override def hasNext: Boolean = hasBatch

      override def next(): VectorSchemaRoot = {
        val batch = ArrowSourceOpExec.copyRoot(srcRoot, alloc)
        hasBatch = arrowReader.loadNextBatch()
        batch
      }
    })
  }

  override def close(): Unit = {
    reader.foreach(_.close())
    root.foreach(_.close())
    allocator.foreach(_.close())
    columnarAllocator.foreach(_.close())
  }
}

object ArrowSourceOpExec {

  /**
    * Copies a VectorSchemaRoot into a fresh root owned by `allocator`, column by column. This is a
    * columnar copy (no boxed Tuple objects, no per-field decode/encode): far cheaper than
    * materializing rows, and it leaves the source root intact for the reader's next batch.
    */
  private def copyRoot(src: VectorSchemaRoot, allocator: BufferAllocator): VectorSchemaRoot = {
    val dest = VectorSchemaRoot.create(src.getSchema, allocator)
    val rowCount = src.getRowCount
    val srcVectors = src.getFieldVectors
    val dstVectors = dest.getFieldVectors
    var c = 0
    while (c < srcVectors.size) {
      val s = srcVectors.get(c)
      val d = dstVectors.get(c)
      d.setInitialCapacity(rowCount)
      d.allocateNew()
      var r = 0
      while (r < rowCount) {
        d.copyFromSafe(r, r, s)
        r += 1
      }
      d.setValueCount(rowCount)
      c += 1
    }
    dest.setRowCount(rowCount)
    dest
  }
}
