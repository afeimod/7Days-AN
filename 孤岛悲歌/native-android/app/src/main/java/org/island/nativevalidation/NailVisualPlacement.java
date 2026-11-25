package org.island.nativevalidation;

/** Corrects the original flat nail decal so it clears the cabinet top. */
final class NailVisualPlacement {
    private NailVisualPlacement() {}

    static float verticalLift(GameEngine.Entity entity) {
        if (entity == null) return 0f;
        String model = entity.data.getOrDefault("model", "").toLowerCase(java.util.Locale.ROOT);
        // Map 2's original tiedingd mesh is a horizontal decal at z=56. The
        // cabinet surface reaches z=60, so the unmodified mesh is buried.
        return model.endsWith("/tiedingd.sd2") ? 6f : 0f;
    }

    static float visualScale(GameEngine.Entity entity) {
        if (entity == null) return 1f;
        String model = entity.data.getOrDefault("model", "").toLowerCase(java.util.Locale.ROOT);
        // Preserve the original mesh and texture, while making this small,
        // low-contrast prop readable at phone resolution.
        return model.endsWith("/tiedingd.sd2") ? 2f : 1f;
    }
}
