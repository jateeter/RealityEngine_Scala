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
  def redactMachineResults(step: Json): Json =
    step.mapObject(_.add("machineResults", Json.obj()))
}
