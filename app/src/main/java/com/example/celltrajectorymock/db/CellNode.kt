package com.example.celltrajectorymock.db

/**
 * Coordinates of a base station resolved from the offline database.
 *
 * Kept in its own file (separate from [CellDatabase], which pulls in Android
 * APIs) so the model can be compiled and unit-tested on a plain JVM.
 */
data class CellNode(
    val latitude: Double,
    val longitude: Double,
    val rangeMeters: Int
)
