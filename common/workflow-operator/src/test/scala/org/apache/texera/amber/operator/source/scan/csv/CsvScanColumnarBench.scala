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

package org.apache.texera.amber.operator.source.scan.csv

import org.apache.texera.amber.core.storage.FileResolver
import org.apache.texera.amber.core.tuple.{Schema, SeqTupleLike, Tuple}
import org.apache.texera.amber.util.ArrowUtils
import org.apache.texera.amber.util.JSONUtils.objectMapper

import java.io.{File, PrintWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

/**
  * CSV analog of ArrowScanColumnarBench: isolates the CSV file-scan SOURCE OUTPUT cost across
  * the three options.
  *
  *   row                     - COLUMNAR_WIRE off: produceTuple -> Tuples (no serialize)
  *   columnar-no-passthrough - produceTuple -> Tuples -> ArrowUtils.serializeTuples (re-encode)
  *   columnar                - CSVScanSourceOpExec.produceColumnarBatch -> ArrowUtils.serializeRoot
  *                             (parse straight to Arrow, no Tuples)
  *
  *   sbt "WorkflowOperator/Test/runMain \
  *     org.apache.texera.amber.operator.source.scan.csv.CsvScanColumnarBench"
  *
  * Env: CSV_BENCH_ROWS (default 2,000,000), CSV_BENCH_BATCH (default 10000), CSV_BENCH_RUNS (3).
  */
object CsvScanColumnarBench {

  private val Rows: Int = sys.env.get("CSV_BENCH_ROWS").map(_.toInt).getOrElse(2000000)
  private val BatchRows: Int = sys.env.get("CSV_BENCH_BATCH").map(_.toInt).getOrElse(10000)
  private val Runs: Int = sys.env.get("CSV_BENCH_RUNS").map(_.toInt).getOrElse(3)
  private val OutCsv: Path = Paths.get("bench-results", "csv-columnar-scan.csv")

  def main(args: Array[String]): Unit = {
    Files.createDirectories(OutCsv.getParent)
    val file = generateCsvFile()
    val (descStr, schema) = descAndSchema(file)

    val results = Seq(
      "row" -> (() => runRow(descStr, schema)),
      "columnar-no-passthrough" -> (() => runColumnarNoPassthrough(descStr, schema)),
      "columnar" -> (() => runColumnar(descStr))
    ).map { case (name, fn) => name -> median(fn) }

    writeCsv(results)
    val base = results.head._2
    println(s"\n[csv-bench] rows=$Rows batch=$BatchRows runs=$Runs")
    results.foreach {
      case (name, ms) =>
        println(f"[csv-bench] $name%-26s ${ms}%8.1f ms   ${base / ms}%.2fx vs row")
    }
    println(f"[csv-bench] columnar speedup over columnar-no-passthrough: ${results(1)._2 / results(2)._2}%.2fx")
  }

  private def runRow(desc: String, schema: Schema): Long = {
    val exec = new CSVScanSourceOpExec(desc)
    exec.open()
    try {
      var checksum = 0L
      val it = exec.produceTuple()
      // enforceSchema materializes the Tuple, exactly as the engine does before shipping a
      // source's row output (this is where the per-tuple cost, incl. inMemSize, is paid).
      while (it.hasNext)
        checksum += it.next().asInstanceOf[SeqTupleLike].enforceSchema(schema).getFields.length.toLong
      checksum
    } finally exec.close()
  }

  private def runColumnarNoPassthrough(desc: String, schema: Schema): Long = {
    val exec = new CSVScanSourceOpExec(desc)
    exec.open()
    try {
      var bytes = 0L
      val buf = new Array[Tuple](BatchRows)
      var filled = 0
      val it = exec.produceTuple()
      while (it.hasNext) {
        buf(filled) = it.next().asInstanceOf[SeqTupleLike].enforceSchema(schema)
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

  private def runColumnar(desc: String): Long = {
    val exec = new CSVScanSourceOpExec(desc)
    exec.open()
    try {
      var bytes = 0L
      val it = exec.produceColumnarBatch().getOrElse(sys.error("expected columnar batch iterator"))
      while (it.hasNext) {
        val root = it.next()
        try bytes += ArrowUtils.serializeRoot(root).length.toLong
        finally root.close()
      }
      bytes
    } finally exec.close()
  }

  private def median(fn: () => Long): Double = {
    fn() // warmup
    val times = (1 to Runs).map { _ =>
      val t0 = System.nanoTime()
      val c = fn()
      val ms = (System.nanoTime() - t0) / 1e6
      if (c == Long.MinValue) println("unreachable")
      ms
    }
    times.sorted.apply(times.length / 2)
  }

  private def generateCsvFile(): File = {
    val path = Paths.get(sys.env.getOrElse("TMPDIR", "/tmp"), s"csv-scan-bench-$Rows.csv")
    val file = path.toFile
    if (file.exists() && file.length() > 0) return file
    val pw = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8))
    try {
      pw.println("l_orderkey,l_partkey,l_quantity,l_discount,l_comment")
      var v = 0
      while (v < Rows) {
        pw.println(s"$v,${v % 1000},${v * 1.5},${(v % 10) / 10.0},comment row $v padding text")
        v += 1
      }
    } finally pw.close()
    println(s"[csv-bench] generated CSV file: $path (${file.length()} bytes)")
    file
  }

  private def descAndSchema(file: File): (String, Schema) = {
    val desc = new CSVScanSourceOpDesc()
    desc.fileName = Some(file.toURI.toString)
    desc.customDelimiter = Some(",")
    desc.hasHeader = true
    desc.setResolvedFileName(FileResolver.resolve(file.toURI.toString))
    (objectMapper.writeValueAsString(desc), desc.sourceSchema())
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
