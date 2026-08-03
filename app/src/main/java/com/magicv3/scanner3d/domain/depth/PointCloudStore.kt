package com.magicv3.scanner3d.domain.depth

import com.magicv3.scanner3d.domain.usecase.Point3D
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PointCloudStore @Inject constructor() {

    private val _accumulatedPoints = Collections.synchronizedList(mutableListOf<Point3D>())
    
    private val _pointCount = MutableStateFlow(0)
    val pointCount: StateFlow<Int> = _pointCount.asStateFlow()

    fun getPoints(): List<Point3D> {
        return synchronized(_accumulatedPoints) {
            _accumulatedPoints.toList()
        }
    }

    fun addPoints(points: List<Point3D>) {
        synchronized(_accumulatedPoints) {
            if (_accumulatedPoints.size < MAX_POINTS) {
                _accumulatedPoints.addAll(points)
            }
            _pointCount.value = _accumulatedPoints.size
        }
    }

    fun clear() {
        synchronized(_accumulatedPoints) {
            _accumulatedPoints.clear()
            _pointCount.value = 0
        }
    }

    companion object {
        private const val MAX_POINTS = 300_000
    }
}
