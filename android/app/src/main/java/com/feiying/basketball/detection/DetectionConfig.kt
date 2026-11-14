package com.feiying.basketball.detection

/**
 * Tunables for the on-device classical detector.
 *
 * The defaults mirror `server/app/services/classical_shot_detection.py` so a
 * phone and the server look at the same evidence. They are kept as a data
 * class so tests can use smaller, faster values.
 */
data class DetectionConfig(
    /** Frames per second sampled from the video for tracking. */
    val sampleFps: Double = 10.0,
    val shotCooldownSeconds: Double = 1.5,
    val ballMinRadiusRatio: Double = 0.010,
    val ballMaxRadiusRatio: Double = 0.120,
    val hueMin: Int = 0,
    val hueMax: Int = 38,
    val saturationMin: Int = 70,
    val valueMin: Int = 50,
    val hoopPersistence: Double = 0.50,
    val hoopMinWidthRatio: Double = 0.04,
    val hoopMaxWidthRatio: Double = 0.45,
    val hoopMaxHeightRatio: Double = 0.60,
    val hoopTopRatio: Double = 0.80,
    val staticPersistenceThreshold: Double = 0.95,
    val hoopMinConfidence: Double = 0.25,
    val hoopCenterWeight: Double = 0.20,
    val maxTrackGap: Int = 5,
    val minTrackPoints: Int = 5,
    val minVerticalRangeRatio: Double = 0.04,
    /** Frames are downscaled to this width before analysis, like the server. */
    val maxAnalysisWidth: Int = 640,
    /** Weight of the current mask in the exponential persistence map. */
    val persistenceAlpha: Double = 0.12,
    val maxBallCandidates: Int = 8,
)
