package com.realityengine.perception

import io.circe.parser.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.realityengine.perception.mqtt.{MqttBridge, MqttMappingRule, MqttSources}

/** MQTT sources are named after their topic, as every other runtime names them
  * (RealityEngine_CI#463). The name is the cross-runtime membership key, so a
  * sensorId-named source reads as absent from this runtime.
  */
class MqttSourcesSpec extends AnyFlatSpec with Matchers {

  private def rules(mappings: String): Vector[MqttMappingRule] =
    MqttBridge.parseRegistry(parse(s"""{"mappings": [$mappings]}""").toOption.get).toOption.get

  private def rule(id: String, topicFilter: String, sensorIdTemplate: String, offset: Int) =
    s"""{"id": "$id", "topicFilter": "$topicFilter", "sensorIdTemplate": "$sensorIdTemplate",
       |  "region": {"offset": $offset, "length": 1},
       |  "extract": {"type": "json", "pointer": "/data/v"}}""".stripMargin

  private val yumaTopic = "LATERAL/AmbientSuite/DEV0000009/SensorReadings/v1"

  "MqttSources.declarable" should "name each source after its topic and key it by sensorId" in {
    val declared = MqttSources.declarable(rules(Seq(
      rule("t", yumaTopic, "ambient.DEV0000009.temp", 0),
      rule("h", yumaTopic, "ambient.DEV0000009.humidity", 1),
    ).mkString(",")))
    declared.map(s => (s.id, s.name, s.region.offset)) shouldBe Vector(
      ("ambient.DEV0000009.temp", s"mqtt:$yumaTopic", 0),
      ("ambient.DEV0000009.humidity", s"mqtt:$yumaTopic", 1),
    )
    declared.map(_.sensorId) shouldBe declared.map(_.id)
    declared.forall(!_.active) shouldBe true
  }

  it should "leave a rule whose topic filter has wildcards to declare on first signal" in {
    MqttSources.declarable(rules(Seq(
      rule("plus", "LATERAL/+/DEV0000009/SensorReadings/v1", "ambient.any.temp", 0),
      rule("hash", "LATERAL/#", "ambient.all.temp", 1),
    ).mkString(","))) shouldBe empty
  }

  it should "leave a rule whose sensorId interpolates topic captures to declare on first signal" in {
    MqttSources.declarable(rules(rule("cap", yumaTopic, "ambient.{1}.temp", 0))) shouldBe empty
  }

  "MqttSources.source" should "name a first-signal source after the topic the message arrived on" in {
    val s = MqttSources.source("ambient.DEV0000042.temp", "LATERAL/AmbientSuite/DEV0000042/SensorReadings/v1", 5, 1, 30000L)
    s.name shouldBe "mqtt:LATERAL/AmbientSuite/DEV0000042/SensorReadings/v1"
    s.id shouldBe "ambient.DEV0000042.temp"
    s.origin shouldBe Some("mqtt")
  }
}
