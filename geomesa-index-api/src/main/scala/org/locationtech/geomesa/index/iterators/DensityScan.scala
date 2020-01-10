/***********************************************************************
 * Copyright (c) 2013-2019 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.index.iterators
import com.typesafe.scalalogging.LazyLogging
import org.geotools.filter.text.ecql.ECQL
import org.geotools.util.factory.Hints
import org.geotools.util.factory.Hints.ClassKey
import org.locationtech.geomesa.features.ScalaSimpleFeature
import org.locationtech.geomesa.features.kryo.impl.{KryoFeatureDeserialization, KryoFeatureSerialization}
import org.locationtech.geomesa.filter.FilterHelper
import org.locationtech.geomesa.index.api.GeoMesaFeatureIndex
import org.locationtech.geomesa.index.api.QueryPlan.ResultsToFeatures
import org.locationtech.geomesa.index.iterators.DensityScan.GeometryRenderer
import org.locationtech.geomesa.utils.conf.GeoMesaSystemProperties.SystemProperty
import org.locationtech.geomesa.utils.geotools.converters.FastConverter
import org.locationtech.geomesa.utils.geotools.{GeometryUtils, GridSnap, RenderingGrid}
import org.locationtech.geomesa.utils.interop.SimpleFeatureTypes
import org.locationtech.jts.geom._
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}
import org.opengis.filter.Filter
import org.opengis.filter.expression.Expression

trait DensityScan extends AggregatingScan[RenderingGrid] {

  // we snap each point into a pixel and aggregate based on that
  protected var renderer: GeometryRenderer = _

  private var batchSize: Int = -1

  override protected def initResult(
      sft: SimpleFeatureType,
      transform: Option[SimpleFeatureType],
      options: Map[String, String]): RenderingGrid = {
    renderer = DensityScan.getRenderer(sft, options.get(DensityScan.Configuration.WeightOpt))
    val bounds = options(DensityScan.Configuration.EnvelopeOpt).split(",").map(_.toDouble)
    val envelope = new Envelope(bounds(0), bounds(1), bounds(2), bounds(3))
    val Array(width, height) = options(DensityScan.Configuration.GridOpt).split(",").map(_.toInt)
    batchSize = DensityScan.BatchSize.toInt.get // has a valid default so should be safe to .get
    new RenderingGrid(envelope, width, height)
  }

  override protected def aggregateResult(sf: SimpleFeature, result: RenderingGrid): Unit =
    renderer.render(result, sf)

  override protected def notFull(result: RenderingGrid): Boolean = result.size < batchSize

  override protected def encodeResult(result: RenderingGrid): Array[Byte] = DensityScan.encodeResult(result)
}

object DensityScan extends LazyLogging {

  import org.locationtech.geomesa.index.conf.QueryHints.RichHints
  import org.locationtech.geomesa.utils.geotools.RichSimpleFeatureType.RichSimpleFeatureType

  type GridIterator  = SimpleFeature => Iterator[(Double, Double, Double)]

  val BatchSize = SystemProperty("geomesa.density.batch.size", "100000")

  val DensitySft: SimpleFeatureType = SimpleFeatureTypes.createType("density", "*geom:Point:srid=4326")
  val DensityValueKey = new ClassKey(classOf[Array[Byte]])

  // configuration keys
  object Configuration {
    val EnvelopeOpt = "envelope"
    val GridOpt     = "grid"
    val WeightOpt   = "weight"
  }

  def configure(
      sft: SimpleFeatureType,
      index: GeoMesaFeatureIndex[_, _],
      filter: Option[Filter],
      hints: Hints): Map[String, String] = {
    import AggregatingScan.{OptionToConfig, StringToConfig}
    import Configuration.{EnvelopeOpt, GridOpt, WeightOpt}

    val envelope = hints.getDensityEnvelope.get
    val (width, height) = hints.getDensityBounds.get
    val base = AggregatingScan.configure(sft, index, filter, None, hints.getSampling) // note: don't pass transforms
    base ++ AggregatingScan.optionalMap(
      EnvelopeOpt -> s"${envelope.getMinX},${envelope.getMaxX},${envelope.getMinY},${envelope.getMaxY}",
      GridOpt     -> s"$width,$height",
      WeightOpt   -> hints.getDensityWeight
    )
  }

  /**
    * Encodes a sparse matrix into a byte array
    */
  def encodeResult(result: RenderingGrid): Array[Byte] = {
    val output = KryoFeatureSerialization.getOutput(null)
    result.iterator.toList.groupBy(_._1._1).foreach { case (row, cols) =>
      output.writeInt(row, true)
      output.writeInt(cols.size, true)
      cols.foreach { case (xy, weight) =>
        output.writeInt(xy._2, true)
        output.writeDouble(weight)
      }
    }
    output.toBytes
  }

  /**
    * Returns a mapping of simple features (returned from a density query) to weighted points in the
    * form of (x, y, weight)
    */
  def decodeResult(envelope: Envelope, gridWidth: Int, gridHeight: Int): GridIterator =
    decodeResult(new GridSnap(envelope, gridWidth, gridHeight))

  /**
    * Decodes a result feature into an iterator of (x, y, weight)
    */
  def decodeResult(gridSnap: GridSnap)(sf: SimpleFeature): Iterator[(Double, Double, Double)] = {
    val result = sf.getUserData.get(DensityValueKey).asInstanceOf[Array[Byte]]
    val input = KryoFeatureDeserialization.getInput(result, 0, result.length)
    new Iterator[(Double, Double, Double)]() {
      private var x = 0.0
      private var colCount = 0
      override def hasNext: Boolean = input.position < input.limit
      override def next(): (Double, Double, Double) = {
        if (colCount == 0) {
          x = gridSnap.x(input.readInt(true))
          colCount = input.readInt(true)
        }
        val y = gridSnap.y(input.readInt(true))
        val weight = input.readDouble()
        colCount -= 1
        (x, y, weight)
      }
    }
  }

  /**
    * Get the attributes used by a density query
    *
    * @param hints query hints
    * @param sft simple feature type
    * @return
    */
  def propertyNames(hints: Hints, sft: SimpleFeatureType): Seq[String] = {
    val weight = hints.getDensityWeight.map(ECQL.toExpression)
    (Seq(sft.getGeomField) ++ weight.toSeq.flatMap(FilterHelper.propertyNames(_, sft))).distinct
  }

  /**
    * Gets a renderer for the associated geometry binding
    *
    * @param sft simple feature type
    * @return
    */
  def getRenderer(sft: SimpleFeatureType, weight: Option[String]): GeometryRenderer = {
    // function to get the weight from the feature - defaults to 1.0 unless an attribute/exp is specified
    val weigher = weight match {
      case None => EqualWeight
      case Some(w) =>
        val i = sft.indexOf(w)
        if (i == -1) {
          new WeightByExpression(ECQL.toExpression(w))
        } else if (classOf[Number].isAssignableFrom(sft.getDescriptor(i).getType.getBinding)) {
          new WeightByNumber(i)
        } else {
          new WeightByNonNumber(i)
        }
    }

    sft.getGeometryDescriptor.getType.getBinding match {
      case b if b == classOf[Point]           => new PointRenderer(sft.getGeomIndex, weigher)
      case b if b == classOf[MultiPoint]      => new MultiPointRenderer(sft.getGeomIndex, weigher)
      case b if b == classOf[LineString]      => new LineStringRenderer(sft.getGeomIndex, weigher)
      case b if b == classOf[MultiLineString] => new MultiLineStringRenderer(sft.getGeomIndex, weigher)
      case b if b == classOf[Polygon]         => new PolygonRenderer(sft.getGeomIndex, weigher)
      case b if b == classOf[MultiPolygon]    => new MultiPolygonRenderer(sft.getGeomIndex, weigher)
      case _                                  => new MultiRenderer(sft.getGeomIndex, weigher)
    }
  }

  abstract class DensityResultsToFeatures[T] extends ResultsToFeatures[T] {

    override def init(state: Map[String, String]): Unit = {}

    override def state: Map[String, String] = Map.empty

    override def schema: SimpleFeatureType = DensityScan.DensitySft

    override def apply(result: T): SimpleFeature = {
      val sf = new ScalaSimpleFeature(DensityScan.DensitySft, "", Array(GeometryUtils.zeroPoint))
      sf.getUserData.put(DensityScan.DensityValueKey, bytes(result))
      sf
    }

    protected def bytes(result: T): Array[Byte]

    def canEqual(other: Any): Boolean = other.isInstanceOf[DensityResultsToFeatures[T]]

    override def equals(other: Any): Boolean = other match {
      case that: DensityResultsToFeatures[T] if that.canEqual(this) => true
      case _ => false
    }

    override def hashCode(): Int = schema.hashCode()
  }

  /**
    * Gets the weight for a simple feature
    */
  sealed trait Weigher {
    def weight(sf: SimpleFeature): Double
  }

  case object EqualWeight extends Weigher {
    override def weight(sf: SimpleFeature): Double = 1d
  }

  /**
    * Gets the weight for a feature from a numeric attribute
    */
  class WeightByNumber(i: Int) extends Weigher {
    override def weight(sf: SimpleFeature): Double = {
      val d = sf.getAttribute(i).asInstanceOf[Number]
      if (d == null) { 0.0 } else { d.doubleValue }
    }
  }

  /**
    * Tries to convert a non-double attribute into a double
    */
  class WeightByNonNumber(i: Int) extends Weigher {
    override def weight(sf: SimpleFeature): Double = {
      val d = sf.getAttribute(i)
      if (d == null) { 0.0 } else {
        val converted = FastConverter.convert(d, classOf[java.lang.Double])
        if (converted == null) { 1.0 } else { converted.doubleValue() }
      }
    }
  }

  /**
    * Evaluates an arbitrary expression against the simple feature to return a weight
    */
  class WeightByExpression(e: Expression) extends Weigher {
    override def weight(sf: SimpleFeature): Double = {
      val d = e.evaluate(sf, classOf[java.lang.Double])
      if (d == null) { 0.0 } else { d }
    }
  }

  /**
    * Renderer for geometries
    */
  sealed trait GeometryRenderer {
    def render(grid: RenderingGrid, sf: SimpleFeature)
  }

  /**
    * Writes a density record from a feature that has a point geometry
    */
  class PointRenderer(i: Int, weigher: Weigher) extends GeometryRenderer {
    override def render(grid: RenderingGrid, sf: SimpleFeature): Unit =
      grid.render(sf.getAttribute(i).asInstanceOf[Point], weigher.weight(sf))
  }

  /**
    * Writes a density record from a feature that has a multi-point geometry
    */
  class MultiPointRenderer(i: Int, weigher: Weigher) extends GeometryRenderer {
    override def render(grid: RenderingGrid, sf: SimpleFeature): Unit =
      grid.render(sf.getAttribute(i).asInstanceOf[MultiPoint], weigher.weight(sf))
  }

  /**
    * Writes a density record from a feature that has a line geometry
    */
  class LineStringRenderer(i: Int, weigher: Weigher) extends GeometryRenderer {
    override def render(grid: RenderingGrid, sf: SimpleFeature): Unit =
      grid.render(sf.getAttribute(i).asInstanceOf[LineString], weigher.weight(sf))
  }

  /**
    * Writes a density record from a feature that has a multi-line geometry
    */
  class MultiLineStringRenderer(i: Int, weigher: Weigher) extends GeometryRenderer {
    override def render(grid: RenderingGrid, sf: SimpleFeature): Unit =
      grid.render(sf.getAttribute(i).asInstanceOf[MultiLineString], weigher.weight(sf))
  }

  /**
    * Writes a density record from a feature that has a polygon geometry
    */
  class PolygonRenderer(i: Int, weigher: Weigher) extends GeometryRenderer {
    override def render(grid: RenderingGrid, sf: SimpleFeature): Unit =
      grid.render(sf.getAttribute(i).asInstanceOf[Polygon], weigher.weight(sf))
  }

  /**
    * Writes a density record from a feature that has a polygon geometry
    */
  class MultiPolygonRenderer(i: Int, weigher: Weigher) extends GeometryRenderer {
    override def render(grid: RenderingGrid, sf: SimpleFeature): Unit =
      grid.render(sf.getAttribute(i).asInstanceOf[MultiPolygon], weigher.weight(sf))
  }

  /**
    * Writes a density record from a feature that has an arbitrary geometry
    */
  class MultiRenderer(i: Int, weigher: Weigher) extends GeometryRenderer {
    override def render(grid: RenderingGrid, sf: SimpleFeature): Unit =
      grid.render(sf.getAttribute(i).asInstanceOf[Geometry], weigher.weight(sf))
  }
}
