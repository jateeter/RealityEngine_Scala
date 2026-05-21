package com.realityengine

import com.realityengine.models._
import com.realityengine.engine._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * RealityEngineSpec — validates core engine logic without network I/O.
 */
class RealityEngineSpec extends AnyFlatSpec with Matchers {

  // ── RealityVector ─────────────────────────────────────────────────────────

  "RealityVector" should "match with GTE comparator (both HIGH)" in {
    val elem   = VectorElement(value = 0.9, threshold = Some(0.5))
    val vector = new RealityVector(Vector(elem), isInitial = true)
    vector.matchAlgorithm = ComparatorType.GTE
    val result = vector.match_(Vector(0.8))
    result.matched shouldBe true
    result.score shouldBe >(0.0)
  }

  it should "not match with GTE when input is LOW and value is HIGH" in {
    val elem   = VectorElement(value = 0.9, threshold = Some(0.5))
    val vector = new RealityVector(Vector(elem), isInitial = true)
    vector.matchAlgorithm = ComparatorType.GTE
    val result = vector.match_(Vector(0.2))
    result.matched shouldBe false
  }

  it should "match with GTE when both are LOW" in {
    val elem   = VectorElement(value = 0.1, threshold = Some(0.5))
    val vector = new RealityVector(Vector(elem), isInitial = true)
    vector.matchAlgorithm = ComparatorType.GTE
    val result = vector.match_(Vector(0.2))
    result.matched shouldBe true
  }

  it should "return dimension mismatch error for wrong input length" in {
    val vector = new RealityVector(Vector(VectorElement(0.5), VectorElement(0.5)), isInitial = true)
    val result = vector.match_(Vector(0.5))
    result.matched shouldBe false
    result.metadata.get("error") shouldBe defined
  }

  it should "match with Equals comparator" in {
    val elem   = VectorElement(value = 0.5, comparatorType = Some(ComparatorType.Equals))
    val vector = new RealityVector(Vector(elem), isInitial = true)
    vector.match_(Vector(0.5)).matched shouldBe true
    vector.match_(Vector(0.6)).matched shouldBe false
  }

  it should "match with Threshold comparator within tolerance" in {
    val elem   = VectorElement(value = 0.5, comparatorType = Some(ComparatorType.Threshold), threshold = Some(0.1))
    val vector = new RealityVector(Vector(elem), isInitial = true)
    vector.match_(Vector(0.55)).matched shouldBe true
    vector.match_(Vector(0.65)).matched shouldBe false
  }

  it should "serialize and deserialize via toJson/fromJson" in {
    val elem   = VectorElement(value = 0.7, threshold = Some(0.5))
    val vector = new RealityVector(Vector(elem), isInitial = true)
    vector.addNextVector("next-1")
    val json      = vector.toJson
    val recovered = RealityVector.fromJson(json)
    recovered.id           shouldBe vector.id
    recovered.isInitial    shouldBe true
    recovered.getNextVectorIds should contain("next-1")
  }

  // ── CriticalEventSequence ─────────────────────────────────────────────────

  "CriticalEventSequence" should "activate successors after transition (deferred)" in {
    val seqElem   = VectorElement(value = 0.9, threshold = Some(0.5))
    val initial   = new RealityVector(Vector(seqElem), isInitial = true)
    val successor = new RealityVector(Vector(seqElem), isInitial = false)
    initial.addNextVector(successor.id)

    val outputElem = VectorElement(value = 1.0, threshold = Some(0.5))
    val outVec = new RealityVector(Vector(outputElem), isInitial = false)
    outVec.addOutputVector(OutputVector("out-1", Vector(1.0), Map.empty, System.currentTimeMillis()))
    successor.addNextVector(outVec.id)

    val seq = new CriticalEventSequence("test-seq")
    seq.addVector(initial)
    seq.addVector(successor)
    seq.addVector(outVec)

    // Cycle 1: initial matches, successor gets activated (deferred)
    val r1 = seq.transition(Vector(0.8))
    r1.matchedVectors should contain(initial.id)
    r1.activatedVectors should contain(successor.id)
    successor.isActive shouldBe true

    // Cycle 2: successor matches
    val r2 = seq.transition(Vector(0.8))
    r2.matchedVectors should contain(successor.id)
  }

  it should "validate correctly" in {
    val seq = new CriticalEventSequence("empty")
    val (valid, errors) = seq.validate()
    valid shouldBe false
    errors should have length 2
  }

  // ── OutputArbiter ─────────────────────────────────────────────────────────

  "OutputArbiter" should "produce output with PASSTHROUGH when any sequence has output" in {
    val ov      = OutputVector("ov1", Vector(1.0), Map.empty, System.currentTimeMillis())
    val arbiter = new OutputArbiter(ArbiterRule.PASSTHROUGH)
    val decision = arbiter.arbitrate(Map("seq1" -> List(ov)), totalSequences = 2)
    decision.shouldOutput shouldBe true
    decision.machineOutput shouldBe defined
  }

  it should "require ALL sequences with AND rule" in {
    val ov      = OutputVector("ov1", Vector(1.0), Map.empty, System.currentTimeMillis())
    val arbiter = new OutputArbiter(ArbiterRule.AND)
    val decision = arbiter.arbitrate(Map("seq1" -> List(ov), "seq2" -> Nil), totalSequences = 2)
    decision.shouldOutput shouldBe false
  }

  it should "succeed with AND when all sequences have output" in {
    val ov      = OutputVector("ov1", Vector(1.0), Map.empty, System.currentTimeMillis())
    val arbiter = new OutputArbiter(ArbiterRule.AND)
    val decision = arbiter.arbitrate(Map("seq1" -> List(ov), "seq2" -> List(ov)), totalSequences = 2)
    decision.shouldOutput shouldBe true
  }

  // ── Machine ───────────────────────────────────────────────────────────────

  "Machine" should "process input and produce output via PASSTHROUGH arbiter" in {
    val elem    = VectorElement(value = 0.9, threshold = Some(0.5))
    val initial = new RealityVector(Vector(elem), isInitial = true)
    initial.matchAlgorithm = ComparatorType.GTE
    initial.addOutputVector(OutputVector("out-1", Vector(1.0), Map.empty, System.currentTimeMillis()))

    val seq = new CriticalEventSequence("s1")
    seq.addVector(initial)

    val machine = new Machine("TestMachine", arbiterRule = ArbiterRule.PASSTHROUGH)
    machine.matchAlgorithm = ComparatorType.GTE
    machine.addSequence(seq)

    val result = machine.processInput(Vector(0.9))
    result.arbiterMetadata.shouldOutput shouldBe true
    result.machineOutput shouldBe defined
  }

  it should "clone independently" in {
    val elem    = VectorElement(value = 0.9, threshold = Some(0.5))
    val initial = new RealityVector(Vector(elem), isInitial = true)
    initial.matchAlgorithm = ComparatorType.GTE

    val seq = new CriticalEventSequence("s1")
    seq.addVector(initial)
    val machine = new Machine("M")
    machine.addSequence(seq)

    val clone = machine.clone()
    clone.id shouldBe machine.id
    clone.getAllSequences should not be theSameInstanceAs(machine.getAllSequences)
  }

  // ── PerceptualSpace ────────────────────────────────────────────────────────

  "PerceptualSpace" should "extract and merge machine input/output" in {
    val ps = new PerceptualSpace(10)
    ps.updateRegion(0, Vector(1.0, 2.0, 3.0))
    ps.getRegion(0, 3) shouldBe Vector(1.0, 2.0, 3.0)
    ps.getRegion(3, 2) shouldBe Vector(0.0, 0.0)

    val mapping = PerceptualMapping(RegionMapping(0, 3), RegionMapping(5, 2))
    val machineIn = ps.extractMachineInput(mapping)
    machineIn shouldBe Vector(1.0, 2.0, 3.0)

    ps.mergeMachineOutput(Vector(9.0, 8.0), mapping)
    ps.getRegion(5, 2) shouldBe Vector(9.0, 8.0)
  }

  // ── PreceptionEngine ──────────────────────────────────────────────────────

  "PreceptionEngine" should "resolve machine input from universal space" in {
    val pe      = new PreceptionEngine(10)
    val mapping = PerceptualMapping(RegionMapping(2, 3), RegionMapping(6, 2))
    val space   = Vector(0.0, 0.0, 1.0, 2.0, 3.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    val resolved = pe.resolveInputEventVector(space, mapping)
    resolved shouldBe Vector(1.0, 2.0, 3.0)
  }

  // ── MachineLoader JSON round-trip ─────────────────────────────────────────

  "MachineLoader" should "validate valid machine JSON" in {
    val json =
      """{
        "version": "1.0.0",
        "machine": {
          "name": "Test",
          "description": "Unit test machine",
          "arbiterRule": "PASSTHROUGH",
          "sequences": [
            {
              "name": "seq1",
              "vectors": [
                { "elements": [{"value": 0.5}], "isInitial": true }
              ]
            }
          ]
        }
      }"""
    val (valid, errors) = com.realityengine.services.MachineLoader.validate(json)
    valid shouldBe true
    errors shouldBe empty
  }

  it should "load a machine from JSON and round-trip" in {
    val json =
      """{
        "version": "1.0.0",
        "machine": {
          "name": "RoundTrip",
          "description": "round trip test",
          "arbiterRule": "PASSTHROUGH",
          "matchAlgorithm": "gte",
          "sequences": [
            {
              "name": "seq1",
              "vectors": [
                { "elements": [{"value": 0.9, "threshold": 0.5}], "isInitial": true,
                  "outputVectors": [{"vector": [1.0]}] }
              ]
            }
          ]
        }
      }"""
    val machine = com.realityengine.services.MachineLoader.loadFromJson(json)
    machine.name shouldBe "RoundTrip"
    machine.getSequenceCount shouldBe 1
    machine.matchAlgorithm shouldBe ComparatorType.GTE

    val exported = com.realityengine.services.MachineLoader.saveToJson(machine)
    val reimported = com.realityengine.services.MachineLoader.loadFromJson(exported)
    reimported.name shouldBe "RoundTrip"
    reimported.getSequenceCount shouldBe 1
  }

  // ── End-to-end flow ────────────────────────────────────────────────────────

  "RealityEngine (no VectorStore)" should "process universal input through machine with perceptual mapping" in {
    // Build a simple machine: monitors offset [0,2) of the universal 10-byte space
    val elem    = VectorElement(value = 0.9, threshold = Some(0.5))
    val initial = new RealityVector(Vector(elem, elem), isInitial = true)
    initial.matchAlgorithm = ComparatorType.GTE
    initial.addOutputVector(OutputVector("out-1", Vector(0.5, 0.5), Map.empty, System.currentTimeMillis()))

    val seq = new CriticalEventSequence("main-seq")
    seq.addVector(initial)

    val machine = new Machine(
      name             = "TestMachine",
      arbiterRule      = ArbiterRule.PASSTHROUGH,
      perceptualMapping = Some(PerceptualMapping(RegionMapping(0, 2), RegionMapping(5, 2)))
    )
    machine.matchAlgorithm = ComparatorType.GTE
    machine.addSequence(seq)

    // Test machine processing directly — no VectorStore needed
    val universalSpace = Vector.fill(10)(0.0).patch(0, Vector(0.9, 0.8), 2)
    val mapping        = machine.perceptualMapping.get
    val machineInput   = universalSpace.slice(mapping.input.offset, mapping.input.offset + mapping.input.length)
    val result         = machine.processInput(machineInput)
    result.arbiterMetadata.shouldOutput shouldBe true
  }
}
