package com.realityengine.perception

import io.circe.parser.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.realityengine.perception.mqtt.MqttBridge

/** POST /api/mqtt/enable connects as the instance's MQTT_CLIENT_ID (#207).
  * It always used reality-engine-pe-scala, so every Scala PE on the shared
  * Yuma broker was one client and each connect evicted the others.
  */
class MqttClientIdSpec extends AnyFlatSpec with Matchers {

  private val rules = MqttBridge.parseRegistry(parse(
    """{"mappings": [{"id": "a", "topicFilter": "t/a", "sensorIdTemplate": "a",
      |  "region": {"offset": 0, "length": 1}, "extract": {"type": "csv-float"}}]}""".stripMargin
  ).toOption.get).toOption.get

  "MqttBridge.enableClientId" should "be the default with no bridge and no MQTT_CLIENT_ID" in {
    MqttBridge.enableClientId(None, Map.empty) shouldBe "reality-engine-pe-scala"
  }

  it should "be the instance's MQTT_CLIENT_ID when no bridge is running" in {
    MqttBridge.enableClientId(None, Map("MQTT_CLIENT_ID" -> "re-pe-scala-1-2c7d92e3")) shouldBe "re-pe-scala-1-2c7d92e3"
  }

  it should "treat an empty MQTT_CLIENT_ID as unset" in {
    MqttBridge.enableClientId(None, Map("MQTT_CLIENT_ID" -> "")) shouldBe "reality-engine-pe-scala"
  }

  it should "keep a running bridge's id" in {
    val running = new MqttBridge("mqtt://127.0.0.1:1", "running-bridge-id", rules, (_, _, _, _, _, _, _) => (), () => ())
    MqttBridge.enableClientId(Some(running), Map("MQTT_CLIENT_ID" -> "re-pe-scala-1-2c7d92e3")) shouldBe "running-bridge-id"
  }
}
