package com.realityengine.perception.api

import io.circe.Json

/** What a completion writes, resolved as C++ `ingest_completion` resolves it
  * (RealityEngine_Scala#157). A mapping applies only when the body names one;
  * there is no default. This defaulted to the ACP/OpenClaw mapping, so a
  * self-describing completion (its own region, no sourceMappingId) landed on
  * [4210:4214] under the ACP name, on this runtime only.
  *
  * Precedence, body before mapping: sensorId (then the mapping's sensorId,
  * then its template, then `agent.<agent>.completion`); name (then
  * `agent:<provider>/<agent>/completion`); region; values; ttlMs (then 300000).
  */
object CompletionResolution {
  final case class Target(
    smId: Option[String], provider: String, agent: String, sensorId: String, name: String,
    region: Option[(Int, Int)], values: Vector[Double], ttlMs: Long)

  def sourceIdPart(value: String): String = {
    val sb = new StringBuilder
    value.foreach { c =>
      if (c.isLetterOrDigit && c < 128) sb.append(c.toLower)
      else if (sb.nonEmpty && sb.last != '-') sb.append('-')
    }
    val out = sb.toString.reverse.dropWhile(_ == '-').reverse
    if (out.isEmpty) "unnamed" else out
  }

  def resolve(body: Json, mappings: String => Option[Json]): Either[String, Target] = {
    val c = body.hcursor
    val smId = c.get[String]("sourceMappingId").toOption.filter(_.nonEmpty)
      .orElse(c.get[String]("mappingId").toOption.filter(_.nonEmpty))
    val configured: Either[String, Json] = smId match {
      case None     => Right(Json.obj())
      case Some(id) => mappings(id).toRight(s"""Unknown sourceMappingId "$id"""")
    }
    configured.map { base =>
      val mapping  = c.downField("sourceMapping").focus.filter(_.isObject).fold(base)(o => base.deepMerge(o))
      val m        = mapping.hcursor
      val provider = c.get[String]("provider").getOrElse("external")
      val agent    = c.get[String]("agent").orElse(c.get[String]("agentId")).getOrElse("agent")
      val sensorId = c.get[String]("sensorId").toOption.filter(_.nonEmpty)
        .orElse(m.get[String]("sensorId").toOption.filter(_.nonEmpty))
        .orElse(m.get[String]("sensorIdTemplate").toOption.map(tpl => template(tpl, Map(
          "provider"      -> sourceIdPart(provider),
          "agent"         -> sourceIdPart(agent),
          "correlationId" -> sourceIdPart(c.get[String]("correlationId").getOrElse("")),
          "envelopeId"    -> sourceIdPart(c.get[String]("envelopeId").getOrElse(""))))))
        .getOrElse(s"agent.${sourceIdPart(agent)}.completion")
      val name = c.get[String]("name").toOption
        .orElse(m.get[String]("name").toOption)
        .getOrElse(s"agent:$provider/$agent/completion")
      def regionOf(r: io.circe.ACursor) = for {
        o <- r.get[Int]("offset").toOption
        l <- r.get[Int]("length").toOption
      } yield (o, l)
      val region = regionOf(c.downField("region")).orElse(regionOf(m.downField("region")))
      val values = c.downField("values").as[Vector[Double]].toOption
        .orElse(m.downField("values").as[Vector[Double]].toOption)
        .getOrElse(Vector(1.0))
      val ttl = c.get[Long]("ttlMs").toOption.orElse(m.get[Long]("ttlMs").toOption).getOrElse(300000L)
      Target(smId, provider, agent, sensorId, name, region, values, ttl)
    }
  }


  private def template(tpl: String, tokens: Map[String, String]): String =
    tokens.foldLeft(tpl) { case (t, (k, v)) => t.replace(s"{$k}", v) }
}
