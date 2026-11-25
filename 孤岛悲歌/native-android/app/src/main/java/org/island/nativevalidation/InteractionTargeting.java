package org.island.nativevalidation;

import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Selects the interaction volume actually under the player's aim. */
final class InteractionTargeting {
    static final class Candidate {
        final GameEngine.Entity entity;
        final float rayEntry;
        final float fallbackScore;
        final boolean onRay;
        final float aimDot;

        Candidate(GameEngine.Entity entity, float rayEntry, float fallbackScore, boolean onRay) {
            this(entity, rayEntry, fallbackScore, onRay, onRay ? 1f : 0f);
        }

        Candidate(GameEngine.Entity entity, float rayEntry, float fallbackScore,
                boolean onRay, float aimDot) {
            this.entity = entity;
            this.rayEntry = rayEntry;
            this.fallbackScore = fallbackScore;
            this.onRay = onRay;
            this.aimDot = aimDot;
        }
    }

    private InteractionTargeting() {}

    static boolean acceptsAim(GameEngine game, GameEngine.Entity entity, float aimDot, boolean onRay) {
        return onRay || aimDot >= .45f || (game.isPreciseAimTarget(entity) && aimDot >= .20f);
    }

    static float rayEntry(GameEngine.Entity entity, float x, float y, float z,
            float dx, float dy, float dz, float reach, float padding) {
        if (entity == null || entity.bounds == null) return -1;
        float[] b = entity.bounds;
        float[] origin = {x, y, z};
        float[] direction = {dx, dy, dz};
        float[] offset = {entity.dx, entity.dy, entity.dz};
        float enter = 0;
        float exit = reach;
        for (int axis = 0; axis < 3; axis++) {
            float low = b[axis] + offset[axis] - padding;
            float high = b[axis + 3] + offset[axis] + padding;
            if (Math.abs(direction[axis]) < .00001f) {
                if (origin[axis] < low || origin[axis] > high) return -1;
                continue;
            }
            float a = (low - origin[axis]) / direction[axis];
            float c = (high - origin[axis]) / direction[axis];
            enter = Math.max(enter, Math.min(a, c));
            exit = Math.min(exit, Math.max(a, c));
            if (enter > exit) return -1;
        }
        return exit >= 0 && enter <= reach ? Math.max(0, enter) : -1;
    }

    static GameEngine.Entity choose(GameEngine game, List<Candidate> candidates) {
        Set<Integer> suppressed = new HashSet<>();
        Map<Integer,Float> promoted = new HashMap<>();
        // Large original observation volumes often cover an entire counter or
        // cabinet. When a small authored object inside that volume is aimed at,
        // use the object's own script instead of the generic surface. A nearby
        // item can be a few pixels off the exact ray because its original hitbox
        // is tiny, so let it inherit the container's ray depth in that case.
        for (Candidate precise : candidates) {
            if (!game.isPreciseAimTarget(precise.entity)) continue;
            for (Candidate broad : candidates) {
                if (broad.onRay && game.isBroadObservationContainer(broad.entity, precise.entity)
                        && (precise.onRay || precise.aimDot >= .20f)) {
                    suppressed.add(broad.entity.id);
                    if (!precise.onRay) {
                        float depth = broad.rayEntry + Math.max(0f, precise.fallbackScore) * .001f - .001f;
                        promoted.merge(precise.entity.id, depth, Math::min);
                    }
                }
            }
        }

        boolean anyOnRay = false;
        for (Candidate candidate : candidates) {
            if ((candidate.onRay && !suppressed.contains(candidate.entity.id))
                    || promoted.containsKey(candidate.entity.id)) {
                anyOnRay = true;
                break;
            }
        }
        Candidate best = null;
        float bestScore = Float.MAX_VALUE;
        for (Candidate candidate : candidates) {
            if (suppressed.contains(candidate.entity.id)) continue;
            Float promotedDepth = promoted.get(candidate.entity.id);
            boolean aimed = candidate.onRay || promotedDepth != null;
            if (anyOnRay && !aimed) continue;
            float score = promotedDepth != null ? promotedDepth
                    : candidate.onRay ? candidate.rayEntry : candidate.fallbackScore;
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best == null ? null : best.entity;
    }
}
