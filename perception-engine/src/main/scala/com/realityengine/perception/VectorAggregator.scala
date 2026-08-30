package com.realityengine.perception

import io.circe.Json
import scala.collection.mutable

// VectorAggregator — PE machine output aggregator
//
// Merges gated machine CES output vectors from RE's SimulationStep.machineResults
// into the base perceptual space vector to produce the next InputSpaceVector.
//
// Gating:      only machines whose transitionResult.arbiterMetadata.shouldOutput
//              is true contribute to the merge.
// Merge order: deterministic *and* identical across runtimes — records sorted by
//              machineName, which the corpus declares and which is globally
//              unique across it.
//
//              Sorting by machineId was deterministic within one runtime and
//              different between them: the corpus declares no id, so each
//              runtime mints its own. Where two machines' output regions overlap
//              the merge is last-writer-wins, so the winner was decided by an id
//              that differs per engine and the merged vector — the next
//              InputSpaceVector — diverged. Seen on AgHarvestReadinessAssessor,
//              whose output [3967:3971] overlaps AGX055's [3959:3971]: ISRE cell
//              3968 read 1.0 on C++/LSP and 0.0 here while every OREV agreed
//              (RealityEngine_CI corpus parity sweep, 2026-08-19).
//
// This is a thin, stateless object so the aggregation restriction (all machine
// outputs must be present before the next input vector is assembled) can be
// relaxed in the future without changing call sites.

object VectorAggregator {

  private case class MergeRecord(
    sortKey:      String,
    outputOffset: Int,
    outputLength: Int,
    outputVector: Vector[Double]
  )

  // Merge gated machine CES output vectors into a copy of `baseVector` and
  // return the merged nextInputSpaceVector.  `machineResults` is the
  // `machineResults` field from RE's SimulationStep JSON response.
  private def mergeRecords(machineResults: Json): List[MergeRecord] = {
    machineResults.asObject.toList.flatMap(_.toList).flatMap { case (machineId, result) =>
      val shouldOutput = result.hcursor
        .downField("transitionResult")
        .downField("arbiterMetadata")
        .get[Boolean]("shouldOutput")
        .getOrElse(false)

      if (!shouldOutput) None
      else for {
        offset <- result.hcursor.downField("outputRegion").get[Int]("offset").toOption
        length <- result.hcursor.downField("outputRegion").get[Int]("length").toOption
        if length > 0
        // Prefer mergedOutputVector: the machine's collection of potential
        // outputs folded under its own outputMergeTransformation. Presenting
        // that fold is the Reality Engine's job and the last thing it does in
        // the step, so it is the machine's actual output.
        //
        // outputVector is one arbitrarily chosen member of that collection, and
        // which member differed per runtime — reading it here is what carried
        // the RE's disagreement into the perceptual space
        // (RealityEngine_CI#154). Falling back to it keeps this working against
        // a Reality Engine that has not yet been updated.
        vec    <- result.hcursor.downField("mergedOutputVector").as[Vector[Double]].toOption
                    .orElse(result.hcursor.downField("outputVector").as[Vector[Double]].toOption)
        if vec.nonEmpty
      } yield MergeRecord(
        // machineName is corpus-declared and stable across runtimes; machineId
        // is minted locally. Fall back to the id only when a result carries no
        // name, which keeps a malformed payload ordered rather than unordered.
        result.hcursor.get[String]("machineName").toOption.filter(_.nonEmpty).getOrElse(machineId),
        offset, length, vec)
    }
  }

  def aggregate(baseVector: Vector[Double], machineResults: Json): Vector[Double] = {
    val records = mergeRecords(machineResults)

    if (records.isEmpty) return baseVector

    // Deterministic merge order, identical on every runtime — sort by machineName
    val sorted = records.sortBy(_.sortKey)

    val buf = mutable.ArrayBuffer[Double](baseVector: _*)
    for (rec <- sorted) {
      val writeLen = math.min(rec.outputVector.length, rec.outputLength)
      val needed   = rec.outputOffset + writeLen
      if (needed > buf.length)
        buf.appendAll(Array.fill(needed - buf.length)(0.0))
      for (i <- 0 until writeLen)
        buf(rec.outputOffset + i) = rec.outputVector(i)
    }
    buf.toVector
  }

  /** One operation per machine per output region — FOLD_PLACEMENT.md §1.
    *
    * It used to be one operation per asserted output, carrying the sequenceId
    * and the index within that sequence. That was right while the fold sat
    * beside the arbitration path: the merged value was reported and every
    * asserted output still reached the arbiter on its own, so a machine with
    * seven completed Reality Events contributed seven values to the same cell.
    * Now the machine presents one output and this reports one operation for it.
    *
    * `sequenceIds` is the evidence for that value — the set of CESs that
    * completed. `contributors` pairs each of them with its OWN asserted values,
    * which is what governance resolves against (§3); it is carried rather than
    * re-derived so the join cannot be given the folded vector by accident.
    */
  private case class MergeOp(
    machineId:    String,
    sequenceIds:  List[String],
    contributors: List[(String, Vector[Double])],
    outputOffset: Int,
    outputLength: Int,
    values:       Vector[Double],
    // The Reality Events that actually matched this step, per contributing
    // sequence, in the order the step reported them. This is the evidence
    // behind the folded value and it comes from the pair's own live step —
    // not from a corpus file, and not from a second view of the machines.
    provenance:   List[String],
  )

  private def mergeOps(machineResults: Json): List[MergeOp] =
    machineResults.asObject.toList.flatMap(_.toList).flatMap { case (machineId, result) =>
      val cursor       = result.hcursor
      val transition   = cursor.downField("transitionResult")
      val shouldOutput = transition.downField("arbiterMetadata").get[Boolean]("shouldOutput").getOrElse(false)

      val region = for {
        offset <- cursor.downField("outputRegion").get[Int]("offset").toOption
        length <- cursor.downField("outputRegion").get[Int]("length").toOption
        if length > 0
      } yield (offset, length)

      // The folded value, and no fallback to `outputVector`.
      //
      // Absent means the machine presents nothing, and there are exactly two
      // ways to get there: it completed no Reality Event, or its fold refused —
      // the Łukasiewicz pair with no declared chain top (§2). Both mean no
      // operation. Falling back to `outputVector` would substitute one
      // arbitrarily chosen member of the collection, which differed per runtime
      // and is the divergence the fold exists to remove (RealityEngine_CI#154);
      // `aggregate` above keeps that fallback only because the perceptual space
      // must survive an un-upgraded Reality Engine, and RE and PE move together
      // here (§7).
      val folded = cursor.downField("mergedOutputVector").as[Vector[Double]].toOption.filter(_.nonEmpty)

      (shouldOutput, region, folded) match {
        case (true, Some((offset, length)), Some(values)) =>
          // Sorted by sequenceId: `sequenceResults` is a JSON object keyed by
          // ids minted per runtime, so its order was arbitrary and the
          // governance tie-break below would have inherited that.
          val contributors = transition.downField("sequenceResults").focus
            .flatMap(_.asObject).map(_.toList).getOrElse(Nil)
            .sortBy(_._1)
            .flatMap { case (sequenceId, sequenceResult) =>
              sequenceResult.hcursor.downField("assertedOutputs").focus
                .flatMap(_.asArray).getOrElse(Vector.empty).toList
                .flatMap(_.hcursor.get[Vector[Double]]("vector").toOption.filter(_.nonEmpty))
                .map(sequenceId -> _)
            }
          // Provenance from the live step, in first-seen order, deduped
          // (FOLD_PLACEMENT.md §1: "union of contributors' provenance,
          // order-preserved, deduped"). `matchedVectors` is the set of Reality
          // Events that matched in this machine's atomic step, which is the
          // evidence for the value it just presented.
          //
          // Read only from sequences that contributed, so the union describes
          // the operation rather than the machine's whole step.
          val contributingIds = contributors.map(_._1).toSet
          val provenance = transition.downField("sequenceResults").focus
            .flatMap(_.asObject).map(_.toList).getOrElse(Nil)
            .sortBy(_._1)
            .filter { case (sequenceId, _) => contributingIds.contains(sequenceId) }
            .flatMap { case (_, sequenceResult) =>
              sequenceResult.hcursor.get[List[String]]("matchedVectors").getOrElse(Nil)
            }
            .distinct
          if (contributors.isEmpty) Nil
          else List(MergeOp(machineId, contributors.map(_._1).distinct.sorted,
                            contributors, offset, length, values, provenance))
        case _ => Nil
      }
    }

  // ── Governance join (FOLD_PLACEMENT.md §3) ─────────────────────────────────

  /** The severity chain the join runs over: GREEN/absent 0 < AMBER 1 < RED 2.
    * Mirrors `Arbiter.severityRank` in the Reality Engine. */
  private val RagRank = Map("GREEN" -> 0, "AMBER" -> 1, "RED" -> 2)

  /** Governance for a folded contribution — the join over its contributors'
    * severity ranks, ties to the lexicographically smallest sequenceId.
    *
    * Each contributor is resolved against its OWN asserted values and never
    * against the fold: a rule written for one CES's output need not match the
    * fold, and 135 of 1328 corpus machines have an `outputMatches` pattern that
    * maps to more than one RAG code, so the sequenceId filter is doing real
    * work. The winner's decision travels WHOLE — `ragStatusCode`,
    * `processStatus`, `ownerTeam`, `sequenceId` and the rest — because a record
    * composed from fields of different rules describes no rule that exists.
    *
    * Safety-preserving, which is the point: a RED-governed firing cannot be
    * hidden by a GREEN one that folded alongside it.
    */
  private def joinGovernance(op: MergeOp, corpus: MachineCorpus): Option[Json] = {
    val resolved = op.contributors.flatMap { case (sequenceId, values) =>
      corpus.governance(op.machineId, sequenceId, values).map(sequenceId -> _)
    }
    if (resolved.isEmpty) None
    else Some(resolved.minBy { case (sequenceId, decision) =>
      val rag = decision.hcursor.get[String]("ragStatusCode").toOption.getOrElse("")
      (-RagRank.getOrElse(rag, 0), sequenceId)
    }._2)
  }

  /** @param corpus resolves the two fields the merge batch reports about a
    *   fired sequence that the machine results themselves do not carry: the
    *   audit trail (`provenance`) and the governance contract.  Both are the
    *   PE's to resolve, because the output→next-Reality-Event mapping is the
    *   PE's responsibility; the RE is never asked.  Defaults to `empty`, which
    *   degrades to the pre-existing five-key entry rather than failing.
    */
  def mergeBatch(machineResults: Json, corpus: MachineCorpus = MachineCorpus.empty): Vector[Json] =
    // Canonical merge ordering — `machineId` alone (§6). The comparator was
    // (machineId, sequenceId, outputIndex); with one operation per machine the
    // secondary keys are constant, so machineId already orders it totally.
    mergeOps(machineResults)
      .sortBy(_.machineId)
      .toVector
      .map { op =>
        val base = Json.obj(
          "machineId" -> Json.fromString(op.machineId),
          // An array, replacing the scalar `sequenceId`, and `outputIndex` is
          // gone with it (§7): one operation covers the machine, so there is no
          // single firing sequence to name and no index to name it within. A
          // consumer reading `sequenceId` as a string gets nothing, which is
          // why RE and PE move together.
          "sequenceIds" -> Json.arr(op.sequenceIds.map(Json.fromString): _*),
          "region" -> Json.obj(
            "offset" -> Json.fromInt(op.outputOffset),
            "length" -> Json.fromInt(op.outputLength),
          ),
          // "values", not "vector" — C++ and LSP both name it values, and the
          // C++ PE's trigger dispatch reads op.at("values"). Folded now, rather
          // than one sequence's asserted output.
          "values" -> Json.arr(op.values.map(Json.fromDoubleOrNull): _*),
          // The union over contributors, order-preserved and deduped (§1),
          // taken from the live step rather than resolved against the corpus.
          //
          // This read `corpus.provenance`, which looks up each sequence's
          // `initialVectorIds`. That was wrong twice over. It named where a
          // sequence *starts*, not the chain the Reality Event actually walked,
          // so it could never match C++ for the 37% of corpus sequences with
          // more than one vector. And it asked a second view of the machines a
          // question the pair could answer about itself — the machine list and
          // the step are the authoritative source for a pair's own state
          // (RealityEngine_CI#209).
          //
          // Unconditional, like C++: an entry with no chain reports an empty
          // array rather than dropping the key, so a consumer can tell "no
          // provenance" apart from "this runtime does not report provenance".
          "provenance" -> Json.arr(op.provenance.map(Json.fromString): _*),
        )
        // Conditional, like C++: present only when a trigger rule matched.
        joinGovernance(op, corpus).fold(base)(g => base.mapObject(_.add("governance", g)))
      }
}
