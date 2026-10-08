package com.realityengine.perception

import io.circe.parser.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import com.realityengine.perception.metrics.SemanticMetrics
import com.realityengine.perception.mqtt.{MqttBridge, MqttMappingRule}

/** The MQTT block of GET /api/metrics (RealityEngine_Scala#186).
  *
  * Only the TypeScript PE exported it, so the Semantic Guardrails MQTT panels
  * were empty on every native universe. The text is pinned literally: C++ and
  * LSP emit the same bytes after the runtime label, and verify-metrics-parity
  * compares the whole exposition.
  */
class MqttMetricsSpec extends AnyFlatSpec with Matchers {
  private def rules(mappings: String): Vector[MqttMappingRule] =
    MqttBridge.parseRegistry(parse(s"""{"mappings": [$mappings]}""").toOption.get).toOption.get

  private val Rule =
    """{"id": "temp", "topicFilter": "site/dev9/readings", "sensorIdTemplate": "dev9.temp",
      | "region": {"offset": 0, "length": 1}, "extract": {"type": "json", "pointer": "/data/aTemp"},
      | "normalize": {"mode": "minmax", "min": 0, "max": 150, "clamp": true},
      | "pushMode": "immediate"}""".stripMargin

  "renderMqtt" should "emit the two gauges at zero and no counters when the bridge is off" in {
    SemanticMetrics.renderMqtt(None) shouldBe
      """# HELP mqtt_bridge_enabled MQTT bridge is configured (1) or disabled (0).
        |# TYPE mqtt_bridge_enabled gauge
        |mqtt_bridge_enabled{runtime="scala"} 0
        |# HELP mqtt_bridge_connected MQTT bridge is currently connected to the broker (1/0).
        |# TYPE mqtt_bridge_connected gauge
        |mqtt_bridge_connected{runtime="scala"} 0
        |""".stripMargin
  }

  it should "emit all eight series, in the TypeScript PE's order, from the bridge's counters" in {
    val bridge = new MqttBridge("tcp://127.0.0.1:1", "metrics-spec", rules(Rule),
      (_, _, _, _, _, _, _) => (), () => ())
    bridge.injectMessage("site/dev9/readings", """{"data": {"aTemp": 72.5}}""")
    bridge.injectMessage("site/dev9/readings", "not-json")
    bridge.injectMessage("nobody/listens", """{"x": 1}""")

    val samples = SemanticMetrics.renderMqtt(Some(bridge)).linesIterator.filterNot(_.startsWith("#")).toList
    samples shouldBe List(
      """mqtt_bridge_enabled{runtime="scala"} 1""",
      """mqtt_bridge_connected{runtime="scala"} 0""",
      """mqtt_messages_received_total{runtime="scala"} 3""",
      """mqtt_messages_mapped_total{runtime="scala"} 1""",
      """mqtt_messages_rejected_total{runtime="scala"} 1""",
      """mqtt_messages_unmatched_total{runtime="scala"} 1""",
      """mqtt_pushes_triggered_total{runtime="scala"} 1""",
      """mqtt_mappings_loaded{runtime="scala"} 1""")
  }
}
