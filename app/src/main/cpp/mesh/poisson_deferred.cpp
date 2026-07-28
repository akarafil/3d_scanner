#include "poisson_deferred.h"
#include <android/log.h>
#include <fstream>
#include <vector>
#include <cmath>
#include <algorithm>

#define LOG_TAG "PoissonRecon"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct MeshVertex {
    float x, y, z;
    float nx, ny, nz;
};

struct MeshFace {
    int v1, v2, v3;
};

bool PoissonDeferredReconstruction::GenerateMeshFromPointCloud(const std::vector<Point3D>& pointCloud, const std::string& outputFilePath) {
    if (pointCloud.empty()) {
        LOGE("Point cloud is empty, cannot reconstruct mesh.");
        return false;
    }

    LOGI("Processing %zu points for Surface Nets reconstruction...", pointCloud.size());

    // 1. Bounding Box hesaplama
    float minX = 1e9f, minY = 1e9f, minZ = 1e9f;
    float maxX = -1e9f, maxY = -1e9f, maxZ = -1e9f;
    for (const auto& pt : pointCloud) {
        minX = std::min(minX, pt.x);
        minY = std::min(minY, pt.y);
        minZ = std::min(minZ, pt.z);
        maxX = std::max(maxX, pt.x);
        maxY = std::max(maxY, pt.y);
        maxZ = std::max(maxZ, pt.z);
    }

    // Bounding Box'a 5 cm emniyet payı ekleme
    float padding = 0.05f;
    minX -= padding; minY -= padding; minZ -= padding;
    maxX += padding; maxY += padding; maxZ += padding;

    // Grid boyutları (dim = 40, toplam 40x40x40 hücre, mobil cihazlar için mükemmel denge)
    const int dim = 40;
    float stepX = (maxX - minX) / dim;
    float stepY = (maxY - minY) / dim;
    float stepZ = (maxZ - minZ) / dim;

    // 2. Spatial Grid (Hızlı Arama İndeksi) oluşturma
    // Bu indeks yakın noktaları O(1) sürede bulmak için 32x32x32 boyutlarındadır.
    const int hashDim = 32;
    float hStepX = (maxX - minX) / hashDim;
    float hStepY = (maxY - minY) / hashDim;
    float hStepZ = (maxZ - minZ) / hashDim;

    std::vector<std::vector<int>> spatialGrid(hashDim * hashDim * hashDim);
    for (int pIdx = 0; pIdx < (int)pointCloud.size(); ++pIdx) {
        const auto& pt = pointCloud[pIdx];
        int gx = std::clamp(static_cast<int>((pt.x - minX) / hStepX), 0, hashDim - 1);
        int gy = std::clamp(static_cast<int>((pt.y - minY) / hStepY), 0, hashDim - 1);
        int gz = std::clamp(static_cast<int>((pt.z - minZ) / hStepZ), 0, hashDim - 1);
        int cellIdx = (gx * hashDim + gy) * hashDim + gz;
        spatialGrid[cellIdx].push_back(pIdx);
    }

    // Truncation aralığı
    float maxStep = std::max({stepX, stepY, stepZ});
    float truncationRadius = maxStep * 2.5f;

    // 3. Voxel köşe noktaları için SDF ve Ağırlık hesaplama
    int numNodes = (dim + 1) * (dim + 1) * (dim + 1);
    std::vector<float> sdfGrid(numNodes, 1e9f);
    std::vector<Point3D> nearestPoints(numNodes);

    #pragma omp parallel for collapse(3)
    for (int i = 0; i <= dim; ++i) {
        for (int j = 0; j <= dim; ++j) {
            for (int k = 0; k <= dim; ++k) {
                int nodeIdx = (i * (dim + 1) + j) * (dim + 1) + k;

                float vx = minX + i * stepX;
                float vy = minY + j * stepY;
                float vz = minZ + k * stepZ;

                // Hash hücre indeksini bul
                int hx = std::clamp(static_cast<int>((vx - minX) / hStepX), 0, hashDim - 1);
                int hy = std::clamp(static_cast<int>((vy - minY) / hStepY), 0, hashDim - 1);
                int hz = std::clamp(static_cast<int>((vz - minZ) / hStepZ), 0, hashDim - 1);

                float minDistSq = 1e9f;
                int nearestPIdx = -1;

                // Komşu hash hücrelerini tara (3x3x3 arama bölgesi)
                for (int dx = -1; dx <= 1; ++dx) {
                    int nx = hx + dx;
                    if (nx < 0 || nx >= hashDim) continue;
                    for (int dy = -1; dy <= 1; ++dy) {
                        int ny = hy + dy;
                        if (ny < 0 || ny >= hashDim) continue;
                        for (int dz = -1; dz <= 1; ++dz) {
                            int nz = hz + dz;
                            if (nz < 0 || nz >= hashDim) continue;

                            int cellIdx = (nx * hashDim + ny) * hashDim + nz;
                            for (int pIdx : spatialGrid[cellIdx]) {
                                const auto& pt = pointCloud[pIdx];
                                float distSq = (vx - pt.x) * (vx - pt.x) + (vy - pt.y) * (vy - pt.y) + (vz - pt.z) * (vz - pt.z);
                                if (distSq < minDistSq) {
                                    minDistSq = distSq;
                                    nearestPIdx = pIdx;
                                }
                            }
                        }
                    }
                }

                if (nearestPIdx != -1) {
                    float dist = std::sqrt(minDistSq);
                    if (dist < truncationRadius) {
                        const auto& pt = pointCloud[nearestPIdx];
                        // Signed Distance (Noktanın normali ile çarpım)
                        float dotVal = (vx - pt.x) * pt.nx + (vy - pt.y) * pt.ny + (vz - pt.z) * pt.nz;
                        sdfGrid[nodeIdx] = dotVal;
                        nearestPoints[nodeIdx] = pt;
                    }
                }
            }
        }
    }

    // 4. Surface Nets hücre köşe noktalarını (Vertices) oluşturma
    std::vector<MeshVertex> vertices;
    std::vector<int> cellVertices(dim * dim * dim, -1);

    for (int i = 0; i < dim; ++i) {
        for (int j = 0; j < dim; ++j) {
            for (int k = 0; k < dim; ++k) {
                // Hücrenin 8 köşesi
                int corners[8] = {
                    (i * (dim + 1) + j) * (dim + 1) + k,
                    ((i + 1) * (dim + 1) + j) * (dim + 1) + k,
                    (i * (dim + 1) + (j + 1)) * (dim + 1) + k,
                    ((i + 1) * (dim + 1) + (j + 1)) * (dim + 1) + k,
                    (i * (dim + 1) + j) * (dim + 1) + (k + 1),
                    ((i + 1) * (dim + 1) + j) * (dim + 1) + (k + 1),
                    (i * (dim + 1) + (j + 1)) * (dim + 1) + (k + 1),
                    ((i + 1) * (dim + 1) + (j + 1)) * (dim + 1) + (k + 1)
                };

                bool hasInvalidCorner = false;
                bool hasPositive = false;
                bool hasNegative = false;

                for (int cIdx = 0; cIdx < 8; ++cIdx) {
                    float val = sdfGrid[corners[cIdx]];
                    if (val > 1e8f) {
                        hasInvalidCorner = true;
                        break;
                    }
                    if (val >= 0.0f) hasPositive = true;
                    if (val < 0.0f) hasNegative = true;
                }

                // Sınır geçişi (sign change) var mı?
                if (!hasInvalidCorner && hasPositive && hasNegative) {
                    // Kenar kesişimlerini enterpole ederek pürüzsüz vertex koordinatı bulma
                    float sumX = 0.0f, sumY = 0.0f, sumZ = 0.0f;
                    float sumNx = 0.0f, sumNy = 0.0f, sumNz = 0.0f;
                    int crossingCount = 0;

                    const int edges[12][2] = {
                        {0, 1}, {1, 3}, {3, 2}, {2, 0},
                        {4, 5}, {5, 7}, {7, 6}, {6, 4},
                        {0, 4}, {1, 5}, {2, 6}, {3, 7}
                    };

                    for (int eIdx = 0; eIdx < 12; ++eIdx) {
                        int c1 = corners[edges[eIdx][0]];
                        int c2 = corners[edges[eIdx][1]];
                        float s1 = sdfGrid[c1];
                        float s2 = sdfGrid[c2];

                        if ((s1 < 0.0f && s2 >= 0.0f) || (s2 < 0.0f && s1 >= 0.0f)) {
                            float t = s1 / (s1 - s2);
                            
                            // Köşe koordinatları
                            int idx1 = edges[eIdx][0];
                            float px1 = minX + (i + ((idx1 & 1) ? 1 : 0)) * stepX;
                            float py1 = minY + (j + ((idx1 & 2) ? 1 : 0)) * stepY;
                            float pz1 = minZ + (k + ((idx1 & 4) ? 1 : 0)) * stepZ;

                            int idx2 = edges[eIdx][1];
                            float px2 = minX + (i + ((idx2 & 1) ? 1 : 0)) * stepX;
                            float py2 = minY + (j + ((idx2 & 2) ? 1 : 0)) * stepY;
                            float pz2 = minZ + (k + ((idx2 & 4) ? 1 : 0)) * stepZ;

                            sumX += px1 + t * (px2 - px1);
                            sumY += py1 + t * (py2 - py1);
                            sumZ += pz1 + t * (pz2 - pz1);

                            // Normal vektör enterpolasyonu
                            sumNx += nearestPoints[c1].nx + t * (nearestPoints[c2].nx - nearestPoints[c1].nx);
                            sumNy += nearestPoints[c1].ny + t * (nearestPoints[c2].ny - nearestPoints[c1].ny);
                            sumNz += nearestPoints[c1].nz + t * (nearestPoints[c2].nz - nearestPoints[c1].nz);

                            crossingCount++;
                        }
                    }

                    MeshVertex v;
                    if (crossingCount > 0) {
                        v.x = sumX / crossingCount;
                        v.y = sumY / crossingCount;
                        v.z = sumZ / crossingCount;
                        
                        float len = std::sqrt(sumNx * sumNx + sumNy * sumNy + sumNz * sumNz);
                        if (len > 0.0001f) {
                            v.nx = sumNx / len;
                            v.ny = sumNy / len;
                            v.nz = sumNz / len;
                        } else {
                            v.nx = 0.0f; v.ny = 0.0f; v.nz = 1.0f;
                        }
                    } else {
                        // Fallback: Hücre merkezi
                        v.x = minX + (i + 0.5f) * stepX;
                        v.y = minY + (j + 0.5f) * stepY;
                        v.z = minZ + (k + 0.5f) * stepZ;
                        v.nx = 0.0f; v.ny = 0.0f; v.nz = 1.0f;
                    }

                    int cellIdx = (i * dim + j) * dim + k;
                    cellVertices[cellIdx] = vertices.size();
                    vertices.push_back(v);
                }
            }
        }
    }

    // 5. Üçgen yüzeyleri (Faces) oluşturma
    std::vector<MeshFace> faces;

    // Grid kenarlarını tara
    for (int i = 0; i < dim; ++i) {
        for (int j = 0; j < dim; ++j) {
            for (int k = 0; k < dim; ++k) {
                // X Ekseni kenarı
                if (i < dim - 1) {
                    int c1 = (i * (dim + 1) + j) * (dim + 1) + k;
                    int c2 = ((i + 1) * (dim + 1) + j) * (dim + 1) + k;
                    float s1 = sdfGrid[c1];
                    float s2 = sdfGrid[c2];
                    if (s1 < 1e8f && s2 < 1e8f && ((s1 < 0.0f && s2 >= 0.0f) || (s2 < 0.0f && s1 >= 0.0f))) {
                        // Kenarı çevreleyen 4 hücre
                        int v0 = cellVertices[(i * dim + (j - 1)) * dim + (k - 1)];
                        int v1 = cellVertices[(i * dim + j) * dim + (k - 1)];
                        int v2 = cellVertices[(i * dim + j) * dim + k];
                        int v3 = cellVertices[(i * dim + (j - 1)) * dim + k];

                        if (v0 >= 0 && v1 >= 0 && v2 >= 0 && v3 >= 0) {
                            if (s1 > s2) {
                                faces.push_back({v0, v2, v1});
                                faces.push_back({v0, v3, v2});
                            } else {
                                faces.push_back({v0, v1, v2});
                                faces.push_back({v0, v2, v3});
                            }
                        }
                    }
                }

                // Y Ekseni kenarı
                if (j < dim - 1) {
                    int c1 = (i * (dim + 1) + j) * (dim + 1) + k;
                    int c2 = (i * (dim + 1) + (j + 1)) * (dim + 1) + k;
                    float s1 = sdfGrid[c1];
                    float s2 = sdfGrid[c2];
                    if (s1 < 1e8f && s2 < 1e8f && ((s1 < 0.0f && s2 >= 0.0f) || (s2 < 0.0f && s1 >= 0.0f))) {
                        // Kenarı çevreleyen 4 hücre
                        int v0 = cellVertices[((i - 1) * dim + j) * dim + (k - 1)];
                        int v1 = cellVertices[(i * dim + j) * dim + (k - 1)];
                        int v2 = cellVertices[(i * dim + j) * dim + k];
                        int v3 = cellVertices[((i - 1) * dim + j) * dim + k];

                        if (v0 >= 0 && v1 >= 0 && v2 >= 0 && v3 >= 0) {
                            if (s1 > s2) {
                                faces.push_back({v0, v1, v2});
                                faces.push_back({v0, v2, v3});
                            } else {
                                faces.push_back({v0, v2, v1});
                                faces.push_back({v0, v3, v2});
                            }
                        }
                    }
                }

                // Z Ekseni kenarı
                if (k < dim - 1) {
                    int c1 = (i * (dim + 1) + j) * (dim + 1) + k;
                    int c2 = (i * (dim + 1) + j) * (dim + 1) + (k + 1);
                    float s1 = sdfGrid[c1];
                    float s2 = sdfGrid[c2];
                    if (s1 < 1e8f && s2 < 1e8f && ((s1 < 0.0f && s2 >= 0.0f) || (s2 < 0.0f && s1 >= 0.0f))) {
                        // Kenarı çevreleyen 4 hücre
                        int v0 = cellVertices[((i - 1) * dim + (j - 1)) * dim + k];
                        int v1 = cellVertices[(i * dim + (j - 1)) * dim + k];
                        int v2 = cellVertices[(i * dim + j) * dim + k];
                        int v3 = cellVertices[((i - 1) * dim + j) * dim + k];

                        if (v0 >= 0 && v1 >= 0 && v2 >= 0 && v3 >= 0) {
                            if (s1 > s2) {
                                faces.push_back({v0, v2, v1});
                                faces.push_back({v0, v3, v2});
                            } else {
                                faces.push_back({v0, v1, v2});
                                faces.push_back({v0, v2, v3});
                            }
                        }
                    }
                }
            }
        }
    }

    // 6. Mesh PLY Dışa Aktarımı
    std::ofstream outFile(outputFilePath);
    if (!outFile.is_open()) {
        LOGE("Failed to open output file: %s", outputFilePath.c_str());
        return false;
    }

    outFile << "ply\n";
    outFile << "format ascii 1.0\n";
    outFile << "element vertex " << vertices.size() << "\n";
    outFile << "property float x\n";
    outFile << "property float y\n";
    outFile << "property float z\n";
    outFile << "property float nx\n";
    outFile << "property float ny\n";
    outFile << "property float nz\n";
    outFile << "element face " << faces.size() << "\n";
    outFile << "property list uchar int vertex_indices\n";
    outFile << "end_header\n";

    for (const auto& v : vertices) {
        outFile << v.x << " " << v.y << " " << v.z << " "
                << v.nx << " " << v.ny << " " << v.nz << "\n";
    }

    for (const auto& f : faces) {
        outFile << "3 " << f.v1 << " " << f.v2 << " " << f.v3 << "\n";
    }

    outFile.close();
    LOGI("Deferred Poisson (Surface Nets) Mesh Reconstruction saved successfully: v=%zu, f=%zu to %s",
         vertices.size(), faces.size(), outputFilePath.c_str());
    return true;
}
