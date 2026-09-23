package com.realityengine.perception.triggers

import io.circe.Json
import io.circe.syntax._

import java.util.concurrent.atomic.AtomicLong

/** Trigger dispatch: turns the Reality Engine's mergeBatch into
  * `ces.terminal.event` envelopes and dispatch records.
  *
  * This runtime had none (RealityEngine_Scala#149) and declared trigger
  * dispatch `unsupported`. The behaviour here is the one the C++ and LSP PEs
  * already agree on, held 3-of-3 by RealityEngine_CI SURFACE_SPEC.md
  * ("Dispatch surface shapes") and by RealityEngine_Machines
  * schemas/ai-trigger-envelope.schema.json:
  *
  *   - a merge entry without governance is droppedNoGovernance;
  *   - a machine absent from a catalog that has never loaded is
  *     droppedCatalogCold; absent from a loaded one, or declaring no agent or
  *     trigger, droppedNoDispatch;
  *   - otherwise one envelope and one 18-key dispatch record.
  *
  * Pure: the catalog, the semantics manifest, the clock and the id source are
  * passed in, so every rule is testable without an ActorSystem or an RE.
  */
final class TriggerDispatcher(
  val enabled: Boolean,
  val mode: String,
  val graphqlEndpoint: String,
  realityEngineUrl: String,
  catalog: () => (Map[String, Json], Long),
  semanticsBase: String => Option[String],
  onSemantics: (Boolean, Option[String]) => Unit = (_, _) => (),
  now: () => Long = () => System.currentTimeMillis(),
  newId: String => String = TriggerDispatcher.defaultId,
) {
  private val envelopesCreatedN    = new AtomicLong(0)
  private val droppedNoGovernanceN = new AtomicLong(0)
  private val droppedNoDispatchN   = new AtomicLong(0)
  private val droppedCatalogColdN  = new AtomicLong(0)
  private val dispatchErrorsN      = new AtomicLong(0)

  /** Records created by this step, in merge order. Never throws: a failure on
    * one entry is counted as a dispatchError and the rest still run. */
  def dispatchStep(step: Json): Vector[Json] = {
    if (!enabled) return Vector.empty
    val ops = step.hcursor.downField("mergeBatch").as[Vector[Json]].getOrElse(Vector.empty)
    if (ops.isEmpty) return Vector.empty
    val (machines, refreshedAt) = catalog()
    ops.flatMap { op =>
      try dispatchOne(op, machines, refreshedAt)
      catch { case _: Exception => dispatchErrorsN.incrementAndGet(); None }
    }
  }

  private def dispatchOne(op: Json, machines: Map[String, Json], refreshedAt: Long): Option[Json] = {
    val c = op.hcursor
    val governance = c.downField("governance").focus.filter(_.isObject)
    if (governance.isEmpty) { droppedNoGovernanceN.incrementAndGet(); return None }
    val machineId = c.get[String]("machineId").getOrElse("")
    machines.get(machineId) match {
      case None =>
        // A catalog that has never loaded knows no machine, so the drop says
        // nothing about this one (RealityEngine_LSP#63).
        if (refreshedAt == 0L) droppedCatalogColdN.incrementAndGet() else droppedNoDispatchN.incrementAndGet()
        None
      case Some(machine) =>
        val md      = machine.hcursor.downField("metadata").focus.getOrElse(Json.obj())
        val values  = c.downField("values").focus.filter(_.isArray).getOrElse(Json.arr())
        val binding = TriggerDispatcher.binding(md, values)
        if (binding.agent.isEmpty || binding.trigger.isEmpty) { droppedNoDispatchN.incrementAndGet(); return None }

        val envelopeId    = newId("trigger-envelope")
        val correlationId = newId("trigger-correlation")
        val envelope      = buildEnvelope(op, machine, md, binding, values, envelopeId, correlationId)
        val sequenceIds   = c.downField("sequenceIds").as[Vector[String]].getOrElse(Vector.empty)
        val g             = governance.get.hcursor
        def nullIfEmpty(k: String): Json = g.get[String](k).toOption.filter(_.nonEmpty).map(_.asJson).getOrElse(Json.Null)
        val createdAt     = now()
        val machineName   = machine.hcursor.get[String]("name").getOrElse(machineId)
        val semantics     = TriggerDispatcher.semantics(semanticsBase(machineName), governance.get, sequenceIds)
        val actionCode    = g.get[String]("actionCode").toOption.filter(_.nonEmpty)
        onSemantics(semantics.hcursor.get[String]("machineIri").isRight,
          actionCode.filter(TriggerDispatcher.EscalationActions).map(_ => g.get[String]("ragStatusCode").getOrElse("")))

        envelopesCreatedN.incrementAndGet()
        Some(Json.obj(
          "id"              -> newId("dispatch").asJson,
          "envelopeId"      -> envelopeId.asJson,
          "correlationId"   -> correlationId.asJson,
          "status"          -> "recorded".asJson,
          "mode"            -> mode.asJson,
          "target"          -> binding.agent.asJson,
          "machineId"       -> machineId.asJson,
          "sequenceIds"     -> sequenceIds.asJson,
          "ragStatusCode"   -> nullIfEmpty("ragStatusCode"),
          "processStatus"   -> nullIfEmpty("processStatus"),
          "attempts"        -> 0.asJson,
          "createdAt"       -> createdAt.asJson,
          "updatedAt"       -> createdAt.asJson,
          "providerReceipt" -> Json.Null,
          "envelope"        -> envelope,
          "error"           -> Json.Null,
          "semantics"       -> semantics,
          "replayOf"        -> Json.Null
        ))
    }
  }

  private def buildEnvelope(op: Json, machine: Json, md: Json, binding: TriggerDispatcher.Binding,
                            values: Json, envelopeId: String, correlationId: String): Json = {
    val c             = op.hcursor
    val machineId     = c.get[String]("machineId").getOrElse("")
    val triggerConfig = md.hcursor.downField("triggerConfig")
    val tcDispatch    = triggerConfig.downField("dispatch")
    val n             = values.asArray.map(_.size).getOrElse(0)
    val graphql       = mode == "graphql"
    Json.obj(
      "schemaVersion" -> "1.0.0".asJson,
      "envelopeType"  -> "ces.terminal.event".asJson,
      "envelopeId"    -> envelopeId.asJson,
      "correlationId" -> correlationId.asJson,
      "emittedAtMs"   -> now().asJson,
      "source"        -> Json.obj("engine" -> "PE".asJson, "observedEngine" -> "RE".asJson, "endpoint" -> realityEngineUrl.asJson),
      "ces" -> Json.obj(
        "machineId"         -> machineId.asJson,
        "machineName"       -> machine.hcursor.get[String]("name").getOrElse(machineId).asJson,
        "machineCode"       -> md.hcursor.get[String]("machineCode").getOrElse("").asJson,
        "sequenceIds"       -> c.downField("sequenceIds").focus.filter(_.isArray).getOrElse(Json.arr()),
        "stepNumber"        -> 0.asJson,
        "perceptualMapping" -> Json.obj("output" -> c.downField("region").focus.getOrElse(Json.Null)),
        "provenance"        -> c.downField("provenance").focus.filter(_.isArray).getOrElse(Json.arr()),
        "deprecation"       -> c.downField("deprecation").focus.getOrElse(Json.Null)
      ),
      "outputVector" -> Json.obj(
        "values"        -> values,
        "encoding"      -> "vector".asJson,
        "semantics"     -> Json.arr((0 until n).map(i => Json.obj("index" -> i.asJson, "label" -> s"cell_$i".asJson)): _*),
        "assertedLabel" -> TriggerDispatcher.assertedLabel(values).asJson
      ),
      "projection" -> Json.Null,
      "governance" -> c.downField("governance").focus.filter(_.isObject).getOrElse(Json.Null),
      "dispatch" -> Json.obj(
        "processId"           -> triggerConfig.get[String]("processId").getOrElse("").asJson,
        "processName"         -> triggerConfig.get[String]("processName").getOrElse("").asJson,
        "agent"               -> binding.agent.asJson,
        "action"              -> binding.action.asJson,
        "agentActionsCatalog" -> binding.actions.asJson,
        "trigger"             -> binding.trigger.asJson,
        "autonomyMode"        -> binding.autonomyMode.asJson,
        "writeBack"           -> binding.writeBack,
        "endpoint" -> Json.obj(
          "kind"      -> mode.asJson,
          "url"       -> (if (graphql) triggerConfig.get[String]("endpoint").getOrElse(graphqlEndpoint) else "").asJson,
          "mutation"  -> (if (graphql) tcDispatch.get[String]("mutation").getOrElse("updateProcessState") else "").asJson,
          "schemaRef" -> (if (graphql) tcDispatch.get[String]("schemaRef").getOrElse("localAIStack/services/api/routers/graphql_endpoint.py") else "").asJson
        )
      )
    )
  }

  def envelopesCreated: Long    = envelopesCreatedN.get()
  def droppedNoGovernance: Long = droppedNoGovernanceN.get()
  def droppedNoDispatch: Long   = droppedNoDispatchN.get()
  def droppedCatalogCold: Long  = droppedCatalogColdN.get()
  def dispatchErrors: Long      = dispatchErrorsN.get()
}

object TriggerDispatcher {
  final case class Binding(agent: String, trigger: String, autonomyMode: String,
                           actions: Vector[String], action: String, writeBack: Json)

  val EscalationActions: Set[String] = Set("emergency-dispatch", "urgent-intervention")

  def defaultId(kind: String): String =
    s"$kind-${System.currentTimeMillis()}-${scala.util.Random.nextInt(1000000000)}"

  /** agentBinding first, the legacy dispatchableAgent / aiTrigger / agentActions
    * aliases as fallback -- as C++ dispatch_binding_from_metadata. */
  def binding(md: Json, values: Json): Binding = {
    val m   = md.hcursor
    val ab  = m.downField("agentBinding")
    val has = ab.focus.exists(_.isObject)
    def strs(j: Option[Json]): Vector[String] = j.flatMap(_.asArray).map(_.flatMap(_.asString)).getOrElse(Vector.empty)
    val legacyAgent   = m.get[String]("dispatchableAgent").getOrElse("")
    val legacyTrigger = m.get[String]("aiTrigger").getOrElse("")
    val legacyActions = strs(m.downField("agentActions").focus)
    val (agent, trigger, mode, actions, writeBack) =
      if (has) {
        val allowed = strs(ab.downField("allowedActions").focus)
        (ab.get[String]("agent").getOrElse(legacyAgent), ab.get[String]("trigger").getOrElse(legacyTrigger),
         ab.get[String]("mode").getOrElse(""), if (allowed.nonEmpty) allowed else legacyActions,
         ab.downField("writeBack").focus.getOrElse(Json.Null))
      } else (legacyAgent, legacyTrigger, "", legacyActions, Json.Null)
    Binding(agent, trigger, mode, actions, selectAction(actions, values), writeBack)
  }

  /** The action at the first non-zero cell's index, else the first action. */
  def selectAction(actions: Vector[String], values: Json): String =
    if (actions.isEmpty) ""
    else values.asArray.flatMap { vs =>
      vs.zipWithIndex.collectFirst { case (v, i) if v.asNumber.exists(_.toDouble != 0.0) && i < actions.size => actions(i) }
    }.getOrElse(actions.head)

  /** cell_<i>+cell_<j> for every non-zero cell, "none" when all are zero. */
  def assertedLabel(values: Json): String =
    values.asArray.map { vs =>
      val labels = vs.zipWithIndex.collect { case (v, i) if v.asNumber.exists(_.toDouble != 0.0) => s"cell_$i" }
      if (labels.isEmpty) "none" else labels.mkString("+")
    }.getOrElse("")

  /** {machineIri, sequenceIri, actionCode} -- the derivation SURFACE_SPEC.md
    * fixes for every runtime: sequence from governance.sequenceId, else the sole
    * contributing sequence; local name sanitised to [A-Za-z0-9_-]. */
  def semantics(base: Option[String], governance: Json, sequenceIds: Vector[String]): Json = {
    val g   = governance.hcursor
    val seq = g.get[String]("sequenceId").toOption.filter(_.nonEmpty)
      .orElse(if (sequenceIds.size == 1) sequenceIds.headOption else None)
    def local(s: String): String = {
      val cleaned = s.map(ch => if (ch.isLetterOrDigit && ch < 128 || ch == '_' || ch == '-') ch else '_')
      if (cleaned.isEmpty) "unnamed" else cleaned
    }
    Json.obj(
      "machineIri"  -> base.map(b => s"$b#machine".asJson).getOrElse(Json.Null),
      "sequenceIri" -> (for (b <- base; s <- seq) yield s"$b#seq-${local(s)}".asJson).getOrElse(Json.Null),
      "actionCode"  -> g.get[String]("actionCode").toOption.filter(_.nonEmpty).map(_.asJson).getOrElse(Json.Null)
    )
  }
}
