#ifndef POISSON_DEFERRED_H
#define POISSON_DEFERRED_H

#include <vector>
#include <string>

struct Point3D {
    float x, y, z;
    float nx, ny, nz;
};

class PoissonDeferredReconstruction {
public:
    bool GenerateMeshFromPointCloud(const std::vector<Point3D>& pointCloud, const std::string& outputFilePath);
};

#endif // POISSON_DEFERRED_H
