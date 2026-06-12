package com.realityengine.services

import com.realityengine.models.Machine
import io.circe.Json

case class DispatchBinding(
  agent: String,
  trigger: String,
  action: String,
  agentActionsCatalog: Vector[String],
  autonomyMode: String,
  writeBack: Json
) {
  def hasDispatch: Boolean = agent.nonEmpty && trigger.nonEmpty
}

object DispatchBinding {
  private val DefaultSchemaRef = "localAIStack/services/api/routers/graphql_endpoint.py"

  private def field(json: Json, name: String): Option[Json] =
    json.hcursor.downField(name).focus

  private def stringField(json: Json, name: String): Option[String] =
    field(json, name).flatMap(_.asString).filter(_.nonEmpty)

  private def metadataString(metadata: Map[String, Json], name: String): String =
    metadata.get(name).flatMap(_.asString).getOrElse("")

  private def stringArray(value: Option[Json]): Vector[String] =
    value.flatMap(_.asArray).getOrElse(Vector.empty).flatMap(_.asString)

  private def selectAction(actions: Vector[String], values: Vector[Double]): String =
    values.zipWithIndex.collectFirst {
      case (value, index) if value != 0.0 && index < actions.length => actions(index)
    }.orElse(actions.headOption).getOrElse("")

  def fromMachine(machine: Machine, values: Vector[Double] = Vector.empty): DispatchBinding =
    fromMetadata(machine.metadata, values)

  def fromMetadata(metadata: Map[String, Json], values: Vector[Double] = Vector.empty): DispatchBinding = {
    val legacyActions = stringArray(metadata.get("agentActions"))
    val binding = metadata.get("agentBinding").filter(_.isObject)
    val bindingActions = binding.map(b => stringArray(field(b, "allowedActions"))).filter(_.nonEmpty)
    val actions = bindingActions.getOrElse(legacyActions)

    DispatchBinding(
      agent = binding.flatMap(stringField(_, "agent")).getOrElse(metadataString(metadata, "dispatchableAgent")),
      trigger = binding.flatMap(stringField(_, "trigger")).getOrElse(metadataString(metadata, "aiTrigger")),
      action = selectAction(actions, values),
      agentActionsCatalog = actions,
      autonomyMode = binding.flatMap(stringField(_, "mode")).getOrElse(""),
      writeBack = binding.flatMap(field(_, "writeBack")).getOrElse(Json.Null)
    )
  }

  def dispatchJson(
    metadata: Map[String, Json],
    values: Vector[Double] = Vector.empty,
    dispatchMode: String = "graphql",
    defaultGraphqlUrl: String = ""
  ): Json = {
    val binding = fromMetadata(metadata, values)
    val triggerConfig = metadata.get("triggerConfig").filter(_.isObject)
    val triggerDispatch = triggerConfig.flatMap(field(_, "dispatch")).filter(_.isObject)
    val graphql = dispatchMode == "graphql"

    Json.obj(
      "processId" -> Json.fromString(triggerConfig.flatMap(stringField(_, "processId")).getOrElse("")),
      "processName" -> Json.fromString(triggerConfig.flatMap(stringField(_, "processName")).getOrElse("")),
      "agent" -> Json.fromString(binding.agent),
      "action" -> Json.fromString(binding.action),
      "agentActionsCatalog" -> Json.arr(binding.agentActionsCatalog.map(Json.fromString): _*),
      "trigger" -> Json.fromString(binding.trigger),
      "autonomyMode" -> Json.fromString(binding.autonomyMode),
      "writeBack" -> binding.writeBack,
      "endpoint" -> Json.obj(
        "kind" -> Json.fromString(dispatchMode),
        "url" -> Json.fromString(if (graphql) triggerConfig.flatMap(stringField(_, "endpoint")).getOrElse(defaultGraphqlUrl) else ""),
        "mutation" -> Json.fromString(if (graphql) triggerDispatch.flatMap(stringField(_, "mutation")).getOrElse("updateProcessState") else ""),
        "schemaRef" -> Json.fromString(if (graphql) triggerDispatch.flatMap(stringField(_, "schemaRef")).getOrElse(DefaultSchemaRef) else "")
      )
    )
  }
}
