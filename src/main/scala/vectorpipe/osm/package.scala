package vectorpipe

import java.io.{ FileInputStream, InputStream }

import scala.util.{ Failure, Success, Try }

import cats.implicits._
import geotrellis.vector._
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql._
import vectorpipe.osm.internal.{ ElementToFeature => E2F, PlanetHistory }

// --- //

/** Types and functions unique to working with OpenStreetMap data. */
package object osm {

  type OSMFeature = Feature[Geometry, ElementMeta]
  private[vectorpipe] type OSMPoint = Feature[Point, ElementMeta]
  private[vectorpipe] type OSMLine = Feature[Line, ElementMeta]
  private[vectorpipe] type OSMPolygon = Feature[Polygon, ElementMeta]
  private[vectorpipe] type OSMMultiPoly = Feature[MultiPolygon, ElementMeta]

  /** Given a path to an OSM XML file, parse it into usable types. */
  def fromLocalXML(
    path: String
  )(implicit sc: SparkContext): Either[String, (RDD[(Long, Node)], RDD[(Long, Way)], RDD[(Long, Relation)])] = {
    /* A byte stream, so as to not tax the heap */
    Try(new FileInputStream(path): InputStream).flatMap(xml => Element.elements.parse(xml)) match {
      case Failure(e) => Left(e.toString)
      case Success((ns, ws, rs)) =>
        Right((sc.parallelize(ns), sc.parallelize(ws), sc.parallelize(rs)))
    }
  }

  /** Given a path to an Apache ORC file containing OSM data, read out RDDs of each Element type.
    * If you want to read a file from S3, you must call [[vectorpipe.useS3]] first
    * to properly configure Hadoop to read your S3 credentials.
    */
  def fromORC(
    path: String
  )(implicit ss: SparkSession): Either[String, (RDD[(Long, Node)], RDD[(Long, Way)], RDD[(Long, Relation)])] = {
    Try(ss.read.orc(path)) match {
      case Failure(e) => Left(e.toString)
      case Success(data) => Right(fromDataFrame(data))
    }
  }

  /** Given a [[DataFrame]] that follows [[https://github.com/mojodna/osm2orc#schema this table schema]],
    * read out RDDs of each [[Element]] type.
    */
  def fromDataFrame(
    data: DataFrame
  )(implicit ss: SparkSession): (RDD[(Long, Node)], RDD[(Long, Way)], RDD[(Long, Relation)]) = {
    /* WARNING: Here be Reflection Dragons!
     * You may be look at the methods below and think: gee, that seems a bit verbose. You'd be right,
     * but that doesn't change what's necessary. The workings here are fairly brittle - things
     * might compile but fail mysteriously at runtime if anything is changed here (specifically regarding
     * the explicit type hand-holding).
     *
     * Changes made here might also be respected by demo code local to this library,
     * but then fail at runtime when published and used in a separate project.
     * How the `Member`s list below is handled is an example of this.
     * Moral of the story: avoid reflection and other runtime trickery.
     */
    (allNodes(data), allWays(data), allRelations(data))
  }

  /** Collect all the Nodes that exist in the given DataFrame. */
  private[this] def allNodes(data: DataFrame)(implicit ss: SparkSession): RDD[(Long, Node)] = {
    import ss.implicits._

    data
      .select("lat", "lon", "id", "user", "uid", "changeset", "version", "timestamp", "visible", "tags")
      .where("type = 'node'")
      .map { row =>
        /* ASSUMPTION: 0 and 1 here assume the order of the `select` terms won't change! */
        val lat: Double = if (row.isNullAt(0)) 0 else row.getAs[java.math.BigDecimal]("lat").doubleValue()
        val lon: Double = if (row.isNullAt(1)) 0 else row.getAs[java.math.BigDecimal]("lon").doubleValue()
        val tags: scala.collection.immutable.Map[String, String] =
          row.getAs[scala.collection.immutable.Map[String, String]]("tags")

        (lat, lon, metaFromRow(row), tags)
      }
      .rdd
      .map { case (lat, lon, rawMeta, tags) =>
        val meta: ElementMeta = makeMeta(rawMeta, tags)

        (meta.id, Node(lat, lon, meta))
      }
  }

  /** Collect all the Ways that exist in the given DataFrame. */
  private[this] def allWays(data: DataFrame)(implicit ss: SparkSession): RDD[(Long, Way)] = {
    import ss.implicits._

    data
      .select($"nds.ref".alias("nds"), $"id", $"user", $"uid", $"changeset", $"version", $"timestamp", $"visible", $"tags")
      .where("type = 'way'")
      .map { row =>
        val nodes: Vector[Long] = row.getAs[Seq[Long]]("nds").toVector
        val tags: scala.collection.immutable.Map[String, String] =
          row.getAs[scala.collection.immutable.Map[String, String]]("tags")

        (nodes, metaFromRow(row), tags)
      }
      .rdd
      .map { case (nodes, rawMeta, tags) =>
        val meta: ElementMeta = makeMeta(rawMeta, tags)

        (meta.id, Way(nodes, meta))
      }
  }

  /** Collect all the Relations that exist in the given DataFrame. */
  private[this] def allRelations(data: DataFrame)(implicit ss: SparkSession): RDD[(Long, Relation)] = {
    import ss.implicits._

    data
      .select($"members.type".alias("types"), $"members.ref".alias("refs"), $"members.role".alias("roles"), $"id", $"user", $"uid", $"changeset", $"version", $"timestamp", $"visible", $"tags")
      .where("type = 'relation'")
      .map { row =>

        /* ASSUMPTION: These three lists respect their original ordering as they
         * were stored in Orc STRUCTs. i.e. the first element of each list were
         * from the same STRUCT, as were those from the second, and third, etc.
         */
        val types: Seq[String] = row.getAs[Seq[String]]("types")
        val refs: Seq[Long] = row.getAs[Seq[Long]]("refs")
        val roles: Seq[String] = row.getAs[Seq[String]]("roles")

        val tags: scala.collection.immutable.Map[String, String] =
          row.getAs[scala.collection.immutable.Map[String, String]]("tags")

        (types, refs, roles, metaFromRow(row), tags)
      }
      .rdd
      .map { case (types, refs, roles, rawMeta, tags) =>
        /* Scala has no `zip3` or `zipWith`, so we have to combine these three Seqs
         * somewhat inefficiently. This line really needs improvement.
         *
         * The issue here is that reflection can't figure out how to read a `Seq[Member]`
         * from a `Row`, but _only when vectorpipe is used in another project_. Demo code
         * local to this project will work just fine. The exception thrown is:
         *
         * java.lang.ClassCastException: org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema
         * cannot be cast to vectorpipe.osm.Member
         *
         * This was supposed to have been fixed in an earlier version of Spark,
         * and there is little mention of the issue in general on the internet.
         */
        val members: Seq[Member] =
          types.zip(refs).zip(roles).map { case ((ty, rf), ro) => Member(ty, rf, ro) }

        val meta: ElementMeta = makeMeta(rawMeta, tags)

        (meta.id, Relation(members.toList, meta))
      }
  }

  /** An unfortunate necessity to avoid reflection errors involving `java.time.Instant` */
  private[this] def makeMeta(m: (Long, String, Long, Long, Long, Long, Boolean), tags: Map[String, String]): ElementMeta =
    ElementMeta(m._1, m._2, m._3, m._4, m._5, java.time.Instant.ofEpochMilli(m._6), m._7, tags)

  private[this] def metaFromRow(row: Row): (Long, String, Long, Long, Long, Long, Boolean) = {
    (
      row.getAs[Long]("id"),
      row.getAs[String]("user"),
      row.getAs[Long]("uid"),
      row.getAs[Long]("changeset"),
      row.getAs[Long]("version"),
      row.getAs[java.sql.Timestamp]("timestamp").toInstant.toEpochMilli,
      row.getAs[Boolean]("visible")
    )
  }

  /**
   * Convert an RDD of raw OSM [[Element]]s into interpreted GeoTrellis
   * [[Feature]]s. In order to mix the various subtypes together, they've
   * been upcasted internally to [[Geometry]]. Note:
   * {{{
   * type OSMFeature = Feature[Geometry, ElementMeta]
   * }}}
   *
   * ===Behaviour===
   * This algorithm aims to losslessly "sanitize" its input data,
   * in that it will break down malformed Relation structures, as
   * well as cull member references to Elements which no longer
   * exist (or exist outside the subset of data you're working
   * on). Mathematically speaking, there should exist a function
   * to reverse this conversion. This theoretical function and
   * `toFeatures` form an isomorphism if the source data is
   * correct. In other words, given:
   * {{{
   * parse: XML => RDD[Element]
   * toFeatures: RDD[Element] => RDD[OSMFeature]
   * restore: RDD[OSMFeature] => RDD[Element]
   * unparse: RDD[Element] => XML
   * }}}
   * then:
   * {{{
   * unparse(restore(toFeatures(parse(xml: XML))))
   * }}}
   * will yield a body of semantically correct OSM data.
   *
   * To achieve this sanity, the algorithm has the following behaviour:
   *   - Graphs of [[Relation]]s will be broken into spanning [[Tree]]s.
   *   - It doesn't make sense to represent non-multipolygon Relations as
   *     GeoTrellis `Geometry`s, so Relation metadata is disseminated
   *     across its child members. Otherwise, Relations are "dropped"
   *     from the output.
   */
  def toSnapshot(
    logError: (Feature[Line, ElementMeta] => String) => Feature[Line, ElementMeta] => Unit,
    nodes: RDD[Node],
    ways: RDD[Way],
    relations: RDD[Relation]
  ): RDD[OSMFeature] = {

    /* All Geometric OSM Relations.
     * A (likely false) assumption made in the `flatTree` function is that
     * Geometric Relations never appear in Relation Graphs. Therefore we can
     * naively grab them all here.
     */
    val geomRelations: RDD[Relation] = relations.filter({ r =>
      r.meta.tags.get("type") === Some("multipolygon")
    })

    val (points, rawLines, rawPolys) = E2F.geometries(nodes, ways)

    /* Depending on the dataset used, `Way` data may be incomplete. That is,
     * the local version of a Way may have fewer Node references that the original
     * as found on OpenStreetMap. These usually occur along "dataset bounding
     * boxes" found in OSM subregion extracts, where a Polygon is cut in half by
     * the BBOX. The resulting Polygons, with only a subset of the original Nodes,
     * are often self-intersecting. This causes Topology Exceptions during the
     * clipping stage of the pipeline. Our only recourse is to remove them here.
     *
     * See: https://github.com/geotrellis/vectorpipe/pull/16#issuecomment-290144694
     */
    val simplePolys = rawPolys.filter(_.geom.isValid)

    val (multiPolys, lines, polys) = E2F.multipolygons(logError, rawLines, simplePolys, geomRelations)

    /* A trick to allow us to fuse the RDDs of various Geom types */
    val pnt: RDD[OSMFeature] = points.map(identity)
    val lns: RDD[OSMFeature] = lines.map(identity)
    val pls: RDD[OSMFeature] = polys.map(identity)
    val mps: RDD[OSMFeature] = multiPolys.map(identity)

    nodes.sparkContext.union(pnt, lns, pls, mps)
  }

  @deprecated("Use toSnapshot instead.", "2017-11-14")
  def toFeatures(
    logError: (Feature[Line, ElementMeta] => String) => Feature[Line, ElementMeta] => Unit,
    nodes: RDD[Node],
    ways: RDD[Way],
    relations: RDD[Relation]
  ): RDD[OSMFeature] = toSnapshot(logError, nodes, ways, relations)

  /** All Lines and Polygons that could be reconstructed from a set of all
    * historical OSM Elements.
    */
  def toHistory(
    nodes: RDD[(Long, Node)],
    ways: RDD[(Long, Way)]
  ): (RDD[Feature[Point, ElementMeta]], RDD[Feature[Line, ElementMeta]], RDD[Feature[Polygon, ElementMeta]]) = {
    val (points, lines, polygons) = PlanetHistory.features(nodes, ways)

    /* Depending on the dataset used, `Way` data may be incomplete. That is,
     * the local version of a Way may have fewer Node references that the original
     * as found on OpenStreetMap. These usually occur along "dataset bounding
     * boxes" found in OSM subregion extracts, where a Polygon is cut in half by
     * the BBOX. The resulting Polygons, with only a subset of the original Nodes,
     * are often self-intersecting. This causes Topology Exceptions during the
     * clipping stage of the pipeline. Our only recourse is to remove them here.
     *
     * See: https://github.com/geotrellis/vectorpipe/pull/16#issuecomment-290144694
     */
    (points, lines, polygons.filter(_.geom.isValid))
  }
}
