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

import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.ArrowFileWriter
import org.apache.texera.amber.core.tuple.{Attribute, AttributeType, Schema, Tuple}
import org.apache.texera.amber.util.ArrowUtils
import org.apache.texera.amber.util.JSONUtils.objectMapper

import java.io.{File, FileOutputStream, PrintWriter}
import java.nio.channels.Channels
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

/**
  * Isolates the Arrow file-scan SOURCE OUTPUT cost across the three options, so the
  * Arrow -> ColumnarFrame passthrough can be compared without the full engine/DB:
  *
  *   row                     - COLUMNAR_WIRE off: produceTuple -> Tuples (no serialize)
  *   columnar-no-passthrough - COLUMNAR_WIRE on, source without produceColumnarBatch:
  *                             produceTuple -> Tuples -> ArrowUtils.serializeTuples (decode + re-encode)
  *   columnar-passthrough    - this change: produceColumnarBatch -> ArrowUtils.serializeRoot
  *                             (columnar copy, no Tuples)
  *
  * Options 2 and 3 put the SAME Arrow IPC bytes on the wire, so 2 -> 3 is the passthrough win.
  *
  *   sbt "WorkflowOperator/Test/runMain \
  *     org.apache.texera.amber.operator.source.scan.arrow.ArrowScanColumnarBench"
  *
  * Env: ARROW_BENCH_ROWS (default 2,000,000), ARROW_BENCH_BATCH (rows/batch, default 10000),
  * ARROW_BENCH_RUNS (timed runs, default 3).
  */
object ArrowScanColumnarBench {

  private val Rows: Int = sys.env.get("ARROW_BENCH_ROWS").map(_.toInt).getOrElse(2000000)
  private val BatchRows: Int = sys.env.get("ARROW_BENCH_BATCH").map(_.toInt).getOrElse(10000)
  private val Runs: Int = sys.env.get("ARROW_BENCH_RUNS").map(_.toInt).getOrElse(3)
  private val OutCsv: Path = Paths.get("bench-results", "arrow-columnar-scan.csv")

  private val schema: Schema = Schema(
    List(
      new Attribute("l_orderkey", AttributeType.LONG),
      new Attribute("l_partkey", AttributeType.INTEGER),
      new Attribute("l_quantity", AttributeType.DOUBLE),
      new Attribute("l_discount", AttributeType.DOUBLE),
      new Attribute("l_comment", AttributeType.STRING)
    )
  )

  def main(args: Array[String]): Unit = {
    Files.createDirectories(OutCsv.getParent)
    val file = generateArrowFile()
    val desc = descString(file)

    val results = Seq(
      "row" -> (() => runRow(desc)),
      "columnar-no-passthrough" -> (() => runColumnarNoPassthrough(desc)),
      "columnar-passthrough" -> (() => runColumnarPassthrough(desc))
    ).map { case (name, fn) => name -> median(name, fn) }

    writeCsv(results)
    val base = results.head._2
    println(s"\n[arrow-bench] rows=$Rows batch=$BatchRows runs=$Runs")
    results.foreach {
      case (name, ms) =>
        println(f"[arrow-bench] $name%-26s ${ms}%8.1f ms   ${base / ms}%.2fx vs row")
    }
    val noPt = results(1)._2
    val pt = results(2)._2
    println(f"[arrow-bench] passthrough speedup over columnar-no-passthrough: ${noPt / pt}%.2fx")
  }

  // --- option 1: row path (build Tuples, no serialize) -------------------------------------
  private def runRow(desc: String): Long = {
    val exec = new ArrowSourceOpExec(desc)
    exec.open()
    try {
      var checksum = 0L
      val it = exec.produceTuple()
      while (it.hasNext) checksum += it.next().getFields.length.toLong
      checksum
    } finally exec.close()
  }

  // --- option 2: columnar wire, no native producer (Tuples -> serializeTuples per batch) ----
  private def runColumnarNoPassthrough(desc: String): Long = {
    val exec = new ArrowSourceOpExec(desc)
    exec.open()
    try {
      var bytes = 0L
      val buf = new Array[Tuple](BatchRows)
      var filled = 0
      val it = exec.produceTuple()
      while (it.hasNext) {
        buf(filled) = it.next().asInstanceOf[Tuple]
        filled += 1
        if (filled == BatchRows) {
          bytes += ArrowUtils.serializeTuples(schema, buf).length.toLong
          filled = 0
        }
      }
      if (filled > 0) bytes += ArrowUtils.serializeTuples(schema, buf.take(filled)).length.toLong
      bytes
    } finally exec.close()
  }

  // --- option 3: columnar passthrough (produceColumnarBatch -> serializeRoot) ----------------
  private def runColumnarPassthrough(desc: String): Long = {
    val exec = new ArrowSourceOpExec(desc)
    exec.open()
    try {
      var bytes = 0L
      val it = exec.produceColumnarBatch().getOrElse(sys.error("expected columnar batch iterator"))
      while (it.hasNext) {
        val root = it.next()
        try bytes += ArrowUtils.serializeRoot(root).length.toLong
        finally root.close() // the engine closes each emitted root after send
      }
      bytes
    } finally exec.close()
  }

  private def median(name: String, fn: () => Long): Double = {
    fn() // warmup
    val times = (1 to Runs).map { _ =>
      val t0 = System.nanoTime()
      val checksum = fn()
      val ms = (System.nanoTime() - t0) / 1e6
      if (checksum == Long.MinValue) println("unreachable") // keep checksum live
      ms
    }
    times.sorted.apply(times.length / 2)
  }

  // --- Arrow file generation (cached by row count) -----------------------------------------
  private def generateArrowFile(): File = {
    val path = Paths.get(sys.env.getOrElse("TMPDIR", "/tmp"), s"arrow-scan-bench-$Rows.arrow")
    val file = path.toFile
    if (file.exists() && file.length() > 0) return file
    val allocator = new RootAllocator()
    val root = VectorSchemaRoot.create(ArrowUtils.fromTexeraSchema(schema), allocator)
    val out = new FileOutputStream(file)
    val writer = new ArrowFileWriter(root, null, Channels.newChannel(out))
    try {
      writer.start()
      var written = 0
      while (written < Rows) {
        val n = math.min(BatchRows, Rows - written)
        root.allocateNew()
        var i = 0
        while (i < n) {
          val v = written + i
          ArrowUtils.setTexeraTuple(
            Tuple
              .builder(schema)
              .addSequentially(
                Array[Any](
                  Long.box(v.toLong),
                  Int.box(v % 1000),
                  Double.box(v * 1.5),
                  Double.box((v % 10) / 10.0),
                  s"comment row $v padding text"
                )
              )
              .build(),
            i,
            root
          )
          i += 1
        }
        root.setRowCount(n)
        writer.writeBatch()
        written += n
      }
      writer.end()
    } finally {
      writer.close()
      root.close()
      allocator.close()
      out.close()
    }
    println(s"[arrow-bench] generated Arrow file: $path (${file.length()} bytes)")
    file
  }

  private def descString(file: File): String = {
    val desc = new ArrowSourceOpDesc()
    desc.fileName = Some(file.toURI.toString)
    objectMapper.writeValueAsString(desc)
  }

  private def writeCsv(results: Seq[(String, Double)]): Unit = {
    val exists = Files.exists(OutCsv) && Files.size(OutCsv) > 0
    val pw = new PrintWriter(
      Files.newBufferedWriter(
        OutCsv,
        StandardCharsets.UTF_8,
        java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.APPEND
      )
    )
    try {
      if (!exists) pw.println("option,rows,batch_rows,median_ms")
      results.foreach { case (name, ms) => pw.println(f"$name,$Rows,$BatchRows,$ms%.1f") }
    } finally pw.close()
  }
}
