package com.realityengine

/** Where this engine reads its machine corpus from.
  *
  * One definition, because there were three. `Main`, `Routes` and the
  * perception engine's `SemanticMetrics` each carried
  * `sys.env.getOrElse("MACHINES_DIR", "../RealityEngine_Machines/machines")`
  * independently, so a change to the fallback had to be made in three places
  * and any two of them could disagree without failing to compile.
  *
  * `MACHINES_DIR` here is the **machines directory itself**, not the repository
  * root — `.../RealityEngine_Machines/machines`. That is the engine-side
  * convention; `startUniverse.sh` appends the `/machines` suffix when it hands
  * the value over, and it hands over the corpus it actually selected rather
  * than the whole repository. So an engine started by the universe loads the
  * deployed corpus, and one started by hand falls back to the sibling checkout.
  *
  * The same name means the repository root elsewhere in the workspace and
  * localAIStack's own `data/machines` inside that stack — three meanings that
  * produced four distinct defects. See
  * `RealityEngine_CI/docs/MACHINES_DIR_SWEEP.md`, and
  * `RealityEngine_CI/docs/BUILD_CONTROL_CONTRACT.md` for why a parity claim is
  * made from the deployed artifact rather than from the repository.
  */
object MachineCorpus {

  /** The engine-side default: the sibling checkout's `machines/` directory. */
  val DefaultDir: String = "../RealityEngine_Machines/machines"

  /** The corpus directory this process should read, from `MACHINES_DIR`. */
  def dir: String = sys.env.getOrElse("MACHINES_DIR", DefaultDir)
}
