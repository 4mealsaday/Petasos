# Landmark encounter integration — dev.22

The implementation is integrated in the application workspace. No production coordinates, review status, policy thresholds, SDK meaning adapter, or route membership evidence is enabled by this work. Build and device results are recorded separately in the dev.22 verification report.

## Existing requirements retained

- PR-04/RUN-05 keep automatic story changes after music. A landmark is the highest-priority **next permitted automatic story**, not a new interruption or a suspended-story stack.
- LOC-04 is rechecked after paragraph/track boundaries and on location, route, input and freshness events. Interaction suppresses output while candidate monitoring continues. There is at most one landmark candidate.
- `ContentPlanner` alone owns available/consumed/expired. An expired ID stays terminal. `AutomaticStoryPlaybackBridge` remains the single chooser and automatic-output authority; the coordinator continues to own timing.
- DATA-11 requires reviewed policy data. `LandmarkPolicy` has no production threshold defaults. Supported rule tokens are `decreasing_distance` and `increasing_distance_after_approach`; unknown rules are rejected by the storage decoder. The adapter version is pinned explicitly.

## Implementation contracts

`LandmarkStoryCandidate` carries an immutable prepared story, place ID, WGS84 coordinates, coordinate source revision, typed policy and current-evidence fingerprint. The storage boundary verifies the review envelope, rights, source/fact freshness and completed-story/fact exclusions. The review envelope pins `storyId` and an independent `associationFactRevisionId`; that association remains current even if generated paragraphs omit the fact. `story.place_id` alone does not authorize an automatic landmark.

`LandmarkCandidateSession(planner, sessionId, clockScopeId)` is owned by the serialized host worker. `update(candidates, point, context, route)` updates the shared planner and returns its one `LandmarkOffer`; it never chooses content or emits playback events. The bridge invokes `chooseAtBoundary(context, session::stillApproaching)` once, resolves either ordinary or landmark inventory, and consumes only the offer acknowledged by actual SDK output start. `session.isCurrent(offer)` is an atomic identity permit for the final output thread; it does not replace the bridge's location, elapsed-time and ingress authority fences or the worker's current evidence lookup.

Host context must describe the fresh **observed** point independently of `regionalTarget`. Neither pending regional generation nor interaction may turn an otherwise valid observed point into a regional-planning-dependent landmark permit. Known inactive navigation may use `LandmarkRouteContext.CurrentPosition`. Active navigation requires matching route revision and explicit membership evidence (`OnRoute`). Unknown navigation, active navigation without membership evidence, or unverified waypoint geometry uses `Unavailable`.

## Encounter lifecycle decisions

These are engineering behavior, separate from verification of the supplied numeric policy values:

1. Admission requires two distinct measurements with increasing verified `observedAtEpochMs` and increasing `receivedAtElapsedMs`, in the same clock scope and expected quality adapter. New callback/point IDs alone do not count. Cached measurements retain their measurement-age deadline. Conflicting coordinates for one measurement break continuity.
2. Distance comparisons use a WGS84 curvature-bounded geodesic interval plus the location adapter's accuracy value. The current interval must lie within the reviewed approach radius; the whole current interval must be closer than the preceding interval for initial approach. This is a conservative use of the provider's accuracy semantics, not a claim that a probabilistic accuracy estimate is an absolute position guarantee.
3. A stationary or overlapping-distance observation can retain an already admitted fresh candidate; it cannot admit one. Receding distance, proven exit, uncertain radius overlap, stale/invalid quality, changed source/policy/content eligibility, route evidence change or revoked input authority expires the pending candidate. A missed freshness interval cannot be bridged by the next point.
4. Terminal state never changes for the old candidate ID. A **new encounter** for the same place requires cooldown elapsed in the same clock, then a later fresh measurement whose entire accuracy envelope is outside the approach radius, and then a new proven approach based on distinct measurements. The outside measurement's own monotonic time (receipt minus verified measurement age), not just its callback/processing time, must follow the cooldown. An outside point measured before cooldown, repeated cached data, route change, timer, or interaction completion does not rearm it. All these observations must follow the terminal measurement. A policy/geometry identity change cannot bypass this sequence within the existing session.
5. A stable offer is retained while eligible, even if another landmark becomes closer. Without an active offer, eligible approaches are ordered by conservative distance, then place/story ID. Spatial eligibility precedes any future inventory limit.
6. `clear()` expires the current candidate and retains terminal/cooldown history; it is not a general presentation refresh. Paused/interaction paths should keep monitoring and separately withdraw playback authority. A genuinely new active session constructs a new session object; elapsed cooldown is never compared across clock scopes. Global heard-story/stable-fact filtering remains a storage responsibility.

The in-memory session contains only reviewed candidate identity and bounded live observation state per encountered place; it is not persisted as a recoverable automatic offer. Existing recovery does not auto-play or restart microphone capture.

## Verification coverage

Pure tests cover approach vs noise, radius uncertainty, stale/wrong-adapter/unknown data, repeated measurement IDs, exact route membership, one-candidate selection, interaction-time terminal expiry, distinct encounter cooldown/rearm, old-ID non-revival, evidence changes, and atomic permit revocation. Integration tests cover existing music/story continuation, final SDK-start race fencing, Room source/policy withdrawal and the current observed-point/region-target separation. Final counts and device evidence are in [the dev.22 report](./2026-11-08-landmark-and-recovery.md). No actual user location or microphone input is needed for these synthetic checks.
