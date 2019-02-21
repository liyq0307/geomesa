/***********************************************************************
 * Copyright (c) 2013-2019 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.tools.ingest

import java.util.concurrent.Executors
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}

import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.pool2.impl.{DefaultPooledObject, GenericObjectPool}
import org.apache.commons.pool2.{BasePooledObjectFactory, PooledObject}
import org.geotools.data.{DataStore, DataUtilities, FeatureWriter, Transaction}
import org.locationtech.geomesa.convert.{DefaultCounter, EvaluationContext}
import org.locationtech.geomesa.convert2.SimpleFeatureConverter
import org.locationtech.geomesa.tools.Command
import org.locationtech.geomesa.tools.ingest.AbstractConverterIngest.StatusCallback
import org.locationtech.geomesa.tools.ingest.LocalConverterIngest.LocalIngestCounter
import org.locationtech.geomesa.utils.collection.CloseableIterator
import org.locationtech.geomesa.utils.geotools.FeatureUtils
import org.locationtech.geomesa.utils.io.fs.FileSystemDelegate.FileHandle
import org.locationtech.geomesa.utils.io.fs.LocalDelegate.StdInHandle
import org.locationtech.geomesa.utils.io.{CloseWithLogging, PathUtils}
import org.locationtech.geomesa.utils.stats.CountingInputStream
import org.locationtech.geomesa.utils.text.TextTools
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}

import scala.util.control.NonFatal

/**
  * Ingestion that uses geomesa converters to process input files
  *
  * @param sft simple feature type
  * @param dsParams data store parameters
  * @param converterConfig converter definition
  * @param inputs paths to ingest
  * @param numThreads how many threads to use
  */
class LocalConverterIngest(
    dsParams: Map[String, String],
    sft: SimpleFeatureType,
    converterConfig: Config,
    inputs: Seq[String],
    numThreads: Int
  ) extends AbstractConverterIngest(dsParams, sft) with LazyLogging {

  /**
    * Hook to allow modification of the feature returned by the converter
    *
    * @param iter features
    * @return
    */
  protected def features(iter: CloseableIterator[SimpleFeature]): CloseableIterator[SimpleFeature] = iter

  override protected def runIngest(ds: DataStore, sft: SimpleFeatureType, callback: StatusCallback): Unit = {
    Command.user.info("Running ingestion in local mode")

    val converters = new GenericObjectPool[SimpleFeatureConverter](
      new BasePooledObjectFactory[SimpleFeatureConverter] {
        override def wrap(obj: SimpleFeatureConverter) = new DefaultPooledObject[SimpleFeatureConverter](obj)
        override def create(): SimpleFeatureConverter = SimpleFeatureConverter(sft, converterConfig)
        override def destroyObject(p: PooledObject[SimpleFeatureConverter]): Unit = p.getObject.close()
      }
    )

    // global counts shared among threads
    val written = new AtomicLong(0)
    val failed = new AtomicLong(0)
    val errors = new AtomicInteger(0)

    val bytesRead = new AtomicLong(0L)

    class LocalIngestWorker(file: FileHandle) extends Runnable {
      override def run(): Unit = {
        try {
          val converter = converters.borrowObject()
          val counter = new LocalIngestCounter(failed)
          val ec = converter.createEvaluationContext(EvaluationContext.inputFileParam(file.path), counter = counter)

          var fw: FeatureWriter[SimpleFeatureType, SimpleFeature] = null

          // count the raw bytes read from the file, as that's what we based our total on
          val countingStream = new CountingInputStream(file.open)
          val is = PathUtils.handleCompression(countingStream, file.path)
          try {
            val features = LocalConverterIngest.this.features(converter.process(is, ec))
            if (features.hasNext) {
              fw = ds.getFeatureWriterAppend(sft.getTypeName, Transaction.AUTO_COMMIT)
            }
            features.foreach { sf =>
              FeatureUtils.copyToWriter(fw, sf, useProvidedFid = true)
              try {
                fw.write()
                written.incrementAndGet()
              } catch {
                case NonFatal(e) =>
                  logger.error(s"Failed to write '${DataUtilities.encodeFeature(sf)}'", e)
                  failed.incrementAndGet()
              }
              bytesRead.addAndGet(countingStream.getCount)
              countingStream.resetCount()
            }
          } finally {
            converters.returnObject(converter)
            CloseWithLogging(is)
            if (fw != null) {
              fw.close() // allow exception to propagate
            }
          }
        } catch {
          case e @ (_: ClassNotFoundException | _: NoClassDefFoundError) =>
            // Rethrow exception so it can be caught by getting the future of this runnable in the main thread
            // which will in turn cause the exception to be handled by org.locationtech.geomesa.tools.Runner
            // Likely all threads will fail if a dependency is missing so it will terminate quickly
            throw e

          case NonFatal(e) =>
            // Don't kill the entire program b/c this thread was bad! use outer try/catch
            val msg = s"Fatal error running local ingest worker on ${file.path}"
            Command.user.error(msg)
            logger.error(msg, e)
            errors.incrementAndGet()
        }
      }
    }

    // if inputs is empty, we've already validated that stdin has data to read
    val stdin = inputs.isEmpty

    val files = if (stdin) { StdInHandle.available().toSeq } else { inputs.flatMap(PathUtils.interpretPath) }

    val threads = if (numThreads <= files.length) { numThreads } else {
      Command.user.warn("Can't use more threads than there are input files - reducing thread count")
      files.length
    }

    Command.user.info(s"Ingesting ${if (stdin) { "from stdin" } else { TextTools.getPlural(files.length, "file") }} " +
        s"with ${TextTools.getPlural(threads, "thread")}")

    val totalLength: () => Float = if (stdin) {
      () => (bytesRead.get + files.map(_.length).sum).toFloat // re-evaluate each time as bytes are read from stdin
    } else {
      val length = files.map(_.length).sum.toFloat // only evaluate once
      () => length
    }

    def progress(): Float = bytesRead.get() / totalLength()

    val start = System.currentTimeMillis()
    val es = Executors.newFixedThreadPool(threads)
    val futures = files.map(f => es.submit(new LocalIngestWorker(f))).toList
    es.shutdown()

    def counters = Seq(("ingested", written.get()), ("failed", failed.get()))

    while (!es.isTerminated) {
      Thread.sleep(500)
      callback("", progress(), counters, done = false)
    }
    callback("", progress(), counters, done = true)

    CloseWithLogging(converters)

    // Get all futures so that we can propagate the logging up to the top level for handling
    // in org.locationtech.geomesa.tools.Runner to catch missing dependencies
    futures.foreach(_.get)

    Command.user.info(s"Local ingestion complete in ${TextTools.getTime(start)}")
    if (files.lengthCompare(1) == 0) {
      Command.user.info(IngestCommand.getStatInfo(written.get, failed.get, input = s" for file: ${files.head.path}"))
    } else {
      Command.user.info(IngestCommand.getStatInfo(written.get, failed.get))
    }
    if (errors.get > 0) {
      Command.user.warn("Some files caused errors, ingest counts may not be accurate")
    }
  }
}

object LocalConverterIngest {
  // keep track of failure at a global level, keep line counts and success local
  class LocalIngestCounter(failed: AtomicLong) extends DefaultCounter {
    override def incFailure(i: Long): Unit = failed.getAndAdd(i)
    override def getFailure: Long          = failed.get()
  }
}
