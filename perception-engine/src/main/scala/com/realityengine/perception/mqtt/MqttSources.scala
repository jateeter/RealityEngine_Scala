package com.realityengine.perception.mqtt

import com.realityengine.perception.models.{Region, SensorSourceConfig}

/** The sensor sources the MQTT integration declares.
  *
  * A source is named after the topic that feeds it, `"mqtt:" + topic`, as the
  * C++, LSP and TypeScript PEs name it; its id is the resolved sensorId. The
  * name is the source's cross-runtime membership key (name, type, offset,
  * length), so naming it after the sensorId made every MQTT source read as
  * absent from this runtime (RealityEngine_CI#463).
  */
object MqttSources {

  def source(sensorId: String, topic: String, offset: Int, length: Int, ttlMs: Long): SensorSourceConfig =
    SensorSourceConfig(
      id          = sensorId,
      name        = s"mqtt:$topic",
      region      = Region(offset, length),
      active      = false,
      sensorId    = sensorId,
      lastValue   = Vector.empty,
      lastUpdated = None,
      ttlMs       = ttlMs,
      origin      = Some("mqtt"),
    )

  /** The sources declarable at registration, before any message arrives.
    *
    * Enabling the bridge is the MQTT integration registering, so it declares
    * its source set there and then (RealityEngine_CI#163 points 1 and 2a). A
    * rule qualifies only when both halves of its source are fixed by the rule
    * itself: a `sensorIdTemplate` without topic captures (`{1}`, `{2}`) fixes
    * the id, and a `topicFilter` without wildcards (`+`, `#`) is the one topic
    * that can feed it, which fixes the name. Any other rule declares on first
    * signal, when the topic is known.
    */
  def declarable(rules: Vector[MqttMappingRule]): Vector[SensorSourceConfig] =
    rules
      .filterNot(r => r.sensorIdTemplate.contains("{") || r.topicFilter.exists(c => c == '+' || c == '#'))
      .map(r => source(r.sensorIdTemplate, r.topicFilter, r.regionOffset, r.regionLength, r.ttlMs))
}
