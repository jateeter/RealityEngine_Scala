package com.realityengine.perception.api

import io.circe.Json

/** Request/response shaping for `POST /api/push`.
  *
  * Kept separate from the route so both halves are testable without an actor
  * system, a Reality Engine to push to, or an HTTP client.
  */
object PushRequest {

  /** Read the `compact` flag from a raw request body.
    *
    * Lenient by construction: the push route historically took no entity at
    * all, so callers post an empty body, no content type, or something that is
    * not JSON. None of those is an error — they simply are not asking for a
    * compact response, which is what every existing caller already expects.
    */
  def compactFrom(raw: String): Boolean =
    io.circe.parser
      .parse(raw)
      .toOption
      .flatMap(_.hcursor.get[Boolean]("compact").toOption)
      .getOrElse(false)

  /** Empty the step's machine results while keeping the key present.
    *
    * The key stays and its contents go, which is what the LSP runtime does;
    * C++ omits the key entirely. Both satisfy the parity signature, because
    * an absent and an empty machineResults each contribute nothing to it.
    *
    * Uses `add`, not `deepMerge`: deep-merging an empty object into a
    * populated one returns the populated one unchanged, so it would read like
    * a fix and clear nothing.
    */
  /** Omit machineResults under `compact`, per SURFACE_SPEC.md.
    *
    * This replaced the value with an empty object instead of removing the key.
    * An empty object is not an absent key to a consumer walking the response,
    * and the other two runtimes omit it — so a compact push from here was a
    * different shape from a compact push from C++ or LSP.
    */
  def redactMachineResults(step: Json): Json =
    step.mapObject(_.remove("machineResults"))

  /** Read the `only` subset selector from a raw request body
    * (RealityEngine_CI#367).
    *
    * `Some` whenever the key is present and is an object, including an object
    * naming nothing: the selector is active because it is *present*, and
    * naming nothing then selects nothing. Widening an empty selector back to
    * the universe would make it unfalsifiable — a caller building
    * `sequenceIds` from a list that happened to be empty would be handed
    * everything and could not tell.
    */
  def onlyFrom(raw: String): Option[Json] =
    io.circe.parser
      .parse(raw)
      .toOption
      .flatMap(_.hcursor.downField("only").focus)
      .filter(_.isObject)

  private def stringSet(j: Json): Set[String] =
    j.asArray.map(_.flatMap(_.asString).toSet).getOrElse(Set.empty)

  /** Narrow a step to the caller's requested subset.
    *
    * The Reality Engine applies the same selector to the step it builds, and
    * this must produce the identical payload: the acceptance criterion on #367
    * is tri-runtime byte equivalence, and a caller cannot be expected to know
    * whether it reached the engine directly or through the Perception Engine.
    * The predicates are `to_json(SimulationStep, ..., StepSelector)` in the C++
    * engine, transcribed against parsed JSON.
    *
    * Applied to the reply, never to the request the PE makes: the RE filters
    * `machineResults` like everything else, and `VectorAggregator.aggregate`
    * reads that field to build the next InputSpaceVector. Asking the engine for
    * a subset would therefore change what a push *does*, which is the rule the
    * `compact` scaladoc on `doPush` already records.
    */
  def applySelector(step: Json, only: Option[Json]): Json = only match {
    case None      => step
    case Some(sel) =>
      val wantSeq  = stringSet(sel.hcursor.downField("sequenceIds").focus.getOrElse(Json.Null))
      val wantName = stringSet(sel.hcursor.downField("machineNames").focus.getOrElse(Json.Null))

      // Both sets come from the UNFILTERED step, so the order the fields are
      // rewritten in below cannot change the answer.
      val selectedIds: Set[String] =
        step.hcursor.downField("machineResults").focus.flatMap(_.asObject) match {
          case Some(obj) => obj.toIterable.collect {
            case (id, mr) if mr.hcursor.get[String]("machineName").toOption.exists(wantName.contains) => id
          }.toSet
          case None => Set.empty
        }

      // Machines carrying a requested sequence id on an operation they actually
      // produced this step. Declared membership is not enough: a machine whose
      // sequence did not fire produced no operation, and the engine keeps it
      // out.
      val sequenceMachines: Set[String] =
        step.hcursor.downField("mergeBatch").focus.flatMap(_.asArray).getOrElse(Vector.empty).collect {
          case op if stringSet(op.hcursor.downField("sequenceIds").focus.getOrElse(Json.Null)).exists(wantSeq.contains) =>
            op.hcursor.get[String]("machineId").toOption.getOrElse("")
        }.toSet

      def keepId(id: String): Boolean = selectedIds.contains(id) || sequenceMachines.contains(id)
      def field(name: String): Option[Json] = step.hcursor.downField(name).focus

      // Each field is rewritten only when it is present. An absent key stays
      // absent rather than becoming an empty array: `includeActiveRegions:
      // false` omits the key, and emitting `[]` instead would claim no regions
      // were active where the engine said it was never asked.
      def withField(acc: Json, name: String, next: Json => Json): Json =
        field(name).fold(acc)(v => acc.mapObject(_.add(name, next(v))))

      val filteredResults = (j: Json) => j.asObject match {
        case Some(obj) => Json.fromJsonObject(obj.filterKeys(keepId))
        case None      => j
      }
      val filteredBatch = (j: Json) => j.asArray match {
        case Some(arr) => Json.fromValues(arr.filter { op =>
          selectedIds.contains(op.hcursor.get[String]("machineId").toOption.getOrElse("")) ||
            stringSet(op.hcursor.downField("sequenceIds").focus.getOrElse(Json.Null)).exists(wantSeq.contains)
        })
        case None => j
      }
      val filteredBus = (j: Json) => j.asArray match {
        case Some(arr) => Json.fromValues(arr.filter { w =>
          w.hcursor.get[String]("producerSequenceId").toOption.exists(wantSeq.contains) ||
            w.hcursor.get[String]("producerMachineId").toOption.exists(selectedIds.contains) ||
            w.hcursor.get[String]("subscriberMachineId").toOption.exists(selectedIds.contains)
        })
        case None => j
      }
      val filteredRegions = (j: Json) => j.asArray match {
        case Some(arr) => Json.fromValues(arr.filter { r =>
          r.hcursor.get[String]("machineId").toOption.exists(selectedIds.contains)
        })
        case None => j
      }

      val a = withField(step, "machineResults", filteredResults)
      val b = withField(a, "mergeBatch", filteredBatch)
      val c = withField(b, "eventBus", filteredBus)
      withField(c, "activeRegions", filteredRegions)
  }
}
