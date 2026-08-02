package com.magicv3.scanner3d.domain.usecase

import android.graphics.Bitmap
import android.graphics.Color
import com.magicv3.scanner3d.domain.ar.CameraPose

data class Point3D(
    val x: Float,
    val y: Float,
    val z: Float,
    val r: Int,
    val g: Int,
    val b: Int
)

class DepthToPointsUseCase {

    companion object {
        private const val FX = 500f
        private const val FY = 500f
        private const val CX = 259f
        private const val CY = 259f
        private const val STRIDE = 3 // Downsampling to keep memory and CPU low
    }

    /**
     * Back-projects 2D depth map to 3D world points using camera pose and RGB colors.
     */
    fun execute(
        depths: FloatArray,
        width: Int,
        height: Int,
        pose: CameraPose?,
        rgbBitmap: Bitmap
    ): List<Point3D> {
        val points = mutableListOf<Point3D>()
        val scaledRgb = Bitmap.createScaledBitmap(rgbBitmap, width, height, false)

        val translation = pose?.translation ?: floatArrayOf(0f, 0f, 0f)
        val rotation = pose?.rotationQuaternion ?: floatArrayOf(0f, 0f, 0f, 1f)

        for (y in 0 until height step STRIDE) {
            for (x in 0 until width step STRIDE) {
                val idx = y * width + x
                val depth = depths[idx]

                // Avoid noise / extreme zero depths
                if (depth <= 0.01f || depth >= 0.99f) continue

                // 1. Camera space projection
                // Scale depth to representative meters (e.g. max range 2.5 meters)
                val zCamera = depth * 2.5f 
                val xCamera = (x - CX) * zCamera / FX
                val yCamera = (y - CY) * zCamera / FY

                // 2. Rotate vector using camera quaternion pose
                val cameraPoint = floatArrayOf(xCamera, yCamera, zCamera)
                val rotatedPoint = rotateVectorByQuaternion(cameraPoint, rotation)

                // 3. Translate to world coordinates
                val xWorld = rotatedPoint[0] + translation[0]
                val yWorld = rotatedPoint[1] + translation[1]
                val zWorld = rotatedPoint[2] + translation[2]

                // Get RGB color from Bitmap
                val color = scaledRgb.getPixel(x, y)
                val r = Color.red(color)
                val g = Color.green(color)
                val b = Color.blue(color)

                points.add(Point3D(xWorld, yWorld, zWorld, r, g, b))
            }
        }

        return points
    }

    private fun rotateVectorByQuaternion(v: FloatArray, q: FloatArray): FloatArray {
        val qx = q[0]
        val qy = q[1]
        val qz = q[2]
        val qw = q[3]

        // Quaternion-Vector cross products
        val ix = qw * v[0] + qy * v[2] - qz * v[1]
        val iy = qw * v[1] + qz * v[0] - qx * v[2]
        val iz = qw * v[2] + qx * v[1] - qy * v[0]
        val iw = -qx * v[0] - qy * v[1] - qz * v[2]

        val rx = ix * qw + iw * -qx + iy * -qz - iz * -qy
        val ry = iy * qw + iw * -qy + iz * -qx - ix * -qz
        val rz = iz * qw + iw * -qz + ix * -qy - iy * -qx

        return floatArrayOf(rx, ry, rz)
    }
}
