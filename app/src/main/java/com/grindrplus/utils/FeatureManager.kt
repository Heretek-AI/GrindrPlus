package com.grindrplus.utils

/**
 * A named, toggleable feature flag. Used by hooks that want a per-feature
 * on/off switch distinct from the global hook on/off in [Hook] /
 * [com.grindrplus.core.Config].
 *
 * @property name Stable identifier (typically the Grindr feature flag key,
 *                e.g. `"online-until-updates"`).
 * @property isEnabled Whether the feature is currently on.
 */
data class Feature(val name: String, var isEnabled: Boolean)

/**
 * In-memory registry of named feature flags. Backed by a [MutableMap] keyed
 * by feature name; not persisted across process restarts (use
 * [com.grindrplus.core.Config] for that).
 */
class FeatureManager {
    private val features = mutableMapOf<String, Feature>()

    /**
     * Register a [Feature]. Overwrites any prior entry with the same [Feature.name].
     */
    fun add(feature: Feature) {
        features[feature.name] = feature
    }

    /**
     * Returns whether [featureName] is currently registered AND enabled.
     * Returns `false` for unknown features.
     */
    fun isEnabled(featureName: String): Boolean {
        return features[featureName]?.isEnabled ?: false
    }

    /**
     * Returns whether [featureName] is registered (regardless of its
     * current enabled state). Useful for distinguishing "feature absent"
     * from "feature disabled".
     */
    fun isManaged(featureName: String): Boolean {
        return features.containsKey(featureName)
    }
}